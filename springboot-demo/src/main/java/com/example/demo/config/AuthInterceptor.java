package com.example.demo.config;

import com.example.demo.security.AuthSession;
import com.example.demo.service.impl.AuthServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import org.springframework.web.server.ResponseStatusException;

/**
 * 登录态校验拦截器
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_SESSION_ATTRIBUTE = AuthInterceptor.class.getName() + ".session";

    private final AuthServiceImpl authService;

    public AuthInterceptor(AuthServiceImpl authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthSession session = authService.requireSession(resolveToken(request));
        request.setAttribute(AUTH_SESSION_ATTRIBUTE, session);
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path.startsWith("/settings") && !"ADMIN".equals(session.getUser().getRole())) {
            throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        String token = request.getHeader("X-Token");
        return token == null ? "" : token.trim();
    }
}
