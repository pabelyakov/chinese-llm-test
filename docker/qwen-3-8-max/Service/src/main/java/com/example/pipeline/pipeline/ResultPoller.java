package com.example.pipeline.pipeline;

import com.example.pipeline.correlation.CorrelationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Polling-fallback: каждые pipeline.poll-interval-ms (100мс) проверяет Redis-ключи
 * для всех pending correlationId. Гарантирует завершение future даже если
 * pub/sub-уведомление потеряно. Также подчищает зависшие записи реестра.
 */
@Component
public class ResultPoller {

    private static final Logger log = LoggerFactory.getLogger(ResultPoller.class);
    private static final long STALE_ENTRY_MILLIS = 120_000;

    private final CorrelationRegistry registry;
    private final FutureCompleter futureCompleter;

    public ResultPoller(CorrelationRegistry registry, FutureCompleter futureCompleter) {
        this.registry = registry;
        this.futureCompleter = futureCompleter;
    }

    @Scheduled(fixedDelayString = "${pipeline.poll-interval-ms:100}")
    public void poll() {
        for (String cid : registry.pendingIds()) {
            try {
                futureCompleter.tryComplete(cid);
            } catch (Exception e) {
                log.warn("[{}] poll error: {}", cid, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void evictStaleEntries() {
        long now = System.currentTimeMillis();
        for (String cid : registry.pendingIds()) {
            var entry = registry.get(cid);
            if (entry != null && now - entry.registeredAtMillis() > STALE_ENTRY_MILLIS) {
                log.warn("[{}] evicting stale registry entry (no HTTP waiter)", cid);
                registry.remove(cid);
            }
        }
    }
}
