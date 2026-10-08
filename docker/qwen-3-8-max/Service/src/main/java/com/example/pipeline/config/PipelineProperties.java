package com.example.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    private String kafkaTopic = "pipeline.inbound";
    private String rabbitExchange = "pipeline.exchange";
    private String rabbitQueue = "pipeline.stage2.queue";
    private String rabbitRoutingKey = "stage.rabbit";
    private String redisResultPrefix = "pipeline:result:";
    private String redisCompletedChannel = "pipeline:completed";
    private long resultTtlSeconds = 60;
    private long timeoutSeconds = 15;
    private long pollIntervalMs = 100;

    public String getKafkaTopic() { return kafkaTopic; }
    public void setKafkaTopic(String kafkaTopic) { this.kafkaTopic = kafkaTopic; }

    public String getRabbitExchange() { return rabbitExchange; }
    public void setRabbitExchange(String rabbitExchange) { this.rabbitExchange = rabbitExchange; }

    public String getRabbitQueue() { return rabbitQueue; }
    public void setRabbitQueue(String rabbitQueue) { this.rabbitQueue = rabbitQueue; }

    public String getRabbitRoutingKey() { return rabbitRoutingKey; }
    public void setRabbitRoutingKey(String rabbitRoutingKey) { this.rabbitRoutingKey = rabbitRoutingKey; }

    public String getRedisResultPrefix() { return redisResultPrefix; }
    public void setRedisResultPrefix(String redisResultPrefix) { this.redisResultPrefix = redisResultPrefix; }

    public String getRedisCompletedChannel() { return redisCompletedChannel; }
    public void setRedisCompletedChannel(String redisCompletedChannel) { this.redisCompletedChannel = redisCompletedChannel; }

    public long getResultTtlSeconds() { return resultTtlSeconds; }
    public void setResultTtlSeconds(long resultTtlSeconds) { this.resultTtlSeconds = resultTtlSeconds; }

    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
}
