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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
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
    private final Clock clock;

    /** 同一账号连续输错密码 5 次，锁定 10 分钟；只在内存中计数，重启后清零。 */
    static final int MAX_LOGIN_FAILURES = 5;
    static final Duration LOGIN_LOCK = Duration.ofMinutes(10);
    private static final int MAX_TRACKED_USERNAMES = 1000;
    private final ConcurrentMap<String, LoginFailures> loginFailures = new ConcurrentHashMap<>();

    private record LoginFailures(int count, Instant lockedUntil) {
    }

    @Autowired
    public AuthServiceImpl(
            AppUserRepository appUserRepository,
            OperationLogRepository operationLogRepository,
            AuthTokenStore authTokenStore) {
        this(appUserRepository, operationLogRepository, authTokenStore, Clock.systemUTC());
    }

    public AuthServiceImpl(
            AppUserRepository appUserRepository,
            OperationLogRepository operationLogRepository,
            AuthTokenStore authTokenStore,
            Clock clock) {
        this.appUserRepository = appUserRepository;
        this.operationLogRepository = operationLogRepository;
        this.authTokenStore = authTokenStore;
        this.clock = clock;
    }

    @Override
    public AuthSessionResponse login(AuthLoginRequest request) {
        String username = request == null ? null : normalize(request.getUsername());
        String password = request == null ? null : request.getPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalArgumentException("账号和密码不能为空");
        }
        ensureNotLocked(username);
        AppUser user = appUserRepository.findByUsername(username).orElse(null);
        if (user == null || !PasswordSupport.matches(password, user.getPassword())) {
            recordLoginFailure(username);
            throw new ResponseStatusException(UNAUTHORIZED, "账号或密码错误");
        }
        loginFailures.remove(username);
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

    private void ensureNotLocked(String username) {
        LoginFailures failures = loginFailures.get(username);
        if (failures != null && failures.lockedUntil() != null && failures.lockedUntil().isAfter(clock.instant())) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "密码输错次数太多，请 10 分钟后再试");
        }
    }

    private void recordLoginFailure(String username) {
        if (loginFailures.size() > MAX_TRACKED_USERNAMES) {
            loginFailures.clear();
        }
        Instant now = clock.instant();
        loginFailures.compute(username, (key, previous) -> {
            boolean fresh = previous == null || (previous.lockedUntil() != null && !previous.lockedUntil().isAfter(now));
            int count = fresh ? 1 : previous.count() + 1;
            return new LoginFailures(count, count >= MAX_LOGIN_FAILURES ? now.plus(LOGIN_LOCK) : null);
        });
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
