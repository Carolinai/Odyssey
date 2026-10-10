// 主页：页签结构——客户页签（关键卡片列表）+ Jenkins 视图页签（过滤展示）
// Jenkins 视图在首次切到该页签时才加载

// ---------- 页签切换 ----------

const tabButtons = document.querySelectorAll('.page-tabs .tab-btn');
const tabCustomers = document.getElementById('tab-customers');
const tabJenkins = document.getElementById('tab-jenkins');

let jenkinsLoaded = false;

function switchPage(page) {
    tabButtons.forEach(b => b.classList.toggle('active', b.dataset.page === page));
    const isCustomers = page === 'customers';
    tabCustomers.classList.toggle('hidden', !isCustomers);
    tabJenkins.classList.toggle('hidden', isCustomers);
    try {
        localStorage.setItem('odyssey.home.tab', page);
    } catch (e) { /* 忽略存储失败 */ }
    // Jenkins 页签懒加载：首次切过去才拉数据
    if (!isCustomers && !jenkinsLoaded) {
        jenkinsLoaded = true;
        loadJenkinsViews();
    }
}

tabButtons.forEach(btn => {
    btn.addEventListener('click', () => switchPage(btn.dataset.page));
});

// 恢复上次停留的页签
try {
    const last = localStorage.getItem('odyssey.home.tab');
    if (last === 'jenkins') switchPage('jenkins');
} catch (e) { /* 忽略 */ }

// ---------- 客户页签：关键卡片列表 ----------

const searchInput = document.getElementById('search');
const envList = document.getElementById('env-list');
const emptyHint = document.getElementById('empty-hint');

let debounceTimer = null;

searchInput.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(loadList, 250);
});

async function loadList() {
    const kw = searchInput.value.trim();
    const items = await api('/api/environments?kw=' + encodeURIComponent(kw) + '&detail=true');

    envList.innerHTML = '';
    emptyHint.classList.toggle('hidden', items.length > 0);
    items.forEach(renderEnvCard);
}

// 一条速览信息：label + 只读值（保留备用）


// 卡片分支展示用的环境块顺序与中文标签
const ENV_KEYS_ORDER = [
    { key: 'prod', label: '生产' },
    { key: 'validation', label: '验证' },
    { key: 'test', label: '测试' }
];

function renderEnvCard(env) {
    const card = document.createElement('div');
    card.className = 'form-section env-card compact';
    card.title = '点击进入详情';

    // 头部：客户名 + 别名
    const head = document.createElement('div');
    head.className = 'env-head';

    const nameEl = document.createElement('span');
    nameEl.className = 'env-name';
    nameEl.textContent = env.name;
    head.appendChild(nameEl);

    (env.alias || []).forEach(alias => {
        const tag = document.createElement('span');
        tag.className = 'alias-tag';
        tag.textContent = alias;
        head.appendChild(tag);
    });
    card.appendChild(head);

    // 标签（颜色可自选）
    if (env.tags && env.tags.length > 0) {
        const tagRow = document.createElement('div');
        tagRow.className = 'card-tag-row';
        env.tags.forEach(tag => {
            if (!tag.name) return;
            const chip = document.createElement('span');
            chip.className = 'tag-chip';
            chip.textContent = tag.name;
            chip.style.backgroundColor = tag.color || '#95a5a6';
            tagRow.appendChild(chip);
        });
        card.appendChild(tagRow);
    }

    // 代码分支：各环境块的项目/分支一览
    const branchRows = [];
    ENV_KEYS_ORDER.forEach(({ key, label }) => {
        const code = env.environments && env.environments[key] && env.environments[key].code;
        if (!code || code.length === 0) return;
        code.forEach(entry => {
            if (!entry || (!entry.project && !entry.branch)) return;
            const row = document.createElement('div');
            row.className = 'branch-row';

            const envLabel = document.createElement('span');
            envLabel.className = 'branch-env branch-env-' + key;
            envLabel.textContent = label;
            row.appendChild(envLabel);

            const text = document.createElement('span');
            text.className = 'branch-text';
            text.textContent = entry.project ? entry.project + ' @ ' + entry.branch : entry.branch;
            row.appendChild(text);

            const copyBtn = document.createElement('button');
            copyBtn.type = 'button';
            copyBtn.className = 'icon-btn';
            copyBtn.textContent = '复制';
            copyBtn.addEventListener('click', e => {
                e.stopPropagation(); // 不触发卡片跳转
                copyText(text.textContent);
            });
            row.appendChild(copyBtn);

            branchRows.push(row);
        });
    });
    if (branchRows.length > 0) {
        const box = document.createElement('div');
        box.className = 'card-branch-box';
        branchRows.forEach(r => box.appendChild(r));
        card.appendChild(box);
    }

    // 整卡点击进详情
    card.addEventListener('click', () => {
        location.href = '/env.html?name=' + encodeURIComponent(env.name);
    });
    envList.appendChild(card);
}

// ---------- Jenkins 视图页签 ----------

const jenkinsList = document.getElementById('jenkins-list');
const jkCount = document.getElementById('jk-count');
const jkFilter = document.getElementById('jk-filter');
const jkDescOnly = document.getElementById('jk-desc-only');

let jkViews = []; // 缓存已加载的视图数据，过滤时不再请求
let jkBaseUrl = ''; // Jenkins 基础地址（后端下发），拼视图详情链接用

async function loadJenkinsViews() {
    jenkinsList.textContent = '加载中…';
    try {
        const result = await api('/api/jenkins/views');
        jkViews = result.views || [];
        jkBaseUrl = (result.base_url || '').replace(/\/+$/, '');
        renderJenkinsViews();
    } catch (e) {
        jkViews = [];
        jkBaseUrl = '';
        jenkinsList.textContent = '加载失败：' + e.message;
    }
}

function renderJenkinsViews() {
    const kw = jkFilter.value.trim().toLowerCase();
    const descOnly = jkDescOnly.checked;

    const shown = jkViews.filter(v => {
        if (descOnly && !v.desc) return false;
        if (!kw) return true;
        return (v.name || '').toLowerCase().includes(kw)
            || (v.desc || '').toLowerCase().includes(kw);
    });

    jkCount.textContent = '共 ' + shown.length + ' / ' + jkViews.length + ' 个视图';
    jenkinsList.innerHTML = '';

    if (shown.length === 0) {
        const empty = document.createElement('span');
        empty.className = 'jenkins-empty';
        empty.textContent = '（无匹配视图）';
        jenkinsList.appendChild(empty);
        return;
    }

    shown.forEach(view => {
        const row = document.createElement('div');
        row.className = 'jenkins-row';

        const name = document.createElement('a');
        name.className = 'jenkins-view-name';
        if (jkBaseUrl) {
            // 新开页跳转 Jenkins 视图详情
            name.href = jkBaseUrl + '/view/' + encodeURIComponent(view.name) + '/';
            name.target = '_blank';
            name.rel = 'noopener';
        }
        name.textContent = view.name;

        const desc = document.createElement('span');
        desc.className = 'jenkins-view-desc' + (view.desc ? '' : ' no-desc');
        desc.textContent = view.desc ? '→ ' + view.desc : '';

        row.appendChild(name);
        row.appendChild(desc);
        jenkinsList.appendChild(row);
    });
}

jkFilter.addEventListener('input', renderJenkinsViews);
jkDescOnly.addEventListener('change', renderJenkinsViews);
document.getElementById('jenkins-refresh').addEventListener('click', loadJenkinsViews);

loadList();
