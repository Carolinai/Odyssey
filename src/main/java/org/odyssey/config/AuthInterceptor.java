package org.odyssey.config;

import org.odyssey.api.AuthController;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登录拦截器：/api/**（登录接口本身除外）都要求已用 Jenkins 账号登录，
 * 未登录返回 401，由前端统一跳转登录页。
 */
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        String user = session == null ? null : (String) session.getAttribute(AuthController.SESSION_USER);
        if (user != null && !user.trim().isEmpty()) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"未登录或会话已过期\"}");
        return false;
    }
}
