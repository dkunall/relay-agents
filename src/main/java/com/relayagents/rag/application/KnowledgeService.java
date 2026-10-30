package com.relayagents.rag.application;

import com.relayagents.rag.application.KnowledgeRepository.KnowledgeBase;
import com.relayagents.rag.application.KnowledgeRepository.NewChunk;
import com.relayagents.rag.application.KnowledgeRepository.StoredChunk;
import com.relayagents.rag.domain.Embedder;
import com.relayagents.rag.domain.InjectionHeuristics;
import com.relayagents.rag.domain.PiiRedactor;
import com.relayagents.rag.domain.ReciprocalRankFusion;
import com.relayagents.rag.domain.RecursiveChunker;
import com.relayagents.rag.domain.RetrievalMode;
import com.relayagents.shared.crypto.Sha256;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.InvalidRequestException;
import com.relayagents.shared.error.NotFoundException;
import com.relayagents.shared.error.StateConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeService {

    static final int MAX_DOCUMENT_CHARS = 200_000;
    private static final int CANDIDATES = 50;

    private final KnowledgeRepository repository;
    private final Embedder embedder;
    private final RecursiveChunker chunker;

    public KnowledgeService(KnowledgeRepository repository, Embedder embedder, RecursiveChunker chunker) {
        this.repository = repository;
        this.embedder = embedder;
        this.chunker = chunker;
    }

    public KnowledgeBase create(Actor actor, String name) {
        KnowledgeBase kb = new KnowledgeBase(UUID.randomUUID(), actor.tenant(), name, embedder.model(), actor.subject());
        if (!repository.insertKnowledgeBase(kb)) {
            throw new StateConflictException("kb-exists", "Knowledge base '" + name + "' already exists");
        }
        return kb;
    }

    public List<KnowledgeBase> list(Actor actor) {
        return repository.listKnowledgeBases(actor.tenant());
    }

    public record Ingested(UUID documentId, int chunks, int redactions, int flaggedChunks, boolean duplicate) {
    }

    @Transactional
    public Ingested ingest(Actor actor, UUID kbId, String title, String content) {
        KnowledgeBase kb = require(actor.tenant(), kbId);
        if (content.length() > MAX_DOCUMENT_CHARS) {
            throw new InvalidRequestException("document-too-large", "Documents are limited to " + MAX_DOCUMENT_CHARS + " characters");
        }
        String hash = Sha256.hex(content);
        var existing = repository.findDocumentByHash(kb.id(), hash);
        if (existing.isPresent()) {
            return new Ingested(existing.get(), 0, 0, 0, true);
        }
        PiiRedactor.Result redacted = PiiRedactor.redact(content);
        List<NewChunk> chunks = new ArrayList<>();
        int flagged = 0;
        List<String> texts = chunker.chunk(redacted.text());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            var flags = InjectionHeuristics.flags(text);
            if (!flags.isEmpty()) {
                flagged++;
            }
            chunks.add(new NewChunk(i, text, flags, embedder.embed(title + "\n" + text)));
        }
        UUID documentId = UUID.randomUUID();
        if (!repository.insertDocument(kb.id(), documentId, title, hash, redacted.redactions(), chunks)) {
            UUID winner = repository.findDocumentByHash(kb.id(), hash).orElseThrow();
            return new Ingested(winner, 0, 0, 0, true);
        }
        return new Ingested(documentId, chunks.size(), redacted.redactions(), flagged, false);
    }

    public List<RetrievedChunk> search(String tenant, UUID kbId, String query, int k, RetrievalMode mode) {
        require(tenant, kbId);
        if (query == null || query.isBlank()) {
            throw new InvalidRequestException("invalid-query", "query must not be blank");
        }
        int limit = Math.max(1, Math.min(k, 20));
        List<List<Long>> rankings = new ArrayList<>();
        if (mode != RetrievalMode.VECTOR) {
            rankings.add(repository.keywordSearch(kbId, query, CANDIDATES));
        }
        if (mode != RetrievalMode.KEYWORD) {
            float[] embedding = embedder.embed(query);
            if (nonZero(embedding)) {
                rankings.add(repository.vectorSearch(kbId, embedding, CANDIDATES));
            }
        }
        var fused = ReciprocalRankFusion.fuse(rankings, ReciprocalRankFusion.DEFAULT_K, limit);
        Map<Long, StoredChunk> chunks = repository.load(fused.stream().map(ReciprocalRankFusion.Fused::item).toList());
        List<RetrievedChunk> results = new ArrayList<>();
        for (var f : fused) {
            StoredChunk c = chunks.get(f.item());
            if (c != null) {
                results.add(new RetrievedChunk(c.id(), c.documentId(), c.title(), c.text(), f.score(), c.flags()));
            }
        }
        return results;
    }

    private KnowledgeBase require(String tenant, UUID kbId) {
        return repository.findKnowledgeBase(tenant, kbId)
            .orElseThrow(() -> new NotFoundException("kb-not-found", "No knowledge base " + kbId));
    }

    private static boolean nonZero(float[] vector) {
        for (float v : vector) {
            if (v != 0f) {
                return true;
            }
        }
        return false;
    }
}
