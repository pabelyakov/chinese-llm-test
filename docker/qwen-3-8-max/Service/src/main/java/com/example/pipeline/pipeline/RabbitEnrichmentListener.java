package com.example.pipeline.pipeline;

import com.example.pipeline.config.PipelineProperties;
import com.example.pipeline.correlation.CorrelationRegistry;
import com.example.pipeline.domain.Envelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Этап 2: читает JSON-строку из pipeline.stage2.queue, обогащает метаданными Rabbit,
 * пишет финальный envelope в Redis (TTL 60c), публикует correlationId в pipeline:completed
 * и сразу пытается завершить future (pub/sub + polling — страховка от потери уведомления).
 */
@Component
public class RabbitEnrichmentListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitEnrichmentListener.class);

    private final ObjectMapper objectMapper;
    private final EnrichmentService enrichmentService;
    private final CorrelationRegistry registry;
    private final RedisResultStore resultStore;
    private final FutureCompleter futureCompleter;
    private final ConnectionFactory rabbitConnectionFactory;
    private final PipelineProperties props;

    private volatile String cachedRabbitNode;

    public RabbitEnrichmentListener(ObjectMapper objectMapper,
                                    EnrichmentService enrichmentService,
                                    CorrelationRegistry registry,
                                    RedisResultStore resultStore,
                                    FutureCompleter futureCompleter,
                                    ConnectionFactory rabbitConnectionFactory,
                                    PipelineProperties props) {
        this.objectMapper = objectMapper;
        this.enrichmentService = enrichmentService;
        this.registry = registry;
        this.resultStore = resultStore;
        this.futureCompleter = futureCompleter;
        this.rabbitConnectionFactory = rabbitConnectionFactory;
        this.props = props;
    }

    @RabbitListener(queues = "${pipeline.rabbit-queue}")
    public void onMessage(Message message, Channel channel) throws Exception {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        Envelope envelope = objectMapper.readValue(json, Envelope.class);
        String cid = envelope.getCorrelationId();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String queue = message.getMessageProperties().getConsumerQueue() != null
                ? message.getMessageProperties().getConsumerQueue()
                : props.getRabbitQueue();
        String node = rabbitNode();
        log.info("[{}] Rabbit stage: consumed queue={} deliveryTag={} node={}", cid, queue, deliveryTag, node);

        enrichmentService.enrichRabbit(envelope, queue, deliveryTag, node);
        registry.markStage(cid, "rabbit-enrichment");

        String finalJson = objectMapper.writeValueAsString(envelope);
        resultStore.write(cid, finalJson);
        resultStore.publishCompleted(cid);

        futureCompleter.tryComplete(cid);
        log.info("[{}] Rabbit stage done, pipeline finished", cid);
    }

    private String rabbitNode() {
        String node = cachedRabbitNode;
        if (node != null) {
            return node;
        }
        try {
            var connection = rabbitConnectionFactory.createConnection();
            var serverProps = connection.getDelegate().getServerProperties();
            Object n = serverProps.get("node");
            if (n == null) {
                n = serverProps.get("cluster_name");
            }
            node = n != null ? n.toString() : "unknown";
        } catch (Exception e) {
            log.warn("Failed to resolve RabbitMQ node name: {}", e.getMessage());
            node = "unknown";
        }
        cachedRabbitNode = node;
        return node;
    }
}
