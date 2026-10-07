package com.example.monolith.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String kafkaTopic,
        String rabbitExchange,
        String rabbitQueue,
        String rabbitRoutingKey,
        String redisKeyPrefix,
        Duration redisTtl,
        Duration flowTimeout,
        Duration kafkaSendTimeout) {
}
