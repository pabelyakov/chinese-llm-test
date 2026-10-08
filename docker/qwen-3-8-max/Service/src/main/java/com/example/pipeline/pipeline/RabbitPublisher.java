package com.example.pipeline.pipeline;

import com.example.pipeline.config.PipelineProperties;
import com.example.pipeline.domain.Envelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Публикует envelope как JSON-строку (SimpleMessageConverter -> content_type text/plain),
 * чтобы Rabbit-консьюмер читал ровно то, что написал Kafka-консьюмер.
 */
@Component
public class RabbitPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final PipelineProperties props;
    private final ObjectMapper objectMapper;

    public RabbitPublisher(RabbitTemplate rabbitTemplate,
                           PipelineProperties props,
                           ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public void publish(Envelope envelope) throws JsonProcessingException {
        String cid = envelope.getCorrelationId();
        String json = objectMapper.writeValueAsString(envelope);
        log.info("[{}] publishing to RabbitMQ exchange={} routingKey={}",
                cid, props.getRabbitExchange(), props.getRabbitRoutingKey());
        rabbitTemplate.convertAndSend(props.getRabbitExchange(), props.getRabbitRoutingKey(), json);
    }
}
