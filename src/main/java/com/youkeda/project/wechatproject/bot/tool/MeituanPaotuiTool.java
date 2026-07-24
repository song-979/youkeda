package com.youkeda.project.wechatproject.bot.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MeituanPaotuiTool implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(MeituanPaotuiTool.class);

    public static final String PASSPORT_CLIENT_ID_PROPERTY = "meituan.passport.client-id";

    private static final String PASSPORT_COMMAND_PROPERTY = "meituan.passport.command";
    private static final String PASSPORT_ENV_PROPERTY = "meituan.passport.env";
    private static final String PASSPORT_CACHE_DIR_PROPERTY = "meituan.passport.cache-dir";
    private static final String NODE_COMMAND_PROPERTY = "meituan.paotui.node-command";
    private static final String SCRIPT_PATH_PROPERTY = "meituan.paotui.script-path";
    private static final String ZIP_PATH_PROPERTY = "meituan.paotui.zip-path";
    private static final String SOURCE_FROM_PROPERTY = "meituan.paotui.source-from";
    private static final String TIMEOUT_SECONDS_PROPERTY = "meituan.paotui.timeout-seconds";

    private static final String DEFAULT_NODE_COMMAND = "node";
    private static final String DEFAULT_SOURCE_FROM = "aihub";
    private static final String DEFAULT_PASSPORT_COMMAND = "pt-passport";
    private static final String DEFAULT_PASSPORT_CLIENT_ID = "ac76c22d257c4c6d9164719eea64ed4b";
    private static final String DEFAULT_PASSPORT_ENV = "prod";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private static final Pattern AUTH_LINK_PATTERN = Pattern.compile("(?m)^AUTH_LINK:\\s*(https?://\\S+)\\s*$");

    private final Environment environment;
    private final ThreadLocal<String> passportUserScope = new ThreadLocal<>();
    private volatile Path cachedScriptPath;

    @Autowired
    public MeituanPaotuiTool(Environment environment) {
        this.environment = environment;
    }

    @Tool(name = "start_meituan_passport_authorization", description = "发起美团 Passport 用户授权，返回供用户在美团 App 中确认的官方授权链接。")
    public String startPassportAuthorization() {
        return startPassportAuthorization(false);
    }

    String startPassportAuthorization(boolean force) {
        List<String> args = new ArrayList<>(List.of(
                "auth", "get-code",
                "--client_id", passportClientId(),
                "--env", passportEnvironment()
        ));
        if (force) {
            args.add("--force");
        }
        ProcessResult result = runPassport(args, resolveTimeout());
        if (result.exitCode() != 0) {
            return "美团授权发起失败，请稍后重试。";
        }
        if (containsToken(result.stdout())) {
            return "美团授权已生效。";
        }
        Matcher matcher = AUTH_LINK_PATTERN.matcher(result.stdout());
        if (!matcher.find()) {
            return "美团授权发起失败，未取得授权链接，请稍后重试。";
        }
        return "请使用美团 App 打开以下官方链接并确认授权：\n\n"
                + matcher.group(1) + "\n\n授权完成后，请回复“已授权”。";
    }

    @Tool(name = "complete_meituan_passport_authorization", description = "用户在美团 App 确认后完成 Passport 授权，并验证跑腿业务接口是否可用。")
    public String completePassportAuthorization() {
        if (resolvePassportToken() == null) {
            ProcessResult pollResult = runPassport(List.of(
                    "auth", "poll-token",
                    "--client_id", passportClientId(),
                    "--timeout", String.valueOf(Math.min(resolveTimeout().toSeconds(), 60))
            ), resolveTimeout().plusSeconds(5));
            if (pollResult.exitCode() != 0 || !containsToken(pollResult.stdout())) {
                return "美团授权尚未完成或已失效，请重新发起授权。";
            }
        }
        String validation = runPaotui("美团跑腿业务授权验证失败", List.of("confirm_auth"));
        return looksSuccessful(validation) ? "美团授权成功，可以继续下单。" : validation;
    }

    void setPassportUserScope(String userId) {
        passportUserScope.set(userId == null || userId.isBlank() ? "anonymous" : userId.trim());
    }

    void clearPassportUserScope() {
        passportUserScope.remove();
    }

    @Tool(name = "check_meituan_paotui_login", description = "检查美团跑腿 Token 是否已登录。跑腿、帮送、帮买、同城配送等真实下单流程开始前必须先调用。")
    public String checkLogin() {
        return runPaotui("美团跑腿登录检查失败", List.of("login"));
    }

    @Tool(name = "list_meituan_paotui_addresses", description = "获取美团跑腿地址簿。登录成功后调用，用于让用户选择取件地址、收件地址或帮忙地址。")
    public String listAddresses(Integer addressType, Integer businessType, Integer scene) {
        List<String> args = new ArrayList<>();
        args.add("get_address_list");
        addOption(args, "--address-type", defaultNumber(addressType, 1));
        addOption(args, "--business-type", defaultNumber(businessType, 1));
        addOption(args, "--scene", defaultNumber(scene, 2));
        return runPaotui("美团跑腿地址簿查询失败", args);
    }

    @Tool(name = "search_meituan_paotui_poi", description = "搜索美团跑腿 POI 地址。地址簿匹配不到或用户提供新地址时调用，需要返回可用于下单的坐标和城市信息。")
    public String searchPoi(String keyword, String city, String lat, String lng) {
        if (keyword == null || keyword.isBlank()) {
            return "请提供要搜索的地址关键词。";
        }
        List<String> args = new ArrayList<>();
        args.add("search_poi");
        addOption(args, "--keyword", keyword);
        addOption(args, "--city", city);
        addOption(args, "--lat", lat);
        addOption(args, "--lng", lng);
        return runPaotui("美团跑腿 POI 搜索失败", args);
    }

    @Tool(name = "preview_meituan_paotui_order", description = "预览美团跑腿订单费用，只预览不提交。senderJson、recipientJson、goodsJson 必须使用地址簿或 POI 返回的信息构造。")
    public String previewOrder(String senderJson, String recipientJson, String goodsJson,
                               String businessType, String bizTypeSceneTag, String businessTypeTag,
                               String tipFee, String purchaseDetail, String remark,
                               String conversationId) {
        String missing = validateOrderArgs(senderJson, recipientJson, goodsJson);
        if (missing != null) {
            return missing;
        }

        List<String> args = buildPreviewAndSubmitArgs(senderJson, recipientJson, goodsJson,
                businessType, bizTypeSceneTag, businessTypeTag, tipFee, purchaseDetail, remark, conversationId);
        return runPaotui("美团跑腿费用预览失败", args);
    }

    @Tool(name = "submit_meituan_paotui_order", description = "提交真实美团跑腿订单。只能在费用预览后、用户明确回复确认下单时调用，参数必须与预览完全一致。")
    public String submitOrder(String senderJson, String recipientJson, String goodsJson,
                              String businessType, String bizTypeSceneTag, String businessTypeTag,
                              String tipFee, String purchaseDetail, String remark,
                              String conversationId, String userConfirmation) {
        if (!isConfirmed(userConfirmation)) {
            return "跑腿下单是真实消费。请先展示费用预览，并等待用户明确回复“确认”后再提交订单。";
        }
        String missing = validateOrderArgs(senderJson, recipientJson, goodsJson);
        if (missing != null) {
            return missing;
        }

        List<String> args = buildPreviewAndSubmitArgs(senderJson, recipientJson, goodsJson,
                businessType, bizTypeSceneTag, businessTypeTag, tipFee, purchaseDetail, remark, conversationId);
        args.add("--confirm");
        return runPaotui("美团跑腿订单提交失败", args);
    }

    @Tool(name = "query_meituan_paotui_order_status", description = "查询美团跑腿订单状态。用户询问跑腿订单进度时调用。")
    public String queryOrderStatus(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return "请提供要查询的跑腿订单号。";
        }
        return runPaotui("美团跑腿订单状态查询失败", List.of("get_order_status", "--order-id", orderId.trim()));
    }

    private List<String> buildPreviewAndSubmitArgs(String senderJson, String recipientJson, String goodsJson,
                                                   String businessType, String bizTypeSceneTag,
                                                   String businessTypeTag, String tipFee,
                                                   String purchaseDetail, String remark,
                                                   String conversationId) {
        List<String> args = new ArrayList<>();
        args.add("preview_and_submit");
        addOption(args, "--sender", encodeJsonArgument(senderJson));
        addOption(args, "--recipient", encodeJsonArgument(recipientJson));
        addOption(args, "--goods", encodeJsonArgument(goodsJson));
        addOption(args, "--business-type", blankToDefault(businessType, "1"));
        addOption(args, "--biz-type-scene-tag", blankToDefault(bizTypeSceneTag, "0"));
        addOption(args, "--business-type-tag", blankToDefault(businessTypeTag, "0"));
        addOption(args, "--tip-fee", blankToDefault(tipFee, "0"));
        addOption(args, "--purchase-detail", purchaseDetail);
        addOption(args, "--remark", remark);
        addOption(args, "--conversation-id", conversationId);
        return args;
    }

    private String runPaotui(String failurePrefix, List<String> args) {
        log.info("Meituan Paotui tool invoked: args={}, passportConfigured={}, zipPath={}, scriptPath={}",
                args, property(PASSPORT_CLIENT_ID_PROPERTY) != null,
                property(ZIP_PATH_PROPERTY), property(SCRIPT_PATH_PROPERTY));

        String passportToken = resolvePassportToken();
        if (passportToken == null) {
            log.info("Meituan Paotui tool stopped because Passport authorization is unavailable");
            return "尚未完成美团 Passport 授权。";
        }

        Path scriptPath;
        try {
            scriptPath = resolveScriptPath();
        } catch (Exception e) {
            log.warn("Meituan Paotui tool failed to resolve script path: {}", e.getMessage());
            return failurePrefix + "：" + e.getMessage();
        }

        List<String> command = new ArrayList<>();
        command.add(blankToDefault(property(NODE_COMMAND_PROPERTY), DEFAULT_NODE_COMMAND));
        command.add(scriptPath.toString());
        command.addAll(args);

        log.info("Meituan Paotui tool executing command: {}", safeCommandSummary(command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(scriptPath.getParent().toFile());
        processBuilder.environment().put("MCP_ACCESS_TOKEN", passportToken);
        processBuilder.environment().put("MEITUAN_PAOTUI_SOURCE_FROM",
                blankToDefault(property(SOURCE_FROM_PROPERTY), DEFAULT_SOURCE_FROM));

        try {
            Process process = processBuilder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outThread = copyAsync(process.getInputStream(), stdout);
            Thread errThread = copyAsync(process.getErrorStream(), stderr);

            boolean finished = process.waitFor(resolveTimeout().toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return failurePrefix + "：请求超时，请稍后再试。";
            }

            outThread.join(1000);
            errThread.join(1000);
            String output = stdout.toString(StandardCharsets.UTF_8).trim();
            String error = stderr.toString(StandardCharsets.UTF_8).trim();
            log.info("Meituan Paotui tool finished: exitCode={}, stdout={}, stderr={}",
                    process.exitValue(), abbreviate(output), abbreviate(error));

            if (process.exitValue() == 0) {
                return output.isBlank() ? "美团跑腿请求成功，但未返回内容。" : output;
            }
            return failurePrefix + "：" + (error.isBlank() ? output : error);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failurePrefix + "：请求被中断，请稍后再试。";
        } catch (Exception e) {
            return failurePrefix + "：" + e.getMessage();
        }
    }

    private synchronized Path resolveScriptPath() throws IOException {
        if (cachedScriptPath != null && Files.isRegularFile(cachedScriptPath)) {
            return cachedScriptPath;
        }

        String configuredScriptPath = property(SCRIPT_PATH_PROPERTY);
        if (configuredScriptPath != null) {
            Path scriptPath = Path.of(configuredScriptPath);
            if (!Files.isRegularFile(scriptPath)) {
                throw new IOException("找不到美团跑腿脚本：" + scriptPath);
            }
            patchScriptCompatibility(scriptPath);
            cachedScriptPath = scriptPath;
            return cachedScriptPath;
        }

        String zipPathValue = property(ZIP_PATH_PROPERTY);
        if (zipPathValue == null) {
            throw new IOException("未配置 " + SCRIPT_PATH_PROPERTY + " 或 " + ZIP_PATH_PROPERTY);
        }

        Path zipPath = Path.of(zipPathValue);
        if (!Files.isRegularFile(zipPath)) {
            Path fallbackZipPath = Path.of(System.getProperty("user.home"), "Downloads", "meituan-paotui.zip");
            if (!Files.isRegularFile(fallbackZipPath)) {
                throw new IOException("找不到美团跑腿 zip 包：" + zipPath);
            }
            log.info("Meituan Paotui zip path fallback matched: {}", fallbackZipPath);
            zipPath = fallbackZipPath;
        }

        Path targetDir = Path.of(System.getProperty("java.io.tmpdir"), "youkeda-meituan-paotui");
        unzip(zipPath, targetDir);
        Path scriptPath = targetDir.resolve("meituan-paotui").resolve("paotui.js");
        if (!Files.isRegularFile(scriptPath)) {
            throw new IOException("zip 包中未找到 meituan-paotui/paotui.js");
        }
        patchScriptCompatibility(scriptPath);
        cachedScriptPath = scriptPath;
        return cachedScriptPath;
    }

    private static void patchScriptCompatibility(Path scriptPath) throws IOException {
        patchJsonArgumentDecoder(scriptPath);
        patchSubmitPurchaseDetail(scriptPath);
    }

    private static void patchJsonArgumentDecoder(Path scriptPath) throws IOException {
        String script = Files.readString(scriptPath, StandardCharsets.UTF_8);
        String marker = "YOUKEDA_JSON_ARGUMENT_DECODER";
        if (script.contains(marker)) {
            return;
        }
        String shebang = "#!/usr/bin/env node";
        if (!script.startsWith(shebang)) {
            throw new IOException("当前美团跑腿脚本缺少标准 Node 启动标记");
        }
        String decoder = """

                // YOUKEDA_JSON_ARGUMENT_DECODER: preserve JSON arguments on Windows.
                for (let i = 2; i < process.argv.length - 1; i++) {
                  if (["--sender", "--recipient", "--goods"].includes(process.argv[i])
                      && process.argv[i + 1].startsWith("base64:")) {
                    process.argv[i + 1] = Buffer.from(process.argv[i + 1].slice(7), "base64").toString("utf8");
                  }
                }
                """;
        Files.writeString(scriptPath, shebang + decoder + script.substring(shebang.length()),
                StandardCharsets.UTF_8);
        log.info("Applied Meituan Paotui Windows JSON argument compatibility patch");
    }

    private static void patchSubmitPurchaseDetail(Path scriptPath) throws IOException {
        String script = Files.readString(scriptPath, StandardCharsets.UTF_8);
        String patchedMarker = "'purchaseGoodDetail':_0x300ae1||'',"
                + "'bizTypeSceneTag':Number(_0x350084||0),"
                + "'businessTypeTag':Number(_0x2a461f||0),'remark':"
                + "_0x3bb517['ikUDF'](_0x40b111,'')";
        if (script.contains(patchedMarker)) {
            return;
        }

        String previousPatchedMarker = "'purchaseGoodDetail':_0x300ae1||'','remark':"
                + "_0x3bb517['ikUDF'](_0x40b111,'')";
        String originalMarker = "'remark':_0x3bb517['ikUDF'](_0x40b111,'')";
        String markerToReplace = script.contains(previousPatchedMarker)
                ? previousPatchedMarker
                : originalMarker;
        if (!script.contains(markerToReplace)) {
            throw new IOException("当前美团跑腿脚本版本缺少可识别的帮买提交参数，请更新兼容配置");
        }

        // paotui.js 1.0.5 omits three previewed business fields from submit_order.
        Files.writeString(scriptPath, script.replace(markerToReplace, patchedMarker), StandardCharsets.UTF_8);
        log.info("Applied Meituan Paotui submit payload compatibility patch");
    }

    private static void unzip(Path zipPath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("__MACOSX/")) {
                    zipInputStream.closeEntry();
                    continue;
                }
                Path targetPath = targetDir.resolve(entry.getName()).normalize();
                if (!targetPath.startsWith(targetDir)) {
                    throw new IOException("zip 包路径不安全：" + entry.getName());
                }
                Files.createDirectories(targetPath.getParent());
                Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                zipInputStream.closeEntry();
            }
        }
    }

    private static Thread copyAsync(InputStream inputStream, ByteArrayOutputStream outputStream) {
        Thread thread = new Thread(() -> {
            try (inputStream; outputStream) {
                inputStream.transferTo(outputStream);
            } catch (IOException ignored) {
                // The process result path will report the command failure if needed.
            }
        }, "meituan-paotui-output-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private String resolvePassportToken() {
        ProcessResult result = runPassport(List.of(
                "get-token",
                "--client_id", passportClientId(),
                "--env", passportEnvironment()
        ), Duration.ofSeconds(15));
        if (result.exitCode() != 0) {
            return null;
        }
        String[] lines = result.stdout().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String value = lines[i].trim();
            if (!value.isBlank() && !value.startsWith("[CLIGuard")) {
                return value;
            }
        }
        return null;
    }

    private ProcessResult runPassport(List<String> args, Duration timeout) {
        List<String> command = new ArrayList<>();
        String passportCommand = blankToDefault(property(PASSPORT_COMMAND_PROPERTY), DEFAULT_PASSPORT_COMMAND);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/s");
            command.add("/c");
            command.add(passportCommand.endsWith(".cmd") ? passportCommand : passportCommand + ".cmd");
        } else {
            command.add(passportCommand);
        }
        command.addAll(args);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().put("PT_PASSPORT_AUTH_FILE", passportCacheFile().toString());
            Process process = processBuilder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outThread = copyAsync(process.getInputStream(), stdout);
            Thread errThread = copyAsync(process.getErrorStream(), stderr);
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "", "timeout");
            }
            outThread.join(1000);
            errThread.join(1000);
            String output = stdout.toString(StandardCharsets.UTF_8).trim();
            String error = stderr.toString(StandardCharsets.UTF_8).trim();
            log.info("Meituan Passport command finished: action={}, exitCode={}",
                    args.isEmpty() ? "unknown" : args.get(0), process.exitValue());
            return new ProcessResult(process.exitValue(), output, error);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "", "interrupted");
        } catch (Exception e) {
            log.warn("Meituan Passport command failed: {}", e.getMessage());
            return new ProcessResult(-1, "", e.getMessage());
        }
    }

    private String passportClientId() {
        return blankToDefault(property(PASSPORT_CLIENT_ID_PROPERTY), DEFAULT_PASSPORT_CLIENT_ID);
    }

    private String passportEnvironment() {
        return blankToDefault(property(PASSPORT_ENV_PROPERTY), DEFAULT_PASSPORT_ENV);
    }

    private Path passportCacheFile() throws IOException {
        String configuredDir = property(PASSPORT_CACHE_DIR_PROPERTY);
        Path cacheDir = configuredDir != null
                ? Path.of(configuredDir)
                : Path.of(System.getProperty("java.io.tmpdir"), "youkeda-meituan-passport");
        Files.createDirectories(cacheDir);
        return cacheDir.resolve(hashScope(passportUserScope.get()) + ".json");
    }

    private static String hashScope(String scope) {
        String value = scope == null || scope.isBlank() ? "anonymous" : scope;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                result.append(String.format("%02x", digest[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean containsToken(String output) {
        return output != null && output.lines().anyMatch(line -> line.trim().startsWith("Token:"));
    }

    private static boolean looksSuccessful(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String normalized = output.toLowerCase(Locale.ROOT);
        return !normalized.contains("error") && !normalized.contains("失败")
                && (output.contains("有效") || output.contains("成功") || normalized.contains("success"));
    }

    private Duration resolveTimeout() {
        String value = property(TIMEOUT_SECONDS_PROPERTY);
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        try {
            long seconds = Long.parseLong(value);
            return Duration.ofSeconds(Math.max(5, Math.min(seconds, 180)));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT;
        }
    }

    private String property(String name) {
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String validateOrderArgs(String senderJson, String recipientJson, String goodsJson) {
        if (senderJson == null || senderJson.isBlank()) {
            return "缺少取件/购买/帮忙地址，请先确认地址。";
        }
        if (recipientJson == null || recipientJson.isBlank()) {
            return "缺少收件地址，请先确认地址。";
        }
        if (goodsJson == null || goodsJson.isBlank()) {
            return "缺少物品信息，请先确认要配送或购买的物品。";
        }
        return null;
    }

    private static void addOption(List<String> args, String name, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (text.isBlank()) {
            return;
        }
        args.add(name);
        args.add(text.trim());
    }

    private static String encodeJsonArgument(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        return "base64:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static int defaultNumber(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static boolean isConfirmed(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "确认".equals(normalized) || "确定".equals(normalized)
                || "确认下单".equals(normalized) || "同意".equals(normalized)
                || "yes".equals(normalized) || "ok".equals(normalized);
    }

    private static String safeCommandSummary(List<String> command) {
        if (command == null || command.size() <= 2) {
            return "";
        }
        return String.join(" ", command.subList(0, Math.min(command.size(), 4)));
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
