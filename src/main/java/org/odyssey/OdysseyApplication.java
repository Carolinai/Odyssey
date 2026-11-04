package org.odyssey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Odyssey 启动类：客户环境信息一站式工具。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // 工单每日增量同步（TicketSyncService）
public class OdysseyApplication {

    public static void main(String[] args) {
        // AI 网关按 Host 头路由（虚拟主机）：走用户隧道（127.0.0.{槽位}:18083）时需改写 Host 头，
        // HttpURLConnection 默认禁止该受限头，这里对整个服务端应用放开（仅我们自己的出站调用）
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        SpringApplication.run(OdysseyApplication.class, args);
    }
}
