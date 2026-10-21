package com.example.demo.common;

import com.example.demo.config.AuthInterceptor;
import com.example.demo.security.AuthSession;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前登录的操作人。操作人只能来自登录令牌，不能由前端在请求内容里自己填写。
 */
public final class CurrentOperator {

    private CurrentOperator() {
    }

    /** 当前请求的登录用户 id；不在 HTTP 请求里（例如小五后台任务、单元测试）时返回 null。 */
    public static Long idOrNull() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (value instanceof AuthSession session && session.getUser() != null) {
            return session.getUser().getId();
        }
        return null;
    }

    /** 当前请求的登录用户 id，没有登录时报错。 */
    public static Long require() {
        Long id = idOrNull();
        if (id == null) {
            throw new IllegalArgumentException("无法识别当前操作人，请重新登录");
        }
        return id;
    }
}
