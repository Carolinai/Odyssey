// 解决经验库：客户/产品线/关键词检索、分页列表、详情弹层（Markdown + 跳转源工单）、手动提炼
// 后端接口：GET /api/tickets/insights（分页筛选）、/options、/pending、/{id}；POST /extract

const ONES_TASK_URL = 'http://10.10.191.213/project/#/team/3HHnQbuG/project/NfvccPP54SaDCYAS/task/';

const PAGE_SIZE = 20;
let page = 1;
let total = 0;
let pollTimer = null;

const el = id => document.getElementById(id);

function pad(n) { return String(n).padStart(2, '0'); }
function fmtDate(ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
        + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function showAlert(html) {
    const box = el('insight-alert');
    box.innerHTML = html;
    box.style.display = 'block';
}
function hideAlert() { el('insight-alert').style.display = 'none'; }

// ---------- 筛选选项与待提炼数量 ----------

async function loadOptions() {
    try {
        const opts = await api('/api/tickets/insights/options');
        [['f-customer', opts.customers], ['f-product', opts.products]].forEach(([id, items]) => {
            const sel = el(id);
            (items || []).forEach(o => {
                const opt = document.createElement('option');
                opt.value = o.name;
                opt.textContent = o.name;
                sel.appendChild(opt);
            });
        });
    } catch (e) { /* 选项加载失败不阻塞页面 */ }
    refreshPendingHint();
}

async function refreshPendingHint() {
    try {
        const r = await api('/api/tickets/insights/pending');
        el('insight-pending').textContent = r.pending > 0
            ? '待提炼 ' + r.pending + ' 张（每次一批约 200 张）' : '已全部提炼';
    } catch (e) { /* 提示失败不影响页面 */ }
}

// ---------- 列表 ----------

function filterParams() {
    const p = {};
    const customer = el('f-customer').value;
    const product = el('f-product').value;
    const q = el('f-q').value.trim();
    if (customer) p.customer = customer;
    if (product) p.product = product;
    if (q) p.q = q;
    return p;
}

async function loadList() {
    const box = el('insight-list');
    const params = filterParams();
    const qs = Object.keys(params).map(k => k + '=' + encodeURIComponent(params[k])).join('&');
    let data;
    try {
        data = await api('/api/tickets/insights?' + qs + '&' + qsOfPage());
    } catch (e) {
        box.textContent = '加载失败：' + e.message;
        return;
    }
    total = data.total;
    const rows = data.rows || [];
    if (rows.length === 0) {
        box.innerHTML = '<div class="empty-hint">'
            + (total === 0 && !Object.keys(params).length
                ? '还没有经验，点「提炼经验」开始（或随工单同步自动生成）'
                : '没有符合条件的结果')
            + '</div>';
        renderPager();
        return;
    }
    const trs = rows.map(r => '<tr>'
        + '<td><a href="javascript:void(0)" data-view="' + r.id + '">#' + r.ticket_number + ' '
        + escHtml(r.ticket_name || '') + '</a></td>'
        + '<td>' + (r.customer_name || '—') + '</td>'
        + '<td>' + (r.product_name || '—') + '</td>'
        + '<td>' + (r.status_name || '—') + '</td>'
        + '<td>' + fmtDate(r.created_at) + '</td>'
        + '<td><button class="icon-btn" data-view="' + r.id + '">查看</button></td>'
        + '</tr>').join('');
    box.innerHTML = '<table class="sync-table"><thead><tr>'
        + '<th>源工单</th><th>客户</th><th>产品线</th><th>状态</th><th>提炼时间</th><th>操作</th>'
        + '</tr></thead><tbody>' + trs + '</tbody></table>';
    box.querySelectorAll('[data-view]').forEach(b => {
        b.addEventListener('click', () => openInsight(Number(b.dataset.view)));
    });
    renderPager();
}

function qsOfPage() {
    return 'page=' + page + '&size=' + PAGE_SIZE;
}

function renderPager() {
    const pager = el('insight-pager');
    if (total <= PAGE_SIZE) { pager.style.display = 'none'; return; }
    pager.style.display = 'flex';
    const pages = Math.ceil(total / PAGE_SIZE);
    el('insight-page-info').textContent = '第 ' + page + ' / ' + pages + ' 页（共 ' + total + ' 条）';
    el('insight-prev').disabled = page <= 1;
    el('insight-next').disabled = page >= pages;
}

function escHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ---------- 详情弹层 ----------

async function openInsight(id) {
    el('view-title').textContent = '经验 #' + id;
    el('view-body').textContent = '加载中…';
    el('view-source').style.display = 'none';
    el('view-mask').style.display = 'flex';
    try {
        const r = (await api('/api/tickets/insights/' + id)).insight;
        if (!r) { el('view-body').textContent = '经验不存在'; return; }
        el('view-title').textContent = '#' + r.ticket_number + ' ' + (r.ticket_name || '');
        el('view-body').innerHTML = renderMd(r.content_md || '（经验内容为空）');
        if (r.ticket_uuid) {
            const a = el('view-source');
            a.href = ONES_TASK_URL + r.ticket_uuid;
            a.style.display = '';
        }
    } catch (e) {
        el('view-body').textContent = '加载失败：' + e.message;
    }
}

// ---------- 手动提炼 ----------

async function extract() {
    hideAlert();
    let resp;
    try {
        resp = await api('/api/tickets/insights/extract', { method: 'POST' });
    } catch (e) {
        showAlert('触发失败：' + e.message);
        return;
    }
    if (!resp.started) {
        showAlert((resp.message || '无法触发') + (resp.need_ai_config ? '，<a href="/personal.html">去个人中心配置</a>' : ''));
        return;
    }
    el('insight-extract').disabled = true;
    el('insight-stop').style.display = '';
    el('insight-progress').style.display = 'block';
    setProgress(0, '排队等待中…');
    pollTask(resp.task_id);
}

/** 终止提炼：正在跑的协作式停止（当前这张跑完即停），排队的直接取消 */
async function stopExtract() {
    let resp;
    try {
        resp = await api('/api/tickets/insights/stop', { method: 'POST' });
    } catch (e) {
        showAlert('终止失败：' + e.message);
        return;
    }
    if (!resp.stopped) {
        toast(resp.message || '当前没有正在进行的经验提炼');
        el('insight-stop').style.display = 'none';
        return;
    }
    toast('已请求终止，当前这张跑完后停止');
}

function setProgress(percent, stage) {
    el('insight-bar').style.width = percent + '%';
    el('insight-stage').textContent = stage || '';
    el('insight-percent').textContent = percent > 0 ? percent + '%' : '';
}

async function pollTask(taskId) {
    try {
        const t = await api('/api/tasks/' + taskId);
        if (t.status === 'running' || t.status === 'pending') {
            setProgress(t.progress || 0, t.stage || '执行中…');
            pollTimer = setTimeout(() => pollTask(taskId), 3000);
        } else if (t.status === 'done') {
            el('insight-extract').disabled = false;
            el('insight-stop').style.display = 'none';
            setProgress(100, '提炼完成');
            setTimeout(() => { el('insight-progress').style.display = 'none'; }, 2000);
            refreshPendingHint();
            loadList();
        } else {
            el('insight-extract').disabled = false;
            el('insight-stop').style.display = 'none';
            setProgress(100, '提炼失败');
            showAlert('经验提炼失败：' + (t.error || '未知原因'));
        }
    } catch (e) {
        pollTimer = setTimeout(() => pollTask(taskId), 5000); // 瞬时网络错误不中断
    }
}

// ---------- 初始化 ----------

el('insight-search').addEventListener('click', () => { page = 1; loadList(); });
el('f-q').addEventListener('keydown', e => {
    if (e.key === 'Enter') { page = 1; loadList(); }
});
['f-customer', 'f-product'].forEach(id => {
    el(id).addEventListener('change', () => { page = 1; loadList(); });
});
el('insight-prev').addEventListener('click', () => { if (page > 1) { page--; loadList(); } });
el('insight-next').addEventListener('click', () => { page++; loadList(); });
el('insight-extract').addEventListener('click', extract);
el('insight-stop').addEventListener('click', stopExtract);
el('view-close').addEventListener('click', () => { el('view-mask').style.display = 'none'; });
el('view-mask').addEventListener('click', e => {
    if (e.target === el('view-mask')) el('view-mask').style.display = 'none';
});

loadOptions();
loadList();
