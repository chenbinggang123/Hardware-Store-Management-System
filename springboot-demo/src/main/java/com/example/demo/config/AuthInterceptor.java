package com.example.demo.config;

import com.example.demo.service.impl.AuthServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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
        request.setAttribute(AUTH_SESSION_ATTRIBUTE, authService.requireSession(resolveToken(request)));
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
