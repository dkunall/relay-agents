package com.relayagents.rag.adapter.persistence;

import com.relayagents.rag.application.KnowledgeRepository;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class JdbcKnowledgeRepository implements KnowledgeRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    JdbcKnowledgeRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public boolean insertKnowledgeBase(KnowledgeBase kb) {
        return jdbc.sql("""
                INSERT INTO knowledge_base (id, tenant_id, name, embedding_model, created_by) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, name) DO NOTHING
                """)
            .params(kb.id(), kb.tenant(), kb.name(), kb.embeddingModel(), kb.createdBy())
            .update() == 1;
    }

    @Override
    public Optional<KnowledgeBase> findKnowledgeBase(String tenant, UUID id) {
        return jdbc.sql("SELECT * FROM knowledge_base WHERE tenant_id = ? AND id = ?").params(tenant, id)
            .query((rs, n) -> new KnowledgeBase(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getString("name"), rs.getString("embedding_model"), rs.getString("created_by")))
            .optional();
    }

    @Override
    public List<KnowledgeBase> listKnowledgeBases(String tenant) {
        return jdbc.sql("SELECT * FROM knowledge_base WHERE tenant_id = ? ORDER BY name").param(tenant)
            .query((rs, n) -> new KnowledgeBase(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getString("name"), rs.getString("embedding_model"), rs.getString("created_by")))
            .list();
    }

    @Override
    public Optional<UUID> findDocumentByHash(UUID kbId, String contentHash) {
        return jdbc.sql("SELECT id FROM document WHERE kb_id = ? AND content_hash = ?")
            .params(kbId, contentHash).query(UUID.class).optional();
    }

    @Override
    public boolean insertDocument(UUID kbId, UUID documentId, String title, String contentHash, int redactions,
                               List<NewChunk> chunks) {
        int inserted = jdbc.sql("""
                INSERT INTO document (id, kb_id, title, content_hash, chunk_count, redactions)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (kb_id, content_hash) DO NOTHING
                """)
            .params(documentId, kbId, title, contentHash, chunks.size(), redactions)
            .update();
        if (inserted == 0) {
            return false;
        }
        for (NewChunk chunk : chunks) {
            jdbc.sql("""
                    INSERT INTO chunk (document_id, kb_id, ordinal, text, flags, embedding)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS vector))
                    """)
                .params(documentId, kbId, chunk.ordinal(), chunk.text(),
                    chunk.flags().stream().sorted().toArray(String[]::new), literal(chunk.embedding()))
                .update();
        }
        return true;
    }

    /**
     * Postgres full-text search with OR semantics and cover-density ranking. Not BM25 (no document length
     * normalisation against corpus statistics), but close enough to be the keyword half of hybrid search.
     */
    @Override
    public List<Long> keywordSearch(UUID kbId, String query, int limit) {
        return jdbc.sql("""
                WITH q AS (
                    SELECT NULLIF(replace(plainto_tsquery('english', :query)::text, '&', '|'), '')::tsquery AS query
                )
                SELECT c.id FROM chunk c, q
                WHERE c.kb_id = :kb AND q.query IS NOT NULL AND c.tsv @@ q.query
                ORDER BY ts_rank_cd(c.tsv, q.query) DESC, c.id
                LIMIT :limit
                """)
            .param("query", query).param("kb", kbId).param("limit", limit)
            .query(Long.class).list();
    }

    /**
     * Iterative index scans (pgvector 0.8) keep reading the HNSW graph until enough rows pass the
     * knowledge-base filter; without it, a small knowledge base in a big table can come back nearly empty.
     */
    @Override
    public List<Long> vectorSearch(UUID kbId, float[] embedding, int limit) {
        return tx.execute(status -> {
            jdbc.sql("SET LOCAL hnsw.iterative_scan = strict_order").update();
            jdbc.sql("SET LOCAL hnsw.ef_search = 100").update();
            return jdbc.sql("""
                    SELECT id FROM chunk WHERE kb_id = ?
                    ORDER BY embedding <=> CAST(? AS vector), id
                    LIMIT ?
                    """)
                .params(kbId, literal(embedding), limit)
                .query(Long.class).list();
        });
    }

    @Override
    public Map<Long, StoredChunk> load(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, StoredChunk> chunks = new HashMap<>();
        jdbc.sql("""
                SELECT c.id, c.document_id, d.title, c.ordinal, c.text, c.flags
                FROM chunk c JOIN document d ON d.id = c.document_id
                WHERE c.id IN (:ids)
                """)
            .param("ids", List.copyOf(chunkIds))
            .query(rs -> {
                long id = rs.getLong("id");
                chunks.put(id, new StoredChunk(id, rs.getObject("document_id", UUID.class), rs.getString("title"),
                    rs.getInt("ordinal"), rs.getString("text"), strings(rs.getArray("flags"))));
            });
        return chunks;
    }

    private static Set<String> strings(Array array) throws SQLException {
        return array == null ? Set.of() : Set.of((String[]) array.getArray());
    }

    static String literal(float[] vector) {
        StringBuilder out = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        return out.append(']').toString();
    }
}
