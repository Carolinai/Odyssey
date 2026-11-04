// 建议反馈页：新增反馈、查询筛选（无条件时后端默认按提交时间倒序）

const fbType = document.getElementById('fb-type');
const fbContent = document.getElementById('fb-content');
const fbSubmit = document.getElementById('fb-submit');
const listBox = document.getElementById('fb-list');

const qUsername = document.getElementById('q-username');
const qType = document.getElementById('q-type');
const qFrom = document.getElementById('q-from');
const qTo = document.getElementById('q-to');
const qReset = document.getElementById('q-reset');

const TYPE_LABEL = { bug: 'Bug', feature: '需求', suggestion: '建议' };
// 类型徽标浅色对（底色 / 文字色），同看板状态色规范
const TYPE_CLASS = { bug: 'fb-bug', feature: 'fb-feature', suggestion: 'fb-suggestion' };

function fmtTime(ms) {
    const d = new Date(Number(ms));
    const p = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
        + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

// 时间筛选：本地日期按 UTC+8 换算毫秒（与工单看板口径一致）
function paramMs(input, endOfDay) {
    const v = input.value;
    if (!v) return null;
    const t = new Date(v + 'T00:00:00+08:00').getTime();
    return endOfDay ? t + 24 * 3600 * 1000 - 1 : t;
}

async function loadList() {
    const params = new URLSearchParams();
    const username = qUsername.value.trim();
    if (username) params.set('username', username);
    if (qType.value) params.set('type', qType.value);
    const from = paramMs(qFrom, false);
    const to = paramMs(qTo, true);
    if (from != null) params.set('from', from);
    if (to != null) params.set('to', to);

    try {
        const list = await api('/api/feedbacks' + (params.toString() ? '?' + params.toString() : ''));
        renderList(list || []);
    } catch (e) {
        listBox.textContent = '加载失败：' + e.message;
    }
}

function renderList(list) {
    listBox.innerHTML = '';
    if (list.length === 0) {
        const hint = document.createElement('div');
        hint.className = 'empty-hint';
        hint.textContent = '暂无反馈记录，提交第一条吧';
        listBox.appendChild(hint);
        return;
    }
    const table = document.createElement('table');
    table.className = 'sync-table';
    table.innerHTML = '<thead><tr><th style="width:110px;">用户名</th><th style="width:80px;">类型</th>'
        + '<th>内容</th><th style="width:140px;">时间</th></tr></thead>';
    const tbody = document.createElement('tbody');
    list.forEach(f => {
        const tr = document.createElement('tr');
        const tdUser = document.createElement('td');
        tdUser.textContent = f.user_name || '—';
        const tdType = document.createElement('td');
        const pill = document.createElement('span');
        pill.className = 'fb-pill ' + (TYPE_CLASS[f.type] || '');
        pill.textContent = TYPE_LABEL[f.type] || f.type;
        tdType.appendChild(pill);
        const tdContent = document.createElement('td');
        tdContent.textContent = f.content;
        const tdTime = document.createElement('td');
        tdTime.textContent = fmtTime(f.created_at);
        tr.appendChild(tdUser);
        tr.appendChild(tdType);
        tr.appendChild(tdContent);
        tr.appendChild(tdTime);
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    listBox.appendChild(table);
}

// 提交反馈：类型 + 内容（用户名由后端从登录态解析）
fbSubmit.addEventListener('click', async () => {
    const content = fbContent.value.trim();
    if (!content) {
        alert('请填写反馈内容');
        return;
    }
    fbSubmit.disabled = true;
    try {
        await api('/api/feedbacks', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ type: fbType.value, content: content })
        });
        toast('已提交，感谢反馈');
        fbContent.value = '';
        loadList(); // 新记录出现在列表顶部
    } catch (e) {
        alert('提交失败：' + e.message);
    } finally {
        fbSubmit.disabled = false;
    }
});

// 查询条件：变更即刷新；重置清空全部条件后回到默认倒序
qReset.addEventListener('click', () => {
    qUsername.value = '';
    qType.value = '';
    qFrom.value = '';
    qTo.value = '';
    loadList();
});

[qUsername, qType, qFrom, qTo].forEach(el => el.addEventListener('change', loadList));

// 初始加载
loadList();
