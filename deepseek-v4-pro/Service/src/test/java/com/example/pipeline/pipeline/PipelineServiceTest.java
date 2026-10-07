package com.example.pipeline.pipeline;

import com.example.pipeline.model.PipelineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private KafkaTemplate<String, String> kafkaTemplate;
    private CorrelationRegistry registry;
    private PipelineService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        registry = new CorrelationRegistry();
        service = new PipelineService(kafkaTemplate, objectMapper, registry, "input-topic", 10, 30);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsToKafkaAndReturnsMessageCompletedByListeners() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(1);
            PipelineMessage enriched = new PipelineMessage(id, "hello");
            enriched.addStage("kafka", "kafka-processed");
            enriched.addStage("rabbitmq", "rabbitmq-processed");
            enriched.addStage("redis", "redis-processed");
            registry.complete(id, enriched);
            return CompletableFuture.completedFuture(null);
        });

        PipelineMessage result = service.start("hello");

        assertThat(result.getMessage()).isEqualTo("hello");
        assertThat(result.getStages())
                .extracting(s -> s.name())
                .containsExactly("kafka", "rabbitmq", "redis");

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kafkaTemplate).send(
                topicCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                valueCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("input-topic");
        assertThat(valueCaptor.getValue()).contains("\"message\":\"hello\"");
    }
}
