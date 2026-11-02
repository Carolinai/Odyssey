package org.odyssey.api;

import org.odyssey.service.AuthService;
import org.odyssey.service.JenkinsService;
import org.odyssey.service.TunnelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录接口：用 Jenkins 账号校验身份，登录成功后签发长期令牌 Cookie，
 * 会话记录持久化在服务端会话文件（重启不丢）；
 * 后续所有 Jenkins 调用（如视图查询）都用当前登录用户自己的凭据。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 令牌 Cookie 有效期：内部工具，约 10 年（实际等于不过期，注销时主动清除） */
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(3650);

    private final JenkinsService jenkinsService;
    private final AuthService authService;
    private final TunnelService tunnelService;

    public AuthController(JenkinsService jenkinsService, AuthService authService,
                          TunnelService tunnelService) {
        this.jenkinsService = jenkinsService;
        this.authService = authService;
        this.tunnelService = tunnelService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
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

        String token = authService.create(username, displayName, credential);
        ResponseCookie cookie = loginCookie(token);
        Map<String, Object> result = new HashMap<>();
        result.put("user", username);
        result.put("name", displayName);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        authService.logout(request); // 删除服务端会话
        // 同时让浏览器端的令牌 Cookie 失效
        ResponseCookie expired = ResponseCookie.from(AuthService.TOKEN_COOKIE, "")
                .httpOnly(true).path("/").maxAge(0).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(Collections.emptyMap());
    }

    /** 当前登录用户：user=账号，name=显示名（中文姓名）；未登录时均为 null（本接口不做登录拦截） */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("user", authService.currentUser(request));
        result.put("name", authService.currentUserName(request));
        return ResponseEntity.ok(result);
    }

    // ---------- Jenkins 隧道（登录前初始化，见 TunnelService 类注释） ----------

    /** 隧道状态：installed=该用户已登记、online=该用户隧道当前在线（登录页据此决定是否引导初始化） */
    @GetMapping("/tunnel-status")
    public ResponseEntity<?> tunnelStatus(@RequestParam String username) {
        TunnelService.Status status = tunnelService.statusOf(username == null ? "" : username.trim());
        Map<String, Object> result = new HashMap<>();
        result.put("installed", status.installed);
        result.put("online", status.online);
        return ResponseEntity.ok(result);
    }

    /** 下载初始化脚本：为该用户分配槽位并生成专属密钥，返回可直接双击运行的 bat（可重复运行换新密钥） */
    @GetMapping("/tunnel-script")
    public ResponseEntity<byte[]> tunnelScript(@RequestParam String username) {
        String user = username == null ? "" : username.trim();
        try {
            TunnelService.Enrollment enrollment = tunnelService.enroll(user);
            log.info("为用户 {} 生成隧道初始化脚本（槽位 127.0.0.{}）", user, enrollment.getSlot());
            String script = tunnelService.buildInitScript(enrollment, user);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"odyssey-tunnel-init.bat\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(script.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"用户名含非法字符\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static ResponseCookie loginCookie(String token) {
        return ResponseCookie.from(AuthService.TOKEN_COOKIE, token)
                .httpOnly(true).path("/").maxAge(COOKIE_MAX_AGE).build();
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        Map<String, String> map = new HashMap<>();
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}
