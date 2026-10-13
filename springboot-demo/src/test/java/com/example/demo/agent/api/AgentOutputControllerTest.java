package com.example.demo.agent.api;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.entity.AppUser;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.security.AuthSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentOutputControllerTest {
    private AgentOutputService service;
    private AttachmentStorage storage;
    private MockMvc mockMvc;
    private AuthSession session;

    @BeforeEach
    void setUp() {
        service = mock(AgentOutputService.class);
        storage = mock(AttachmentStorage.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentOutputController(service, storage)).build();
        AppUser user = new AppUser();
        user.setId(9L);
        session = new AuthSession("test-token", user);
    }

    @Test
    void listsFilesOfCurrentUser() throws Exception {
        when(service.listMine(9L)).thenReturn(List.of(new AgentFileView(5L, "收据.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 120, "READY",
                AgentOutputService.AGENT_SOURCE_TYPE, 7L, 3L, null, LocalDateTime.now(), null,
                "/agent/outputs/5/download", null, Map.of())));

        mockMvc.perform(get("/agent/outputs").requestAttr(AuthInterceptor.AUTH_SESSION_ATTRIBUTE, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(5))
                .andExpect(jsonPath("$.data[0].downloadPath").value("/agent/outputs/5/download"));
    }

    @Test
    void deleteEndpointsBothRemoveTheFile() throws Exception {
        mockMvc.perform(post("/agent/outputs/5/delete").requestAttr(AuthInterceptor.AUTH_SESSION_ATTRIBUTE, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("文件已删除"));
        mockMvc.perform(delete("/agent/outputs/6").requestAttr(AuthInterceptor.AUTH_SESSION_ATTRIBUTE, session))
                .andExpect(status().isOk());

        verify(service).delete(5L, 9L);
        verify(service).delete(6L, 9L);
    }

    @Test
    void downloadStreamsStoredBytesWithAttachmentHeaders() throws Exception {
        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setId(5L);
        output.setObjectKey("agent-outputs/9/202610/5-abcd.xlsx");
        output.setOriginalName("收据.xlsx");
        output.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        output.setFileSize(5L);
        when(service.openForDownload(5L, 9L)).thenReturn(output);
        when(storage.openStream("agent-outputs/9/202610/5-abcd.xlsx"))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        MvcResult started = mockMvc.perform(get("/agent/outputs/5/download")
                        .requestAttr(AuthInterceptor.AUTH_SESSION_ATTRIBUTE, session))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult finished = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", output.getMimeType()))
                .andReturn();

        assertThat(finished.getResponse().getContentAsString()).isEqualTo("hello");
        assertThat(finished.getResponse().getHeader("Content-Disposition")).contains("attachment");
    }
}
