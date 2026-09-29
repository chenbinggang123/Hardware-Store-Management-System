package com.example.demo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AgentConversationSummary {
    private Long id;
    private String title;
    private String preview;
    private LocalDateTime updateTime;
}
