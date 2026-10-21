package com.example.demo.service.impl;

import com.example.demo.entity.AppUser;
import com.example.demo.entity.OperationLog;
import com.example.demo.repository.AppUserRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.security.AuthTokenStore;
import com.example.demo.security.PasswordSupport;
import com.example.demo.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 系统设置服务实现
 */
@Service
@Transactional
public class SettingsServiceImpl implements SettingsService {

    private final AppUserRepository appUserRepository;
    private final OperationLogRepository operationLogRepository;
    private final AuthTokenStore authTokenStore;

    public SettingsServiceImpl(AppUserRepository appUserRepository, OperationLogRepository operationLogRepository) {
        this(appUserRepository, operationLogRepository, null);
    }

    @Autowired
    public SettingsServiceImpl(AppUserRepository appUserRepository, OperationLogRepository operationLogRepository,
                               AuthTokenStore authTokenStore) {
        this.appUserRepository = appUserRepository;
        this.operationLogRepository = operationLogRepository;
        this.authTokenStore = authTokenStore;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppUser> getUsers() {
        return appUserRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppUser> getUserById(Long id) {
        return appUserRepository.findById(id);
    }

    @Override
    public AppUser saveUser(AppUser user) {
        fillUserDefaults(user);
        validateUser(user);
        ensureUsernameAvailable(user.getUsername(), null);
        user.setPassword(PasswordSupport.encode(user.getPassword()));
        if (user.getCreateTime() == null) {
            user.setCreateTime(LocalDateTime.now());
        }
        AppUser saved = appUserRepository.save(user);
        saveOperationLog(saved.getId(), "SETTINGS", "CREATE_USER", "新增用户：" + saved.getUsername());
        return saved;
    }

    @Override
    public AppUser updateUser(Long id, AppUser user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        AppUser existingUser = getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在，无法更新"));
        String existingPassword = existingUser.getPassword();
        String password = StringUtils.hasText(user.getPassword())
                ? PasswordSupport.encode(user.getPassword())
                : existingUser.getPassword();
        user.setPassword(password);
        if (user.getStatus() == null) {
            user.setStatus(existingUser.getStatus());
        }
        validateUser(user);
        ensureUsernameAvailable(user.getUsername(), id);
        existingUser.setUsername(user.getUsername());
        existingUser.setPassword(password);
        existingUser.setName(user.getName());
        existingUser.setPhone(user.getPhone());
        existingUser.setRole(user.getRole());
        if (user.getStatus() != null) {
            existingUser.setStatus(user.getStatus());
        }
        AppUser saved = appUserRepository.save(existingUser);
        if (!password.equals(existingPassword) || !Integer.valueOf(1).equals(saved.getStatus())) {
            revokeSessions(saved.getId());
        }
        saveOperationLog(saved.getId(), "SETTINGS", "UPDATE_USER", "更新用户：" + saved.getUsername());
        return saved;
    }

    @Override
    public AppUser updateUserRole(Long id, String role) {
        validateRole(role);
        AppUser user = getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在，无法修改角色"));
        user.setRole(role);
        AppUser saved = appUserRepository.save(user);
        saveOperationLog(saved.getId(), "SETTINGS", "UPDATE_ROLE", "修改角色为：" + role);
        return saved;
    }

    @Override
    public AppUser updateUserStatus(Long id, Integer status) {
        validateStatus(status);
        AppUser user = getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在，无法修改状态"));
        user.setStatus(status);
        AppUser saved = appUserRepository.save(user);
        if (status != 1) {
            revokeSessions(saved.getId());
        }
        saveOperationLog(saved.getId(), "SETTINGS", "UPDATE_STATUS", "修改状态为：" + status);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationLog> getLogs(String module, Long operatorId) {
        return operationLogRepository.findAll().stream()
                .filter(log -> !StringUtils.hasText(module) || module.equalsIgnoreCase(log.getModule()))
                .filter(log -> operatorId == null || operatorId.equals(log.getOperatorId()))
                .collect(Collectors.toList());
    }

    /** 改密码或禁用账号后，该用户所有设备必须重新登录。 */
    private void revokeSessions(Long userId) {
        if (authTokenStore != null) {
            authTokenStore.removeUserSessions(userId);
        }
    }

    private void saveOperationLog(Long operatorId, String module, String action, String detail) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule(module);
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(log);
    }

    private void fillUserDefaults(AppUser user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
    }

    private void validateUser(AppUser user) {
        if (!StringUtils.hasText(user.getUsername())) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (!StringUtils.hasText(user.getName())) {
            throw new IllegalArgumentException("用户姓名不能为空");
        }
        if (!StringUtils.hasText(user.getPassword())) {
            throw new IllegalArgumentException("密码不能为空");
        }
        user.setUsername(user.getUsername().trim());
        user.setName(user.getName().trim());
        validateRole(user.getRole());
        validateStatus(user.getStatus());
    }

    private void validateRole(String role) {
        if (!"ADMIN".equals(role) && !"CLERK".equals(role)) {
            throw new IllegalArgumentException("用户角色只能是 ADMIN 或 CLERK");
        }
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("用户状态只能是 0 或 1");
        }
    }

    private void ensureUsernameAvailable(String username, Long currentUserId) {
        appUserRepository.findByUsername(username).ifPresent(existing -> {
            if (currentUserId == null || !currentUserId.equals(existing.getId())) {
                throw new IllegalArgumentException("用户名已存在");
            }
        });
    }
}
