package com.example.pipeline.config;

import com.example.pipeline.pipeline.CompletionSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    /**
     * Контейнер подписки на pipeline:completed создаётся при старте контекста,
     * т.е. подписка гарантированно существует до первого produce (нет гонки).
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                       CompletionSubscriber completionSubscriber,
                                                                       PipelineProperties props) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(completionSubscriber, new ChannelTopic(props.getRedisCompletedChannel()));
        return container;
    }
}
