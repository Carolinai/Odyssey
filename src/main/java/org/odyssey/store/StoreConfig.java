package org.odyssey.store;

import org.odyssey.config.OdysseyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * 存储层 Bean 装配：EnvironmentStore 需要在单测中用临时目录构造，故不直接标注 @Component。
 */
@Configuration
public class StoreConfig {

    @Bean
    public EnvironmentStore environmentStore(OdysseyProperties properties) {
        return new EnvironmentStore(
                Paths.get(properties.getDataDir(), "data").toAbsolutePath().normalize());
    }
}
