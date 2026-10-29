// 审计日志页：全量日志按操作对象（客户）分组展示

const auditGroups = document.getElementById('audit-groups');
const auditFilter = document.getElementById('audit-filter');

let auditLogs = []; // 后端已按时间倒序

async function loadAuditLogs() {
    auditGroups.textContent = '加载中…';
    try {
        auditLogs = await api('/api/audit');
    } catch (e) {
        auditLogs = [];
        auditGroups.textContent = '加载失败：' + e.message;
        return;
    }
    renderAuditGroups();
}

function renderAuditGroups() {
    const kw = auditFilter.value.trim().toLowerCase();

    // 按对象名称分组，保持倒序出现顺序（最新操作的对象排前）
    const groups = new Map();
    auditLogs.forEach(log => {
        const key = log.target_name || '（未命名）';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(log);
    });

    auditGroups.innerHTML = '';
    let shown = 0;
    groups.forEach((logs, name) => {
        const hit = logs.filter(l => {
            if (!kw) return true;
            return name.toLowerCase().includes(kw)
                || ((l.user || '') + ' ' + (l.description || '')).toLowerCase().includes(kw);
        });
        if (hit.length === 0) return;
        shown += hit.length;

        const group = document.createElement('div');
        group.className = 'audit-group';

        const head = document.createElement('div');
        head.className = 'audit-group-head';
        const title = document.createElement('span');
        title.textContent = name;
        const count = document.createElement('span');
        count.className = 'audit-group-count';
        count.textContent = hit.length + ' 条';
        head.appendChild(title);
        head.appendChild(count);
        group.appendChild(head);

        hit.forEach(l => group.appendChild(buildAuditRow(l)));
        auditGroups.appendChild(group);
    });

    if (shown === 0) {
        auditGroups.innerHTML = '';
        const empty = document.createElement('div');
        empty.className = 'empty-hint';
        empty.textContent = kw ? '（无匹配日志）' : '（暂无审计日志）';
        auditGroups.appendChild(empty);
    }
}

auditFilter.addEventListener('input', renderAuditGroups);
loadAuditLogs();
