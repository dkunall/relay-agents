package com.relayagents.config;

import com.relayagents.shared.messaging.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaConfig {

    private static final int PARTITIONS = 3;

    @Bean
    NewTopic runEventsTopic() {
        return TopicBuilder.name(Topics.RUN_EVENTS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic stepDeadLetterTopic() {
        return TopicBuilder.name(Topics.STEP_DEAD_LETTER).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic runRequestsTopic() {
        return TopicBuilder.name(Topics.RUN_REQUESTS).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * A few quick retries cover transient database errors; anything still failing goes to {topic}.DLT
     * so one bad message cannot stall its partition.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(500L, 3L));
    }
}
