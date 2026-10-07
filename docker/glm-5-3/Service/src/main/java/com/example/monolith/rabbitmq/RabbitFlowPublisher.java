package com.example.monolith.rabbitmq;

import com.example.monolith.config.AppProperties;
import com.example.monolith.model.FlowMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitFlowPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitFlowPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AppProperties properties;

    public RabbitFlowPublisher(RabbitTemplate rabbitTemplate, AppProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(FlowMessage message) {
        rabbitTemplate.convertAndSend(properties.rabbitExchange(), properties.rabbitRoutingKey(), message);
        log.info("published correlationId={} to rabbit exchange={} routingKey={}", message.correlationId(),
                properties.rabbitExchange(), properties.rabbitRoutingKey());
    }
}
