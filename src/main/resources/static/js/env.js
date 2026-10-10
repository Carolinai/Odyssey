// 客户详情页：查看/编辑/新建三态复用；编辑态 URL 带 ?name=xxx
// 一个客户内含 测试/验证/生产 三个环境块，tab 切换编辑
// 表单控件统一携带 data-path（如 environments.test.vpn.entry），保存时按 path 写回对象

const params = new URLSearchParams(location.search);
const envName = params.get('name'); // 编辑态的客户名；null 表示新建
const isNew = !envName;

// 三个环境块：key 与 YAML 对应，label 用于展示
const ENV_KEYS = [
    { key: 'test', label: '测试环境' },
    { key: 'validation', label: '验证环境' },
    { key: 'prod', label: '生产环境' }
];

let state = {};          // 当前客户数据
let activeTab = 'test';  // 当前选中的环境 tab

// ---------- 嵌套对象读写 ----------

function getPath(obj, path) {
    return path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

function setPath(obj, path, value) {
    const keys = path.split('.');
    let cur = obj;
    for (let i = 0; i < keys.length - 1; i++) {
        if (cur[keys[i]] == null) {
            cur[keys[i]] = /^\d+$/.test(keys[i + 1]) ? [] : {};
        }
        cur = cur[keys[i]];
    }
    const last = keys[keys.length - 1];
    if (value === '' || value == null) {
        delete cur[last]; // 空值即省略该字段
    } else {
        cur[last] = value;
    }
}

// ---------- 表单控件构造 ----------

// 一行：label + input + 可选复制/reveal 按钮
function fieldRow(label, path, opts) {
    opts = opts || {};
    const row = document.createElement('div');
    row.className = 'form-row';

    const labelEl = document.createElement('label');
    labelEl.textContent = label;
    row.appendChild(labelEl);

    let input;
    if (opts.type === 'textarea') {
        input = document.createElement('textarea');
    } else if (opts.type === 'select') {
        input = document.createElement('select');
        (opts.options || []).forEach(opt => {
            const o = document.createElement('option');
            o.value = opt; o.textContent = opt || '（无）';
            input.appendChild(o);
        });
    } else if (opts.type === 'color') {
        input = document.createElement('input');
        input.type = 'color';
        input.style.flex = '0 0 60px';
        input.style.padding = '2px';
    } else {
        input = document.createElement('input');
        input.type = opts.type === 'password' ? 'password' : 'text';
    }
    input.dataset.path = path;
    const val = getPath(state, path);
    input.value = val == null ? (opts.type === 'color' ? '#2980b9' : '') : val;
    row.appendChild(input);

    const actions = document.createElement('div');
    actions.className = 'field-actions';

    if (opts.reveal) {
        const revealBtn = document.createElement('button');
        revealBtn.type = 'button';
        revealBtn.className = 'icon-btn';
        revealBtn.textContent = '👁';
        revealBtn.title = '显示/隐藏';
        revealBtn.addEventListener('click', () => {
            input.type = input.type === 'password' ? 'text' : 'password';
        });
        actions.appendChild(revealBtn);
    }
    if (opts.copy) {
        const copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'icon-btn';
        copyBtn.textContent = '复制';
        copyBtn.addEventListener('click', () => copyText(input.value));
        actions.appendChild(copyBtn);
    }
    if (actions.childNodes.length > 0) row.appendChild(actions);
    return row;
}

function cardContainer() {
    const card = document.createElement('div');
    card.className = 'sub-card';
    return card;
}

function subSection(title, nodes) {
    const box = document.createElement('div');
    if (title) {
        const h = document.createElement('h3');
        h.className = 'env-section-title';
        h.textContent = title;
        box.appendChild(h);
    }
    (nodes || []).forEach(n => n && box.appendChild(n));
    return box;
}

function addSectionButton(box, text, onClick) {
    const wrap = document.createElement('div');
    wrap.className = 'section-actions';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'icon-btn';
    btn.textContent = text;
    btn.addEventListener('click', onClick);
    wrap.appendChild(btn);
    box.appendChild(wrap);
}

// 把当前 DOM 上已填的值先收集回 state（切 tab/增删行重渲染前调用，防丢输入）
function collectAll() {
    document.querySelectorAll('input[data-path], textarea[data-path], select[data-path]').forEach(el => {
        setPath(state, el.dataset.path, el.value.trim());
    });
    // alias 特殊：按分隔符拆分
    const aliasInput = document.querySelector('input[data-path="alias"]');
    if (aliasInput) {
        const lines = aliasInput.value.split(/[、,，\n]/).map(s => s.trim()).filter(s => s);
        state.alias = lines.length ? lines : undefined;
    }
}

// ---------- 基本信息与标签 ----------

function renderBasic() {
    const box = document.getElementById('basic-section');
    box.innerHTML = '';
    const nameRow = fieldRow('客户名', 'name');
    if (!isNew) {
        nameRow.querySelector('input').readOnly = true; // name 是主键，编辑态不可改
        nameRow.querySelector('input').title = '客户名不可修改（需删除重建）';
    }
    box.appendChild(nameRow);

    const aliasRow = document.createElement('div');
    aliasRow.className = 'form-row';
    const aliasLabel = document.createElement('label');
    aliasLabel.textContent = '别名';
    const aliasInput = document.createElement('input');
    aliasInput.dataset.path = 'alias';
    aliasInput.placeholder = '多个别名用顿号或换行分隔，如：XX行、某行生产';
    aliasInput.value = (state.alias || []).join('、');
    aliasRow.appendChild(aliasLabel);
    aliasRow.appendChild(aliasInput);
    box.appendChild(aliasRow);
}

function renderTags() {
    const box = document.getElementById('tags-section');
    // 先清空本区域再收集：已删除行的残留输入框会把刚删掉的数据写回 state
    box.innerHTML = '';
    collectAll();
    const labelRow = document.createElement('div');
    labelRow.className = 'form-row';
    const labelEl = document.createElement('label');
    labelEl.textContent = '标签';
    labelRow.appendChild(labelEl);
    box.appendChild(labelRow);

    (state.tags || []).forEach((tag, i) => {
        const row = document.createElement('div');
        row.className = 'form-row tag-editor-row';
        // 缩进占位
        const placeholder = document.createElement('label');
        row.appendChild(placeholder);

        row.appendChild(fieldRow('颜色', 'tags.' + i + '.color', { type: 'color' }));
        const nameInput = document.createElement('input');
        nameInput.dataset.path = 'tags.' + i + '.name';
        nameInput.placeholder = '标签名';
        nameInput.value = tag.name || '';
        row.appendChild(nameInput);

        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'icon-btn';
        delBtn.textContent = '删除';
        delBtn.addEventListener('click', () => {
            if (!confirm('确认删除该标签？')) return;
            collectAll();
            state.tags.splice(i, 1);
            if (state.tags.length === 0) delete state.tags;
            renderTags();
        });
        row.appendChild(delBtn);
        box.appendChild(row);
    });
    addSectionButton(box, '+ 添加标签', () => {
        collectAll();
        if (!state.tags) state.tags = [];
        state.tags.push({ color: '#2980b9' });
        renderTags();
    });
}

// ---------- 附件（客户级）：VPN 软件、使用文档等 ----------

function formatSize(bytes) {
    if (bytes == null) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

// 静默保存当前表单（上传/删除附件前先落盘，避免 init() 刷新丢掉未保存的编辑）
async function saveQuiet() {
    collectAll();
    const payload = pruneEmpty(state) || {};
    if (!payload.name || !payload.name.trim()) return; // 新建未保存时不强推
    await api('/api/environments/' + encodeURIComponent(envName), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });
}

function renderFiles() {
    const box = document.getElementById('files-section');
    box.innerHTML = '';

    if (isNew) {
        const hint = document.createElement('div');
        hint.className = 'empty-hint';
        hint.style.padding = '8px 0';
        hint.textContent = '保存客户后才能上传文件';
        box.appendChild(hint);
        return;
    }

    // 上传行：选文件 + 用途备注 + 上传按钮
    const uploadRow = document.createElement('div');
    uploadRow.className = 'form-row';
    const uploadLabel = document.createElement('label');
    uploadLabel.textContent = '上传文件';
    uploadRow.appendChild(uploadLabel);

    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    uploadRow.appendChild(fileInput);

    const noteInput = document.createElement('input');
    noteInput.placeholder = '用途备注（可选），如：VPN 软件 / 使用文档';
    uploadRow.appendChild(noteInput);

    const uploadBtn = document.createElement('button');
    uploadBtn.type = 'button';
    uploadBtn.className = 'btn btn-primary';
    uploadBtn.textContent = '上传';
    uploadBtn.addEventListener('click', async () => {
        if (!fileInput.files || fileInput.files.length === 0) {
            alert('请先选择文件');
            return;
        }
        uploadBtn.disabled = true;
        try {
            await saveQuiet(); // 先保存表单编辑，再传附件
            const fd = new FormData();
            fd.append('file', fileInput.files[0]);
            if (noteInput.value.trim()) fd.append('note', noteInput.value.trim());
            await api('/api/environments/' + encodeURIComponent(envName) + '/files', {
                method: 'POST',
                body: fd // 不设 Content-Type，让浏览器带 multipart boundary
            });
            toast('已上传');
            init(); // 重新拉取状态（含新附件）
        } catch (e) {
            alert('上传失败：' + e.message);
            uploadBtn.disabled = false;
        }
    });
    uploadRow.appendChild(uploadBtn);
    box.appendChild(uploadRow);

    // 附件列表：文件名（点击下载）+ 大小/时间 + 备注（可编辑，随保存提交）+ 删除
    (state.files || []).forEach((f, i) => {
        const row = document.createElement('div');
        row.className = 'form-row file-row';

        const dl = document.createElement('a');
        dl.className = 'file-name';
        dl.href = '/api/environments/' + encodeURIComponent(envName) + '/files/' + encodeURIComponent(f.stored_name);
        dl.title = '点击下载';
        dl.textContent = f.name || f.stored_name;
        row.appendChild(dl);

        const meta = document.createElement('span');
        meta.className = 'file-meta';
        meta.textContent = formatSize(f.size) + (f.uploaded_at ? ' · ' + f.uploaded_at : '');
        row.appendChild(meta);

        const noteEdit = document.createElement('input');
        noteEdit.dataset.path = 'files.' + i + '.note';
        noteEdit.placeholder = '用途备注';
        noteEdit.value = f.note || '';
        row.appendChild(noteEdit);

        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'icon-btn';
        delBtn.textContent = '删除';
        delBtn.addEventListener('click', async () => {
            if (!confirm('确认删除文件「' + (f.name || '') + '」？')) return;
            try {
                await api('/api/environments/' + encodeURIComponent(envName)
                        + '/files/' + encodeURIComponent(f.stored_name), { method: 'DELETE' });
                toast('已删除');
                init();
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        });
        row.appendChild(delBtn);
        box.appendChild(row);
    });
}

// ---------- 环境块（tab）渲染 ----------

function renderTabs() {
    const box = document.getElementById('env-tabs');
    box.innerHTML = '';
    ENV_KEYS.forEach(({ key, label }) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'tab-btn' + (key === activeTab ? ' active' : '');
        btn.textContent = label;
        btn.addEventListener('click', () => {
            if (key === activeTab) return;
            collectAll();          // 切 tab 前先收集当前输入
            activeTab = key;
            renderTabs();
            renderEnvContent();
        });
        box.appendChild(btn);
    });
}

// 复制配置工具条：把另一个环境块已填好的配置整体复制到当前环境（坑除外）
function renderCopyToolbar(box) {
    const row = document.createElement('div');
    row.className = 'form-row copy-toolbar';

    const label = document.createElement('label');
    label.textContent = '复制配置';
    row.appendChild(label);

    const select = document.createElement('select');
    const currentLabel = ENV_KEYS.find(k => k.key === activeTab).label;
    ENV_KEYS.filter(k => k.key !== activeTab).forEach(({ key, label: l }) => {
        const o = document.createElement('option');
        o.value = key;
        o.textContent = '从' + l + '复制';
        select.appendChild(o);
    });
    row.appendChild(select);

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'icon-btn';
    btn.textContent = '复制过来';
    btn.addEventListener('click', () => {
        collectAll();
        const srcKey = select.value;
        const srcLabel = ENV_KEYS.find(k => k.key === srcKey).label;
        const src = getPath(state, 'environments.' + srcKey);
        const filled = src && Object.keys(src).some(k => k !== 'quirks');
        if (!filled) {
            alert('「' + srcLabel + '」还没有可复制的内容');
            return;
        }
        if (!confirm('将用「' + srcLabel + '」的全部配置覆盖当前「' + currentLabel + '」的内容（“坑”除外），确认？')) return;
        // 深拷贝整个环境块；“坑”是该环境特有的，不跟着复制
        const copy = JSON.parse(JSON.stringify(src));
        delete copy.quirks;
        if (!state.environments) state.environments = {};
        state.environments[activeTab] = copy;
        // 关键：先清空旧输入框。renderEnvContent 开头的 collectAll 会把 DOM 里的旧值写回 state，
        // 若不清空，刚复制进来的 VPN/向日葵等字段会被旧值覆盖
        document.getElementById('env-content').innerHTML = '';
        renderEnvContent();
        toast('已从' + srcLabel + '复制，请继续修改');
    });
    row.appendChild(btn);
    box.appendChild(row);
}

function renderEnvContent() {
    const box = document.getElementById('env-content');
    collectAll();
    box.innerHTML = '';
    const p = 'environments.' + activeTab; // 当前环境块的路径前缀

    // 复制配置工具条
    renderCopyToolbar(box);

    // 向日葵
    const sl = cardContainer();
    sl.appendChild(subSection('向日葵', [
        fieldRow('连接码', p + '.sunlogin.id', { copy: true }),
        fieldRow('密码', p + '.sunlogin.password', { type: 'password', copy: true, reveal: true }),
        fieldRow('备注', p + '.sunlogin.notes', { type: 'textarea' })
    ]));
    box.appendChild(sl);

    // VPN
    const vpn = cardContainer();
    vpn.appendChild(subSection('VPN', [
        fieldRow('客户端类型', p + '.vpn.how'),
        fieldRow('接入地址', p + '.vpn.entry', { copy: true }),
        fieldRow('用户名', p + '.vpn.account.user'),
        fieldRow('密码', p + '.vpn.account.password', { type: 'password', copy: true, reveal: true }),
        fieldRow('动态码说明', p + '.vpn.account.otp'),
        fieldRow('备注', p + '.vpn.notes', { type: 'textarea' })
    ]));
    box.appendChild(vpn);

    // 堡垒机
    const bastion = cardContainer();
    bastion.appendChild(subSection('堡垒机', [
        fieldRow('类型', p + '.bastion.type'),
        fieldRow('入口地址', p + '.bastion.entry', { copy: true }),
        fieldRow('用户名', p + '.bastion.account.user'),
        fieldRow('密码', p + '.bastion.account.password', { type: 'password', copy: true, reveal: true })
    ]));
    box.appendChild(bastion);

    // 客户端清单（多条）：app / db / 文档服务等
    const clientsBox = document.createElement('div');
    renderClientsInto(clientsBox, p);
    box.appendChild(clientsBox);

    // 到达路径（多条）
    const accessBox = document.createElement('div');
    renderAccessInto(accessBox, p);
    box.appendChild(accessBox);

    // 管理员账号（含找回流程）
    const adminBox = document.createElement('div');
    renderAdminInto(adminBox, p);
    box.appendChild(adminBox);

    // 代码分支（多条）
    const codeBox = document.createElement('div');
    renderCodeInto(codeBox, p);
    box.appendChild(codeBox);

    // 坑
    box.appendChild(subSection('坑（该环境特有的注意事项）', [
        fieldRow('', p + '.quirks', { type: 'textarea' })
    ]));
}

function renderClientsInto(box, p) {
    // 先清空本区域再收集，避免已删除行的残留输入框把数据写回 state
    box.innerHTML = '';
    collectAll();
    const basePath = p + '.clients';

    // 名称候选：常见客户端角色，也允许自定义输入
    let datalist = document.getElementById('client-name-options');
    if (!datalist) {
        datalist = document.createElement('datalist');
        datalist.id = 'client-name-options';
        ['app', 'db', '文档服务'].forEach(v => {
            const o = document.createElement('option');
            o.value = v;
            datalist.appendChild(o);
        });
        document.body.appendChild(datalist);
    }

    (getPath(state, basePath) || []).forEach((entry, i) => {
        const card = cardContainer();

        const headRow = document.createElement('div');
        headRow.className = 'form-row';
        const headLabel = document.createElement('label');
        headLabel.textContent = '客户端 ' + (i + 1);
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'icon-btn';
        delBtn.textContent = '删除此客户端';
        delBtn.addEventListener('click', () => {
            if (!confirm('确认删除这个客户端？')) return;
            collectAll();
            getPath(state, basePath).splice(i, 1);
            if (getPath(state, basePath).length === 0) setPath(state, p + '.clients', null);
            renderClientsInto(box, p);
        });
        headRow.appendChild(headLabel);
        headRow.appendChild(delBtn);
        card.appendChild(headRow);

        const nameRow = fieldRow('名称', basePath + '.' + i + '.name', { copy: true });
        const nameInput = nameRow.querySelector('input');
        nameInput.setAttribute('list', 'client-name-options');
        nameInput.placeholder = '如：app、db、文档服务';
        card.appendChild(nameRow);
        card.appendChild(fieldRow('IP', basePath + '.' + i + '.ip', { copy: true }));
        card.appendChild(fieldRow('用户名', basePath + '.' + i + '.account.user', { copy: true }));
        card.appendChild(fieldRow('密码', basePath + '.' + i + '.account.password', { type: 'password', copy: true, reveal: true }));
        box.appendChild(card);
    });
    addSectionButton(box, '+ 添加客户端', () => {
        collectAll();
        if (!getPath(state, basePath)) setPath(state, basePath, []);
        getPath(state, basePath).push({});
        renderClientsInto(box, p);
    });
}

function renderAccessInto(box, p) {
    // 先清空本区域再收集，避免已删除行的残留输入框把数据写回 state
    box.innerHTML = '';
    collectAll();
    const basePath = p + '.access';
    (getPath(state, basePath) || []).forEach((entry, i) => {
        const card = cardContainer();

        const headRow = document.createElement('div');
        headRow.className = 'form-row';
        const headLabel = document.createElement('label');
        headLabel.textContent = '到达路径 ' + (i + 1);
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'icon-btn';
        delBtn.textContent = '删除此路径';
        delBtn.addEventListener('click', () => {
            if (!confirm('确认删除这条到达路径？')) return;
            collectAll();
            getPath(state, basePath).splice(i, 1);
            if (getPath(state, basePath).length === 0) setPath(state, p + '.access', null);
            renderAccessInto(box, p);
        });
        headRow.appendChild(headLabel);
        headRow.appendChild(delBtn);
        card.appendChild(headRow);

        card.appendChild(fieldRow('方式', basePath + '.' + i + '.method', {
            type: 'select', options: ['ssh', 'rdp', 'sunlogin', 'web', 'ssh-hop']
        }));
        card.appendChild(fieldRow('目标', basePath + '.' + i + '.target', { copy: true }));
        card.appendChild(fieldRow('跳板说明', basePath + '.' + i + '.via'));
        card.appendChild(fieldRow('ID', basePath + '.' + i + '.id', { copy: true }));
        card.appendChild(fieldRow('独立密码', basePath + '.' + i + '.password', { type: 'password', copy: true, reveal: true }));
        card.appendChild(fieldRow('用户名', basePath + '.' + i + '.account.user', { copy: true }));
        card.appendChild(fieldRow('密码', basePath + '.' + i + '.account.password', { type: 'password', copy: true, reveal: true }));
        card.appendChild(fieldRow('SSH 私钥', basePath + '.' + i + '.account.key'));
        box.appendChild(card);
    });
    addSectionButton(box, '+ 添加到达路径', () => {
        collectAll();
        if (!getPath(state, basePath)) setPath(state, basePath, []);
        getPath(state, basePath).push({ method: 'ssh' });
        renderAccessInto(box, p);
    });
}

function renderAdminInto(box, p) {
    // 先清空本区域再收集，避免残留输入框把数据写回 state
    box.innerHTML = '';
    collectAll();
    const up = p + '.admin_account';

    const rows = [
        fieldRow('用户名', up + '.user', { copy: true }),
        fieldRow('密码', up + '.password', { type: 'password', copy: true, reveal: true })
    ];
    box.appendChild(subSection('管理员账号', rows));

    // 找回流程段（可整体启用/停用）
    const hasRecovery = !!getPath(state, up + '.recovery');
    const recCard = cardContainer();
    const recHead = document.createElement('div');
    recHead.className = 'form-row';
    const recLabel = document.createElement('label');
    recLabel.textContent = '密码找回流程';
    const recToggle = document.createElement('button');
    recToggle.type = 'button';
    recToggle.className = 'icon-btn';
    recToggle.textContent = hasRecovery ? '停用此段' : '启用此段';
    recToggle.addEventListener('click', () => {
        collectAll();
        if (getPath(state, up + '.recovery')) {
            setPath(state, up + '.recovery', null);
        } else {
            setPath(state, up + '.recovery', {});
        }
        renderAdminInto(box, p);
    });
    recHead.appendChild(recLabel);
    recHead.appendChild(recToggle);
    recCard.appendChild(recHead);

    if (hasRecovery) {
        recCard.appendChild(fieldRow('在哪执行 SQL', up + '.recovery.lookup'));
        recCard.appendChild(fieldRow('SQL', up + '.recovery.sql', { type: 'textarea', copy: true }));
        recCard.appendChild(fieldRow('RSA 私钥文件', up + '.recovery.key_file', { copy: true }));
    }
    box.appendChild(recCard);
}

function renderCodeInto(box, p) {
    // 先清空本区域再收集，避免已删除行的残留输入框把数据写回 state
    box.innerHTML = '';
    collectAll();
    const basePath = p + '.code';

    (getPath(state, basePath) || []).forEach((entry, i) => {
        const row = document.createElement('div');
        row.className = 'form-row';
        const label = document.createElement('label');
        label.textContent = '项目 ' + (i + 1);
        row.appendChild(label);
        const projInput = document.createElement('input');
        projInput.dataset.path = basePath + '.' + i + '.project';
        projInput.placeholder = '项目名';
        projInput.value = entry.project || '';
        row.appendChild(projInput);
        const brInput = document.createElement('input');
        brInput.dataset.path = basePath + '.' + i + '.branch';
        brInput.placeholder = '分支';
        brInput.value = entry.branch || '';
        row.appendChild(brInput);

        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'icon-btn';
        delBtn.textContent = '删除';
        delBtn.addEventListener('click', () => {
            if (!confirm('确认删除这条代码分支？')) return;
            collectAll();
            getPath(state, basePath).splice(i, 1);
            if (getPath(state, basePath).length === 0) setPath(state, p + '.code', null);
            renderCodeInto(box, p);
        });
        row.appendChild(delBtn);
        box.appendChild(row);
    });
    addSectionButton(box, '+ 添加代码分支', () => {
        collectAll();
        if (!getPath(state, basePath)) setPath(state, basePath, []);
        getPath(state, basePath).push({});
        renderCodeInto(box, p);
    });
}

// ---------- 保存前深度清理：空对象/空数组/空字符串不落盘 ----------

function pruneEmpty(value) {
    if (Array.isArray(value)) {
        const arr = value.map(pruneEmpty).filter(v => v !== undefined);
        return arr.length ? arr : undefined;
    }
    if (value && typeof value === 'object') {
        const out = {};
        Object.keys(value).forEach(k => {
            const v = pruneEmpty(value[k]);
            if (v !== undefined) out[k] = v;
        });
        return Object.keys(out).length ? out : undefined;
    }
    return (value === '' || value == null) ? undefined : value;
}

// ---------- 初始化与保存 ----------

async function init() {
    if (isNew) {
        document.getElementById('page-title').textContent = '新建客户';
        state = {};
    } else {
        document.getElementById('page-title').textContent = '客户：' + envName;
        state = await api('/api/environments/' + encodeURIComponent(envName));
    }

    renderBasic();
    renderTags();
    renderFiles();
    renderTabs();
    renderEnvContent();

    document.getElementById('delete-btn').classList.toggle('hidden', isNew);
}

document.getElementById('save-btn').addEventListener('click', async () => {
    collectAll();
    state = pruneEmpty(state) || {};

    if (!state.name || !state.name.trim()) {
        alert('客户名（name）不能为空');
        return;
    }

    try {
        if (isNew) {
            await api('/api/environments', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(state)
            });
            toast('已创建，正在跳转…');
            location.href = '/env.html?name=' + encodeURIComponent(state.name);
        } else {
            await api('/api/environments/' + encodeURIComponent(envName), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(state)
            });
            toast('已保存');
            window.scrollTo(0, 0); // 回到详情页顶部
            init(); // 重新渲染
        }
    } catch (e) {
        alert('保存失败：' + e.message);
    }
});

document.getElementById('delete-btn').addEventListener('click', async () => {
    if (!confirm('确认删除客户「' + envName + '」？此操作不可恢复。')) return;
    try {
        await api('/api/environments/' + encodeURIComponent(envName), { method: 'DELETE' });
        toast('已删除，正在返回列表…');
        location.href = '/';
    } catch (e) {
        alert('删除失败：' + e.message);
    }
});

init();
