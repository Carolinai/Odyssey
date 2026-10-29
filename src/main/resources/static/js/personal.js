// 个人中心：展示当前登录用户信息（显示名 + 账号）

api('/api/auth/me').then(r => {
    document.getElementById('profile-name').textContent = r && (r.name || r.user) ? (r.name || r.user) : '未知';
    document.getElementById('profile-user').textContent = r && r.user ? r.user : '未知';
}).catch(() => { /* 401 已由 api() 统一跳登录页 */ });
