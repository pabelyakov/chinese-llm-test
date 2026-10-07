package com.example.monolith.rabbitmq;

import com.example.monolith.config.AppProperties;
import com.example.monolith.flow.FlowOrchestrator;
import com.example.monolith.model.Enrichments;
import com.example.monolith.model.FlowMessage;
import com.example.monolith.redis.RedisMessageStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class RabbitFlowListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitFlowListener.class);

    private final FlowOrchestrator flowOrchestrator;
    private final RedisMessageStore redisMessageStore;
    private final AppProperties properties;

    public RabbitFlowListener(FlowOrchestrator flowOrchestrator,
                              RedisMessageStore redisMessageStore,
                              AppProperties properties) {
        this.flowOrchestrator = flowOrchestrator;
        this.redisMessageStore = redisMessageStore;
        this.properties = properties;
    }

    @RabbitListener(queues = "${app.rabbit-queue}")
    public void onMessage(FlowMessage message,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(AmqpHeaders.REDELIVERED) boolean redelivered) {
        FlowMessage enriched = message.withEnrichment(
                Enrichments.rabbitStage(properties.rabbitQueue(), deliveryTag, redelivered));
        String payload = redisMessageStore.save(enriched);
        log.info("rabbit stage correlationId={} queue={} deliveryTag={} redelivered={} storedInRedis=true",
                enriched.correlationId(), properties.rabbitQueue(), deliveryTag, redelivered);
        flowOrchestrator.complete(enriched.correlationId(), payload);
    }
}
