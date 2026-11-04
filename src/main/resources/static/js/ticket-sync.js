// 工单同步页：状态/进度轮询、手动触发、同步日志、经验提炼开关

const statusBox = document.getElementById('sync-status');
const metaBox = document.getElementById('sync-meta');
const alertBox = document.getElementById('sync-alert');
const logsBox = document.getElementById('sync-logs');
const runBtn = document.getElementById('sync-run');
const insightAuto = document.getElementById('insight-auto');
const insightRerun = document.getElementById('insight-rerun');
const autoSyncEnabled = document.getElementById('auto-sync-enabled');
const autoSyncInterval = document.getElementById('auto-sync-interval');
const autoSyncUnit = document.getElementById('auto-sync-unit');

const TYPE_LABEL = { full: '全量', incremental: '增量' };
const TRIGGER_LABEL = { manual: '手动', auto: '定时' };
const STATUS_LABEL = { running: '进行中', done: '完成', failed: '失败' };
const STATUS_CLASS = { running: 'st-running', done: 'st-done', failed: 'st-failed' };

let pollTimer = null;

function fmtTime(ms) {
    if (!ms) return '—';
    const d = new Date(Number(ms));
    const p = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
        + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
}

function fmtDuration(startMs, endMs) {
    if (!startMs || !endMs) return '—';
    const sec = Math.max(0, Math.round((endMs - startMs) / 1000));
    if (sec < 60) return sec + ' 秒';
    return Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒';
}

async function refresh() {
    let s;
    try {
        s = await api('/api/tickets/sync/status');
    } catch (e) {
        statusBox.textContent = '状态加载失败：' + e.message;
        scheduleNext(false);
        return;
    }

    // 失败提示条：Cookie 失效时给出醒目指引
    if (s.last_error) {
        const isAuth = s.last_error.indexOf('Cookie') >= 0;
        alertBox.style.display = 'block';
        alertBox.innerHTML = '';
        const strong = document.createElement('strong');
        strong.textContent = '上次同步失败：' + s.last_error;
        alertBox.appendChild(strong);
        if (isAuth) {
            const hint = document.createElement('div');
            hint.style.marginTop = '4px';
            hint.textContent = 'ONES Cookie 属于个人配置且会定期过期：请到 ';
            const link = document.createElement('a');
            link.href = '/personal.html';
            link.textContent = '个人中心 → 工单助手配置';
            hint.appendChild(link);
            hint.appendChild(document.createTextNode(' 更新「ONES Cookie」后重试。'));
            alertBox.appendChild(hint);
        }
    } else {
        alertBox.style.display = 'none';
    }

    // 状态行 + 进度
    statusBox.innerHTML = '';
    metaBox.innerHTML = '';
    if (s.running) {
        const tag = document.createElement('span');
        tag.className = 'running';
        tag.textContent = '● 同步进行中';
        statusBox.appendChild(tag);
        const progress = document.createElement('span');
        progress.className = 'progress';
        if (s.current && s.current.total > 0) {
            progress.textContent = (TYPE_LABEL[s.current.type] || s.current.type)
                + ' ' + s.current.processed + ' / ' + s.current.total + ' 条';
        } else {
            progress.textContent = '正在拉取数据…';
        }
        statusBox.appendChild(progress);
        // 进度条：总量未知时用流动动画表示进行中
        const bar = document.createElement('div');
        bar.className = 'sync-progress-bar';
        const fill = document.createElement('div');
        fill.className = 'sync-progress-fill';
        if (s.current && s.current.total > 0) {
            const pct = Math.min(100, Math.round(s.current.processed / s.current.total * 100));
            fill.style.width = pct + '%';
        } else {
            fill.classList.add('indeterminate');
        }
        bar.appendChild(fill);
        statusBox.appendChild(bar);
    } else {
        statusBox.textContent = '○ 空闲';
    }
    const metaLines = [];
    if (s.last_success_at) {
        metaLines.push('上次成功同步：' + fmtTime(s.last_success_at));
    } else {
        metaLines.push('尚未成功同步过');
    }
    metaLines.push('增量水位：' + (s.watermark ? fmtTime(s.watermark) : '（无，下次同步将执行全量）'));
    metaBox.innerHTML = metaLines.map(l => '<div>' + l + '</div>').join('');
    runBtn.disabled = s.running;

    renderLogs(s.recent_logs || []);

    // 运行中加密集轮询（2s），空闲时低频刷新（8s）
    scheduleNext(!!s.running);
}

function scheduleNext(running) {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(refresh, running ? 2000 : 8000);
}

function renderLogs(logs) {
    logsBox.innerHTML = '';
    if (logs.length === 0) {
        const hint = document.createElement('div');
        hint.className = 'empty-hint';
        hint.textContent = '暂无同步记录，点击上方「立即同步」开始';
        logsBox.appendChild(hint);
        return;
    }
    const table = document.createElement('table');
    table.className = 'sync-table';
    table.innerHTML = '<thead><tr><th>开始时间</th><th>耗时</th><th>类型</th><th>触发</th>'
        + '<th>状态</th><th>工单</th><th>评论</th><th>历史</th><th>失败原因</th></tr></thead>';
    const tbody = document.createElement('tbody');
    logs.forEach(l => {
        const tr = document.createElement('tr');
        const cells = [
            fmtTime(l.started_at),
            l.status === 'running' ? '—' : fmtDuration(l.started_at, l.finished_at),
            TYPE_LABEL[l.type] || l.type,
            TRIGGER_LABEL[l.trigger_type] || l.trigger_type,
            STATUS_LABEL[l.status] || l.status,
            l.ticket_count == null ? '—' : l.ticket_count,
            l.comment_count == null ? '—' : l.comment_count,
            l.history_count == null ? '—' : l.history_count,
            l.error || ''
        ];
        cells.forEach((text, i) => {
            const td = document.createElement('td');
            td.textContent = text;
            if (i === 4) td.className = STATUS_CLASS[l.status] || '';
            if (i === 8) td.className = 'err';
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    logsBox.appendChild(table);
}

// 手动触发同步（未配置 ONES Cookie 时服务端拦截，返回引导文案）
runBtn.addEventListener('click', async () => {
    runBtn.disabled = true;
    try {
        const res = await api('/api/tickets/sync/run', { method: 'POST' });
        if (res.started) {
            toast('同步已触发');
            refresh();
        } else if (res.need_cookie) {
            toast(res.message);
            runBtn.disabled = false;
        } else {
            toast(res.message || '已有同步在执行中');
            runBtn.disabled = false;
        }
    } catch (e) {
        alert('触发失败：' + e.message);
        runBtn.disabled = false;
    }
});

// 经验提炼开关：读写个人配置（PUT 是全量覆盖，先取最新再整体保存）
insightAuto.addEventListener('change', async () => {
    try {
        const cfg = await api('/api/user-config');
        cfg.insight_auto_enabled = insightAuto.checked;
        await api('/api/user-config', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(cfg)
        });
        toast('已保存');
    } catch (e) {
        alert('保存失败：' + e.message);
        insightAuto.checked = !insightAuto.checked; // 回滚界面状态
    }
});

// 重新提炼：触发一批经验提炼任务并轮询进度（每次一批 200 张，跑完可再点继续）
insightRerun.addEventListener('click', async () => {
    let resp;
    try {
        resp = await api('/api/tickets/insights/extract', { method: 'POST' });
    } catch (e) {
        alert('触发失败：' + e.message);
        return;
    }
    if (!resp.started) {
        alert(resp.message || '无法触发');
        return;
    }
    insightRerun.disabled = true;
    const taskId = resp.task_id;
    const poll = async () => {
        let t;
        try {
            t = await api('/api/tasks/' + taskId);
        } catch (e) {
            setTimeout(poll, 5000); // 瞬时网络错误不中断轮询
            return;
        }
        if (t.status === 'running' || t.status === 'pending') {
            setTimeout(poll, 3000);
        } else if (t.status === 'done') {
            insightRerun.disabled = false;
            refreshPendingHint();
            toast('经验提炼批次完成');
        } else {
            insightRerun.disabled = false;
            alert('经验提炼失败：' + (t.error || '未知原因'));
        }
    };
    toast('经验提炼已开始');
    poll();
});

// 待提炼数量提示（按钮 title：还剩多少张待提炼）
async function refreshPendingHint() {
    try {
        const r = await api('/api/tickets/insights/pending');
        insightRerun.title = r.pending > 0
            ? '还剩 ' + r.pending + ' 张已解决工单待提炼；每次一批（约 200 张），跑完可再点继续'
            : '没有待提炼的已解决工单';
    } catch (e) { /* 提示失败不影响页面 */ }
}

// 定时同步：配置统一存分钟（ticket_auto_sync_interval_minutes），前端按单位换算；
// 点「保存」才写回配置
// 渲染：能整除 60 的分钟数用「小时」展示，否则用「分钟」
function renderAutoSync(config) {
    const minutes = Math.max(1, parseInt(config.ticket_auto_sync_interval_minutes, 10) || 60);
    autoSyncEnabled.checked = config.ticket_auto_sync_enabled === true;
    if (minutes % 60 === 0) {
        autoSyncUnit.value = 'hours';
        autoSyncInterval.value = minutes / 60;
    } else {
        autoSyncUnit.value = 'minutes';
        autoSyncInterval.value = minutes;
    }
    autoSyncInterval.disabled = !autoSyncEnabled.checked;
    autoSyncUnit.disabled = !autoSyncEnabled.checked;
}

// 勾选框只切换输入框可用状态，不写配置
autoSyncEnabled.addEventListener('change', () => {
    autoSyncInterval.disabled = !autoSyncEnabled.checked;
    autoSyncUnit.disabled = !autoSyncEnabled.checked;
});

// 保存：间隔不小于 1；GET 取最新配置改字段后整体 PUT（全量覆盖）
document.getElementById('auto-sync-save').addEventListener('click', async () => {
    const val = Math.floor(Number(autoSyncInterval.value));
    if (autoSyncEnabled.checked && (!Number.isFinite(val) || val < 1)) {
        alert('同步间隔不能小于 1');
        return;
    }
    try {
        const cfg = await api('/api/config');
        cfg.ticket_auto_sync_enabled = autoSyncEnabled.checked;
        cfg.ticket_auto_sync_interval_minutes = autoSyncUnit.value === 'hours' ? val * 60 : val;
        renderAutoSync(await api('/api/config', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(cfg)
        }));
        toast('已保存');
    } catch (e) {
        alert('保存失败：' + e.message);
    }
});

// 初始加载：定时同步开关读全局配置，经验提炼开关读个人配置 + 立即轮询
loadGlobalConfig().then(renderAutoSync);
api('/api/user-config').then(cfg => {
    insightAuto.checked = cfg.insight_auto_enabled === true;
});
refreshPendingHint();
refresh();
