package com.example.pipeline.api;

import com.example.pipeline.config.PipelineProperties;
import com.example.pipeline.correlation.CorrelationRegistry;
import com.example.pipeline.domain.Envelope;
import com.example.pipeline.pipeline.KafkaProducerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/v1")
public class StartController {

    private static final Logger log = LoggerFactory.getLogger(StartController.class);

    private final CorrelationRegistry registry;
    private final KafkaProducerService kafkaProducerService;
    private final PipelineProperties props;

    public StartController(CorrelationRegistry registry,
                           KafkaProducerService kafkaProducerService,
                           PipelineProperties props) {
        this.registry = registry;
        this.kafkaProducerService = kafkaProducerService;
        this.props = props;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@Valid @RequestBody StartRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[{}] POST /api/v1/start received: message='{}'", correlationId, request.message());

        Envelope seed = new Envelope(correlationId, request.message(), Instant.now().toString());
        CorrelationRegistry.Entry entry = registry.register(correlationId);
        CompletableFuture<Envelope> future = entry.future();

        try {
            kafkaProducerService.sendInbound(seed);

            Envelope result = future.get(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            log.info("[{}] responding 200 with final envelope (stages={})", correlationId, result.getStages().size());
            return ResponseEntity.ok(result);

        } catch (KafkaProducerService.KafkaUnavailableException e) {
            log.error("[{}] Kafka unavailable: {}", correlationId, e.getMessage());
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, correlationId,
                    "kafka_unavailable", "Failed to produce to Kafka topic " + props.getKafkaTopic(),
                    entry.lastStage().get());

        } catch (TimeoutException e) {
            log.error("[{}] timeout after {}s, stuck at stage '{}'",
                    correlationId, props.getTimeoutSeconds(), entry.lastStage().get());
            return errorResponse(HttpStatus.GATEWAY_TIMEOUT, correlationId,
                    "pipeline_timeout",
                    "Pipeline did not complete within " + props.getTimeoutSeconds() + "s",
                    entry.lastStage().get());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, correlationId,
                    "interrupted", "Request interrupted", entry.lastStage().get());

        } catch (ExecutionException e) {
            log.error("[{}] pipeline execution error: {}", correlationId, e.getCause().getMessage());
            return errorResponse(HttpStatus.BAD_GATEWAY, correlationId,
                    "pipeline_error", String.valueOf(e.getCause().getMessage()), entry.lastStage().get());

        } catch (Exception e) {
            log.error("[{}] unexpected error: {}", correlationId, e.getMessage(), e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, correlationId,
                    "internal_error", String.valueOf(e.getMessage()), entry.lastStage().get());

        } finally {
            registry.remove(correlationId);
        }
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String correlationId,
                                                              String error, String detail, String stuckAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correlationId", correlationId);
        body.put("error", error);
        body.put("detail", detail);
        body.put("stuckAtStage", stuckAt);
        body.put("at", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
