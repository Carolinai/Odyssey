// 工单看板：筛选联动、统计卡片、6 组 ECharts 图表、点击下钻工单明细
// 色板说明：双系列图用 蓝#2a78d6 / 橙#eb6834（已过色盲安全校验）；
// 单系列排名条形图一律蓝#2a78d6（不做按名次变色的彩虹条）；
// 优先级是有序类别，用单色蓝由浅到深（低→高）。

const C = {
    s1: '#2a78d6',      // 系列1：新增 / 已解决 / 单系列条形
    s2: '#eb6834',      // 系列2：解决 / 在办
    ordLow: '#86b6ef',  // 优先级 低（浅）
    ordMid: '#3987e5',  // 优先级 中
    ordHigh: '#1c5cab', // 优先级 高（深）
    grid: '#e1e0d9',    // 网格细线（实线）
    axis: '#898781',    // 轴标签（弱化）
    ink: '#0b0b0b',     // 主文字
    ink2: '#52514e'     // 次文字（数值标签用文字色，不用系列色）
};

const CATEGORY_LABEL = { to_do: '待办类', in_progress: '进行类', processed: '待确认 / 待发布（已处理完）', done: '已解决类' };

const charts = {}; // 图表实例按容器 id 存放
let filterOptions = null;
let drillState = null; // 下钻弹层当前状态 {dim, value, page, size, total}

// ---------- 筛选参数 ----------

function filterParams() {
    const from = document.getElementById('f-from').value;
    const to = document.getElementById('f-to').value;
    const p = {};
    if (from) p.from = new Date(from + 'T00:00:00+08:00').getTime();
    if (to) p.to = new Date(to + 'T23:59:59+08:00').getTime();
    const product = document.getElementById('f-product').value;
    const customer = document.getElementById('f-customer').value;
    const status = document.getElementById('f-status').value;
    const assignee = document.getElementById('f-assignee').value;
    const resolver = document.getElementById('f-resolver').value;
    const watcher = document.getElementById('f-watcher').value;
    if (product) p.product = product;
    if (customer) p.customer = customer;
    if (status) p.status_name = status;
    if (assignee) p.assignee = assignee;
    if (resolver) p.resolver = resolver;
    if (watcher) p.watcher = watcher;
    return p;
}

function qs(params) {
    return Object.keys(params).map(k => k + '=' + encodeURIComponent(params[k])).join('&');
}

// 时间快捷按钮：mode = today / 7d / week / 30d / 90d / 180d / year / all（本地 UTC+8 语义）
function presetRange(mode) {
    const fromEl = document.getElementById('f-from');
    const toEl = document.getElementById('f-to');
    const fmt = d => {
        const p = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
    };
    const today = new Date();
    let from = null;
    let to = today;
    if (mode === 'all') {
        from = null; to = null;
    } else if (mode === 'today') {
        from = today;
    } else if (mode === 'week') {
        // 本周：周一为起点（周末倒退 weekday 天）
        from = new Date(today);
        from.setDate(today.getDate() - (today.getDay() === 0 ? 6 : today.getDay() - 1));
    } else if (mode === 'year') {
        from = new Date(today.getFullYear(), 0, 1);
    } else {
        const days = { '7d': 7, '30d': 30, '90d': 90, '180d': 180 }[mode];
        from = new Date(Date.now() - (days - 1) * 86400000);
    }
    fromEl.value = from ? fmt(from) : '';
    toEl.value = to ? fmt(to) : '';
    refresh();
}

// ---------- 数据加载 ----------

async function loadOptions() {
    try {
        filterOptions = await api('/api/tickets/stats/options');
    } catch (e) { filterOptions = null; return; }
    const fill = (id, items) => {
        const sel = document.getElementById(id);
        (items || []).forEach(o => {
            const opt = document.createElement('option');
            opt.value = o.name;
            opt.textContent = o.name;
            sel.appendChild(opt);
        });
    };
    fill('f-product', filterOptions.products);
    fill('f-customer', filterOptions.customers);
    // 负责人/解决者选项值用 uuid（同名可区分），显示名
    (filterOptions.assignees || []).forEach(a => {
        const opt = document.createElement('option');
        opt.value = a.uuid;
        opt.textContent = a.name;
        document.getElementById('f-assignee').appendChild(opt);
    });
    (filterOptions.resolvers || []).forEach(a => {
        const opt = document.createElement('option');
        opt.value = a.uuid;
        opt.textContent = a.name;
        document.getElementById('f-resolver').appendChild(opt);
    });
    (filterOptions.watchers || []).forEach(a => {
        const opt = document.createElement('option');
        opt.value = a.uuid;
        opt.textContent = a.name;
        document.getElementById('f-watcher').appendChild(opt);
    });
    // 状态选项按分类分组
    const groups = {};
    (filterOptions.statuses || []).forEach(s => {
        (groups[s.category] = groups[s.category] || []).push(s.name);
    });
    const statusSel = document.getElementById('f-status');
    ['to_do', 'in_progress', 'processed', 'done'].forEach(cat => {
        if (!groups[cat]) return;
        const og = document.createElement('optgroup');
        og.label = CATEGORY_LABEL[cat] || cat;
        groups[cat].forEach(n => {
            const opt = document.createElement('option');
            opt.value = n;
            opt.textContent = n;
            og.appendChild(opt);
        });
        statusSel.appendChild(og);
    });
    // 选项较多的维度支持输入模糊筛选
    ['f-customer', 'f-assignee', 'f-resolver', 'f-watcher'].forEach(id => makeSearchable(document.getElementById(id)));
}

/** 把原生 select 增强为可输入模糊筛选的下拉框（选项仍存在 select 里，value 语义不变） */
function makeSearchable(sel) {
    const wrap = document.createElement('span');
    wrap.className = 'dash-combo';
    sel.parentNode.insertBefore(wrap, sel);
    wrap.appendChild(sel);
    sel.style.display = 'none';
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'dash-combo-input';
    input.placeholder = '全部（输入可筛选）';
    const list = document.createElement('div');
    list.className = 'dash-combo-list';
    // 一键清空：点 × 回到「全部」并触发联动刷新
    const clearBtn = document.createElement('button');
    clearBtn.type = 'button';
    clearBtn.className = 'dash-combo-clear';
    clearBtn.textContent = '×';
    clearBtn.title = '清空筛选';
    wrap.appendChild(input);
    wrap.appendChild(clearBtn);
    wrap.appendChild(list);

    // 输入框回显当前选中项文本；「全部」显示为空（占位符即「全部」）
    function syncInput() {
        const opt = sel.options[sel.selectedIndex];
        input.value = opt && opt.value ? opt.textContent : '';
        wrap.classList.toggle('has-value', !!(opt && opt.value));
    }
    sel._comboSync = syncInput; // 重置等场景外部同步回显

    function openList() {
        const q = input.value.trim().toLowerCase();
        list.innerHTML = '';
        Array.from(sel.options).forEach(opt => {
            // 有筛选词时跳过「全部」与不匹配项
            if (q && (!opt.value || !opt.textContent.toLowerCase().includes(q))) return;
            const item = document.createElement('div');
            item.className = 'dash-combo-item';
            item.textContent = opt.value ? opt.textContent : '全部';
            item.dataset.value = opt.value;
            list.appendChild(item);
        });
        list.style.display = 'block';
    }
    // mousedown 先于 input 的 blur，保证点选在收起前生效
    list.addEventListener('mousedown', e => {
        const item = e.target.closest('.dash-combo-item');
        if (!item) return;
        sel.value = item.dataset.value;
        syncInput();
        list.style.display = 'none';
        sel.dispatchEvent(new Event('change'));
    });
    input.addEventListener('focus', openList);
    input.addEventListener('input', openList);
    input.addEventListener('blur', () => {
        // 延迟收起，避免抢在 mousedown 之前；失焦时回显真实选中项
        setTimeout(() => { list.style.display = 'none'; syncInput(); }, 120);
    });
    // mousedown 阻止默认行为，保持输入框焦点不被抢走
    clearBtn.addEventListener('mousedown', e => e.preventDefault());
    clearBtn.addEventListener('click', () => {
        sel.value = '';
        syncInput();
        sel.dispatchEvent(new Event('change'));
        input.focus();
        openList();
    });
    syncInput();
}

async function loadStats() {
    const chartsEl = document.getElementById('charts');
    chartsEl.classList.add('refreshing'); // 保持旧渲染降透明度，不做骨架屏闪烁
    try {
        const data = await api('/api/tickets/stats?' + qs(filterParams()));
        renderTiles(data.cards || {});
        renderCharts(data);
    } catch (e) {
        toast('看板数据加载失败：' + e.message);
    } finally {
        chartsEl.classList.remove('refreshing');
    }
}

function refresh() { loadStats(); }

function renderTiles(cards) {
    document.getElementById('st-total').textContent = fmtNum(cards.total);
    document.getElementById('st-todo').textContent = fmtNum(cards.to_do);
    document.getElementById('st-doing').textContent = fmtNum(cards.in_progress);
    document.getElementById('st-processed').textContent = fmtNum(cards.processed);
    document.getElementById('st-done').textContent = fmtNum(cards.done);
    document.getElementById('st-rate').textContent =
        cards.resolve_rate == null ? '—' : cards.resolve_rate + '%';
}

function fmtNum(v) { return v == null ? '—' : String(v); }

// ---------- 图表 ----------

/** ECharts 公共项：细网格实线、弱化轴、字体沿用系统 sans */
function baseGrid() {
    return { left: 8, right: 44, top: 12, bottom: 8, containLabel: true };
}

function axisCommon() {
    return {
        axisLine: { lineStyle: { color: C.grid } },
        axisTick: { show: false },
        axisLabel: { color: C.axis },
        splitLine: { lineStyle: { color: C.grid, width: 1, type: 'solid' } }
    };
}

function getChart(id) {
    if (!charts[id]) charts[id] = echarts.init(document.getElementById(id));
    return charts[id];
}

function renderCharts(data) {
    renderTrend(data.trend || []);
    renderRankBars('chart-product', data.products || [], 'product');
    renderRankBars('chart-customer', data.customers || [], 'customer');
    renderRankBars('chart-module', data.modules || [], 'module');
    renderAssignee(data.assignees || []);
    renderPriority(data.priorities || []);
    Object.values(charts).forEach(c => c.resize());
}

/** 月度趋势双线（同一单位同一轴，不做双轴） */
function renderTrend(trend) {
    const chart = getChart('chart-trend');
    const months = trend.map(t => t.month);
    chart.setOption({
        color: [C.s1, C.s2],
        legend: { top: 0, left: 'center', icon: 'roundRect', itemWidth: 14, itemHeight: 4,
                  textStyle: { color: C.ink2 } },
        grid: { left: 8, right: 16, top: 32, bottom: 8, containLabel: true },
        tooltip: { trigger: 'axis', axisPointer: { type: 'line', lineStyle: { color: C.axis } } },
        xAxis: Object.assign({ type: 'category', data: months, boundaryGap: false,
            axisLabel: { color: C.axis, rotate: months.length > 18 ? 45 : 0 } }, axisCommon()),
        yAxis: Object.assign({ type: 'value' }, axisCommon()),
        series: [
            { name: '新增', type: 'line', data: trend.map(t => t.created),
              lineStyle: { width: 2 }, symbol: 'circle', symbolSize: 6,
              emphasis: { focus: 'series' } },
            { name: '解决', type: 'line', data: trend.map(t => t.resolved),
              lineStyle: { width: 2 }, symbol: 'circle', symbolSize: 6,
              emphasis: { focus: 'series' } }
        ]
    }, true);
    // 点线下钻：seriesIndex 0=新增（按创建月），1=解决（按解决月）
    chart.off('click');
    chart.on('click', p => {
        if (p.componentType !== 'series') return;
        openDrill(p.seriesIndex === 0 ? 'month_created' : 'month_resolved', p.name,
            (p.seriesIndex === 0 ? '新增' : '解决') + ' · ' + p.name);
    });
}

/** 横向条形图按行数撑高容器，保证每行标签都有位置（避免 ECharts 密集时抽稀/隐藏标签） */
function fitChartHeight(id, rows) {
    const el = document.getElementById(id);
    const h = Math.max(300, rows.length * 24 + 60);
    if (el.style.height !== h + 'px') {
        el.style.height = h + 'px';
    }
}

/** 单系列排名条形图（产品线 / 客户 / 模块）：统一蓝色，条端标数值 */
function renderRankBars(id, rows, dim) {
    const chart = getChart(id);
    fitChartHeight(id, rows);
    // queryForList 倒序返回（量大在前），横向条形图 y 轴自下而上，需再反转让最大值在最上
    const sorted = rows.slice().reverse();
    const names = sorted.map(r => r.name);
    const values = sorted.map(r => r.n);
    chart.setOption({
        grid: baseGrid(),
        tooltip: { trigger: 'item' },
        xAxis: Object.assign({ type: 'value' }, axisCommon()),
        yAxis: Object.assign({ type: 'category', data: names,
            axisLabel: { color: C.axis, interval: 0, width: 110, overflow: 'truncate' } }, axisCommon()),
        series: [{
            type: 'bar', data: values, barWidth: 14,
            itemStyle: { color: C.s1, borderRadius: [0, 4, 4, 0] }, // 4px 圆角只圆数据端
            label: { show: true, position: 'right', color: C.ink2 }
        }]
    }, true);
    chart.resize();
    attachDrill(chart, dim);
}

/** 负责人分布：按业务状态四段堆叠（待办灰 / 进行中橙 / 待确认·待发布 深青绿 / 已解决蓝），随状态筛选联动。
 *  深青绿 #12876f 已过色板校验（对橙在红绿色盲下 ΔE 7.8，处于 6–8 允许带，
 *  依赖堆叠段 2px 白色分隔 + 图例作次级编码；灰色待办段为既定中性色，不参与校验） */
function renderAssignee(rows) {
    const chart = getChart('chart-assignee');
    fitChartHeight('chart-assignee', rows);
    const sorted = rows.slice().reverse();
    const names = sorted.map(r => r.name);
    // 下钻接口的 assignee 参数是 uuid，点击时按姓名反查（显示的是姓名）
    const uuidByName = {};
    rows.forEach(r => { if (!uuidByName[r.name]) uuidByName[r.name] = r.uuid; });
    const common = {
        type: 'bar', stack: 'total', barWidth: 14,
        itemStyle: { borderColor: '#fff', borderWidth: 1 } // 堆叠段 2px 视觉间隙（左右各 1px），合计由 tooltip 呈现
    };
    chart.setOption({
        color: ['#b8b7b0', C.s2, '#12876f', C.s1], // 待办=中性灰、进行中=橙、待确认/待发布=深青绿、已解决=蓝
        legend: { top: 0, left: 'center', icon: 'roundRect', itemWidth: 14, itemHeight: 4,
                  textStyle: { color: C.ink2 } },
        grid: baseGrid(),
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        xAxis: Object.assign({ type: 'value' }, axisCommon()),
        yAxis: Object.assign({ type: 'category', data: names,
            axisLabel: { color: C.axis, interval: 0, width: 90, overflow: 'truncate' } }, axisCommon()),
        series: [
            Object.assign({ name: '待办', data: sorted.map(r => r.todo) }, common),
            Object.assign({ name: '进行中', data: sorted.map(r => r.doing) }, common),
            Object.assign({ name: '待确认/待发布', data: sorted.map(r => r.processed) }, common),
            Object.assign({ name: '已解决', data: sorted.map(r => r.done) }, common)
        ]
    }, true);
    chart.resize();
    chart.off('click');
    chart.on('click', p => {
        if (p.componentType !== 'series') return;
        const uuid = uuidByName[p.name];
        if (!uuid) return;
        openDrill('assignee', uuid, p.name);
    });
}

/** 优先级分布：有序类别用单色蓝浅→深（高=最深），不按数值大小变色 */
function renderPriority(rows) {
    const chart = getChart('chart-priority');
    const ordColor = { '高': C.ordHigh, '中': C.ordMid, '低': C.ordLow };
    // 固定显示顺序 高/中/低（接口已按此顺序返回），横向条形 y 轴反转
    const sorted = rows.slice().reverse();
    chart.setOption({
        grid: baseGrid(),
        tooltip: { trigger: 'item' },
        xAxis: Object.assign({ type: 'value' }, axisCommon()),
        yAxis: Object.assign({ type: 'category', data: sorted.map(r => r.name),
            axisLabel: { color: C.axis, interval: 0 } }, axisCommon()),
        series: [{
            type: 'bar', barWidth: 18,
            data: sorted.map(r => ({
                value: r.n,
                itemStyle: { color: ordColor[r.name] || C.s1, borderRadius: [0, 4, 4, 0] }
            })),
            label: { show: true, position: 'right', color: C.ink2 }
        }]
    }, true);
    attachDrill(chart, 'priority');
}

/** 条形图统一：点击条目下钻对应维度 */
function attachDrill(chart, dim) {
    chart.off('click');
    chart.on('click', p => {
        if (p.componentType !== 'series') return;
        openDrill(dim, p.name, p.name);
    });
}

// ---------- 下钻弹层 ----------

// ONES 工单页路由（SPA hash 路由，与 need.txt 中的视图地址同一规则）
const ONES_TASK_URL = 'http://10.10.191.213/project/#/team/3HHnQbuG/project/NfvccPP54SaDCYAS/task/';

/** 打开编号/标题对应的 ONES 工单页（新标签页） */
function openOnesTicket(uuid) {
    if (uuid) window.open(ONES_TASK_URL + uuid, '_blank');
}

async function openDrill(dim, value, title) {
    drillState = { dim: dim, value: value, page: 1, size: 20, total: 0 };
    document.getElementById('drill-title').textContent = '工单明细：' + title;
    document.getElementById('drill-mask').style.display = 'flex';
    await loadDrill();
}

async function loadDrill() {
    const body = document.getElementById('drill-body');
    // 不清空旧内容，降透明度等待新数据，避免翻页闪动
    body.classList.add('refreshing');
    const jump = document.getElementById('drill-jump');
    try {
        const params = filterParams();
        params.dim = drillState.dim;
        params.value = drillState.value;
        params.page = drillState.page;
        params.size = drillState.size;
        const data = await api('/api/tickets/stats/drill?' + qs(params));
        drillState.total = data.total || 0;
        renderDrillRows(data.rows || []);
        const pages = Math.max(1, Math.ceil(drillState.total / drillState.size));
        document.getElementById('drill-page-info').textContent =
            '/ ' + pages + ' 页 · 共 ' + drillState.total + ' 条';
        document.getElementById('drill-prev').disabled = drillState.page <= 1;
        document.getElementById('drill-next').disabled = drillState.page >= pages;
        jump.value = drillState.page;
        jump.max = pages;
    } catch (e) {
        // 已有旧表格时保留原内容仅提示；首次加载失败才在弹层内报错
        if (body.querySelector('table')) toast('加载失败：' + e.message);
        else body.textContent = '加载失败：' + e.message;
    } finally {
        body.classList.remove('refreshing');
    }
}

function renderDrillRows(rows) {
    const body = document.getElementById('drill-body');
    body.innerHTML = '';
    if (rows.length === 0) { body.textContent = '该维度下暂无工单'; return; }
    const table = document.createElement('table');
    table.className = 'dash-table';
    table.innerHTML = '<thead><tr><th>编号</th><th>标题</th><th>客户</th><th>产品线</th>'
        + '<th>状态</th><th>优先级</th><th>负责人</th><th>解决者</th><th>创建时间</th></tr></thead>';
    const tbody = document.createElement('tbody');
    rows.forEach(r => {
        const tr = document.createElement('tr');
        const cells = [r.number, r.name, r.customer_name || '—', r.products || '—',
         r.status_name, r.priority || '—', r.assign_name || '—',
         r.resolver_name || '—', fmtTime(r.create_time)];
        cells.forEach((text, i) => {
            const td = document.createElement('td');
            if (i <= 1 && r.uuid) {
                // 编号与标题可点击跳转 ONES 工单页
                const a = document.createElement('a');
                a.href = ONES_TASK_URL + r.uuid;
                a.target = '_blank';
                a.rel = 'noopener';
                a.textContent = text == null ? '—' : text;
                td.appendChild(a);
            } else {
                td.textContent = text == null ? '—' : text;
            }
            if (i === 1) td.className = 'dash-cell-name';
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    body.appendChild(table);
}

function fmtTime(ms) {
    if (!ms) return '—';
    const d = new Date(Number(ms));
    const p = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

function closeDrill() {
    document.getElementById('drill-mask').style.display = 'none';
    drillState = null;
}

// ---------- 事件绑定与初始化 ----------

document.querySelectorAll('.dash-preset[data-mode]').forEach(btn => {
    btn.addEventListener('click', () => {
        presetRange(btn.dataset.mode);
        // 点亮的预设为当前选中项；手改日期或重置时由下方逻辑取消
        document.querySelectorAll('.dash-preset.active').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    });
});
['f-from', 'f-to', 'f-product', 'f-customer', 'f-status', 'f-assignee', 'f-resolver'].forEach(id => {
    document.getElementById(id).addEventListener('change', refresh);
});
// 手改日期后不再对应任何预设，取消高亮
['f-from', 'f-to'].forEach(id => {
    document.getElementById(id).addEventListener('input', () => {
        document.querySelectorAll('.dash-preset.active').forEach(b => b.classList.remove('active'));
    });
});
// 重置条件：清空日期与全部下拉后回到全量视图
document.getElementById('f-reset').addEventListener('click', () => {
    ['f-from', 'f-to'].forEach(id => { document.getElementById(id).value = ''; });
    ['f-product', 'f-customer', 'f-status', 'f-assignee', 'f-resolver', 'f-watcher'].forEach(id => {
        const sel = document.getElementById(id);
        sel.value = '';
        if (sel._comboSync) sel._comboSync(); // 同步搜索框回显
    });
    document.querySelectorAll('.dash-preset.active').forEach(b => b.classList.remove('active'));
    refresh();
});
document.getElementById('drill-close').addEventListener('click', closeDrill);
document.getElementById('drill-mask').addEventListener('click', e => {
    if (e.target === e.currentTarget) closeDrill();
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeDrill(); });
document.getElementById('drill-prev').addEventListener('click', () => {
    if (drillState && drillState.page > 1) { drillState.page--; loadDrill(); }
});
document.getElementById('drill-next').addEventListener('click', () => {
    if (drillState) { drillState.page++; loadDrill(); }
});
// 页码输入跳转：回车或失焦时生效，越界自动收敛
function jumpToPage() {
    if (!drillState) return;
    const jump = document.getElementById('drill-jump');
    const max = parseInt(jump.max, 10) || 1;
    const target = Math.min(max, Math.max(1, parseInt(jump.value, 10) || 1));
    if (target !== drillState.page) {
        drillState.page = target;
        loadDrill();
    } else {
        jump.value = drillState.page;
    }
}
document.getElementById('drill-jump').addEventListener('keydown', e => {
    if (e.key === 'Enter') jumpToPage();
});
document.getElementById('drill-jump').addEventListener('change', jumpToPage);
window.addEventListener('resize', () => Object.values(charts).forEach(c => c.resize()));

loadOptions().then(refresh);
