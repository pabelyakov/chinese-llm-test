package com.example.monolith.redis;

import java.util.Optional;

import com.example.monolith.config.AppProperties;
import com.example.monolith.model.FlowMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisMessageStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public RedisMessageStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             AppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String save(FlowMessage message) {
        String payload = serialize(message);
        redisTemplate.opsForValue().set(key(message.correlationId()), payload, properties.redisTtl());
        log.info("saved correlationId={} to redis key={} ttl={}", message.correlationId(),
                key(message.correlationId()), properties.redisTtl());
        return payload;
    }

    public Optional<FlowMessage> find(String correlationId) {
        String payload = redisTemplate.opsForValue().get(key(correlationId));
        if (payload == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(payload));
    }

    public String key(String correlationId) {
        return properties.redisKeyPrefix() + correlationId;
    }

    private String serialize(FlowMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize message " + message.correlationId(), e);
        }
    }

    private FlowMessage deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, FlowMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize redis payload", e);
        }
    }
}
