package com.youkeda.project.wechatproject.bot.memory;

import java.util.List;

/**
 * Retrieval-Augmented Generation store for indexing and searching external documents.
 *
 * <p>Use cases: company knowledge bases, product manuals, uploaded user documents.
 * Namespace-isolated so different domains don't pollute each other's results.
 * UserId-isolated so each user's documents are private.
 *
 * <p>Backed by SQLite with vector embeddings for semantic search.
 */
public interface RagStore {

    /** Index a document into a namespace for a specific user. */
    void index(String userId, String namespace, String docId, String content);

    /** Retrieve relevant document chunks by semantic search. */
    List<RagChunk> retrieve(String userId, String namespace, String query, int topK);

    /** Delete a single document. */
    void delete(String userId, String namespace, String docId);

    /** Delete an entire namespace and all its documents for a user. */
    void deleteNamespace(String userId, String namespace);

    record RagChunk(String docId, String content, double score) {}
}
