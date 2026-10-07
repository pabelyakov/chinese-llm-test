package com.example.pipeline.pipeline;

import com.example.pipeline.model.PipelineMessage;
import com.example.pipeline.model.Stage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitPipelineListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private CorrelationRegistry registry;
    private RabbitPipelineListener listener;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        registry = new CorrelationRegistry();
        listener = new RabbitPipelineListener(redisTemplate, objectMapper, registry, "pipeline:", 300);
    }

    @Test
    void completesFullPipelineWithThreeStages() throws Exception {
        PipelineMessage incoming = new PipelineMessage("id-1", "hello");
        incoming.addStage("kafka", "kafka-processed");
        String incomingJson = objectMapper.writeValueAsString(incoming);

        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            assertThat(key).isEqualTo("pipeline:id-1");
            PipelineMessage afterRabbit = new PipelineMessage("id-1", "hello");
            afterRabbit.addStage("kafka", "kafka-processed");
            afterRabbit.addStage("rabbitmq", "rabbitmq-processed");
            return objectMapper.writeValueAsString(afterRabbit);
        });

        CompletableFuture<PipelineMessage> future = registry.register("id-1");

        listener.onMessage(incomingJson);

        PipelineMessage result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.getStages())
                .extracting(Stage::name)
                .containsExactly("kafka", "rabbitmq", "redis");
        assertThat(result.getStages())
                .extracting(Stage::status)
                .containsExactly("kafka-processed", "rabbitmq-processed", "redis-processed");

        verify(valueOps).set(eq("pipeline:id-1"), anyString(), eq(Duration.ofSeconds(300)));
        verify(valueOps).get("pipeline:id-1");
    }

    @Test
    void ignoresCompletionWhenNoFutureRegistered() throws Exception {
        PipelineMessage incoming = new PipelineMessage("id-missing", "hello");
        incoming.addStage("kafka", "kafka-processed");
        String incomingJson = objectMapper.writeValueAsString(incoming);

        when(valueOps.get(anyString())).thenAnswer(invocation -> {
            PipelineMessage afterRabbit = new PipelineMessage("id-missing", "hello");
            afterRabbit.addStage("kafka", "kafka-processed");
            afterRabbit.addStage("rabbitmq", "rabbitmq-processed");
            return objectMapper.writeValueAsString(afterRabbit);
        });

        listener.onMessage(incomingJson);

        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }
}
