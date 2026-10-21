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
        boolean admin = "ADMIN".equals(session.getUser().getRole());
        if (path.startsWith("/settings") && !admin) {
            throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
        // 删除是不可恢复的操作，只允许管理员；记错的账和单据用冲销、作废，所有人都可以做且留有记录。
        if ("DELETE".equalsIgnoreCase(request.getMethod()) && !admin && isBusinessRecord(path)) {
            throw new ResponseStatusException(FORBIDDEN, "删除需要管理员权限");
        }
        return true;
    }

    /** 商品、客户、库存、单据和账本；小五生成的文件等个人资料仍可由本人删除。 */
    private static boolean isBusinessRecord(String path) {
        for (String prefix : new String[] {"/products", "/customers", "/suppliers", "/inventories",
                "/sales-orders", "/purchase-orders", "/factory-ledgers", "/finance", "/billing"}) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
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
