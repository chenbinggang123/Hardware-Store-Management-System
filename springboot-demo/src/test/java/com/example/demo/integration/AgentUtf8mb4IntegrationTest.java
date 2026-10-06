package com.example.demo.integration;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentUtf8mb4IntegrationTest {
    @Autowired
    private AgentRunRepository repository;

    @Test
    void streamingOutputAcceptsFourByteEmoji() {
        AgentRun run = new AgentRun();
        run.setOperatorId(1L);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setCurrentStep(0);
        run.setInputText("这个小程序怎么用");
        run.setStartedAt(LocalDateTime.now());
        run.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        run = repository.saveAndFlush(run);

        String output = "🔍 我可以帮你查询商品、库存和单据。";
        assertThat(repository.updateRunningOutput(run.getId(), output, AgentRunStatus.RUNNING)).isEqualTo(1);

        assertThat(repository.findById(run.getId()).orElseThrow().getOutputText()).isEqualTo(output);
    }
}
