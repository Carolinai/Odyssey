package org.odyssey.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.odyssey.model.GlobalConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 全局配置存储：单文件 <dataDir>/config.yaml（与环境数据目录、密钥目录平级）。
 * 配置为服务级共享数据，读取时文件不存在返回空配置。
 */
public class GlobalConfigStore {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigStore.class);

    private final Path file;
    private final ObjectMapper mapper;

    public GlobalConfigStore(Path rootDir) {
        this.file = rootDir.resolve("config.yaml");
        this.mapper = new ObjectMapper(
                new YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    }

    /** 读取全局配置；文件不存在或为空时返回空配置 */
    public GlobalConfig get() {
        if (!Files.exists(file)) {
            return new GlobalConfig();
        }
        try {
            GlobalConfig config = mapper.readValue(file.toFile(), GlobalConfig.class);
            if (config.getLaunchers() == null) {
                config.setLaunchers(new java.util.LinkedHashMap<>());
            }
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException("读取全局配置失败：" + file, e);
        }
    }

    /** 保存全局配置：先归一化（去掉空白键值），再原子写回 */
    public void save(GlobalConfig config) {
        final GlobalConfig cfg = config == null ? new GlobalConfig() : config;
        cfg.getLaunchers().keySet().removeIf(
                k -> k == null || k.trim().isEmpty() || cfg.getLaunchers().get(k) == null
                        || cfg.getLaunchers().get(k).trim().isEmpty());
        try {
            Files.createDirectories(file.getParent());
            // 临时文件 + 原子移动，避免写一半被读到脏数据
            Path tmp = Files.createTempFile(file.getParent(), "odyssey-config-", ".tmp");
            mapper.writer().writeValue(tmp.toFile(), cfg);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            log.info("已保存全局配置：{}", file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入全局配置失败：" + file, e);
        }
    }
}
