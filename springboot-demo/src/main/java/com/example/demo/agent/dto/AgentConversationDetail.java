package com.example.demo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AgentConversationDetail {
    private Long id;
    private String title;
    private List<AgentConversationMessage> messages;
}
