package com.example.monolith.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowOrchestratorTest {

    private final FlowOrchestrator orchestrator = new FlowOrchestrator();

    @Test
    void completeResolvesAwaitingFuture() throws Exception {
        CompletableFuture<String> future = orchestrator.register("cid-1");

        boolean completed = orchestrator.complete("cid-1", "payload-1");

        assertThat(completed).isTrue();
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("payload-1");
        assertThat(orchestrator.pendingCount()).isZero();
    }

    @Test
    void awaitTimesOutAndUnregisterCleansUp() throws Exception {
        CompletableFuture<String> future = orchestrator.register("cid-2");

        assertThatThrownBy(() -> future.get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);

        orchestrator.unregister("cid-2", future);

        assertThat(orchestrator.complete("cid-2", "late")).isFalse();
        assertThat(orchestrator.pendingCount()).isZero();
    }

    @Test
    void completeWithoutRegistrationReturnsFalse() {
        assertThat(orchestrator.complete("unknown", "payload")).isFalse();
    }

    @Test
    void parallelCorrelationIdsStayIsolated() throws Exception {
        int count = 5;
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(orchestrator.register("cid-" + i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = count - 1; i >= 0; i--) {
                String id = "cid-" + i;
                CompletableFuture<String> future = futures.get(i);
                results.add(executor.submit(() -> {
                    orchestrator.complete(id, "payload-" + id);
                    return future.get(2, TimeUnit.SECONDS);
                }));
            }

            for (int i = 0; i < count; i++) {
                assertThat(results.get(i).get(5, TimeUnit.SECONDS))
                        .isEqualTo("payload-cid-" + (count - 1 - i));
            }
            assertThat(orchestrator.pendingCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }
}
