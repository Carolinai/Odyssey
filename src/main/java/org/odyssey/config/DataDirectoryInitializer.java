package org.odyssey.config;

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
 * 启动时确保数据目录结构存在：{data-dir}/data/ 与 {data-dir}/keys/。
 */
@Component
public class DataDirectoryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataDirectoryInitializer.class);

    private final OdysseyProperties properties;

    public DataDirectoryInitializer(OdysseyProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path root = Paths.get(properties.getDataDir()).toAbsolutePath().normalize();
        createIfMissing(root.resolve("data"));
        createIfMissing(root.resolve("keys"));
        log.info("数据目录就绪：{}", root);
    }

    private void createIfMissing(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("已创建目录：{}", dir);
        }
    }
}
