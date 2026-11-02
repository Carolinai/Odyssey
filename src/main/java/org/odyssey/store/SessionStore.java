package org.odyssey.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 登录会话存储：令牌 → 用户信息，持久化到 {data-dir}/sessions.json。
 * 服务器重启不丢登录态；文件随数据目录一起备份/迁移。
 * 凭据按内部工具的约定明文保存在服务器磁盘上（与审计日志同口径）。
 */
public class SessionStore {

    /** 一条登录会话 */
    public static class SessionInfo {
        private String user;       // 账号
        private String userName;   // 显示名（Jenkins fullName）
        private String credential; // Jenkins 凭据（密码/API Token），供 JenkinsService 调用
        private String loginTime;  // 登录时间

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getCredential() {
            return credential;
        }

        public void setCredential(String credential) {
            this.credential = credential;
        }

        public String getLoginTime() {
            return loginTime;
        }

        public void setLoginTime(String loginTime) {
            this.loginTime = loginTime;
        }
    }

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path file;
    private Map<String, SessionInfo> sessions; // 懒加载缓存，synchronized 方法内访问

    public SessionStore(Path dataRoot) {
        this.file = dataRoot.resolve("sessions.json");
    }

    /** 创建会话，返回随机令牌 */
    public synchronized String create(String user, String userName, String credential) {
        if (sessions == null) {
            load();
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        SessionInfo info = new SessionInfo();
        info.setUser(user);
        info.setUserName(userName);
        info.setCredential(credential);
        info.setLoginTime(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sessions.put(token, info);
        save();
        return token;
    }

    /** 按令牌取会话；不存在返回 null */
    public synchronized SessionInfo get(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (sessions == null) {
            load();
        }
        return sessions.get(token);
    }

    /** 注销：删除会话 */
    public synchronized void remove(String token) {
        if (sessions == null) {
            load();
        }
        if (sessions.remove(token) != null) {
            save();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            sessions = new HashMap<>();
            return;
        }
        try {
            sessions = mapper.readValue(file.toFile(),
                    new TypeReference<Map<String, SessionInfo>>() {
                    });
        } catch (IOException e) {
            // 空文件或损坏文件按无会话处理：登录态丢失可重登，不应阻塞整个系统
            sessions = new HashMap<>();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "sessions-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), sessions);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入会话文件失败：" + file, e);
        }
    }
}
