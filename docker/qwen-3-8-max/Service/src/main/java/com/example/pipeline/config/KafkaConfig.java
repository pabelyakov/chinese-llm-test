package com.example.pipeline.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class KafkaConfig {

    @Bean(destroyMethod = "close")
    public AdminClient kafkaAdminClient(KafkaProperties kafkaProperties) {
        return AdminClient.create(kafkaProperties.buildAdminProperties(null));
    }

    /**
     * Kafka health indicator для /actuator/health (clusterId + число живых брокеров).
     */
    @Bean
    public HealthIndicator kafkaHealthIndicator(AdminClient adminClient) {
        return () -> {
            try {
                var cluster = adminClient.describeCluster(new DescribeClusterOptions().timeoutMs(3000));
                String clusterId = cluster.clusterId().get(4, TimeUnit.SECONDS);
                var nodes = cluster.nodes().get(4, TimeUnit.SECONDS);
                var controller = cluster.controller().get(4, TimeUnit.SECONDS);
                return Health.up()
                        .withDetail("clusterId", clusterId)
                        .withDetail("nodeCount", nodes.size())
                        .withDetail("controllerId", controller != null ? controller.id() : "n/a")
                        .build();
            } catch (Exception e) {
                return Health.down().withDetail("error", e.getMessage()).build();
            }
        };
    }
}
