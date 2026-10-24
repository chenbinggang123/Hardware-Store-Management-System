package com.example.demo.agent.model;

import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "agent.model", name = "enabled", havingValue = "true")
public class OpenAiCompatibleModelGateway implements AgentModelGateway {
    private static final String SYSTEM_PROMPT = AgentBusinessRules.TEXT;

    private final AgentModelProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleModelGateway(
            AgentModelProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("已启用 Agent 模型，但 AGENT_MODEL_API_KEY 或 AGENT_MODEL_NAME 未配置");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.max(properties.getTimeoutSeconds(), 1) * 1000;
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.restClient = restClientBuilder
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public AgentModelResponse respond(AgentRunContext context, List<AgentToolDefinition> tools) {
        long started = System.nanoTime();
        try {
        Map<String, Object> body = requestBody(context, tools);

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        recordMeasurement(context,body,response,(System.nanoTime()-started)/1_000_000,null);
        return restoreResponse(context,tools,parseResponse(response));
        } finally {
            org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelGateway.class).info("Agent model request runId={} stream=false elapsedMs={}",context.getRunId(),(System.nanoTime()-started)/1_000_000);
        }
    }

    @Override
    public AgentModelResponse respondStreaming(
            AgentRunContext context,
            List<AgentToolDefinition> tools,
            AgentModelStreamListener listener) {
        long started = System.nanoTime();
        Map<String, Object> body = requestBody(context, tools);
        body.put("stream", true);
        long[] firstEventMs = {-1};
        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new IllegalStateException("模型流式请求失败：HTTP " + response.getStatusCode().value());
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                response.getBody(), StandardCharsets.UTF_8))) {
                            AgentModelResponse result=parseStreamingResponse(reader, listener == null ? AgentModelStreamListener.NONE : listener,
                                    () -> {if(firstEventMs[0]<0)firstEventMs[0]=(System.nanoTime()-started)/1_000_000;});
                            recordMeasurement(context,body,null,(System.nanoTime()-started)/1_000_000,firstEventMs[0]<0?null:firstEventMs[0]);
                            return restoreResponse(context,tools,result);
                        } catch (IOException exception) {
                            throw new IllegalStateException("读取模型流式响应失败", exception);
                        }
                    });
        } catch (IllegalArgumentException exception) {
            if (!"模型工具参数不是有效 JSON".equals(exception.getMessage())) throw exception;
            // Some OpenAI-compatible providers occasionally truncate streamed tool arguments.
            // Retry once without streaming so the provider returns one complete JSON payload.
            return respond(context, tools);
        } finally {
            org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelGateway.class).info("Agent model request runId={} stream=true elapsedMs={}",context.getRunId(),(System.nanoTime()-started)/1_000_000);
        }
    }

    private Map<String, Object> requestBody(
            AgentRunContext context,
            List<AgentToolDefinition> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        var profile=BillingRecognitionProfile.select(context,tools);
        body.put("messages", buildMessages(context,profile.map(BillingRecognitionProfile::rules).orElse(SYSTEM_PROMPT)));
        body.put("tools", tools.stream().map(t->profile.map(p->p.compact(t)).orElse(t)).map(this::toToolPayload).toList());
        body.put("tool_choice", "auto");
        body.put("temperature", 0);
        if (properties.getBaseUrl().contains("api.deepseek.com")) {
            // DeepSeek defaults to thinking mode, whose multi-step tool calls require
            // reasoning_content to be replayed. This harness uses the simpler
            // non-thinking tool-call protocol.
            body.put("thinking", Map.of("type", "disabled"));
        }
        return body;
    }

    private AgentModelResponse parseStreamingResponse(
            BufferedReader reader,
            AgentModelStreamListener listener, Runnable onEvent) throws IOException {
        StringBuilder content = new StringBuilder();
        Map<Integer, StreamedToolCall> calls = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            onEvent.run();
            JsonNode event;
            try {
                event = objectMapper.readTree(data);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("模型流式响应不是有效 JSON", exception);
            }
            JsonNode delta = event.path("choices").path(0).path("delta");
            JsonNode contentNode = delta.path("content");
            if (contentNode.isTextual() && !contentNode.asText().isEmpty()) {
                String text = contentNode.asText();
                content.append(text);
                listener.onDelta(text);
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode node : toolCalls) {
                    int index = node.path("index").asInt(calls.size());
                    StreamedToolCall call = calls.computeIfAbsent(index, ignored -> new StreamedToolCall());
                    if (node.path("id").isTextual()) call.id.append(node.path("id").asText());
                    JsonNode function = node.path("function");
                    if (function.path("name").isTextual()) call.name.append(function.path("name").asText());
                    if (function.path("arguments").isTextual()) call.arguments.append(function.path("arguments").asText());
                }
            }
        }
        if (!calls.isEmpty()) {
            List<AgentToolCall> result = calls.entrySet().stream()
                    .sorted(Comparator.comparingInt(Map.Entry::getKey))
                    .map(entry -> {
                        StreamedToolCall call = entry.getValue();
                        String arguments = call.arguments.isEmpty() ? "{}" : call.arguments.toString();
                        return new AgentToolCall(call.id.toString(), call.name.toString(), readArguments(arguments));
                    }).toList();
            return AgentModelResponse.tools(result);
        }
        if (content.isEmpty()) {
            throw new IllegalStateException("模型未返回可用内容");
        }
        return AgentModelResponse.finalAnswer(content.toString());
    }

    private static final class StreamedToolCall {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    private List<Map<String, Object>> buildMessages(AgentRunContext context, String systemPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AgentChatMessage historyMessage : context.getConversationHistory()) {
            if (StringUtils.hasText(historyMessage.content())
                    && ("user".equals(historyMessage.role()) || "assistant".equals(historyMessage.role()))) {
                messages.add(Map.of("role", historyMessage.role(), "content", historyMessage.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", context.getUserInput()));
        for (Map<String, Object> entry : context.getToolResults()) {
            String callId = String.valueOf(entry.get("toolCallId"));
            String toolName = String.valueOf(entry.get("toolName"));
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", toolName);
            function.put("arguments", toJson(entry.get("arguments")));
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", callId);
            toolCall.put("type", "function");
            toolCall.put("function", function);
            messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall)));
            messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", callId,
                    "name", toolName,
                    "content", toJson(entry.get("result"))));
        }
        return messages;
    }

    private Map<String, Object> toToolPayload(AgentToolDefinition definition) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", definition.getName(),
                        "description", definition.getDescription(),
                        "parameters", definition.getInputSchema()));
    }

    private AgentModelResponse restoreResponse(AgentRunContext context,List<AgentToolDefinition> tools,AgentModelResponse response) {
        return BillingRecognitionProfile.select(context,tools).map(p->p.restore(response)).orElse(response);
    }

    private void recordMeasurement(AgentRunContext context,Map<String,Object> body,JsonNode response,long elapsedMs,Long firstEventMs) {
        Map<String,Object> sample=new LinkedHashMap<>();
        sample.put("elapsedMs",elapsedMs);sample.put("requestChars",toJson(body).length());
        sample.put("systemChars",((String)((Map<?,?>)((List<?>)body.get("messages")).get(0)).get("content")).length());
        if(firstEventMs!=null)sample.put("firstEventMs",firstEventMs);
        if(response!=null){
            sample.put("responseChars",response.path("choices").toString().length());
            for(String field:List.of("prompt_tokens","completion_tokens","total_tokens"))
                if(response.path("usage").path(field).isNumber())sample.put(field,response.path("usage").path(field).asLong());
        }
        context.getModelMeasurements().add(sample);
        org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelGateway.class).info(
                "Agent model sizes runId={} requestChars={} responseChars={} promptTokens={} completionTokens={} firstEventMs={}",
                context.getRunId(),sample.get("requestChars"),sample.get("responseChars"),sample.get("prompt_tokens"),sample.get("completion_tokens"),sample.get("firstEventMs"));
    }

    private AgentModelResponse parseResponse(JsonNode response) {
        JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
        if (message == null || message.isMissingNode()) {
            throw new IllegalStateException("模型返回格式不正确");
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<AgentToolCall> calls = new ArrayList<>();
            for (JsonNode node : toolCalls) {
                JsonNode function = node.path("function");
                calls.add(new AgentToolCall(
                        node.path("id").asText(),
                        function.path("name").asText(),
                        readArguments(function.path("arguments").asText("{}"))));
            }
            return AgentModelResponse.tools(calls);
        }
        String content = message.path("content").asText();
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("模型未返回可用内容");
        }
        return AgentModelResponse.finalAnswer(content);
    }

    private Map<String, Object> readArguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("模型工具参数不是有效 JSON", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 上下文序列化失败", exception);
        }
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Agent 模型地址不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
