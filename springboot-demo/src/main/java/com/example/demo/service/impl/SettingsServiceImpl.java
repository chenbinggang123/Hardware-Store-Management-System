package com.example.demo.service.impl;

import com.example.demo.entity.AppUser;
import com.example.demo.entity.OperationLog;
import com.example.demo.repository.AppUserRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.service.SettingsService;
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

    public SettingsServiceImpl(AppUserRepository appUserRepository, OperationLogRepository operationLogRepository) {
        this.appUserRepository = appUserRepository;
        this.operationLogRepository = operationLogRepository;
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
        if (user.getCreateTime() == null) {
            user.setCreateTime(LocalDateTime.now());
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        AppUser saved = appUserRepository.save(user);
        saveOperationLog(saved.getId(), "SETTINGS", "CREATE_USER", "新增用户：" + saved.getUsername());
        return saved;
    }

    @Override
    public AppUser updateUser(Long id, AppUser user) {
        AppUser existingUser = getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在，无法更新"));
        existingUser.setUsername(user.getUsername());
        existingUser.setPassword(user.getPassword());
        existingUser.setName(user.getName());
        existingUser.setPhone(user.getPhone());
        existingUser.setRole(user.getRole());
        if (user.getStatus() != null) {
            existingUser.setStatus(user.getStatus());
        }
        AppUser saved = appUserRepository.save(existingUser);
        saveOperationLog(saved.getId(), "SETTINGS", "UPDATE_USER", "更新用户：" + saved.getUsername());
        return saved;
    }

    @Override
    public AppUser updateUserRole(Long id, String role) {
        AppUser user = getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在，无法修改角色"));
        user.setRole(role);
        AppUser saved = appUserRepository.save(user);
        saveOperationLog(saved.getId(), "SETTINGS", "UPDATE_ROLE", "修改角色为：" + role);
        return saved;
    }

    @Override
    public AppUser updateUserStatus(Long id, Integer status) {
        AppUser user = getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在，无法修改状态"));
        user.setStatus(status);
        AppUser saved = appUserRepository.save(user);
        saveOperationLog(saved.getId(), "SETTINGS", "UPDATE_STATUS", "修改状态为：" + status);
        return saved;
    }

    @Override
    public Map<String, Object> backup() {
        saveOperationLog(1L, "SETTINGS", "BACKUP", "执行系统备份");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("backupName", "backup-" + LocalDateTime.now());
        result.put("message", "当前为 MySQL 版本，备份接口返回模拟结果");
        return result;
    }

    @Override
    public Map<String, Object> restore(String backupName) {
        saveOperationLog(1L, "SETTINGS", "RESTORE", "执行系统恢复：" + backupName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("backupName", backupName);
        result.put("message", "当前为 MySQL 版本，恢复接口返回模拟结果");
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationLog> getLogs(String module, Long operatorId) {
        return operationLogRepository.findAll().stream()
                .filter(log -> !StringUtils.hasText(module) || module.equalsIgnoreCase(log.getModule()))
                .filter(log -> operatorId == null || operatorId.equals(log.getOperatorId()))
                .collect(Collectors.toList());
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
}
