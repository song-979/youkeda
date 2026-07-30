package com.youkeda.project.wechatproject.bot.tool.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Low-level JSON-RPC 2.0 MCP client communicating with chrome-devtools-mcp via stdio.
 * Pattern mirrors {@code DiDiMcpClient} but uses process stdin/stdout instead of HTTP.
 */
public class BrowserMcpClient {

    private static final Logger log = LoggerFactory.getLogger(BrowserMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BrowserMcpProcess process;
    private final BrowserMcpProperties properties;
    private final AtomicInteger requestIdGen = new AtomicInteger(1);
    private final ReentrantLock ioLock = new ReentrantLock();
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    private volatile boolean initialized;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread responseReaderThread;

    public BrowserMcpClient(BrowserMcpProcess process, BrowserMcpProperties properties) {
        this.process = process;
        this.properties = properties;
    }

    /**
     * Initialize the MCP session: send initialize request and start response reader thread.
     */
    public void initialize() throws IOException {
        ioLock.lock();
        try {
            if (initialized) return;

            this.reader = new BufferedReader(new InputStreamReader(process.getStdout(), StandardCharsets.UTF_8));
            this.writer = new PrintWriter(new OutputStreamWriter(process.getStdin(), StandardCharsets.UTF_8), true);

            // Start background thread to read responses
            this.responseReaderThread = new Thread(this::readResponses, "mcp-response-reader");
            this.responseReaderThread.setDaemon(true);
            this.responseReaderThread.start();

            // Send MCP initialize request
            ObjectNode initParams = MAPPER.createObjectNode();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.set("capabilities", MAPPER.createObjectNode());
            ObjectNode clientInfo = MAPPER.createObjectNode();
            clientInfo.put("name", "wechatproject-browser-agent");
            clientInfo.put("version", "1.0.0");
            initParams.set("clientInfo", clientInfo);
            ObjectNode req = buildRequest("initialize", initParams);

            JsonNode result;
            try {
                result = sendAndWait(req);
            } catch (TimeoutException e) {
                throw new IOException("MCP initialize timed out", e);
            }
            log.info("MCP initialize OK, server: {}", result.path("serverInfo").path("name").asText("unknown"));
            initialized = true;
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Call an MCP tool by name with the given arguments.
     */
    public String callTool(String toolName, JsonNode arguments) throws IOException {
        if (!initialized) {
            initialize();
        }

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);

        ObjectNode req = buildRequest("tools/call", params);

        long start = System.currentTimeMillis();
        try {
            JsonNode result = sendAndWait(req);
            long duration = System.currentTimeMillis() - start;
            log.info("MCP tool {} completed in {}ms", toolName, duration);
            return getTextContent(result);
        } catch (TimeoutException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("MCP tool {} timed out after {}ms", toolName, duration);
            throw new BrowserMcpException("TIMEOUT", "Tool " + toolName + " timed out after " + duration + "ms");
        }
    }

    /**
     * Call an MCP tool and return the raw JsonNode result (not just text content).
     * Used by {@code screenshot()} to extract base64 image data from responses.
     */
    public JsonNode callToolRaw(String toolName, JsonNode arguments) throws IOException {
        if (!initialized) {
            initialize();
        }

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);

        ObjectNode req = buildRequest("tools/call", params);

        long start = System.currentTimeMillis();
        try {
            JsonNode result = sendAndWait(req);
            long duration = System.currentTimeMillis() - start;
            log.info("MCP tool {} (raw) completed in {}ms", toolName, duration);
            return result;
        } catch (TimeoutException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("MCP tool {} (raw) timed out after {}ms", toolName, duration);
            throw new BrowserMcpException("TIMEOUT", "Tool " + toolName + " timed out after " + duration + "ms");
        }
    }

    private ObjectNode buildRequest(String method, JsonNode params) {
        int id = requestIdGen.getAndIncrement();
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        return req;
    }

    private JsonNode sendAndWait(ObjectNode request) throws IOException, TimeoutException {
        int id = request.get("id").asInt();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        long timeout = properties.getCallTimeout().toMillis();

        ioLock.lock();
        try {
            String json = MAPPER.writeValueAsString(request);
            writer.println(json);
            writer.flush();
        } finally {
            ioLock.unlock();
        }

        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingRequests.remove(id);
            throw new IOException("Request interrupted", e);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw e;
        } catch (Exception e) {
            pendingRequests.remove(id);
            throw new IOException("Request failed", e);
        }
    }

    /**
     * Background thread: continuously reads JSON-RPC responses from process stdout.
     */
    private void readResponses() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode response = MAPPER.readTree(line);
                    if (response.has("id")) {
                        int id = response.get("id").asInt();
                        CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                        if (future != null) {
                            if (response.has("error")) {
                                JsonNode error = response.get("error");
                                String code = error.path("code").asText("UNKNOWN");
                                String message = error.path("message").asText("MCP error");
                                future.completeExceptionally(new BrowserMcpException(code, message));
                            } else {
                                future.complete(response.path("result"));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse MCP response line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                log.error("Response reader IO error while process is alive", e);
            } else {
                log.debug("Response reader stopped (process exited)");
            }
        }
        // Drain pending requests on exit
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new BrowserMcpException("PROCESS_DOWN", "MCP process exited")));
        pendingRequests.clear();
    }

    /**
     * Extract text content from an MCP tool result's content[0].text.
     */
    public static String getTextContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray() && content.size() > 0) {
            for (JsonNode item : content) {
                JsonNode text = item.path("text");
                if (!text.isMissingNode()) {
                    return text.asText();
                }
            }
        }
        return result.toString();
    }

    public boolean isInitialized() {
        return initialized;
    }
}
