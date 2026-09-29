package com.example.demo.controller;

import com.example.demo.common.GlobalExceptionHandler;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.dto.AuthSessionResponse;
import com.example.demo.entity.AppUser;
import com.example.demo.security.AuthSession;
import com.example.demo.service.AuthService;
import com.example.demo.service.ProductService;
import com.example.demo.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthHttpTest {

    private AuthService authService;
    private AuthServiceImpl authServiceImpl;
    private ProductService productService;
    private MockMvc authMvc;
    private MockMvc protectedMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        authServiceImpl = mock(AuthServiceImpl.class);
        productService = mock(ProductService.class);

        authMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        protectedMvc = MockMvcBuilders.standaloneSetup(new ProductController(productService))
                .addInterceptors(new AuthInterceptor(authServiceImpl))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsUnifiedSuccessResponse() throws Exception {
        AuthSessionResponse session = new AuthSessionResponse();
        session.setId(1L);
        session.setUsername("admin");
        session.setToken("test-token");
        when(authService.login(any())).thenReturn(session);

        authMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("登录成功"))
                .andExpect(jsonPath("$.data.token").value("test-token"));
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        when(authServiceImpl.requireSession(""))
                .thenThrow(new ResponseStatusException(UNAUTHORIZED, "登录状态已失效，请重新登录"));

        protectedMvc.perform(get("/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"));
    }

    @Test
    void protectedEndpointAcceptsBearerToken() throws Exception {
        AppUser user = new AppUser();
        user.setId(1L);
        when(authServiceImpl.requireSession("valid-token"))
                .thenReturn(new AuthSession("valid-token", user));
        when(productService.getAllProducts(null, null)).thenReturn(List.of());

        protectedMvc.perform(get("/products")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(authServiceImpl).requireSession("valid-token");
    }

    @Test
    void protectedEndpointAcceptsLegacyXTokenHeader() throws Exception {
        AppUser user = new AppUser();
        user.setId(1L);
        when(authServiceImpl.requireSession("legacy-token"))
                .thenReturn(new AuthSession("legacy-token", user));
        when(productService.getAllProducts(null, null)).thenReturn(List.of());

        protectedMvc.perform(get("/products").header("X-Token", "legacy-token"))
                .andExpect(status().isOk());

        verify(authServiceImpl).requireSession("legacy-token");
    }
}
