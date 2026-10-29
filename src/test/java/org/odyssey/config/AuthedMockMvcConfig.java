package org.odyssey.config;

import org.odyssey.service.AuthService;
import org.odyssey.store.SessionStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.servlet.Filter;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.util.Arrays;

/**
 * 测试配置：注册一个过滤器，为没有令牌的请求补上「已登录测试账号」的令牌 Cookie
 * （会话预先写入会话存储）。过滤器先于 {@link AuthInterceptor} 执行，等效于已登录。
 * 供需要登录态的 Controller 测试 @Import；登录接口本身的测试不要引入，
 * 以免默认登录态掩盖 401 分支。
 */
@TestConfiguration
public class AuthedMockMvcConfig {

    @Bean
    public Filter authCookieFilter(SessionStore sessionStore) {
        // 预置测试账号会话，令牌随机生成后经 Cookie 注入各请求
        final String token = sessionStore.create("tester", "测试用户", "test-token");
        return (req, res, chain) -> {
            HttpServletRequest request = (HttpServletRequest) req;
            Cookie[] cookies = request.getCookies();
            boolean hasToken = cookies != null && Arrays.stream(cookies)
                    .anyMatch(c -> AuthService.TOKEN_COOKIE.equals(c.getName()));
            if (hasToken) {
                chain.doFilter(req, res);
                return;
            }
            Cookie tokenCookie = new Cookie(AuthService.TOKEN_COOKIE, token);
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public Cookie[] getCookies() {
                    Cookie[] original = super.getCookies();
                    Cookie[] merged = new Cookie[(original == null ? 0 : original.length) + 1];
                    if (original != null) {
                        System.arraycopy(original, 0, merged, 0, original.length);
                    }
                    merged[merged.length - 1] = tokenCookie;
                    return merged;
                }
            }, res);
        };
    }
}
