package com.example.demo.service;

import com.example.demo.dto.AuthLoginRequest;
import com.example.demo.dto.AuthSessionResponse;
import com.example.demo.entity.AppUser;
import com.example.demo.repository.AppUserRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.security.AuthTokenStore;
import com.example.demo.security.PasswordSupport;
import com.example.demo.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private AppUserRepository appUserRepository;
    private OperationLogRepository operationLogRepository;
    private AuthTokenStore authTokenStore;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        operationLogRepository = mock(OperationLogRepository.class);
        authTokenStore = new AuthTokenStore();
        authService = new AuthServiceImpl(appUserRepository, operationLogRepository, authTokenStore);
    }

    @Test
    void loginCreatesSessionAndOperationLog() {
        AppUser user = enabledUser();
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));

        AuthSessionResponse response = authService.login(loginRequest("  admin  ", "123456"));

        assertNotNull(response.getToken());
        assertFalse(response.getToken().isBlank());
        assertEquals(user.getId(), response.getId());
        assertEquals("admin", response.getUsername());
        assertEquals("ADMIN", response.getRole());
        assertEquals(user.getId(), authService.getCurrentUser(response.getToken()).getId());
        verify(operationLogRepository).save(any());
        assertTrue(PasswordSupport.isEncoded(user.getPassword()));
        assertTrue(PasswordSupport.matches("123456", user.getPassword()));
        verify(appUserRepository).save(user);
    }

    @Test
    void loginAcceptsAlreadyHashedPasswordWithoutRewritingIt() {
        AppUser user = enabledUser();
        String encodedPassword = PasswordSupport.encode("123456");
        user.setPassword(encodedPassword);
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        AuthSessionResponse response = authService.login(loginRequest("admin", "123456"));

        assertNotNull(response.getToken());
        assertEquals(encodedPassword, user.getPassword());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void loginRejectsBlankCredentials() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(loginRequest(" ", "")));

        assertTrue(exception.getMessage().contains("不能为空"));
        verify(appUserRepository, never()).findByUsername(any());
        verify(operationLogRepository, never()).save(any());
    }

    @Test
    void loginRejectsUnknownUserAndWrongPassword() {
        when(appUserRepository.findByUsername("missing")).thenReturn(Optional.empty());
        assertUnauthorized(() -> authService.login(loginRequest("missing", "123456")));

        AppUser user = enabledUser();
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        assertUnauthorized(() -> authService.login(loginRequest("admin", "wrong")));

        verify(operationLogRepository, never()).save(any());
    }

    @Test
    void loginRejectsDisabledUser() {
        AppUser user = enabledUser();
        user.setStatus(0);
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.login(loginRequest("admin", "123456")));

        assertEquals(401, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("禁用"));
        verify(operationLogRepository, never()).save(any());
    }

    @Test
    void logoutInvalidatesToken() {
        AppUser user = enabledUser();
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
        String token = authService.login(loginRequest("admin", "123456")).getToken();

        authService.logout(token);

        assertUnauthorized(() -> authService.getCurrentUser(token));
        verify(operationLogRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void currentUserRejectsMissingToken() {
        assertUnauthorized(() -> authService.getCurrentUser(""));
    }

    @Test
    void disablingUserInvalidatesExistingTokenImmediately() {
        AppUser user = enabledUser();
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
        String token = authService.login(loginRequest("admin", "123456")).getToken();

        user.setStatus(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.getCurrentUser(token));
        assertEquals(401, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("禁用"));
        assertUnauthorized(() -> authService.getCurrentUser(token));
    }

    private AppUser enabledUser() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("123456");
        user.setName("系统管理员");
        user.setRole("ADMIN");
        user.setStatus(1);
        return user;
    }

    private AuthLoginRequest loginRequest(String username, String password) {
        AuthLoginRequest request = new AuthLoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private void assertUnauthorized(org.junit.jupiter.api.function.Executable executable) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, executable);
        assertEquals(401, exception.getStatusCode().value());
    }
}
