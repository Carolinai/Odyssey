package org.odyssey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Odyssey 启动类：客户环境信息一站式工具。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OdysseyApplication {

    public static void main(String[] args) {
        SpringApplication.run(OdysseyApplication.class, args);
    }
}
