package com.example.pipeline.pipeline;

import com.example.pipeline.correlation.CorrelationRegistry;
import com.example.pipeline.domain.Envelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Этап 1: читает JSON-строку из pipeline.inbound, обогащает метаданными Kafka
 * и публикует JSON-строку в RabbitMQ (exchange pipeline.exchange, rk stage.rabbit).
 */
@Component
public class KafkaEnrichmentListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaEnrichmentListener.class);

    private final ObjectMapper objectMapper;
    private final EnrichmentService enrichmentService;
    private final RabbitPublisher rabbitPublisher;
    private final CorrelationRegistry registry;
    private final AdminClient adminClient;
    private final ConcurrentHashMap<String, String> leaderCache = new ConcurrentHashMap<>();

    public KafkaEnrichmentListener(ObjectMapper objectMapper,
                                   EnrichmentService enrichmentService,
                                   RabbitPublisher rabbitPublisher,
                                   CorrelationRegistry registry,
                                   AdminClient adminClient) {
        this.objectMapper = objectMapper;
        this.enrichmentService = enrichmentService;
        this.rabbitPublisher = rabbitPublisher;
        this.registry = registry;
        this.adminClient = adminClient;
    }

    @KafkaListener(topics = "${pipeline.kafka-topic}", groupId = "pipeline-service-group")
    public void onMessage(ConsumerRecord<String, String> record) throws Exception {
        Envelope envelope = objectMapper.readValue(record.value(), Envelope.class);
        String cid = envelope.getCorrelationId();
        log.info("[{}] Kafka stage: consumed topic={} partition={} offset={} timestamp={}",
                cid, record.topic(), record.partition(), record.offset(), record.timestamp());

        enrichmentService.enrichKafka(envelope, record.topic(), record.partition(),
                record.offset(), record.timestamp(), partitionLeader(record.topic(), record.partition()));
        registry.markStage(cid, "kafka-enrichment");

        rabbitPublisher.publish(envelope);
        log.info("[{}] Kafka stage done, handed over to RabbitMQ", cid);
    }

    private String partitionLeader(String topic, int partition) {
        String key = topic + "-" + partition;
        return leaderCache.computeIfAbsent(key, k -> {
            try {
                var desc = adminClient.describeTopics(List.of(topic))
                        .allTopicNames().get(3, TimeUnit.SECONDS);
                var leader = desc.get(topic).partitions().get(partition).leader();
                return leader != null ? "broker-" + leader.id() : "unknown";
            } catch (Exception e) {
                log.warn("Failed to resolve partition leader for {}: {}", k, e.getMessage());
                return "unknown";
            }
        });
    }
}
