package org.odyssey.api;

import org.odyssey.service.JenkinsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录接口：用 Jenkins 账号校验身份，登录成功后凭据存入会话，
 * 后续所有 Jenkins 调用（如视图查询）都用当前登录用户自己的凭据。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 从请求会话取当前登录账号；未登录返回 null（供审计等处使用） */
    public static String currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(SESSION_USER);
    }

    /** 从请求会话取当前登录用户的显示名（中文姓名）；空时退回账号 */
    public static String currentUserName(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        String name = (String) session.getAttribute(SESSION_USER_NAME);
        return name == null || name.trim().isEmpty() ? currentUser(request) : name.trim();
    }

    /** 会话属性：登录用户名（其他拦截器、Controller 读取判断登录态） */
    public static final String SESSION_USER = "auth.user";

    /** 会话属性：Jenkins 凭据（密码或 API Token），供 JenkinsService 调用 */
    public static final String SESSION_CREDENTIAL = "auth.credential";

    /** 会话属性：用户显示名（Jenkins fullName，中文姓名） */
    public static final String SESSION_USER_NAME = "auth.user_name";

    private final JenkinsService jenkinsService;

    public AuthController(JenkinsService jenkinsService) {
        this.jenkinsService = jenkinsService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpSession session) {
        String username = body.get("username") == null ? "" : body.get("username").trim();
        String credential = body.get("credential") == null ? "" : body.get("credential").trim();
        if (username.isEmpty() || credential.isEmpty()) {
            return body(HttpStatus.BAD_REQUEST, "请填写账号和密码/Token");
        }

        String displayName;
        try {
            displayName = jenkinsService.authenticate(username, credential);
        } catch (IllegalStateException e) {
            log.warn("Jenkins 登录校验失败：{}", e.getMessage());
            return body(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (displayName == null) {
            return body(HttpStatus.UNAUTHORIZED, "Jenkins 账号或凭据错误");
        }

        session.setAttribute(SESSION_USER, username);
        session.setAttribute(SESSION_CREDENTIAL, credential);
        session.setAttribute(SESSION_USER_NAME, displayName);
        Map<String, Object> result = new HashMap<>();
        result.put("user", username);
        result.put("name", displayName);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Collections.emptyMap());
    }

    /** 当前登录用户：user=账号，name=显示名（中文姓名）；未登录时均为 null（本接口不做登录拦截） */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        result.put("user", session.getAttribute(SESSION_USER));
        result.put("name", session.getAttribute(SESSION_USER_NAME));
        return ResponseEntity.ok(result);
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        Map<String, String> map = new HashMap<>();
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}
