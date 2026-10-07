package com.example.pipeline.pipeline;

import com.example.pipeline.config.RabbitMqConfig;
import com.example.pipeline.model.PipelineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaPipelineListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RabbitTemplate rabbitTemplate;
    private KafkaPipelineListener listener;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        listener = new KafkaPipelineListener(rabbitTemplate, objectMapper);
    }

    @Test
    void enrichesWithKafkaStageAndForwardsToRabbitMq() throws Exception {
        PipelineMessage original = new PipelineMessage("id-1", "hello");
        String json = objectMapper.writeValueAsString(original);

        listener.onMessage(json);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq(RabbitMqConfig.EXCHANGE),
                org.mockito.ArgumentMatchers.eq(RabbitMqConfig.ROUTING_KEY),
                payloadCaptor.capture());

        PipelineMessage forwarded = objectMapper.readValue(payloadCaptor.getValue(), PipelineMessage.class);
        assertThat(forwarded.getId()).isEqualTo("id-1");
        assertThat(forwarded.getMessage()).isEqualTo("hello");
        assertThat(forwarded.getStages()).extracting("name").containsExactly("kafka");
        assertThat(forwarded.getStages()).extracting("status").containsExactly("kafka-processed");
    }
}
