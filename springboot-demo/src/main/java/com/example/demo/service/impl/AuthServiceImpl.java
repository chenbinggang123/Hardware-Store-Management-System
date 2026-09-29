package com.example.demo.service.impl;

import com.example.demo.dto.AuthLoginRequest;
import com.example.demo.dto.AuthSessionResponse;
import com.example.demo.entity.AppUser;
import com.example.demo.entity.OperationLog;
import com.example.demo.repository.AppUserRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.security.AuthSession;
import com.example.demo.security.AuthTokenStore;
import com.example.demo.security.PasswordSupport;
import com.example.demo.service.AuthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 登录认证实现
 */
@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final OperationLogRepository operationLogRepository;
    private final AuthTokenStore authTokenStore;

    public AuthServiceImpl(
            AppUserRepository appUserRepository,
            OperationLogRepository operationLogRepository,
            AuthTokenStore authTokenStore) {
        this.appUserRepository = appUserRepository;
        this.operationLogRepository = operationLogRepository;
        this.authTokenStore = authTokenStore;
    }

    @Override
    public AuthSessionResponse login(AuthLoginRequest request) {
        String username = request == null ? null : normalize(request.getUsername());
        String password = request == null ? null : request.getPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalArgumentException("账号和密码不能为空");
        }
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "账号或密码错误"));
        if (!PasswordSupport.matches(password, user.getPassword())) {
            throw new ResponseStatusException(UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new ResponseStatusException(UNAUTHORIZED, "当前账号已被禁用");
        }
        if (!PasswordSupport.isEncoded(user.getPassword())) {
            user.setPassword(PasswordSupport.encode(password));
            appUserRepository.save(user);
        }
        AuthSession session = authTokenStore.createSession(user);
        saveOperationLog(user.getId(), "AUTH", "LOGIN", "用户登录：" + user.getUsername());
        return toResponse(session);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthSessionResponse getCurrentUser(String token) {
        return toResponse(requireSession(token));
    }

    @Override
    public void logout(String token) {
        AuthSession session = requireSession(token);
        authTokenStore.removeSession(token);
        saveOperationLog(session.getUser().getId(), "AUTH", "LOGOUT", "用户退出登录：" + session.getUser().getUsername());
    }

    public AuthSession requireSession(String token) {
        AuthSession session = authTokenStore.getSession(token)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "登录状态已失效，请重新登录"));
        AppUser currentUser = appUserRepository.findById(session.getUser().getId())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "登录用户不存在，请重新登录"));
        if (currentUser.getStatus() == null || currentUser.getStatus() != 1) {
            authTokenStore.removeSession(token);
            throw new ResponseStatusException(UNAUTHORIZED, "当前账号已被禁用");
        }
        return new AuthSession(token, currentUser, session.getExpiresAt());
    }

    private AuthSessionResponse toResponse(AuthSession session) {
        AppUser user = session.getUser();
        AuthSessionResponse response = new AuthSessionResponse();
        response.setToken(session.getToken());
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setName(user.getName());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setExpiresAt(session.getExpiresAt());
        return response;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
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
