package com.example.demo.service;

import com.example.demo.entity.AppUser;
import com.example.demo.entity.OperationLog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统设置服务
 */
public interface SettingsService {
    List<AppUser> getUsers();
    Optional<AppUser> getUserById(Long id);
    AppUser saveUser(AppUser user);
    AppUser updateUser(Long id, AppUser user);
    AppUser updateUserRole(Long id, String role);
    AppUser updateUserStatus(Long id, Integer status);
    Map<String, Object> backup();
    Map<String, Object> restore(String backupName);
    List<OperationLog> getLogs(String module, Long operatorId);
}
