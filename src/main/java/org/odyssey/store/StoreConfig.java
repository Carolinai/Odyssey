package org.odyssey.store;

import org.odyssey.config.OdysseyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
    public UserConfigStore userConfigStore(Path dataRoot) {
        return new UserConfigStore(dataRoot);
    }

    @Bean
    public AuditStore auditStore(Path dataRoot) {
        return new AuditStore(dataRoot);
    }

    @Bean
    public FeedbackStore feedbackStore(JdbcTemplate jdbcTemplate) {
        return new FeedbackStore(jdbcTemplate);
    }

    @Bean
    public SessionStore sessionStore(Path dataRoot) {
        return new SessionStore(dataRoot);
    }

    /**
     * SQLite 数据源：单文件库 {data-dir}/odyssey.db，随数据目录一起挂卷迁移。
     * 手动装配（不用 Boot 自动配置）：建库前先确保数据目录存在；
     * SQLiteDataSource 不带连接池，每次操作独立连接，规避 SQLITE_BUSY 锁问题，
     * 本项目单实例、查询量级下开销可忽略。
     */
    @Bean
    public DataSource dataSource(Path dataRoot) {
        try {
            Files.createDirectories(dataRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("创建数据目录失败：" + dataRoot, e);
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dataRoot.resolve("odyssey.db"));
        // WAL 模式：写事务进行时读不阻塞（全量同步落库期间页面查询不再受影响）；
        // busy_timeout：仍遇锁时等待最多 10 秒而非直接报 SQLITE_BUSY
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(10_000);
        ds.setConfig(config);
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 启动时幂等建表（DDL 见 TicketSchemaInitializer） */
    @Bean
    public TicketSchemaInitializer ticketSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new TicketSchemaInitializer(jdbcTemplate);
    }
}
