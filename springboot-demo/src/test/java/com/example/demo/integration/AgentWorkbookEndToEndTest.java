package com.example.demo.integration;

import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.entity.AppUser;
import com.example.demo.repository.AppUserRepository;
import com.example.demo.security.PasswordSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real HTTP API with a scripted model: a receipt the model has organised becomes an
 * approval card, a permanent XLSX on disk, a file card in the run and the conversation, a download,
 * and finally a deletion by its owner. It answers the question "where did the file go?".
 */
@SpringBootTest(properties = "agent.attachment.local-directory=${java.io.tmpdir}/agent-e2e-storage")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentWorkbookEndToEndTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private AgentModelGateway gateway;

    private AppUser user;
    private Long runId;
    private Long conversationId;

    @AfterEach
    void cleanUp() {
        if (runId != null) {
            jdbc.update("delete from agent_tool_call where run_id = ?", runId);
            jdbc.update("delete from agent_conversation_run where run_id = ?", runId);
            jdbc.update("delete from agent_run where id = ?", runId);
        }
        if (conversationId != null) jdbc.update("delete from agent_conversation where id = ?", conversationId);
        if (user != null && user.getId() != null) {
            jdbc.update("delete from excel_task_output where operator_id = ?", user.getId());
            jdbc.update("delete from operation_log where operator_id = ?", user.getId());
            users.deleteById(user.getId());
        }
    }

    @Test
    void receiptBecomesPermanentDownloadableWorkbookThatOwnerCanDelete() throws Exception {
        String password = "E2ePass123!";
        user = new AppUser();
        user.setUsername("e2e-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword(PasswordSupport.encode(password));
        user.setName("端到端测试");
        user.setPhone("13800000000");
        user.setRole("ADMIN");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user = users.save(user);
        String token = login(user.getUsername(), password);

        AtomicInteger modelCalls = new AtomicInteger();
        when(gateway.respond(any(), any())).thenAnswer(invocation -> modelCalls.getAndIncrement() == 0
                ? AgentModelResponse.tools(List.of(new AgentToolCall("call-1", "generate_excel_workbook", receiptArguments())))
                : AgentModelResponse.finalAnswer("已生成 Excel 文件，请在下方打开。"));

        // 1. The model asks to generate; the run pauses with an Excel preview card.
        JsonNode started = data(perform(post("/agent/messages").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"把这张收据做成 Excel\",\"attachmentIds\":[]}")));
        runId = started.path("runId").asLong();
        conversationId = started.path("conversationId").asLong();
        assertThat(started.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        JsonNode preview = started.path("pendingAction").path("preview");
        assertThat(preview.path("kind").asText()).isEqualTo("EXCEL_WORKBOOK");
        assertThat(preview.path("rowCount").asInt()).isEqualTo(7);
        assertThat(preview.path("mismatchCount").asInt()).isEqualTo(1);
        assertThat(preview.path("sheets").get(0).path("sampleRows").size()).isEqualTo(5);

        // 2. The owner confirms; the file is generated synchronously and attached to the run.
        JsonNode approved = data(perform(post("/agent/runs/" + runId + "/approval")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}")));
        assertThat(approved.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(approved.path("output").asText()).contains("已生成 Excel 文件");
        assertThat(approved.path("files").size()).isEqualTo(1);
        JsonNode file = approved.path("files").get(0);
        long outputId = file.path("id").asLong();
        assertThat(file.path("status").asText()).isEqualTo("READY");
        assertThat(file.path("expiresAt").isMissingNode() || file.path("expiresAt").isNull()).isTrue();
        assertThat(file.path("downloadPath").asText()).isEqualTo("/agent/outputs/" + outputId + "/download");
        assertThat(file.path("name").asText()).startsWith("恒迪模具五金收款收据0002766_").endsWith(".xlsx");

        // 3. The file is visible wherever the client looks: run, conversation history, my-files list.
        JsonNode run = data(perform(get("/agent/runs/" + runId).header("Authorization", "Bearer " + token)));
        assertThat(run.path("files").get(0).path("id").asLong()).isEqualTo(outputId);
        JsonNode conversation = data(perform(get("/agent/conversations/" + conversationId)
                .header("Authorization", "Bearer " + token)));
        JsonNode lastMessage = conversation.path("messages").get(conversation.path("messages").size() - 1);
        assertThat(lastMessage.path("role").asText()).isEqualTo("assistant");
        assertThat(lastMessage.path("files").get(0).path("id").asLong()).isEqualTo(outputId);
        JsonNode mine = data(perform(get("/agent/outputs").header("Authorization", "Bearer " + token)));
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("id").asLong()).isEqualTo(outputId);

        // 4. Downloading returns a real workbook with verified amounts and a SUM total.
        MvcResult pending = mockMvc.perform(get("/agent/outputs/" + outputId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted()).andReturn();
        byte[] bytes = mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("商品明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("钻头 2.2");
            assertThat(sheet.getRow(10).getCell(5).getNumericCellValue()).isEqualTo(504d);
            assertThat(sheet.getRow(10).getCell(7).getStringCellValue()).contains("28\u00d71.8=50.40");
            assertThat(sheet.getRow(11).getCell(0).getStringCellValue()).isEqualTo("合计");
            assertThat(sheet.getRow(11).getCell(5).getNumericCellValue()).isEqualTo(699d);
            assertThat(workbook.getSheet("单据信息").getRow(2).getCell(1).getStringCellValue()).isEqualTo("0002766");
            assertThat(workbook.getSheet("生成说明")).isNotNull();
        }

        // 5. On disk the object sits under agent-outputs/<operatorId>/<yyyyMM>/ in the attachment storage root.
        Path storageRoot = Path.of(System.getProperty("java.io.tmpdir"), "agent-e2e-storage", "agent-outputs",
                String.valueOf(user.getId()));
        Path stored = findStored(storageRoot, outputId);
        assertThat(stored).isNotNull();
        assertThat(Files.size(stored)).isEqualTo(bytes.length);

        // 6. The owner deletes it: record, listing and disk object all go away.
        perform(post("/agent/outputs/" + outputId + "/delete").header("Authorization", "Bearer " + token));
        assertThat(data(perform(get("/agent/outputs").header("Authorization", "Bearer " + token))).size()).isZero();
        assertThat(Files.exists(stored)).isFalse();
        mockMvc.perform(get("/agent/outputs/" + outputId + "/download").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // 7. Conversation cleanup through the API still works with files gone.
        mockMvc.perform(delete("/agent/conversations/" + conversationId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        JsonNode session = data(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password)))));
        return session.path("token").asText();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.path("success").asBoolean()).as(body.toString()).isTrue();
        return body.path("data");
    }

    private Path findStored(Path root, long outputId) throws IOException {
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(outputId + "-"))
                    .findFirst().orElse(null);
        }
    }

    /** The seven handwritten lines of the sample receipt, as the model would hand them over. */
    private Map<String, Object> receiptArguments() {
        Map<String, Object> sheet = new HashMap<>();
        sheet.put("name", "商品明细");
        sheet.put("columns", List.of(
                column("行号", "INTEGER", null), column("项目", "TEXT", null), column("单位", "TEXT", null),
                column("数量", null, "QUANTITY"), column("单价", null, "UNIT_PRICE"), column("金额", null, "AMOUNT")));
        sheet.put("rows", List.of(
                Arrays.asList(1, "钻头 2.2", "支", 10, 1.5, 15),
                Arrays.asList(2, "钻头 3.2 金色", "支", 10, 2, 20),
                Arrays.asList(3, "航量 3.8", "支", 10, 1.8, 18),
                Arrays.asList(4, "航量 4.8", "支", 10, 2, 20),
                Arrays.asList(5, "铣刀 20", "支", 2, 40, 80),
                Arrays.asList(6, "弹簧 12x300", "支", 6, 7, 42),
                Arrays.asList(7, "气管 10", "米", 28, 1.8, 504)));
        sheet.put("totalColumns", List.of("金额"));
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("title", "恒迪模具五金收款收据0002766");
        arguments.put("attachmentId", null);
        arguments.put("sheets", List.of(sheet));
        arguments.put("info", List.of(
                Map.of("label", "开票方", "value", "恒迪模具五金"),
                Map.of("label", "单号", "value", "0002766"),
                Map.of("label", "日期", "value", "2026-10-06"),
                Map.of("label", "票面合计原文", "value", "2451")));
        arguments.put("includeSourceText", false);
        return arguments;
    }

    private Map<String, Object> column(String name, String type, String role) {
        Map<String, Object> column = new HashMap<>();
        column.put("name", name);
        if (type != null) column.put("type", type);
        if (role != null) column.put("role", role);
        return column;
    }
}
