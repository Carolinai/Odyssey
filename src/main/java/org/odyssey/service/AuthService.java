package org.odyssey.service;

import org.odyssey.store.SessionStore;
import org.springframework.stereotype.Service;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/**
 * 登录会话服务：基于「令牌 Cookie + 服务端会话文件」，
 * 登录态持久化在服务器数据目录，重启不丢，所有用户共享同一份会话存储。
 */
@Service
public class AuthService {

    /** 登录令牌 Cookie 名 */
    public static final String TOKEN_COOKIE = "ODYSSEY_TOKEN";

    private final SessionStore store;

    public AuthService(SessionStore store) {
        this.store = store;
    }

    /** 登录成功后创建会话，返回令牌；调用方负责把令牌写进 Cookie */
    public String create(String user, String userName, String credential) {
        return store.create(user, userName, credential);
    }

    /** 从请求 Cookie 解析当前登录会话；未登录返回 null */
    public AuthSession resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (TOKEN_COOKIE.equals(cookie.getName())) {
                SessionStore.SessionInfo info = store.get(cookie.getValue());
                if (info == null) {
                    return null;
                }
                return new AuthSession(info.getUser(), info.getUserName(), info.getCredential());
            }
        }
        return null;
    }

    /** 当前登录账号；未登录返回 null */
    public String currentUser(HttpServletRequest request) {
        AuthSession session = resolve(request);
        return session == null ? null : session.getUser();
    }

    /** 当前登录用户显示名（中文姓名）；空时退回账号 */
    public String currentUserName(HttpServletRequest request) {
        AuthSession session = resolve(request);
        if (session == null) {
            return null;
        }
        return session.getUserName() == null || session.getUserName().trim().isEmpty()
                ? session.getUser() : session.getUserName().trim();
    }

    /** 注销：删除服务端会话 */
    public void logout(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return;
        }
        for (Cookie cookie : cookies) {
            if (TOKEN_COOKIE.equals(cookie.getName())) {
                store.remove(cookie.getValue());
            }
        }
    }

    /** 登录态快照 */
    public static class AuthSession {
        private final String user;
        private final String userName;
        private final String credential;

        public AuthSession(String user, String userName, String credential) {
            this.user = user;
            this.userName = userName;
            this.credential = credential;
        }

        public String getUser() {
            return user;
        }

        public String getUserName() {
            return userName;
        }

        public String getCredential() {
            return credential;
        }
    }
}
