package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.entity.AppUser;
import com.example.demo.entity.OperationLog;
import com.example.demo.service.SettingsService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 系统设置接口
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/users")
    public ApiResponse<List<AppUser>> getUsers() {
        return ApiResponse.ok("用户列表查询成功", settingsService.getUsers());
    }

    @GetMapping("/users/{id}")
    public ApiResponse<AppUser> getUser(@PathVariable Long id) {
        return ApiResponse.ok("用户详情查询成功", settingsService.getUserById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "用户不存在")));
    }

    @PostMapping("/users")
    public ApiResponse<AppUser> createUser(@RequestBody AppUser user) {
        return ApiResponse.ok("用户新增成功", settingsService.saveUser(user));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<AppUser> updateUser(@PathVariable Long id, @RequestBody AppUser user) {
        return ApiResponse.ok("用户更新成功", settingsService.updateUser(id, user));
    }

    @PatchMapping("/users/{id}/role")
    public ApiResponse<AppUser> updateRole(@PathVariable Long id, @RequestParam String role) {
        return ApiResponse.ok("用户角色更新成功", settingsService.updateUserRole(id, role));
    }

    @PatchMapping("/users/{id}/status")
    public ApiResponse<AppUser> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        return ApiResponse.ok("用户状态更新成功", settingsService.updateUserStatus(id, status));
    }

    @PostMapping("/backup")
    public ApiResponse<Map<String, Object>> backup() {
        return ApiResponse.ok("系统备份执行成功", settingsService.backup());
    }

    @PostMapping("/restore")
    public ApiResponse<Map<String, Object>> restore(@RequestParam String backupName) {
        return ApiResponse.ok("系统恢复执行成功", settingsService.restore(backupName));
    }

    @GetMapping("/logs")
    public ApiResponse<List<OperationLog>> getLogs(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Long operatorId) {
        return ApiResponse.ok("操作日志查询成功", settingsService.getLogs(module, operatorId));
    }
}
