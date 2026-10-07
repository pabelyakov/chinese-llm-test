package com.example.pipeline.pipeline;

import com.example.pipeline.model.PipelineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Entry point of the pipeline. Sends the message to Kafka and then blocks until
 * the asynchronous listeners have moved it through RabbitMQ and Redis.
 *
 * Design choice: the HTTP request is served synchronously while the transport
 * inside the pipeline is asynchronous (Kafka/RabbitMQ listeners). Correlation is
 * done via {@link CorrelationRegistry}, which keeps a {@link CompletableFuture}
 * per message id. This gives a simple, reliable blocking HTTP response without
 * polling the database and without an outbox, while still using real
 * {@code @KafkaListener} / {@code @RabbitListener} consumers.
 */
@Service
public class PipelineService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CorrelationRegistry registry;

    private final String kafkaTopic;
    private final long kafkaSendTimeoutSeconds;
    private final long pipelineTimeoutSeconds;

    public PipelineService(KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper,
                           CorrelationRegistry registry,
                           @Value("${pipeline.kafka.topic:input-topic}") String kafkaTopic,
                           @Value("${pipeline.timeouts.kafka-send-seconds:10}") long kafkaSendTimeoutSeconds,
                           @Value("${pipeline.timeouts.pipeline-seconds:30}") long pipelineTimeoutSeconds) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.kafkaTopic = kafkaTopic;
        this.kafkaSendTimeoutSeconds = kafkaSendTimeoutSeconds;
        this.pipelineTimeoutSeconds = pipelineTimeoutSeconds;
    }

    public PipelineMessage start(String message) {
        String id = UUID.randomUUID().toString();
        PipelineMessage messageEntity = new PipelineMessage(id, message);
        CompletableFuture<PipelineMessage> future = registry.register(id);
        try {
            String json = objectMapper.writeValueAsString(messageEntity);
            kafkaTemplate.send(kafkaTopic, id, json)
                    .get(kafkaSendTimeoutSeconds, TimeUnit.SECONDS);
            return future.get(pipelineTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            registry.remove(id);
            throw new PipelineTimeoutException(
                    "Pipeline did not complete within " + pipelineTimeoutSeconds + " seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            registry.remove(id);
            throw new IllegalStateException("Pipeline interrupted", e);
        } catch (ExecutionException e) {
            registry.remove(id);
            throw new IllegalStateException("Pipeline failed", e.getCause());
        } catch (Exception e) {
            registry.remove(id);
            throw new IllegalStateException("Pipeline failed", e);
        }
    }
}
