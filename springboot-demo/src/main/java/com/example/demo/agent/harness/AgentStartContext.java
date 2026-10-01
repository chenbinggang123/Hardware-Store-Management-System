package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.model.AgentChatMessage;

import java.util.List;

public record AgentStartContext(
        AgentConversation conversation,
        List<AgentChatMessage> history) {
}
