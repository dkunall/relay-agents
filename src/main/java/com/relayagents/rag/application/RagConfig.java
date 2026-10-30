package com.relayagents.rag.application;

import com.relayagents.rag.domain.Embedder;
import com.relayagents.rag.domain.HashingEmbedder;
import com.relayagents.rag.domain.RecursiveChunker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RagConfig {

    @Bean
    Embedder embedder() {
        return new HashingEmbedder(256);
    }

    @Bean
    RecursiveChunker chunker(@Value("${relay.rag.chunk-size:800}") int size,
                             @Value("${relay.rag.chunk-overlap:100}") int overlap) {
        return new RecursiveChunker(size, overlap);
    }
}
