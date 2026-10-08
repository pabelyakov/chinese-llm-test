package com.example.pipeline.pipeline;

import com.example.pipeline.config.PipelineProperties;
import com.example.pipeline.domain.Envelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Produce envelope как JSON-строку (StringSerializer) в топик pipeline.inbound, ключ = correlationId.
 */
@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PipelineProperties props;
    private final ObjectMapper objectMapper;

    public KafkaProducerService(KafkaTemplate<String, String> kafkaTemplate,
                                PipelineProperties props,
                                ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Синхронная отправка (ждём ack от брокера), чтобы controller мог вернуть 503 сразу при недоступности Kafka.
     */
    public void sendInbound(Envelope envelope) throws JsonProcessingException {
        String cid = envelope.getCorrelationId();
        String json = objectMapper.writeValueAsString(envelope);
        log.info("[{}] producing to Kafka topic={} (JSON string payload)", cid, props.getKafkaTopic());
        try {
            var metadata = kafkaTemplate.send(props.getKafkaTopic(), cid, json).get(10, TimeUnit.SECONDS);
            log.info("[{}] produced to Kafka: topic={} partition={} offset={}",
                    cid, metadata.getRecordMetadata().topic(),
                    metadata.getRecordMetadata().partition(),
                    metadata.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaUnavailableException("interrupted while producing to Kafka", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaUnavailableException("Kafka produce failed: " + e.getMessage(), e);
        }
    }

    public static class KafkaUnavailableException extends RuntimeException {
        public KafkaUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
