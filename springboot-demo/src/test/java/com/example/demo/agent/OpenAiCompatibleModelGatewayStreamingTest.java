package com.example.demo.agent;

import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.model.AgentModelProperties;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.model.OpenAiCompatibleModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleModelGatewayStreamingTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void emitsTextDeltasAndReturnsTheCompleteAnswer() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"content":"库存"}}]}

                data: {"choices":[{"delta":{"content":" 24 件"}}]}

                data: [DONE]

                """;
        OpenAiCompatibleModelGateway gateway = gatewayReturning(response);
        List<String> deltas = new ArrayList<>();

        AgentModelResponse result = gateway.respondStreaming(
                new AgentRunContext(1L, 7L, "查库存"), List.of(), deltas::add);

        assertEquals(List.of("库存", " 24 件"), deltas);
        assertEquals("库存 24 件", result.getFinalOutput());
        assertTrue(result.getToolCalls().isEmpty());
    }

    @Test
    void assemblesToolCallsSplitAcrossSeveralSseEvents() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"check_","arguments":"{\\\"product"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"inventory","arguments":"Id\\\":12}"}}]}}]}

                data: [DONE]

                """;
        OpenAiCompatibleModelGateway gateway = gatewayReturning(response);

        AgentModelResponse result = gateway.respondStreaming(
                new AgentRunContext(1L, 7L, "查库存"), List.of(), ignored -> { });

        assertEquals(1, result.getToolCalls().size());
        assertEquals("call_1", result.getToolCalls().get(0).getId());
        assertEquals("check_inventory", result.getToolCalls().get(0).getName());
        assertEquals(12, result.getToolCalls().get(0).getArguments().get("productId"));
    }

    private OpenAiCompatibleModelGateway gatewayReturning(String sseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, sseBody));
        server.start();

        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        return new OpenAiCompatibleModelGateway(properties, new ObjectMapper(), RestClient.builder());
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
