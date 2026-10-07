package com.example.monolith.api;

import java.util.stream.Collectors;

import com.example.monolith.flow.FlowFailedException;
import com.example.monolith.flow.FlowTimeoutException;
import com.example.monolith.flow.KafkaPublishException;
import com.example.monolith.flow.RedisKeyMissingException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(HttpStatus.BAD_REQUEST, null, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, null, "malformed JSON request body");
    }

    @ExceptionHandler(FlowTimeoutException.class)
    public ResponseEntity<ErrorResponse> onFlowTimeout(FlowTimeoutException e) {
        return respond(HttpStatus.GATEWAY_TIMEOUT, e.getCorrelationId(),
                "message did not pass Kafka -> RabbitMQ -> Redis within %s".formatted(e.getTimeout()));
    }

    @ExceptionHandler({FlowFailedException.class, KafkaPublishException.class, RedisKeyMissingException.class})
    public ResponseEntity<ErrorResponse> onFlowFailure(RuntimeException e) {
        return respond(HttpStatus.BAD_GATEWAY, null, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, null, "unexpected error: " + e.getMessage());
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String correlationId, String detail) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(), correlationId, detail));
    }
}
