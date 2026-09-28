package com.example.demo.agent.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.model")
@Data
public class AgentModelProperties {
    private boolean enabled;
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey;
    private String model;
    private int timeoutSeconds = 20;
}
