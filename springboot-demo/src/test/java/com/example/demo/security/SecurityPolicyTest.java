package com.example.demo.security;

import com.example.demo.config.AuthInterceptor;
import com.example.demo.entity.AppUser;
import com.example.demo.service.impl.AuthServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityPolicyTest {

    @Test
    void clerkCannotAccessSettingsButAdminCan() {
        AuthServiceImpl authService = mock(AuthServiceImpl.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);

        AppUser clerk = user("CLERK");
        when(authService.requireSession("clerk-token"))
                .thenReturn(new AuthSession("clerk-token", clerk));
        MockHttpServletRequest clerkRequest = request("/api", "/api/settings/users", "clerk-token");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> interceptor.preHandle(clerkRequest, new MockHttpServletResponse(), new Object()));
        assertTrue(exception.getStatusCode().value() == 403);

        AppUser admin = user("ADMIN");
        when(authService.requireSession("admin-token"))
                .thenReturn(new AuthSession("admin-token", admin));
        MockHttpServletRequest adminRequest = request("/api", "/api/settings/users", "admin-token");

        assertTrue(interceptor.preHandle(adminRequest, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void clerkCanAccessOrdinaryBusinessEndpoints() {
        AuthServiceImpl authService = mock(AuthServiceImpl.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        when(authService.requireSession("clerk-token"))
                .thenReturn(new AuthSession("clerk-token", user("CLERK")));

        MockHttpServletRequest request = request("/api", "/api/products", "clerk-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void userPasswordIsNeverSerializedInApiResponses() throws Exception {
        AppUser user = user("ADMIN");
        user.setPassword("secret-password");

        String json = new ObjectMapper().writeValueAsString(user);

        assertFalse(json.contains("password"));
        assertFalse(json.contains("secret-password"));
    }

    private MockHttpServletRequest request(String contextPath, String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath(contextPath);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private AppUser user(String role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("test");
        user.setName("测试用户");
        user.setRole(role);
        user.setStatus(1);
        return user;
    }
}
