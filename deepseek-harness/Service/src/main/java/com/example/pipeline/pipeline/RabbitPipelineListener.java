package com.example.pipeline.pipeline;

import com.example.pipeline.model.PipelineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumes the message from RabbitMQ, marks it {@code rabbitmq-processed}, stores
 * it in Redis under a message-id key, reads it back, marks it {@code redis-processed}
 * and finally completes the correlation future so the HTTP request can return.
 */
@Component
public class RabbitPipelineListener {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CorrelationRegistry registry;

    private final String redisKeyPrefix;
    private final long redisTtlSeconds;

    public RabbitPipelineListener(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  CorrelationRegistry registry,
                                  @Value("${pipeline.redis.key-prefix:pipeline:}") String redisKeyPrefix,
                                  @Value("${pipeline.redis.ttl-seconds:300}") long redisTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.redisKeyPrefix = redisKeyPrefix;
        this.redisTtlSeconds = redisTtlSeconds;
    }

    @RabbitListener(queues = "${pipeline.rabbitmq.queue:pipeline.queue}")
    public void onMessage(String json) {
        PipelineMessage message = read(json);
        message.addStage("rabbitmq", "rabbitmq-processed");

        String key = redisKeyPrefix + message.getId();
        redisTemplate.opsForValue().set(key, write(message), Duration.ofSeconds(redisTtlSeconds));

        String readBack = redisTemplate.opsForValue().get(key);
        PipelineMessage fromRedis = read(readBack);
        fromRedis.addStage("redis", "redis-processed");

        registry.complete(fromRedis.getId(), fromRedis);
    }

    private PipelineMessage read(String json) {
        try {
            return objectMapper.readValue(json, PipelineMessage.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse message", e);
        }
    }

    private String write(PipelineMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize message", e);
        }
    }
}
