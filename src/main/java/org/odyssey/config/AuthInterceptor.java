package org.odyssey.config;

import org.odyssey.service.AuthService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器：/api/**（登录接口本身除外）都要求已用 Jenkins 账号登录，
 * 登录态从令牌 Cookie 解析（服务端会话文件持久化），未登录返回 401，
 * 由前端统一跳转登录页。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (authService.currentUser(request) != null) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"未登录或会话已过期\"}");
        return false;
    }
}
