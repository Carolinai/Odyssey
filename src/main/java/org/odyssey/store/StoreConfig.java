package org.odyssey.store;

import org.odyssey.config.OdysseyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储层 Bean 装配：EnvironmentStore 等需要在单测中用临时目录构造，故不直接标注 @Component。
 */
@Configuration
public class StoreConfig {

    /** 环境数据根目录：{data-dir} 本身（环境 YAML 在其 data/ 子目录下） */
    @Bean
    public Path dataRoot(OdysseyProperties properties) {
        return Paths.get(properties.getDataDir()).toAbsolutePath().normalize();
    }

    @Bean
    public EnvironmentStore environmentStore(Path dataRoot) {
        return new EnvironmentStore(dataRoot.resolve("data"));
    }

    @Bean
    public GlobalConfigStore globalConfigStore(Path dataRoot) {
        return new GlobalConfigStore(dataRoot);
    }

    @Bean
    public AuditStore auditStore(Path dataRoot) {
        return new AuditStore(dataRoot);
    }
}
