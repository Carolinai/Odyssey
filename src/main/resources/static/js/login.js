// 登录页：Jenkins 账号校验通过后进入系统
// 登录前会自动确保「本机连接」（SSH 反向隧道）在线：首次使用自动下发初始化脚本，
// 用户双击运行后蒙版自动消失进入登录；已装过但离线则提示重跑守护脚本。

// 登录成功后的去向：?next= 指定的来源页，默认首页。只允许站内路径
function nextUrl() {
    const next = new URLSearchParams(location.search).get('next');
    return next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
}

// ---------- 本机连接（隧道）初始化 ----------

let tunnelSkipped = false;

function maskEl() {
    return document.getElementById('tunnel-mask');
}

function setMaskText(text) {
    document.getElementById('tunnel-mask-text').textContent = text;
}

function showMask() {
    tunnelSkipped = false;
    maskEl().classList.remove('hidden');
    document.getElementById('tunnel-mask-redownload').classList.add('hidden');
}

/** 触发下载该用户的隧道初始化脚本（每次下载即换新密钥、旧密钥作废） */
function downloadTunnelScript(username) {
    const a = document.createElement('a');
    a.href = '/api/auth/tunnel-script?username=' + encodeURIComponent(username);
    a.download = 'odyssey-tunnel-init.bat';
    document.body.appendChild(a);
    a.click();
    a.remove();
}

function hideMask() {
    maskEl().classList.add('hidden');
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * 确保该用户的本机连接在线；返回 false 表示初始化未完成（用户可跳过直接登录）。
 * 状态接口异常时静默放行，不阻塞登录。
 */
async function ensureTunnel(username) {
    let status;
    try {
        status = await api('/api/auth/tunnel-status?username=' + encodeURIComponent(username));
    } catch (e) {
        return true; // 检测失败不阻塞登录（后端多线路会自行尝试）
    }
    if (status.online) {
        return true;
    }

    showMask();
    if (!status.installed) {
        // 首次使用：自动下载专属初始化脚本
        downloadTunnelScript(username);
        setMaskText('首次使用，已为您下载初始化脚本，请双击运行下载的 odyssey-tunnel-init.bat，完成后本页自动继续。');
    } else {
        // 已装过：公司组策略禁用注册表，无法用 odyssey:// 协议静默拉起，
        // 提示手动重跑守护脚本，轮询等待恢复；重跑无效时可手动重新下载脚本升级连接通道
        document.getElementById('tunnel-mask-redownload').classList.remove('hidden');
        setMaskText('本机连接未响应，请双击运行 %USERPROFILE%\\.odyssey-tunnel\\start-tunnel.bat'
                + '（或注销重新登录电脑），完成后本页自动继续。'
                + '若重跑无效，可点下方「重新下载初始化脚本」升级连接通道。');
    }

    // 轮询状态，最久等 90 秒
    const deadline = Date.now() + 90000;
    while (Date.now() < deadline) {
        if (tunnelSkipped) {
            hideMask();
            return false;
        }
        await sleep(3000);
        try {
            const s = await api('/api/auth/tunnel-status?username=' + encodeURIComponent(username));
            if (s.online) {
                hideMask();
                return true;
            }
        } catch (e) { /* 继续轮询 */ }
    }
    setMaskText('等待超时。可直接登录（若他人连接在线），或重新运行初始化脚本后再登录。');
    document.getElementById('tunnel-mask-redownload').classList.remove('hidden');
    return false;
}

document.getElementById('tunnel-skip').addEventListener('click', () => {
    tunnelSkipped = true;
});

// 蒙版内的重新下载入口（离线提示/等待超时后出现）：用当前填写的用户名换新密钥并下发脚本
document.getElementById('tunnel-mask-redownload').addEventListener('click', () => {
    const username = document.getElementById('login-user').value.trim();
    if (!username) {
        setMaskText('请先在上方填写用户名，再点「重新下载初始化脚本」。');
        return;
    }
    downloadTunnelScript(username);
    setMaskText('已重新下载初始化脚本，请双击运行下载的 odyssey-tunnel-init.bat，完成后本页自动继续。');
});

// 登录卡片上的手动重新下载入口：供已安装但需升级转发通道的用户（隧道在线时蒙版不会出现）
document.getElementById('tunnel-redownload').addEventListener('click', () => {
    const username = document.getElementById('login-user').value.trim();
    const errBox = document.getElementById('login-error');
    if (!username) {
        errBox.textContent = '请先填写用户名，再重新下载初始化脚本';
        return;
    }
    errBox.textContent = '';
    downloadTunnelScript(username);
});

// ---------- 登录 ----------

async function doLogin() {
    const username = document.getElementById('login-user').value.trim();
    const credential = document.getElementById('login-cred').value.trim();
    const errBox = document.getElementById('login-error');
    const btn = document.getElementById('login-submit');

    if (!username || !credential) {
        errBox.textContent = '请填写账号和密码/Token';
        return;
    }

    errBox.textContent = '';
    btn.disabled = true;
    btn.textContent = '登录中…';

    // 登录前先确保本机连接在线（首次使用会引导初始化，离线则提示重跑守护脚本）
    await ensureTunnel(username);

    try {
        await api('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username, credential: credential })
        });
        location.href = nextUrl();
    } catch (e) {
        errBox.textContent = e.message;
        btn.disabled = false;
        btn.textContent = '登录';
    }
}

document.getElementById('login-submit').addEventListener('click', doLogin);
// 输入框回车直接登录
['login-user', 'login-cred'].forEach(id => {
    document.getElementById(id).addEventListener('keydown', e => {
        if (e.key === 'Enter') doLogin();
    });
});

// 已登录则直接跳走
api('/api/auth/me').then(r => {
    if (r && r.user) location.href = nextUrl();
}).catch(() => { /* 未登录停留在本页 */ });
