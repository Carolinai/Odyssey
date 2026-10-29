package org.odyssey.config;

import org.odyssey.api.AuthController;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 测试配置：注册一个 order=-1 的拦截器（先于 {@link AuthInterceptor} 执行），
 * 为没有会话的请求补上「已登录测试账号」的会话。
 * 供需要登录态的 Controller 测试 @Import；登录接口本身的测试不要引入，
 * 以免默认会话掩盖 401 分支。
 */
@TestConfiguration
public class AuthedMockMvcConfig {

    @Bean
    public WebMvcConfigurer authSessionInjector() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                             Object handler) {
                        HttpSession session = request.getSession(false);
                        if (session == null) {
                            session = new MockHttpSession();
                            ((MockHttpServletRequest) request).setSession(session);
                        }
                        if (session.getAttribute(AuthController.SESSION_USER) == null) {
                            session.setAttribute(AuthController.SESSION_USER, "tester");
                            session.setAttribute(AuthController.SESSION_CREDENTIAL, "test-token");
                            session.setAttribute(AuthController.SESSION_USER_NAME, "测试用户");
                        }
                        return true;
                    }
                }).order(-1);
            }
        };
    }
}
