package org.odyssey.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 工单助手 SQLite 建表初始化：应用启动时幂等建库建表（CREATE TABLE IF NOT EXISTS）。
 *
 * 表设计见 .scratch/workorder-assistant/spec.md §4。约定：
 * - 所有时间戳列统一存 Unix 毫秒（ONES 原始微秒时间戳在同步时换算）
 * - ticket.uuid 为 ONES 任务 UUID，是全部子表的外键关联键（逻辑外键，SQLite 未启用外键约束）
 *
 * 数据目录的创建由 StoreConfig 的 dataSource Bean 保证（建库前 createDirectories）。
 * 本初始化器排 ApplicationRunner 最先（HIGHEST_PRECEDENCE）：后台任务等 runner 的
 * 启动逻辑依赖表已存在（如 TaskExecutor 的僵尸任务清理）。
 */
public class TicketSchemaInitializer implements ApplicationRunner, org.springframework.core.Ordered {

    private static final Logger log = LoggerFactory.getLogger(TicketSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public TicketSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String ddl : DDL) {
            jdbc.execute(ddl);
        }
        log.info("工单助手 SQLite 表结构就绪");
    }

    /** 排在所有其他 ApplicationRunner 之前：先建表，后跑依赖表的启动逻辑 */
    @Override
    public int getOrder() {
        return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
    }

    private static final String[] DDL = {
            // 工单主表
            "CREATE TABLE IF NOT EXISTS ticket (" +
                    "uuid TEXT PRIMARY KEY," +
                    "number INTEGER NOT NULL," +
                    "name TEXT," +
                    "description_text TEXT," +
                    "status_category TEXT," +   // to_do / in_progress / done
                    "status_name TEXT," +
                    "priority TEXT," +
                    "assign_uuid TEXT," +
                    "assign_name TEXT," +
                    "owner_uuid TEXT," +
                    "owner_name TEXT," +
                    "customer_uuid TEXT," +     // ONES 自定义字段「客户（QDT）」_AST3JVZw
                    "customer_name TEXT," +
                    "create_time INTEGER," +    // Unix 毫秒
                    "deadline TEXT," +          // ONESDATE 格式：yyyy-MM-dd 或 null
                    "server_update_stamp INTEGER," +
                    "synced_at INTEGER)",       // 最近一次同步落库时间（毫秒）
            "CREATE INDEX IF NOT EXISTS idx_ticket_create_time ON ticket(create_time)",
            "CREATE INDEX IF NOT EXISTS idx_ticket_status_category ON ticket(status_category)",
            "CREATE INDEX IF NOT EXISTS idx_ticket_customer_name ON ticket(customer_name)",
            "CREATE INDEX IF NOT EXISTS idx_ticket_assign_uuid ON ticket(assign_uuid)",

            // 工单 × 产品线（ONES products 为多值）
            "CREATE TABLE IF NOT EXISTS ticket_product (" +
                    "ticket_uuid TEXT NOT NULL," +
                    "product_uuid TEXT," +
                    "product_name TEXT NOT NULL," +
                    "PRIMARY KEY (ticket_uuid, product_name))",
            "CREATE INDEX IF NOT EXISTS idx_ticket_product_name ON ticket_product(product_name)",

            // 工单 × 产品模块（ONES productModules 为多值）
            "CREATE TABLE IF NOT EXISTS ticket_module (" +
                    "ticket_uuid TEXT NOT NULL," +
                    "module_uuid TEXT," +
                    "module_name TEXT NOT NULL," +
                    "PRIMARY KEY (ticket_uuid, module_name))",
            "CREATE INDEX IF NOT EXISTS idx_ticket_module_name ON ticket_module(module_name)",

            // 工单 × 关注人（ONES watchers 为多值）
            "CREATE TABLE IF NOT EXISTS ticket_watcher (" +
                    "ticket_uuid TEXT NOT NULL," +
                    "watcher_uuid TEXT NOT NULL," +
                    "PRIMARY KEY (ticket_uuid, watcher_uuid))",
            "CREATE INDEX IF NOT EXISTS idx_ticket_watcher_uuid ON ticket_watcher(watcher_uuid)",

            // 评论（ONES messages 接口 type=discussion）
            "CREATE TABLE IF NOT EXISTS ticket_comment (" +
                    "uuid TEXT PRIMARY KEY," +
                    "ticket_uuid TEXT NOT NULL," +
                    "from_uuid TEXT," +
                    "from_name TEXT," +
                    "send_time INTEGER," +
                    "text TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_ticket_comment_ticket ON ticket_comment(ticket_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_ticket_comment_send_time ON ticket_comment(send_time)",

            // 流转/字段变更历史（ONES messages 接口 type=system）
            "CREATE TABLE IF NOT EXISTS ticket_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ticket_uuid TEXT NOT NULL," +
                    "send_time INTEGER," +
                    "operator_uuid TEXT," +
                    "field_name TEXT," +
                    "old_value TEXT," +
                    "new_value TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_ticket_history_ticket ON ticket_history(ticket_uuid)",

            // 解决经验（AI 提炼产物；一工单一经验，UNIQUE 支撑重跑幂等）
            "CREATE TABLE IF NOT EXISTS insight (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ticket_uuid TEXT NOT NULL UNIQUE," +
                    "customer_name TEXT," +
                    "product_name TEXT," +
                    "content_md TEXT," +
                    "model TEXT," +
                    "status TEXT," +            // done / failed
                    "created_at INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_insight_customer_name ON insight(customer_name)",
            "CREATE INDEX IF NOT EXISTS idx_insight_product_name ON insight(product_name)",

            // AI 分析报告
            "CREATE TABLE IF NOT EXISTS ai_report (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "params_json TEXT," +       // 生成参数（时间范围/产品线/客户等）
                    "status TEXT," +            // pending / running / done / failed
                    "content_md TEXT," +
                    "error TEXT," +             // 失败原因
                    "created_by TEXT," +
                    "created_at INTEGER," +
                    "finished_at INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_ai_report_status ON ai_report(status)",
            "CREATE INDEX IF NOT EXISTS idx_ai_report_created_at ON ai_report(created_at)",

            // 同步水位与状态（key-value）
            "CREATE TABLE IF NOT EXISTS sync_state (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT)",

            // 同步日志（每次同步一条：全量/增量、触发方式、数量与成败）
            "CREATE TABLE IF NOT EXISTS sync_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "type TEXT," +              // full / incremental
                    "trigger_type TEXT," +      // manual / auto
                    "status TEXT," +            // running / done / failed
                    "started_at INTEGER," +
                    "finished_at INTEGER," +
                    "ticket_count INTEGER," +
                    "comment_count INTEGER," +
                    "history_count INTEGER," +
                    "error TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_sync_log_started_at ON sync_log(started_at)",

            // ONES 用户缓存（UUID → 姓名）
            "CREATE TABLE IF NOT EXISTS ones_user (" +
                    "uuid TEXT PRIMARY KEY," +
                    "name TEXT)",

            // 后台任务（AI 报告 / 经验提炼等长任务的执行与进度，spec D12：手动触发 + 页面轮询）
            "CREATE TABLE IF NOT EXISTS task (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "type TEXT NOT NULL," +             // 任务类型：ai_report / insight_batch / ...
                    "params_json TEXT," +               // 任务参数（JSON）
                    "status TEXT NOT NULL," +           // pending / running / done / failed
                    "progress INTEGER," +               // 0-100
                    "stage TEXT," +                     // 阶段文案（如「准备语料」「调用 AI」）
                    "error TEXT," +                     // 失败原因
                    "created_by TEXT," +
                    "created_at INTEGER," +
                    "finished_at INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_task_status ON task(status)",
            "CREATE INDEX IF NOT EXISTS idx_task_created_at ON task(created_at)"
    };
}
