// AI 工单分析报告：生成（后台任务进度轮询）、历史列表、Markdown 查看与导出
// 后端接口：POST /api/tickets/report（触发，返回 task_id）、GET 列表/详情、GET /api/tasks/{id}（进度）

const ONES_TASK_URL = 'http://10.10.191.213/project/#/team/3HHnQbuG/project/NfvccPP54SaDCYAS/task/';

let pollTimer = null;
let currentTaskId = null;
let currentReport = null; // 弹层当前报告 {id, created_by, content_md}

const el = id => document.getElementById(id);

function showAlert(html) {
    const box = el('report-alert');
    box.innerHTML = html;
    box.style.display = 'block';
}
function hideAlert() { el('report-alert').style.display = 'none'; }

// ---------- 时间与筛选文字 ----------

function pad(n) { return String(n).padStart(2, '0'); }
function fmtDate(ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
        + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}
function fmtDay(ms) {
    const d = new Date(ms);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

/** 列表/报告参数 → 范围人话（与后端 meta 文案一致：均空 = 近六个月） */
function rangeText(p) {
    if (!p || (!p.from && !p.to)) return '近六个月';
    return (p.from ? fmtDay(p.from) : '（起始不限）') + ' ~ ' + (p.to ? fmtDay(p.to) : '（至今）');
}

function condText(p) {
    if (!p) return '';
    return ['product=产品线', 'customer=客户', 'status_name=状态']
        .map(s => s.split('='))
        .filter(kv => p[kv[0]])
        .map(kv => kv[1] + '=' + p[kv[0]])
        .join('、');
}

// ---------- 筛选选项 ----------

async function loadOptions() {
    try {
        const opts = await api('/api/tickets/stats/options');
        [['f-product', opts.products], ['f-customer', opts.customers], ['f-status', opts.statuses]]
            .forEach(([id, items]) => {
                const sel = el(id);
                (items || []).forEach(o => {
                    const opt = document.createElement('option');
                    opt.value = o.name;
                    opt.textContent = o.name;
                    sel.appendChild(opt);
                });
            });
    } catch (e) { /* 选项加载失败不阻塞页面，筛选留空即可 */ }
}

// ---------- 生成 ----------

function generateParams() {
    const p = {};
    const from = el('f-from').value;
    const to = el('f-to').value;
    if (from) p.from = new Date(from + 'T00:00:00+08:00').getTime();
    if (to) p.to = new Date(to + 'T23:59:59+08:00').getTime();
    const product = el('f-product').value;
    const customer = el('f-customer').value;
    const status = el('f-status').value;
    if (product) p.product = product;
    if (customer) p.customer = customer;
    if (status) p.status_name = status;
    return p;
}

async function generate() {
    hideAlert();
    const params = generateParams();
    const qs = Object.keys(params).map(k => k + '=' + encodeURIComponent(params[k])).join('&');
    let resp;
    try {
        resp = await api('/api/tickets/report', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: qs
        });
    } catch (e) {
        showAlert('触发失败：' + e.message);
        return;
    }
    if (!resp.started) {
        // AI 未配置等：后端给引导文案
        showAlert((resp.message || '无法触发') + (resp.need_ai_config ? '，<a href="/personal.html">去个人中心配置</a>' : ''));
        return;
    }
    currentTaskId = resp.task_id;
    el('report-generate').disabled = true;
    el('report-progress').style.display = 'block';
    setProgress(0, '排队等待中…');
    pollTask(resp.task_id);
}

function setProgress(percent, stage) {
    el('report-bar').style.width = percent + '%';
    el('report-stage').textContent = stage || '';
    el('report-percent').textContent = percent > 0 ? percent + '%' : '';
}

async function pollTask(taskId) {
    try {
        const t = await api('/api/tasks/' + taskId);
        if (t.status === 'running' || t.status === 'pending') {
            setProgress(t.progress || 0, t.stage || (t.status === 'pending' ? '排队等待中…' : '执行中…'));
            pollTimer = setTimeout(() => pollTask(taskId), 2000);
        } else if (t.status === 'done') {
            finishGenerate(true, null);
        } else {
            finishGenerate(false, t.error || '任务失败');
        }
    } catch (e) {
        // 瞬时网络错误不中断轮询，连续失败由用户刷新兜底
        pollTimer = setTimeout(() => pollTask(taskId), 5000);
    }
}

async function finishGenerate(ok, err) {
    el('report-generate').disabled = false;
    if (ok) {
        setProgress(100, '生成完成');
        setTimeout(() => { el('report-progress').style.display = 'none'; }, 2000);
        await loadReports();
        // 打开刚生成的那份（列表第一条）
        const rows = await api('/api/tickets/report');
        const latest = (rows.reports || [])[0];
        if (latest && latest.status === 'done') {
            openReport(latest.id);
        }
    } else {
        setProgress(100, '生成失败');
        showAlert('报告生成失败：' + err);
        loadReports();
    }
}

// ---------- 历史报告列表 ----------

async function loadReports() {
    const box = el('report-list');
    let reports;
    try {
        reports = (await api('/api/tickets/report')).reports || [];
    } catch (e) {
        box.textContent = '加载失败：' + e.message;
        return;
    }
    if (reports.length === 0) {
        box.innerHTML = '<div class="empty-hint">还没有报告，选择范围后点「生成报告」</div>';
        return;
    }
    const rows = reports.map(r => {
        let params = {};
        try { params = JSON.parse(r.params_json || '{}'); } catch (e) { /* 坏参数按空处理 */ }
        const cond = condText(params);
        const st = r.status === 'done' ? '<span class="st-done">完成</span>'
            : r.status === 'failed' ? '<span class="st-failed">失败</span>'
            : '<span class="st-running">生成中</span>';
        const actions = r.status === 'done'
            ? '<button class="icon-btn" data-view="' + r.id + '">查看</button>'
            : r.status === 'failed' ? ''
            : '<span class="progress">生成中…</span>';
        return '<tr>'
            + '<td>' + fmtDate(r.created_at) + '</td>'
            + '<td>' + rangeText(params) + '</td>'
            + '<td>' + (cond || '—') + '</td>'
            + '<td>' + st + '</td>'
            + '<td>' + (r.created_by || '—') + '</td>'
            + '<td>' + actions + '</td>'
            + '</tr>'
            + (r.status === 'failed' && r.error
                ? '<tr><td colspan="6" class="err">失败原因：' + r.error + '</td></tr>' : '');
    }).join('');
    box.innerHTML = '<table class="sync-table"><thead><tr>'
        + '<th>生成时间</th><th>时间范围</th><th>筛选条件</th><th>状态</th><th>触发人</th><th>操作</th>'
        + '</tr></thead><tbody>' + rows + '</tbody></table>';
    box.querySelectorAll('[data-view]').forEach(btn => {
        btn.addEventListener('click', () => openReport(Number(btn.dataset.view)));
    });
}

// ---------- 查看与导出 ----------

async function openReport(id) {
    el('view-title').textContent = '报告 #' + id;
    el('view-body').textContent = '加载中…';
    el('view-mask').style.display = 'flex';
    try {
        const r = (await api('/api/tickets/report/' + id)).report;
        if (!r) { el('view-body').textContent = '报告不存在'; return; }
        currentReport = r;
        el('view-body').innerHTML = renderMd(r.content_md || '（报告内容为空）');
    } catch (e) {
        el('view-body').textContent = '加载失败：' + e.message;
    }
}

/** 导出原始 Markdown 文件 */
function exportReport() {
    if (!currentReport || !currentReport.content_md) { toast('没有可导出的内容'); return; }
    const blob = new Blob([currentReport.content_md], { type: 'text/markdown;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = '工单分析报告-' + currentReport.id + '.md';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(a.href);
}

// Markdown 渲染用 common.js 的 renderMd（与经验库页共用）

// ---------- 初始化 ----------

el('report-generate').addEventListener('click', generate);
el('view-close').addEventListener('click', () => { el('view-mask').style.display = 'none'; });
el('view-export').addEventListener('click', exportReport);
el('view-mask').addEventListener('click', e => {
    if (e.target === el('view-mask')) el('view-mask').style.display = 'none';
});

loadOptions();
loadReports();
