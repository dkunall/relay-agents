package com.relayagents.rag.adapter.web;

import com.relayagents.rag.application.KnowledgeRepository.KnowledgeBase;
import com.relayagents.rag.application.KnowledgeService;
import com.relayagents.rag.application.RetrievedChunk;
import com.relayagents.rag.domain.RetrievalMode;
import com.relayagents.shared.domain.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/kb")
class KnowledgeController {

    private final KnowledgeService knowledge;

    KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeBaseView create(Actor actor, @Valid @RequestBody CreateKnowledgeBase body) {
        return KnowledgeBaseView.of(knowledge.create(actor, body.name()));
    }

    @GetMapping
    List<KnowledgeBaseView> list(Actor actor) {
        return knowledge.list(actor).stream().map(KnowledgeBaseView::of).toList();
    }

    @PostMapping("/{kbId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeService.Ingested ingest(Actor actor, @PathVariable UUID kbId, @Valid @RequestBody IngestDocument body) {
        return knowledge.ingest(actor, kbId, body.title(), body.content());
    }

    @PostMapping("/{kbId}/search")
    List<RetrievedChunk> search(Actor actor, @PathVariable UUID kbId, @Valid @RequestBody Search body) {
        return knowledge.search(actor.tenant(), kbId, body.query(), body.k() == null ? 5 : body.k(),
            body.mode() == null ? RetrievalMode.HYBRID : body.mode());
    }

    record CreateKnowledgeBase(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,62}") String name) {
    }

    record IngestDocument(@NotBlank @Size(max = 200) String title, @NotBlank String content) {
    }

    record Search(@NotBlank @Size(max = 1000) String query, @Min(1) @Max(20) Integer k, RetrievalMode mode) {
    }

    record KnowledgeBaseView(UUID id, String name, String embeddingModel) {

        static KnowledgeBaseView of(KnowledgeBase kb) {
            return new KnowledgeBaseView(kb.id(), kb.name(), kb.embeddingModel());
        }
    }
}
