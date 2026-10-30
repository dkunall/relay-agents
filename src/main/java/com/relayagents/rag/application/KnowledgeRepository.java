package com.relayagents.rag.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface KnowledgeRepository {

    boolean insertKnowledgeBase(KnowledgeBase kb);

    Optional<KnowledgeBase> findKnowledgeBase(String tenant, UUID id);

    List<KnowledgeBase> listKnowledgeBases(String tenant);

    /**
     * @return the existing document id if the same content is already in this knowledge base
     */
    Optional<UUID> findDocumentByHash(UUID kbId, String contentHash);

    /**
     * @return false if a concurrent ingest stored the same content first
     */
    boolean insertDocument(UUID kbId, UUID documentId, String title, String contentHash, int redactions,
                        List<NewChunk> chunks);

    List<Long> keywordSearch(UUID kbId, String query, int limit);

    List<Long> vectorSearch(UUID kbId, float[] embedding, int limit);

    Map<Long, StoredChunk> load(Collection<Long> chunkIds);

    record KnowledgeBase(UUID id, String tenant, String name, String embeddingModel, String createdBy) {
    }

    record NewChunk(int ordinal, String text, Set<String> flags, float[] embedding) {
    }

    record StoredChunk(long id, UUID documentId, String title, int ordinal, String text, Set<String> flags) {
    }
}
