// 公共工具：请求封装、复制、toast 提示（列表页、表单页、RSA 工具页共用）

/** 请求 JSON 接口，非 2xx 时抛出含 message 的 Error；401 统一跳登录页（带来源地址） */
async function api(url, options) {
    const resp = await fetch(url, options);
    if (resp.status === 401 && !location.pathname.startsWith('/login.html')) {
        location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
        const err = new Error('未登录');
        err.status = 401;
        throw err;
    }
    if (!resp.ok) {
        let message = '请求失败（HTTP ' + resp.status + '）';
        try {
            const body = await resp.json();
            if (body && body.message) message = body.message;
        } catch (e) { /* 忽略解析失败 */ }
        const err = new Error(message);
        err.status = resp.status;
        throw err;
    }
    const text = await resp.text();
    return text ? JSON.parse(text) : null;
}

/** 复制文本到剪贴板，成功后弹 toast */
async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
        toast('已复制');
    } catch (e) {
        // 兼容非 HTTPS / 旧浏览器
        const input = document.createElement('textarea');
        input.value = text;
        document.body.appendChild(input);
        input.select();
        document.execCommand('copy');
        document.body.removeChild(input);
        toast('已复制');
    }
}

let toastTimer = null;
function toast(message) {
    let el = document.getElementById('odyssey-toast');
    if (!el) {
        el = document.createElement('div');
        el.id = 'odyssey-toast';
        el.className = 'toast';
        document.body.appendChild(el);
    }
    el.textContent = message;
    el.style.display = 'block';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.style.display = 'none'; }, 1500);
}

// ---------- 全站左侧菜单 ----------
// 全局默认菜单（内置）；个人自定义存 localStorage，存在即整体覆盖默认。
// 内置项带 key（供菜单管理识别与当前页高亮），自定义项只有 label/url
const DEFAULT_MENUS = [
    { key: 'envs', label: '客户列表', url: '/' },
    { key: 'jenkins', label: 'Jenkins 视图', url: '/jenkins.html' },
    { key: 'audit', label: '审计日志', url: '/audit.html' },
    { key: 'decrypt', label: 'RSA 工具', url: '/decrypt.html' },
    { key: 'config', label: '全局配置', url: '/config.html' },
    { key: 'menus', label: '菜单管理', url: '/menus.html' }
];

const MENU_STORE_KEY = 'odyssey.menus.custom';

/** 读取个人菜单覆盖；未自定义或数据不合法时返回 null */
function loadMenuOverride() {
    try {
        const items = JSON.parse(localStorage.getItem(MENU_STORE_KEY));
        if (!Array.isArray(items) || items.length === 0) return null;
        // 内置菜单地址变更时同步旧的个人覆盖（仅纠正未被用户改过的默认地址）
        items.forEach(m => {
            if (m && m.key === 'jenkins' && m.url === '/index.html?view=jenkins') {
                m.url = '/jenkins.html';
            }
        });
        return items;
    } catch (e) {
        return null;
    }
}

function saveMenuOverride(items) {
    localStorage.setItem(MENU_STORE_KEY, JSON.stringify(items));
}

function clearMenuOverride() {
    localStorage.removeItem(MENU_STORE_KEY);
}

/** 当前生效的菜单：个人自定义 > 全局默认；visible === false 的项不显示 */
function currentMenus() {
    return (loadMenuOverride() || DEFAULT_MENUS).filter(m => m.visible !== false);
}

// 内置菜单图标（细线 SVG，颜色随菜单文字）；自定义菜单项统一用链环图标
const MENU_ICONS = {
    envs: '<path d="M17 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2"/><circle cx="10" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    jenkins: '<circle cx="12" cy="12" r="3.5"/><circle cx="12" cy="12" r="8"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/>',
    decrypt: '<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>',
    config: '<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/>',
    menus: '<line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/>',
    custom: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>'
};

function menuIconSvg(iconKey) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '2');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    svg.classList.add('menu-svg');
    svg.innerHTML = MENU_ICONS[iconKey] || MENU_ICONS.custom;
    return svg;
}

// Odyssey 品牌 logo：罗盘造型（呼应「奥德赛」的航程主题），蓝渐变圆环 + 导航指针
function brandLogoSvg() {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.classList.add('brand-logo');
    svg.innerHTML = '<defs><linearGradient id="odyssey-logo-g" x1="0" y1="0" x2="1" y2="1">'
        + '<stop offset="0" stop-color="#5dade2"/><stop offset="1" stop-color="#2e86c1"/></linearGradient></defs>'
        + '<circle cx="12" cy="12" r="9.5" fill="none" stroke="url(#odyssey-logo-g)" stroke-width="2"/>'
        + '<path d="M16.2 7.8 L13.2 13.2 L7.8 16.2 L10.8 10.8 Z" fill="url(#odyssey-logo-g)" '
        + 'stroke="#fff" stroke-width="0.8" stroke-linejoin="round"/>'
        + '<circle cx="12" cy="12" r="1" fill="#fff"/>';
    return svg;
}

// 渲染左侧菜单栏。各页面布局为 <div class="layout"><aside id="sidebar"> + <main class="container">；
// body 的 data-page 标注当前页（内置菜单 key），用于高亮；自定义项按地址匹配。
// 支持收起：品牌只留 logo、菜单只留图标，状态记忆在本浏览器
function renderSidebar() {
    const side = document.getElementById('sidebar');
    if (!side) return;
    const page = document.body.dataset.page || '';
    let collapsed = false;
    try {
        collapsed = localStorage.getItem('odyssey.sidebar.collapsed') === '1';
    } catch (e) { /* 忽略存储失败 */ }
    document.body.classList.toggle('sidebar-collapsed', collapsed);
    side.innerHTML = '';

    const brand = document.createElement('div');
    brand.className = 'side-brand';
    const brandLink = document.createElement('a');
    brandLink.href = '/';
    brandLink.title = 'Odyssey';
    brandLink.appendChild(brandLogoSvg());
    const brandText = document.createElement('span');
    brandText.className = 'menu-text';
    brandText.textContent = 'Odyssey';
    brandLink.appendChild(brandText);
    brand.appendChild(brandLink);
    side.appendChild(brand);

    currentMenus().forEach(menu => {
        const link = document.createElement('a');
        link.className = 'menu-link';
        link.href = menu.url;
        link.title = menu.label || menu.url; // 收起状态下悬停可看全名
        link.appendChild(menuIconSvg(menu.key));
        const text = document.createElement('span');
        text.className = 'menu-text';
        text.textContent = menu.label;
        link.appendChild(text);
        if (menu.key ? menu.key === page
                : (menu.url === location.pathname + location.search
                    || menu.url === location.pathname)) {
            link.classList.add('active');
        }
        side.appendChild(link);
    });

    // 底部收起/展开切换
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'side-toggle';
    toggle.textContent = collapsed ? '»' : '«';
    toggle.title = collapsed ? '展开菜单' : '收起菜单';
    toggle.addEventListener('click', () => {
        const next = !document.body.classList.contains('sidebar-collapsed');
        document.body.classList.toggle('sidebar-collapsed', next);
        try {
            localStorage.setItem('odyssey.sidebar.collapsed', next ? '1' : '0');
        } catch (e) { /* 忽略存储失败 */ }
        renderSidebar();
    });
    side.appendChild(toggle);

    // 最底部用户卡片：展示当前登录用户（中文显示名 + 账号），点卡片/「个人中心」进个人信息页
    api('/api/auth/me').then(r => {
        if (!r || !r.user) return;
        side.appendChild(buildUserCard(r.name || r.user, r.user));
    }).catch(() => { /* 未登录：api() 已统一跳登录页 */ });
}

/** 侧栏底部用户卡片：头像 + 显示名/账号，下方「个人中心」「注销」按钮 */
function buildUserCard(displayName, account) {
    const card = document.createElement('div');
    card.className = 'user-card';

    const main = document.createElement('div');
    main.className = 'user-card-main';
    main.title = '个人中心';
    main.addEventListener('click', () => { location.href = '/personal.html'; });

    const avatar = document.createElement('div');
    avatar.className = 'user-card-avatar';
    avatar.textContent = displayName.charAt(0);
    main.appendChild(avatar);

    const info = document.createElement('div');
    info.className = 'user-card-info';
    const name = document.createElement('div');
    name.className = 'user-card-name';
    name.textContent = displayName;
    const sub = document.createElement('div');
    sub.className = 'user-card-sub';
    sub.textContent = account;
    info.appendChild(name);
    info.appendChild(sub);
    main.appendChild(info);
    card.appendChild(main);

    const actions = document.createElement('div');
    actions.className = 'user-card-actions';
    const centerBtn = document.createElement('button');
    centerBtn.type = 'button';
    centerBtn.textContent = '个人中心';
    centerBtn.addEventListener('click', () => { location.href = '/personal.html'; });
    const logoutBtn = document.createElement('button');
    logoutBtn.type = 'button';
    logoutBtn.textContent = '注销';
    logoutBtn.addEventListener('click', async () => {
        try {
            await api('/api/auth/logout', { method: 'POST' });
        } catch (e) { /* 会话已失效也继续跳转 */ }
        location.href = '/login.html';
    });
    actions.appendChild(centerBtn);
    actions.appendChild(logoutBtn);
    card.appendChild(actions);

    return card;
}

document.addEventListener('DOMContentLoaded', renderSidebar);

/** 构造一条审计日志行：时间 | 用户名称(中文) | 账号 | 事件徽标 | 描述（审计日志页与详情页共用） */
function buildAuditRow(entry) {
    const row = document.createElement('div');
    row.className = 'audit-row';

    const time = document.createElement('span');
    time.className = 'audit-time';
    time.textContent = entry.time || '';
    row.appendChild(time);

    const userName = document.createElement('span');
    userName.className = 'audit-user';
    userName.textContent = entry.user_name || '';
    row.appendChild(userName);

    const user = document.createElement('span');
    user.className = 'audit-account';
    user.textContent = entry.user || '';
    row.appendChild(user);

    const evt = entry.event || '';
    const event = document.createElement('span');
    event.className = 'audit-event audit-event-'
        + (evt === '新增' ? 'create' : (evt === '删除' ? 'delete' : 'update'));
    event.textContent = evt;
    row.appendChild(event);

    const desc = document.createElement('span');
    desc.className = 'audit-desc';
    desc.textContent = entry.description || '';
    row.appendChild(desc);

    return row;
}

// ---------- 全局配置（懒加载缓存；详情页唤起按钮等处使用） ----------
let _globalConfigPromise = null;
function loadGlobalConfig() {
    if (!_globalConfigPromise) {
        _globalConfigPromise = api('/api/config').catch(() => ({}));
    }
    return _globalConfigPromise;
}
