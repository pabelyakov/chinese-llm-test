package com.example.monolith.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    @Bean
    public DirectExchange messagesExchange(AppProperties properties) {
        return ExchangeBuilder.directExchange(properties.rabbitExchange()).durable(true).build();
    }

    @Bean
    public Queue messagesQueue(AppProperties properties) {
        return QueueBuilder.durable(properties.rabbitQueue())
                .quorum()
                .withArgument("x-quorum-initial-group-size", 3)
                .build();
    }

    @Bean
    public Binding messagesBinding(DirectExchange messagesExchange, Queue messagesQueue,
                                   AppProperties properties) {
        return BindingBuilder.bind(messagesQueue)
                .to(messagesExchange)
                .with(properties.rabbitRoutingKey());
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
