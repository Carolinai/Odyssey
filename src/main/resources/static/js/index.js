// 客户列表页：搜索过滤 + 客户关键卡片（点击进详情）

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

loadList();
