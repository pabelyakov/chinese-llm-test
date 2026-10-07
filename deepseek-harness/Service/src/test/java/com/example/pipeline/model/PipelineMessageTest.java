package com.example.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void addStageAppendsStageInOrder() {
        PipelineMessage message = new PipelineMessage("id-1", "hello");
        message.addStage("kafka", "kafka-processed");
        message.addStage("rabbitmq", "rabbitmq-processed");
        message.addStage("redis", "redis-processed");

        assertThat(message.getStages())
                .extracting(Stage::name)
                .containsExactly("kafka", "rabbitmq", "redis");
        assertThat(message.getStages())
                .extracting(Stage::status)
                .containsExactly("kafka-processed", "rabbitmq-processed", "redis-processed");
        assertThat(message.getStages())
                .extracting(Stage::processedAt)
                .doesNotContainNull();
    }

    @Test
    void messageSerializesAndDeserializesRoundTrip() throws Exception {
        PipelineMessage message = new PipelineMessage("id-1", "hello");
        message.addStage("kafka", "kafka-processed");

        String json = objectMapper.writeValueAsString(message);
        PipelineMessage restored = objectMapper.readValue(json, PipelineMessage.class);

        assertThat(restored.getId()).isEqualTo("id-1");
        assertThat(restored.getMessage()).isEqualTo("hello");
        assertThat(restored.getStages()).hasSize(1);
        assertThat(restored.getStages().get(0).name()).isEqualTo("kafka");
        assertThat(restored.getStages().get(0).status()).isEqualTo("kafka-processed");
        assertThat(restored.getStages().get(0).processedAt()).isNotNull();
    }

    @Test
    void stageListIsMutableAndDefaulted() {
        PipelineMessage message = new PipelineMessage();
        assertThat(message.getStages()).isEqualTo(List.of());
        message.addStage("redis", "redis-processed");
        assertThat(message.getStages()).hasSize(1);
    }
}
