package com.example.demo.excel.api;

import com.example.demo.config.AuthInterceptor;
import com.example.demo.entity.AppUser;
import com.example.demo.excel.service.ExcelTaskCommitService;
import com.example.demo.excel.service.ExcelTaskLifecycleService;
import com.example.demo.excel.service.ExcelTaskMappingService;
import com.example.demo.excel.service.ExcelTaskService;
import com.example.demo.security.AuthSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExcelTaskControllerTest {

    private ExcelTaskLifecycleService lifecycleService;
    private MockMvc mockMvc;
    private AuthSession session;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(ExcelTaskLifecycleService.class);
        ExcelTaskController controller = new ExcelTaskController(
                mock(ExcelTaskService.class),
                mock(ExcelTaskMappingService.class),
                mock(ExcelTaskCommitService.class),
                lifecycleService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        AppUser user = new AppUser();
        user.setId(9L);
        session = new AuthSession("test-token", user);
    }

    @Test
    void postDeleteCompatibilityEndpointDeletesTask() throws Exception {
        mockMvc.perform(post("/excel-tasks/2/delete")
                        .requestAttr(AuthInterceptor.AUTH_SESSION_ATTRIBUTE, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("文件任务已删除"));

        verify(lifecycleService).delete(2L, 9L);
    }

    @Test
    void originalDeleteEndpointRemainsAvailable() throws Exception {
        mockMvc.perform(delete("/excel-tasks/3")
                        .requestAttr(AuthInterceptor.AUTH_SESSION_ATTRIBUTE, session))
                .andExpect(status().isOk());

        verify(lifecycleService).delete(3L, 9L);
    }
}
