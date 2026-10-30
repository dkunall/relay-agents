package com.relayagents.rag.application;

import java.util.Set;
import java.util.UUID;

public record RetrievedChunk(long chunkId, UUID documentId, String title, String text, double score,
                             Set<String> flags) {
}
