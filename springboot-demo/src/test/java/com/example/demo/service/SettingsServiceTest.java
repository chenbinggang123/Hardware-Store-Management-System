package com.example.demo.service;

import com.example.demo.entity.AppUser;
import com.example.demo.repository.AppUserRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.security.PasswordSupport;
import com.example.demo.service.impl.SettingsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsServiceTest {

    private AppUserRepository appUserRepository;
    private OperationLogRepository operationLogRepository;
    private SettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        operationLogRepository = mock(OperationLogRepository.class);
        service = new SettingsServiceImpl(appUserRepository, operationLogRepository);
    }

    @Test
    void createRejectsInvalidAndDuplicateUser() {
        AppUser blank = user(null, " ", "", "ADMIN", 1);
        assertThrows(IllegalArgumentException.class, () -> service.saveUser(blank));

        AppUser duplicate = user(null, "admin", "123456", "ADMIN", 1);
        when(appUserRepository.findByUsername("admin"))
                .thenReturn(Optional.of(user(1L, "admin", "123456", "ADMIN", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.saveUser(duplicate));

        AppUser invalidRole = user(null, "tester", "123456", "OWNER", 1);
        assertThrows(IllegalArgumentException.class, () -> service.saveUser(invalidRole));

        verify(appUserRepository, never()).save(any());
    }

    @Test
    void createHashesPasswordBeforeSaving() {
        AppUser input = user(null, "new-user", "123456", "CLERK", 1);
        when(appUserRepository.findByUsername("new-user")).thenReturn(Optional.empty());
        when(appUserRepository.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AppUser saved = service.saveUser(input);

        assertTrue(PasswordSupport.isEncoded(saved.getPassword()));
        assertTrue(PasswordSupport.matches("123456", saved.getPassword()));
    }

    @Test
    void updateWithBlankPasswordPreservesExistingPassword() {
        AppUser existing = user(2L, "clerk01", "old-password", "CLERK", 1);
        AppUser update = user(2L, "clerk02", " ", "CLERK", 1);
        when(appUserRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(appUserRepository.findByUsername("clerk02")).thenReturn(Optional.empty());
        when(appUserRepository.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AppUser saved = service.updateUser(2L, update);

        assertEquals("old-password", saved.getPassword());
        assertEquals("clerk02", saved.getUsername());
    }

    @Test
    void roleAndStatusOnlyAcceptSupportedValues() {
        AppUser existing = user(2L, "clerk01", "123456", "CLERK", 1);
        when(appUserRepository.findById(2L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.updateUserRole(2L, "OWNER"));
        assertThrows(IllegalArgumentException.class, () -> service.updateUserStatus(2L, 2));

        verify(appUserRepository, never()).save(any());
    }

    @Test
    void restoreRejectsBlankBackupNameAndReportsSimulation() {
        assertThrows(IllegalArgumentException.class, () -> service.restore(" "));

        assertEquals("SIMULATED", service.backup().get("status"));
        assertTrue(service.backup().get("message").toString().contains("模拟"));
    }

    private AppUser user(Long id, String username, String password, String role, Integer status) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(password);
        user.setName("测试用户");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
