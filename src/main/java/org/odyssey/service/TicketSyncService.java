package org.odyssey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.model.OnesComment;
import org.odyssey.model.OnesHistory;
import org.odyssey.model.OnesTaskMessages;
import org.odyssey.model.OnesTicket;
import org.odyssey.model.GlobalConfig;
import org.odyssey.model.UserConfig;
import org.odyssey.store.GlobalConfigStore;
import org.odyssey.store.TicketStore;
import org.odyssey.store.UserConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工单同步编排（spec §3.2/§3.3）：
 * - 首次全量（无水位时）：createTime 倒序拉取近两年工单 → 逐条拉动态流 → 落库；
 * - 每日增量（凌晨定时）：按 serverUpdateStamp 水位倒序早停拉取变更工单 → 重拉动态流覆盖；
 * - 手动同步：页面按钮触发（记审计），无水位时自动升级为全量；
 * - 动态流批量并行拉取（避免 8k+ 次串行调用拖长全量耗时），落库串行规避 SQLITE_BUSY；
 * - 进度写 sync_state.current_sync 供页面轮询；失败保留水位原值（下次增量重试同窗口）。
 *
 * 同步全程异步执行（单线程互斥），接口层只负责触发与查状态。
 *
 * ONES Cookie 为个人配置（工单 13）：手动同步用触发用户的 Cookie；
 * 定时同步（无登录用户）优先用上次同步成功用户的 Cookie，否则任取一个已配置用户，
 * 一个都没有则跳过（手动入口由控制器在触发前拦截并提示）。
 */
@Service
public class TicketSyncService {

    private static final Logger log = LoggerFactory.getLogger(TicketSyncService.class);

    // sync_state 键
    public static final String KEY_WATERMARK = "watermark_ms";        // 增量水位（毫秒）
    public static final String KEY_LAST_SUCCESS = "last_success_at_ms";
    public static final String KEY_LAST_ERROR = "last_error";
    public static final String KEY_CURRENT = "current_sync";          // 进行中进度 JSON，空闲为 null
    public static final String KEY_AUTO_INTERVAL_LAST = "auto_interval_last_ms"; // 自定义间隔同步上次触发时间（毫秒）
    public static final String KEY_LAST_SYNC_USER = "last_sync_user";            // 最近一次同步成功使用的用户（自动同步优先取其 Cookie）

    /** 增量水位回拨量：覆盖同步执行期间被更新但未拉到的工单 */
    private static final long WATERMARK_OVERLAP_MS = 60_000;
    /** 动态流并行拉取：每批工单数 / 并行线程数（仅 HTTP 并行，落库仍在同步线程串行） */
    private static final int MESSAGE_BATCH = 60;
    private static final int MESSAGE_WORKERS = 6;

    private final OnesClient onesClient;
    private final TicketStore store;
    private final UserConfigStore configStore;
    private final GlobalConfigStore globalConfigStore;
    private final TaskExecutor taskExecutor;
    private final InsightService insightService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);

    public TicketSyncService(OnesClient onesClient, TicketStore store, UserConfigStore configStore,
                             GlobalConfigStore globalConfigStore, TaskExecutor taskExecutor,
                             InsightService insightService) {
        this.onesClient = onesClient;
        this.store = store;
        this.configStore = configStore;
        this.globalConfigStore = globalConfigStore;
        this.taskExecutor = taskExecutor;
        this.insightService = insightService;
    }

    /**
     * 触发一次同步（异步执行）。已有同步在跑时返回 false 不重复触发。
     *
     * @param triggerType manual / auto
     * @param triggerUser 触发用户（手动同步传当前登录用户，用其个人 Cookie；定时同步传 null）
     */
    public boolean startSync(String triggerType, String triggerUser) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                runSync(triggerType, triggerUser);
            } finally {
                running.set(false);
            }
        }, "ticket-sync");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 每日凌晨 01:30（Asia/Shanghai）自动增量同步；进行中则跳过本次 */
    @Scheduled(cron = "0 30 1 * * *", zone = "Asia/Shanghai")
    public void scheduledIncrementalSync() {
        if (running.get()) {
            log.info("定时同步跳过：已有同步在执行");
            return;
        }
        startSync("auto", null);
    }

    /**
     * 自定义间隔自动同步（工单同步页勾选后生效）：每分钟检查一次配置，
     * 开启且距上次触发超过设定间隔时触发。触发时间在启动前推进，
     * 失败也按下一个周期重试（不会逐分钟密集轰炸）；有同步进行中则顺延到下一轮。
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void autoIntervalSyncCheck() {
        // 定时同步开关与间隔为系统级全局配置；Cookie 为个人配置（见 runSync）
        GlobalConfig cfg = globalConfigStore.get();
        if (!Boolean.TRUE.equals(cfg.getTicketAutoSyncEnabled())) {
            return;
        }
        Integer intervalMinutes = cfg.getTicketAutoSyncIntervalMinutes();
        if (intervalMinutes == null || intervalMinutes < 1) {
            return;
        }
        if (running.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        String last = store.getState(KEY_AUTO_INTERVAL_LAST);
        long lastMs = last == null || last.isEmpty() ? 0L : Long.parseLong(last);
        if (now - lastMs < intervalMinutes * 60_000L) {
            return;
        }
        store.setState(KEY_AUTO_INTERVAL_LAST, String.valueOf(now));
        log.info("自定义间隔自动同步触发：间隔 {} 分钟", intervalMinutes);
        startSync("auto", null);
    }

    // ---- 同步主体 ----

    private void runSync(String triggerType, String triggerUser) {
        // 解析本次同步使用的个人 ONES Cookie：手动 → 触发用户；
        // 定时 → 优先上次同步成功的用户，否则任取一个已配置用户（工单 13 Q2 决策）
        String syncUser = triggerUser != null ? triggerUser : resolveAutoSyncUser();
        String cookie = syncUser == null ? null : configStore.get(syncUser).getOnesCookie();
        if (cookie == null || cookie.trim().isEmpty()) {
            if (triggerUser != null) {
                // 控制器已在触发前拦截，此处兜底：记一条失败日志供同步页提示
                String msg = "未配置 ONES Cookie：请到「个人中心 → 工单助手配置」填写并保存后再同步";
                long startMs = System.currentTimeMillis();
                boolean full = store.getState(KEY_WATERMARK) == null || store.getState(KEY_WATERMARK).isEmpty();
                long logId = store.startSyncLog(full ? "full" : "incremental", triggerType, startMs);
                store.finishSyncLog(logId, "failed", 0, 0, 0, System.currentTimeMillis(), msg);
                store.setState(KEY_LAST_ERROR, msg);
            } else {
                log.warn("自动同步跳过：没有任何用户配置 ONES Cookie");
            }
            return;
        }

        String watermark = store.getState(KEY_WATERMARK);
        boolean full = watermark == null || watermark.isEmpty();
        String type = full ? "full" : "incremental";
        long startMs = System.currentTimeMillis();
        long logId = store.startSyncLog(type, triggerType, startMs);
        log.info("工单同步开始：type={}, trigger={}, user={}", type, triggerType, syncUser);
        progress(type, 0, 0);

        int ticketCount = 0;
        int commentCount = 0;
        int historyCount = 0;
        try {
            // 1. 拉取工单主记录
            List<OnesTicket> tickets = full
                    ? onesClient.fetchTicketsCreatedSince(cookie, startMs - 2L * 365 * 24 * 3600 * 1000)
                    : onesClient.fetchTicketsUpdatedSince(cookie, Long.parseLong(watermark));

            // 2. 用户缓存（评论人/操作人姓名解析，一次拉全）
            Map<String, String> userNames = onesClient.fetchUserNames(cookie);
            store.upsertUsers(userNames);

            // 3. 逐条落库 + 并行拉动态流（已解决工单记下来，供完成后自动经验提炼）
            List<String> solvedUuids = new ArrayList<>();
            for (int batchStart = 0; batchStart < tickets.size(); batchStart += MESSAGE_BATCH) {
                List<OnesTicket> batch = tickets.subList(batchStart, Math.min(batchStart + MESSAGE_BATCH, tickets.size()));
                Map<String, OnesTaskMessages> messages = fetchMessagesParallel(cookie, batch);

                for (OnesTicket ticket : batch) {
                    store.upsertTicket(ticket, startMs);
                    if ("done".equals(ticket.getStatusCategory())) {
                        solvedUuids.add(ticket.getUuid());
                    }
                    OnesTaskMessages m = messages.get(ticket.getUuid());
                    if (m != null) {
                        store.replaceMessages(ticket.getUuid(), m.getComments(), m.getHistories(), userNames);
                        commentCount += m.getComments().size();
                        historyCount += m.getHistories().size();
                    }
                    ticketCount++;
                }
                progress(type, tickets.size(), ticketCount);
            }

            // 4. 成功收尾：推进水位（回拨重叠窗口），记录成功时间
            store.setState(KEY_WATERMARK, String.valueOf(startMs - WATERMARK_OVERLAP_MS));
            store.setState(KEY_LAST_SUCCESS, String.valueOf(System.currentTimeMillis()));
            store.setState(KEY_LAST_ERROR, null);
            store.setState(KEY_LAST_SYNC_USER, syncUser); // 自动同步优先复用该用户的 Cookie
            store.finishSyncLog(logId, "done", ticketCount, commentCount, historyCount,
                    System.currentTimeMillis(), null);
            log.info("工单同步完成：{} 条工单，评论 {} 条，历史 {} 条", ticketCount, commentCount, historyCount);

            // 5. 经验提炼联动（工单 11 / D14）：开关为个人配置，按本次同步用户的设置判断；
            //    只提炼本次同步到的已解决工单（无经验的那部分由提炼任务自己跳过已有经验得出），
            //    单次任务受批上限保护，存量未提炼的部分靠后续同步或手动触发继续
            if (Boolean.TRUE.equals(configStore.get(syncUser).getInsightAutoEnabled())
                    && !solvedUuids.isEmpty()) {
                long taskId = taskExecutor.submit("insight_batch", null, syncUser,
                        ctx -> insightService.run(ctx, syncUser, solvedUuids, null));
                log.info("同步完成，已提交自动经验提炼任务 {}（本次已解决工单 {} 张，user={}）",
                        taskId, solvedUuids.size(), syncUser);
            }
        } catch (Exception e) {
            // 失败：不动水位（下次增量重试同窗口），记录失败原因供页面提示
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.setState(KEY_LAST_ERROR, message);
            store.finishSyncLog(logId, "failed", ticketCount, commentCount, historyCount,
                    System.currentTimeMillis(), message);
            log.error("工单同步失败（已完成 {} 条）：{}", ticketCount, message, e);
        } finally {
            store.setState(KEY_CURRENT, null);
        }
    }

    /**
     * 定时同步取 Cookie 的用户：优先上次同步成功的那位（同一 ONES 团队谁拉的数据一致，
     * Cookie 只是通行证），其 Cookie 已清空时任取一个已配置用户，都没有则返回 null。
     */
    private String resolveAutoSyncUser() {
        String last = store.getState(KEY_LAST_SYNC_USER);
        if (last != null && !last.isEmpty() && hasCookie(configStore.get(last))) {
            return last;
        }
        for (String user : configStore.configuredUsers()) {
            if (hasCookie(configStore.get(user))) {
                return user;
            }
        }
        return null;
    }

    private static boolean hasCookie(UserConfig cfg) {
        return cfg.getOnesCookie() != null && !cfg.getOnesCookie().trim().isEmpty();
    }

    /** 并行拉取一批工单的动态流（HTTP 并行、结果按 uuid 归集） */
    private Map<String, OnesTaskMessages> fetchMessagesParallel(String cookie, List<OnesTicket> batch)
            throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(MESSAGE_WORKERS);
        try {
            Map<String, Future<OnesTaskMessages>> futures = new LinkedHashMap<>();
            for (OnesTicket ticket : batch) {
                futures.put(ticket.getUuid(),
                        pool.submit(() -> onesClient.fetchMessages(cookie, ticket.getUuid())));
            }
            Map<String, OnesTaskMessages> result = new HashMap<>();
            IOException failure = null;
            for (Map.Entry<String, Future<OnesTaskMessages>> e : futures.entrySet()) {
                try {
                    result.put(e.getKey(), e.getValue().get());
                } catch (Exception ex) {
                    // 记录首个拉取异常，其余任务等完后再抛（不中断同批其他结果）
                    if (failure == null) {
                        failure = ex instanceof IOException
                                ? (IOException) ex : new IOException(ex.getMessage(), ex);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    /** 进度写 sync_state（页面轮询用）；total 未知时为 0 */
    private void progress(String type, int total, int processed) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("type", type);
            state.put("total", total);
            state.put("processed", processed);
            store.setState(KEY_CURRENT, mapper.writeValueAsString(state));
        } catch (Exception e) {
            log.warn("进度状态写入失败", e);
        }
    }

    // ---- 状态快照（接口层用） ----

    /** 当前同步状态快照：进行中/最近成败/水位/最近日志 */
    public Map<String, Object> statusSnapshot() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());
        String current = store.getState(KEY_CURRENT);
        if (current != null && !current.isEmpty()) {
            try {
                status.put("current", mapper.readTree(current));
            } catch (Exception e) {
                status.put("current", null);
            }
        }
        status.put("last_success_at", store.getState(KEY_LAST_SUCCESS));
        String lastError = store.getState(KEY_LAST_ERROR);
        status.put("last_error", lastError == null || lastError.isEmpty() ? null : lastError);
        status.put("watermark", store.getState(KEY_WATERMARK));
        status.put("recent_logs", store.recentSyncLogs(20));
        return status;
    }
}
