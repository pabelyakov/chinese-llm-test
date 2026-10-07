package com.example.monolith.kafka;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component
public class KafkaHealthIndicator extends AbstractHealthIndicator {

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            DescribeClusterResult cluster = admin.describeCluster();
            String clusterId = cluster.clusterId().get(3, TimeUnit.SECONDS);
            builder.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("bootstrapServers",
                            kafkaAdmin.getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
        }
    }
}
