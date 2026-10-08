package com.example.pipeline.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Подписчик pub/sub канала pipeline:completed (основной механизм уведомления).
 * Зарегистрирован в RedisMessageListenerContainer при старте приложения — ДО любого produce.
 */
@Component
public class CompletionSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CompletionSubscriber.class);

    private final FutureCompleter futureCompleter;

    public CompletionSubscriber(FutureCompleter futureCompleter) {
        this.futureCompleter = futureCompleter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String cid = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("[{}] pub/sub notification received on channel={}",
                cid, new String(message.getChannel(), StandardCharsets.UTF_8));
        try {
            futureCompleter.tryComplete(cid);
        } catch (Exception e) {
            log.warn("[{}] completion via pub/sub failed (polling fallback will retry): {}", cid, e.getMessage());
        }
    }
}
