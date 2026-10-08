package com.example.pipeline.pipeline;

import com.example.pipeline.config.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Хранение итогового envelope в Redis MASTER: ключ pipeline:result:{correlationId}, TTL 60 c.
 */
@Component
public class RedisResultStore {

    private static final Logger log = LoggerFactory.getLogger(RedisResultStore.class);

    private final StringRedisTemplate redisTemplate;
    private final PipelineProperties props;

    public RedisResultStore(StringRedisTemplate redisTemplate, PipelineProperties props) {
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    public String key(String correlationId) {
        return props.getRedisResultPrefix() + correlationId;
    }

    public void write(String correlationId, String envelopeJson) {
        String key = key(correlationId);
        redisTemplate.opsForValue().set(key, envelopeJson, Duration.ofSeconds(props.getResultTtlSeconds()));
        log.info("[{}] result written to Redis key={} ttl={}s", correlationId, key, props.getResultTtlSeconds());
    }

    public Optional<String> read(String correlationId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(correlationId)));
    }

    public void publishCompleted(String correlationId) {
        redisTemplate.convertAndSend(props.getRedisCompletedChannel(), correlationId);
        log.info("[{}] published to Redis pub/sub channel={}", correlationId, props.getRedisCompletedChannel());
    }
}
