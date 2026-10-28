package org.odyssey.config;

import org.odyssey.store.EnvironmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时确保数据目录结构存在：{data-dir}/data/、{data-dir}/keys/ 与 {data-dir}/data/images/，
 * 并给缺时间戳的旧环境文件回填 created_at / updated_at、清洗仅有远程方式的 sunlogin 脏数据。
 */
@Component
public class DataDirectoryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataDirectoryInitializer.class);

    private final OdysseyProperties properties;
    private final EnvironmentStore store;

    public DataDirectoryInitializer(OdysseyProperties properties, EnvironmentStore store) {
        this.properties = properties;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path root = Paths.get(properties.getDataDir()).toAbsolutePath().normalize();
        createIfMissing(root.resolve("data"));
        createIfMissing(root.resolve("keys"));
        createIfMissing(root.resolve("data").resolve("images")); // 模块截图存储目录
        store.backfillMissingTimestamps();
        store.cleanupRemoteTypeOnlySunlogin();
        log.info("数据目录就绪：{}", root);
    }

    private void createIfMissing(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("已创建目录：{}", dir);
        }
    }
}
