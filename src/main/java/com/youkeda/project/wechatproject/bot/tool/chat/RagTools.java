package com.youkeda.project.wechatproject.bot.tool.chat;

import com.youkeda.project.wechatproject.bot.memory.RagStore;
import com.youkeda.project.wechatproject.bot.memory.RagStore.RagChunk;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI-callable tools for document RAG (Retrieval-Augmented Generation).
 *
 * <p>Exposes the {@link RagStore} to AI agents so they can:
 * <ul>
 *   <li>Search previously indexed documents for answers</li>
 *   <li>Index new documents the user wants remembered</li>
 *   <li>List and manage existing namespaces and documents</li>
 * </ul>
 *
 * <p>Uses a {@link ThreadLocal} for the current user ID, set by the router
 * before each orchestration loop and cleared after.
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.rag", name = "enabled", havingValue = "true")
public class RagTools implements ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(RagTools.class);

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private final RagStore ragStore;

    public RagTools(RagStore ragStore) {
        this.ragStore = ragStore;
    }

    /** Set by the router before the orchestration loop. */
    public static void setCurrentUser(String userId) {
        if (userId != null && !userId.isBlank()) {
            CURRENT_USER.set(userId);
        }
    }

    /** Clear the current user after the orchestration loop. */
    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }

    @Override
    public String category() {
        return "information";
    }

    // ── tools ──────────────────────────────────────────────────

    @Tool(name = "search_rag",
          description = "在已索引的知识库文档中搜索相关内容。用于回答用户关于之前上传的文件、文档、或知识库的问题。"
                  + "传入查询文本和命名空间（如 work/study/family），返回最相关的文档片段。"
                  + "如果用户没有指定命名空间，默认使用 \"default\"。")
    public String searchRag(
            @ToolParam(description = "要搜索的查询文本，如 '产品保修期是多久'") String query,
            @ToolParam(description = "知识库命名空间，如 work、study、family。用户未指定时填 'default'") String namespace) {
        String userId = currentUser();
        String ns = isBlank(namespace) ? "default" : namespace.trim();
        log.info("search_rag: userId={}, namespace={}, query={}", userId, ns, truncate(query, 80));

        List<RagChunk> results = ragStore.retrieve(userId, ns, query, 6);
        if (results.isEmpty()) {
            return "在命名空间 \"" + ns + "\" 中未找到与查询相关的内容。"
                    + "如果用户之前上传过文件并希望搜索，请提示用户先使用 index_rag_document 索引文件内容。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("在命名空间 \"").append(ns).append("\" 中找到 ")
                .append(results.size()).append(" 个相关文档片段：\n\n");
        for (int i = 0; i < results.size(); i++) {
            RagChunk chunk = results.get(i);
            sb.append("--- 片段 ").append(i + 1)
                    .append("（来源：").append(chunk.docId())
                    .append("，相关度：").append(String.format("%.2f", chunk.score())).append("）---\n");
            sb.append(chunk.content()).append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(name = "index_rag_document",
          description = "将文档内容索引到知识库中，以便后续搜索。当用户要求'记住这个文件'、'保存到知识库'、"
                  + "或'把这个文档加到我的XX知识库'时调用。传入文档ID（如文件名）、内容文本和命名空间。"
                  + "索引后，用户可以通过 search_rag 搜索该文档内容。")
    public String indexRagDocument(
            @ToolParam(description = "文档标识，通常使用文件名，如 'product-manual.pdf'") String docId,
            @ToolParam(description = "文档的完整文本内容") String content,
            @ToolParam(description = "知识库命名空间，如 work、study、family。用户未指定时填 'default'") String namespace) {
        String userId = currentUser();
        String ns = isBlank(namespace) ? "default" : namespace.trim();
        if (isBlank(docId) || isBlank(content)) {
            return "索引失败：文档ID和内容不能为空。";
        }
        log.info("index_rag_document: userId={}, namespace={}, docId={}, contentLen={}",
                userId, ns, docId, content.length());

        ragStore.index(userId, ns, docId, content);
        return "已成功将文档 \"" + docId + "\" 索引到知识库 \"" + ns + "\" 中。"
                + "用户现在可以通过搜索来查询该文档的内容。";
    }

    @Tool(name = "delete_rag_document",
          description = "从知识库中删除指定的文档。当用户要求'删除知识库中的XX文件'、'移除XX文档'时调用。")
    public String deleteRagDocument(
            @ToolParam(description = "要删除的文档ID（通常是文件名）") String docId,
            @ToolParam(description = "知识库命名空间。用户未指定时填 'default'") String namespace) {
        String userId = currentUser();
        String ns = isBlank(namespace) ? "default" : namespace.trim();
        if (isBlank(docId)) {
            return "删除失败：请指定要删除的文档ID。";
        }
        log.info("delete_rag_document: userId={}, namespace={}, docId={}", userId, ns, docId);

        ragStore.delete(userId, ns, docId);
        return "已从知识库 \"" + ns + "\" 中删除文档 \"" + docId + "\"。";
    }

    @Tool(name = "delete_rag_namespace",
          description = "删除整个知识库命名空间及其所有文档。当用户要求'清空XX知识库'、'删除所有XX文档'时调用。"
                  + "注意：此操作不可逆！")
    public String deleteRagNamespace(
            @ToolParam(description = "要删除的命名空间，如 work、study、family") String namespace) {
        String userId = currentUser();
        if (isBlank(namespace)) {
            return "删除失败：请指定要删除的命名空间。";
        }
        String ns = namespace.trim();
        log.warn("delete_rag_namespace: userId={}, namespace={}", userId, ns);

        ragStore.deleteNamespace(userId, ns);
        return "已删除整个知识库 \"" + ns + "\" 及其所有文档。";
    }

    // ── helpers ────────────────────────────────────────────────

    private String currentUser() {
        String userId = CURRENT_USER.get();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("RagTools: no current user in ThreadLocal context");
        }
        return userId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }
}
