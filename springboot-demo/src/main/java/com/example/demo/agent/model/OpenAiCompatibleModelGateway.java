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
    private static final String SYSTEM_PROMPT = """
            你是五金店经营助手。你可以查询客户、商品和库存，管理商品上架状态、新增商品、盘点调整库存，也可以创建和提交销售草稿。
            只能根据工具返回的数据回答，不得编造客户、商品、价格或库存。
            搜索存在多个结果时，应清楚列出候选项，不得擅自选择。
            上架、下架或调整库存前，必须先通过搜索或查询工具确认唯一的商品 ID；如果有多个候选商品，必须询问用户。
            新增商品至少需要明确商品名称和唯一条码；缺少这些字段时必须先询问，不得自行编造。新增商品、上下架和库存调整都需要用户确认。
            盘点库存时必须明确调整后的实际数量和原因，不得把增减数量误当成实际数量。
            创建销售草稿前必须明确客户 ID、商品 ID 和数量；草稿需要用户确认后才会真正创建。
            修改已有草稿前先读取草稿；修改时必须提交完整的新草稿内容和当前草稿版本，不能只传局部修改字段。
            只有用户意图明确时才可请求把草稿提交为正式销售单；正式提交还需要第二次用户确认。
            正式销售单会更新客户欠款，但不会自动出库。当前不得收款、删除商品或删除业务数据。
            """;

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
        Map<String, Object> body = requestBody(context, tools);

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return parseResponse(response);
    }

    @Override
    public AgentModelResponse respondStreaming(
            AgentRunContext context,
            List<AgentToolDefinition> tools,
            AgentModelStreamListener listener) {
        Map<String, Object> body = requestBody(context, tools);
        body.put("stream", true);
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
                        return parseStreamingResponse(reader, listener == null ? AgentModelStreamListener.NONE : listener);
                    } catch (IOException exception) {
                        throw new IllegalStateException("读取模型流式响应失败", exception);
                    }
                });
    }

    private Map<String, Object> requestBody(
            AgentRunContext context,
            List<AgentToolDefinition> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", buildMessages(context));
        body.put("tools", tools.stream().map(this::toToolPayload).toList());
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
            AgentModelStreamListener listener) throws IOException {
        StringBuilder content = new StringBuilder();
        Map<Integer, StreamedToolCall> calls = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
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

    private List<Map<String, Object>> buildMessages(AgentRunContext context) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
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
