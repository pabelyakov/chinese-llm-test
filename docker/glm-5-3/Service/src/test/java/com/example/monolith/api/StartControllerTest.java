package com.example.monolith.api;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.example.monolith.config.AppProperties;
import com.example.monolith.flow.FlowOrchestrator;
import com.example.monolith.kafka.KafkaPublisher;
import com.example.monolith.model.Enrichment;
import com.example.monolith.model.FlowMessage;
import com.example.monolith.redis.RedisMessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StartControllerTest {

    private final KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
    private final FlowOrchestrator flowOrchestrator = mock(FlowOrchestrator.class);
    private final RedisMessageStore redisMessageStore = mock(RedisMessageStore.class);

    private final AppProperties properties = new AppProperties(
            "messages.topic", "messages.exchange", "messages.queue", "messages.key",
            "message:", Duration.ofMinutes(10), Duration.ofMillis(200), Duration.ofSeconds(5));

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StartController controller =
                new StartController(kafkaPublisher, flowOrchestrator, redisMessageStore, properties);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    void happyPathReturnsBothEnrichmentsAndRedisMarker() throws Exception {
        when(flowOrchestrator.register(anyString()))
                .thenReturn(CompletableFuture.completedFuture("stored-payload"));
        FlowMessage stored = FlowMessage.initial("any", "hello")
                .withEnrichment(new Enrichment("KAFKA", "2026-10-08T12:00:00Z",
                        "consumed from partition 1 offset 42"))
                .withEnrichment(new Enrichment("RABBITMQ", "2026-10-08T12:00:01Z",
                        "consumed from queue messages.queue"));
        when(redisMessageStore.find(anyString())).thenReturn(Optional.of(stored));

        mockMvc.perform(post("/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.originalMessage").value("hello"))
                .andExpect(jsonPath("$.finalMessage").value("hello"))
                .andExpect(jsonPath("$.enrichments.length()").value(2))
                .andExpect(jsonPath("$.enrichments[0].stage").value("KAFKA"))
                .andExpect(jsonPath("$.enrichments[1].stage").value("RABBITMQ"))
                .andExpect(jsonPath("$.retrievedFromRedisAt").isNotEmpty());

        verify(kafkaPublisher).publish(any(FlowMessage.class));
        verify(flowOrchestrator).unregister(anyString(), any());
    }

    @Test
    void timeoutYields504WithDiagnosticBody() throws Exception {
        when(flowOrchestrator.register(anyString())).thenReturn(new CompletableFuture<>());

        mockMvc.perform(post("/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"slow\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.error").value("Gateway Timeout"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty());

        verify(flowOrchestrator).unregister(anyString(), any());
    }

    @Test
    void redisKeyMissingYields502() throws Exception {
        when(flowOrchestrator.register(anyString()))
                .thenReturn(CompletableFuture.completedFuture("stored-payload"));
        when(redisMessageStore.find(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"lost\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not found in Redis")));
    }

    @Test
    void blankMessageYields400() throws Exception {
        mockMvc.perform(post("/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("message")));
    }

    @Test
    void missingMessageFieldYields400() throws Exception {
        mockMvc.perform(post("/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonYields400() throws Exception {
        mockMvc.perform(post("/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
