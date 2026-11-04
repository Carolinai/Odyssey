// 个人中心：展示当前登录用户信息 + 工单助手个人配置（ONES Cookie / AI / 经验提炼开关）
// 配置按当前登录用户隔离，保存以页面当前内容为准全量覆盖

let currentUsername = '';

api('/api/auth/me').then(r => {
    document.getElementById('profile-name').textContent = r && (r.name || r.user) ? (r.name || r.user) : '未知';
    document.getElementById('profile-user').textContent = r && r.user ? r.user : '未知';
    currentUsername = r && r.user ? r.user : '';
    refreshTunnelState();
}).catch(() => { /* 401 已由 api() 统一跳登录页 */ });

// ---- 本机连接（隧道） ----

function refreshTunnelState() {
    const el = document.getElementById('tunnel-state');
    if (!currentUsername) {
        el.textContent = '未知';
        return;
    }
    api('/api/auth/tunnel-status?username=' + encodeURIComponent(currentUsername)).then(s => {
        el.textContent = !s.installed ? '未安装（尚未下载过初始化脚本）'
                : s.online ? '在线' : '已安装，当前离线';
    }).catch(() => {
        el.textContent = '状态获取失败';
    });
}

// 重新下载初始化脚本：服务端立即换新密钥（旧密钥作废、隧道断开），须提示用户马上运行
document.getElementById('tunnel-redownload').addEventListener('click', () => {
    if (!confirm('重新下载会立即作废旧密钥，当前隧道将断开，直到你在本机运行新脚本。确定继续吗？')) {
        return;
    }
    const a = document.createElement('a');
    a.href = '/api/auth/tunnel-script?username=' + encodeURIComponent(currentUsername);
    a.download = 'odyssey-tunnel-init.bat';
    document.body.appendChild(a);
    a.click();
    a.remove();
    toast('已下载初始化脚本，请立即双击运行');
    setTimeout(refreshTunnelState, 5000); // 稍后刷新状态（脚本运行后恢复在线）
});

// ---- 工单助手配置（个人） ----
// 文本项 id ↔ 配置 JSON 字段（服务端 SnakeCase 命名）
const textFields = [
    { id: 'ucfg-ones-cookie', key: 'ones_cookie' },
    { id: 'ucfg-ai-base-url', key: 'ai_base_url' },
    { id: 'ucfg-ai-api-key',  key: 'ai_api_key' },
    { id: 'ucfg-ai-model',    key: 'ai_model' }
];

// AI 调用参数（数字项，可留空走服务端默认值）
const numberFields = [
    { id: 'ucfg-ai-interval', key: 'ai_call_interval_ms' },
    { id: 'ucfg-ai-timeout',  key: 'ai_timeout_ms' },
    { id: 'ucfg-ai-retries',  key: 'ai_max_retries' }
];

function renderUserConfig(config) {
    textFields.forEach(f => {
        document.getElementById(f.id).value = config[f.key] || '';
    });
    numberFields.forEach(f => {
        document.getElementById(f.id).value = config[f.key] == null ? '' : config[f.key];
    });
    document.getElementById('ucfg-insight-auto').checked = config.insight_auto_enabled === true;
}

// 密码类输入框的显示/隐藏切换（明文存储，前端切换查看）
document.querySelectorAll('button[data-reveal]').forEach(btn => {
    btn.addEventListener('click', () => {
        const input = document.getElementById(btn.dataset.reveal);
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        btn.textContent = show ? '隐藏' : '显示';
    });
});

// AI 连通性测试：把页面当前 AI 三项发给服务端发一次最小请求（留空项按已保存配置计）
const aiTestBtn = document.getElementById('ucfg-ai-test');
const aiTestResult = document.getElementById('ucfg-ai-test-result');
aiTestBtn.addEventListener('click', async () => {
    aiTestBtn.disabled = true;
    aiTestBtn.textContent = '测试中…';
    aiTestResult.textContent = '';
    try {
        const res = await api('/api/user-config/ai-test', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                ai_base_url: document.getElementById('ucfg-ai-base-url').value.trim(),
                ai_api_key: document.getElementById('ucfg-ai-api-key').value.trim(),
                ai_model: document.getElementById('ucfg-ai-model').value.trim()
            })
        });
        aiTestResult.textContent = res.message || (res.ok ? '连通正常' : '不可用');
        aiTestResult.style.color = res.ok ? '#1a7f37' : '#c0392b';
        if (res.ok) toast('AI 连通正常');
    } catch (e) {
        aiTestResult.textContent = '测试请求失败：' + e.message;
        aiTestResult.style.color = '#c0392b';
    } finally {
        aiTestBtn.disabled = false;
        aiTestBtn.textContent = '测试 AI 连通';
    }
});

// 保存：整体覆盖当前用户的个人配置；数字项留空提交 null（走服务端默认值）
document.getElementById('save-personal-config').addEventListener('click', async () => {
    const body = {};
    textFields.forEach(f => {
        body[f.key] = document.getElementById(f.id).value.trim();
    });
    numberFields.forEach(f => {
        const raw = document.getElementById(f.id).value.trim();
        body[f.key] = raw === '' ? null : Number(raw);
    });
    body.insight_auto_enabled = document.getElementById('ucfg-insight-auto').checked;
    try {
        const saved = await api('/api/user-config', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        toast('已保存');
        renderUserConfig(saved); // 服务端归一化后重渲染
    } catch (e) {
        alert('保存失败：' + e.message);
    }
});

api('/api/user-config').then(renderUserConfig);
