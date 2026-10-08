package com.example.pipeline.pipeline;

import com.example.pipeline.domain.Envelope;
import com.example.pipeline.domain.Stage;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Чистая логика обогащения envelope (без зависимостей от брокеров) — покрыта unit-тестами.
 */
@Service
public class EnrichmentService {

    private final String localHostname = resolveLocalHostname();

    public Envelope enrichKafka(Envelope envelope, String topic, int partition, long offset,
                                long timestamp, String kafkaNodeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("partition", partition);
        data.put("offset", offset);
        data.put("timestamp", timestamp);
        data.put("kafkaNodeId", kafkaNodeId != null ? kafkaNodeId : "unknown");
        data.put("hostname", localHostname);
        envelope.addStage(new Stage("kafka-enrichment", Instant.now().toString(), data));
        return envelope;
    }

    public Envelope enrichRabbit(Envelope envelope, String queue, long deliveryTag, String rabbitNode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("queue", queue);
        data.put("node", rabbitNode != null ? rabbitNode : localHostname);
        data.put("deliveryTag", deliveryTag);
        data.put("hostname", localHostname);
        envelope.addStage(new Stage("rabbit-enrichment", Instant.now().toString(), data));
        return envelope;
    }

    private static String resolveLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
