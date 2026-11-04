package org.odyssey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 后台任务执行器（spec D12）：单工作线程串行执行（SQLite 写入本就串行，AI 网关调用也
 * 不宜并发轰炸），任务提交后立即返回 id，页面轮询进度。
 *
 * 生命周期约定：
 * - 启动时把遗留的 pending/running 任务一律标记 failed（不复活），保证无僵尸任务。
 *   清理用 ApplicationRunner 且排最后：表结构初始化器也是 ApplicationRunner，须先建表
 * - 任务体抛任何异常都落为 failed，错误信息入库（页面可见）
 */
@Service("backgroundTaskExecutor") // 避开 Spring Boot 自带 taskExecutor 别名
public class TaskExecutor implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);

    private final TaskStore taskStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "task-worker");
        t.setDaemon(true);
        return t;
    });

    public TaskExecutor(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    /** 启动清理：上次进程遗留的未完成任务标记为失败（排在表结构初始化之后） */
    @Override
    public void run(ApplicationArguments args) {
        cleanupOnBoot();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** 启动清理本体（测试可直接调用） */
    public void cleanupOnBoot() {
        int n = taskStore.failAllUnfinished("应用重启，任务中断");
        if (n > 0) {
            log.warn("启动清理：{} 个遗留后台任务已标记为失败", n);
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 提交任务：先落库（pending），再入队串行执行，立即返回任务 id。
     * 手动触发方（报告页/经验提炼等）在各自接口层记审计日志，这里不重复记。
     */
    public long submit(String type, Map<String, Object> params, String createdBy, TaskBody body) {
        String paramsJson;
        try {
            paramsJson = mapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalArgumentException("任务参数序列化失败", e);
        }
        final long id = taskStore.create(type, paramsJson, createdBy);
        pool.submit(() -> runTask(id, body));
        return id;
    }

    private void runTask(long id, TaskBody body) {
        try {
            // 排队期间被外部终止（直接标 failed）的任务不再执行，也不覆盖状态
            Map<String, Object> existing = taskStore.get(id);
            if (existing != null && "failed".equals(existing.get("status"))) {
                log.info("后台任务 {} 已被终止（外部标记失败），跳过执行", id);
                return;
            }
            taskStore.markRunning(id, "开始执行");
            body.run(new TaskContext(id));
            taskStore.markDone(id);
        } catch (Exception e) {
            log.error("后台任务 {} 执行失败", id, e);
            taskStore.markFailed(id, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** 任务体：通过 ctx 汇报进度（0-100）与阶段文案 */
    public interface TaskBody {
        void run(TaskContext ctx) throws Exception;
    }

    /** 传给任务体的上下文：写进度 + 读自身任务 id */
    public class TaskContext {
        private final long id;

        TaskContext(long id) {
            this.id = id;
        }

        public long taskId() {
            return id;
        }

        public void progress(int percent, String stage) {
            int p = Math.max(0, Math.min(100, percent));
            taskStore.updateProgress(id, p, stage);
        }
    }
}
