package com.example.demo.agent.trace;

import com.example.demo.agent.entity.AgentToolCallTrace;
import com.example.demo.agent.repository.AgentToolCallTraceRepository;
import com.example.demo.agent.model.AgentToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AgentTraceRecorder {
    private final AgentToolCallTraceRepository repository;
    private final ObjectMapper objectMapper;

    public AgentTraceRecorder(AgentToolCallTraceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(Long runId, AgentToolCall call, Object result, String status, long durationMs) {
        AgentToolCallTrace trace = new AgentToolCallTrace();
        trace.setRunId(runId);
        trace.setToolCallId(call.getId());
        trace.setToolName(call.getName());
        trace.setArgumentsJson(toJson(call.getArguments()));
        trace.setResultJson(toJson(result));
        trace.setStatus(status);
        trace.setDurationMs(durationMs);
        trace.setCreateTime(LocalDateTime.now());
        repository.save(trace);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"serializationError\":true}";
        }
    }
}
