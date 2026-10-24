package com.example.demo.agent.model;

import com.example.demo.billing.HandwrittenItemContinuation;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One current business contract for the model and instruction snapshots. */
public final class AgentBusinessRules {
    private AgentBusinessRules() {}
    public static final String TEXT = load() + HandwrittenItemContinuation.MODEL_RULE;
    private static String load() {
        try {
            return new ClassPathResource("agent/business-rules.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载小五业务规则", exception);
        }
    }
}
