package com.example.monolith.api;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.example.monolith.config.AppProperties;
import com.example.monolith.flow.FlowFailedException;
import com.example.monolith.flow.FlowOrchestrator;
import com.example.monolith.flow.FlowTimeoutException;
import com.example.monolith.flow.KafkaPublishException;
import com.example.monolith.flow.RedisKeyMissingException;
import com.example.monolith.kafka.KafkaPublisher;
import com.example.monolith.model.FlowMessage;
import com.example.monolith.redis.RedisMessageStore;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
public class StartController {

    private final KafkaPublisher kafkaPublisher;
    private final FlowOrchestrator flowOrchestrator;
    private final RedisMessageStore redisMessageStore;
    private final AppProperties properties;

    public StartController(KafkaPublisher kafkaPublisher,
                           FlowOrchestrator flowOrchestrator,
                           RedisMessageStore redisMessageStore,
                           AppProperties properties) {
        this.kafkaPublisher = kafkaPublisher;
        this.flowOrchestrator = flowOrchestrator;
        this.redisMessageStore = redisMessageStore;
        this.properties = properties;
    }

    @PostMapping(path = "/start", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public FinalResponse start(@Valid @RequestBody StartRequest request) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<String> completion = flowOrchestrator.register(correlationId);
        try {
            kafkaPublisher.publish(FlowMessage.initial(correlationId, request.message()));
            completion.get(properties.flowTimeout().toMillis(), TimeUnit.MILLISECONDS);
            FlowMessage stored = redisMessageStore.find(correlationId)
                    .orElseThrow(() -> new RedisKeyMissingException(correlationId));
            String retrievedFromRedisAt = Instant.now().toString();
            return new FinalResponse(correlationId, stored.originalMessage(), stored.originalMessage(),
                    stored.enrichments(), retrievedFromRedisAt);
        } catch (TimeoutException e) {
            throw new FlowTimeoutException(correlationId, properties.flowTimeout(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowFailedException(correlationId, e);
        } catch (ExecutionException e) {
            throw new FlowFailedException(correlationId, e.getCause());
        } catch (KafkaPublishException | RedisKeyMissingException e) {
            throw e;
        } finally {
            flowOrchestrator.unregister(correlationId, completion);
        }
    }
}
