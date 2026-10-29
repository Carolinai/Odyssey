// 登录页：Jenkins 账号校验通过后进入系统

// 登录成功后的去向：?next= 指定的来源页，默认首页。只允许站内路径
function nextUrl() {
    const next = new URLSearchParams(location.search).get('next');
    return next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
}

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
