package org.odyssey.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.odyssey.model.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 个人配置存储：每用户一个文件 <dataDir>/users/<urlencode(user)>.yaml。
 *
 * 文件名用 URLEncoder 编码原始用户名（Jenkins 账号），既规避非法文件名字符，
 * 又可逆——遍历 users 目录即可还原出全部已配置用户（自动同步取 Cookie 时需要）。
 * 读取时文件不存在返回空配置；禁用未知字段容错，兼容后续字段增减。
 */
public class UserConfigStore {

    private static final Logger log = LoggerFactory.getLogger(UserConfigStore.class);

    private final Path dir;
    private final ObjectMapper mapper;

    public UserConfigStore(Path rootDir) {
        this.dir = rootDir.resolve("users");
        this.mapper = new ObjectMapper(
                new YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 读取指定用户的个人配置；文件不存在或为空时返回空配置 */
    public UserConfig get(String user) {
        Path file = fileOf(user);
        if (!Files.exists(file)) {
            return new UserConfig();
        }
        try {
            return mapper.readValue(file.toFile(), UserConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取个人配置失败：" + file, e);
        }
    }

    /** 保存指定用户的个人配置：先归一化（空白字符串视为未配置），再原子写回 */
    public void save(String user, UserConfig config) {
        final UserConfig cfg = config == null ? new UserConfig() : config;
        cfg.setOnesCookie(normalize(cfg.getOnesCookie()));
        cfg.setAiBaseUrl(normalize(cfg.getAiBaseUrl()));
        cfg.setAiApiKey(normalize(cfg.getAiApiKey()));
        cfg.setAiModel(normalize(cfg.getAiModel()));
        try {
            Files.createDirectories(dir);
            // 临时文件 + 原子移动，避免写一半被读到脏数据
            Path tmp = Files.createTempFile(dir, "odyssey-user-config-", ".tmp");
            mapper.writer().writeValue(tmp.toFile(), cfg);
            Files.move(tmp, fileOf(user), StandardCopyOption.REPLACE_EXISTING);
            log.info("已保存个人配置：{}", fileOf(user));
        } catch (IOException e) {
            throw new UncheckedIOException("写入个人配置失败：" + user, e);
        }
    }

    /**
     * 列出全部已配置用户（按文件名还原原始用户名），目录不存在时返回空列表。
     * 供自动同步挑选可用 Cookie 使用。
     */
    public List<String> configuredUsers() {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<String> users = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        // 注意：URLDecoder.decode(String, Charset) 是 Java 10+ 重载，本项目 Java 8
                        // 只能用字符集名的 String 重载；UTF-8 恒受支持，异常实际不会发生
                        try {
                            users.add(URLDecoder.decode(
                                    name.substring(0, name.length() - ".yaml".length()),
                                    StandardCharsets.UTF_8.name()));
                        } catch (java.io.UnsupportedEncodingException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("遍历个人配置目录失败：" + dir, e);
        }
        return users;
    }

    /** 用户名 → 配置文件路径（URLEncoder 全量编码，Windows 文件名安全且可逆） */
    private Path fileOf(String user) {
        try {
            // 同上：URLEncoder.encode(String, Charset) 为 Java 10+，用字符集名重载兼容 Java 8
            return dir.resolve(URLEncoder.encode(user, StandardCharsets.UTF_8.name()) + ".yaml");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 字符串项归一化：去首尾空白，空白视为未配置（null） */
    private static String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
