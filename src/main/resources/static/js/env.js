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
let activeModule = 'all'; // 环境块内当前选中的模块（'all' = 全部）

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
    if (opts.list) input.setAttribute('list', opts.list); // datalist：下拉候选 + 可自行输入
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
    if (actions.childNodes.length > 0 || opts.actions) row.appendChild(actions);
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

// 取一个全局共享的 datalist（input 的 list 属性指向其 id），首次调用时创建。
// input 既能从候选下拉选择，也能自行输入候选之外的值
function sharedDatalist(id, options) {
    let datalist = document.getElementById(id);
    if (!datalist) {
        datalist = document.createElement('datalist');
        datalist.id = id;
        options.forEach(v => {
            const o = document.createElement('option');
            o.value = v;
            datalist.appendChild(o);
        });
        document.body.appendChild(datalist);
    }
    return datalist;
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

        // 颜色选择块（collectAll 按 data-path 通用收集，无需包一层 fieldRow）
        const colorInput = document.createElement('input');
        colorInput.type = 'color';
        colorInput.dataset.path = 'tags.' + i + '.color';
        colorInput.value = tag.color || '#2980b9';
        row.appendChild(colorInput);

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
            rerenderWithStableScroll(() => {
                renderTabs();
                renderModuleSide(); // 模块导航的“有数据”标识需随环境切换刷新
                renderEnvContent();
            });
        });
        box.appendChild(btn);
    });
}

// 复制配置工具条：按左侧选中的模块，把另一个环境块的配置复制到当前环境；
// 选「全部」时整体复制（其他信息或备注除外），选单模块时只覆盖该模块
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
        const isAll = activeModule === 'all';
        const moduleLabel = isAll ? '' : MODULES.find(m => m.key === activeModule).label;
        // 按左侧选中的模块做针对性检查：全部 = 除备注外任一模块有内容即可；单模块 = 该模块有内容
        const filled = isAll
            ? src && Object.keys(src).some(k => k !== 'quirks')
            : src && pruneEmpty(src[activeModule]) !== undefined;
        if (!filled) {
            alert(isAll
                ? '「' + srcLabel + '」还没有可复制的内容'
                : '「' + srcLabel + '」还没有可复制的「' + moduleLabel + '」内容');
            return;
        }
        if (!confirm('将用「' + srcLabel + '」'
                + (isAll ? '的全部配置' : '的「' + moduleLabel + '」配置')
                + '覆盖当前「' + currentLabel + '」的内容'
                + (isAll ? '（“其他信息或备注”除外）' : '')
                + '，确认？')) return;
        if (!state.environments) state.environments = {};
        if (!state.environments[activeTab]) state.environments[activeTab] = {};
        if (isAll) {
            // 深拷贝整个环境块；“其他信息或备注”是该环境特有的，不跟着复制
            const copy = JSON.parse(JSON.stringify(src));
            delete copy.quirks;
            state.environments[activeTab] = copy;
        } else {
            // 只覆盖左侧当前选中模块的数据，其他模块保持不变
            state.environments[activeTab][activeModule] = JSON.parse(JSON.stringify(src[activeModule]));
        }
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

    // 按左侧选中的模块渲染（全部 = 依序渲染所有模块）
    MODULES.filter(m => activeModule === 'all' || m.key === activeModule).forEach(m => {
        const wrap = document.createElement('div');
        m.render(wrap, p);
        box.appendChild(wrap);
    });
}

// 重渲染环境内容时保持页面滚动位置不变
function rerenderWithStableScroll(render) {
    const content = document.getElementById('env-content');
    // 记住当前内容高度：切到内容更少的模块时用它撑住页面，
    // 否则文档骤然变短，浏览器会把超出部分的滚动条强制上收（表现为跳顶）
    const reserve = content.offsetHeight;
    // 焦点元素马上会被 innerHTML 重建移除，先移走焦点避免浏览器异常滚动
    if (document.activeElement && document.activeElement.blur) {
        document.activeElement.blur();
    }
    const anchor = document.getElementById('env-tabs');
    const topBefore = anchor.getBoundingClientRect().top;
    render();
    // 用上一次的内容高度撑住文档，滚动条位置就不会被浏览器改变
    content.style.minHeight = reserve + 'px';
    // 新内容本身更高时去掉预留，让页面正常伸缩
    if (content.offsetHeight > reserve) content.style.minHeight = '';
    // 微调：保证环境区块标题在视口中的位置稳定
    window.scrollBy(0, anchor.getBoundingClientRect().top - topBefore);
}

// 环境块内的模块清单：key 同时是数据字段名，render 负责把该模块渲染进容器
const MODULES = [
    { key: 'sunlogin', label: '远程方式', render: renderSunloginInto },
    { key: 'vpn', label: 'VPN', render: renderVpnInto },
    { key: 'bastion', label: '堡垒机', render: renderBastionInto },
    { key: 'clients', label: '服务器信息', render: renderClientsInto },
    { key: 'access', label: '到达路径', render: renderAccessInto },
    { key: 'admin', label: '管理员账号', render: renderAdminInto },
    { key: 'code', label: '代码分支', render: renderCodeInto },
    { key: 'quirks', label: '其他信息或备注', render: renderQuirksInto }
];

// 左侧模块导航：全部 + 各模块；切换时只重渲染内容区，不改动其他模块的数据
function renderModuleSide() {
    const box = document.getElementById('env-side');
    box.innerHTML = '';
    [{ key: 'all', label: '全部' }].concat(MODULES).forEach(({ key, label }) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'side-btn' + (key === activeModule ? ' active' : '');
        // 当前环境下已填数据的模块在按钮上显示小圆点（“全部”不参与）；
        // 复用 pruneEmpty：空对象/空数组/空字符串都视为无数据
        const moduleData = ((state.environments || {})[activeTab] || {})[key];
        // 远程方式：仅选择了方式（type）不算有数据，需连接码/密码/备注等实际内容
        let dataForCheck = moduleData;
        if (key === 'sunlogin' && moduleData) {
            dataForCheck = Object.assign({}, moduleData);
            delete dataForCheck.type;
        }
        if (key !== 'all' && pruneEmpty(dataForCheck) !== undefined) {
            btn.classList.add('has-data');
        }
        btn.textContent = label;
        btn.addEventListener('click', () => {
            if (key === activeModule) return;
            collectAll(); // 切换前先收集当前输入
            activeModule = key;
            rerenderWithStableScroll(() => {
                renderModuleSide();
                renderEnvContent();
            });
        });
        box.appendChild(btn);
    });
}

// ---------- 模块图片：远程方式/VPN/堡垒机/服务器信息的截图上传与展示 ----------
// 文件经 /api/images 上传落盘（UUID 文件名，≤10MB，jpg/png/webp/gif），
// 环境数据里只保存文件名列表；从其他环境复制配置时引用随数据深拷贝，多环境共享同一图片文件

// 一行图片区：缩略图（点击放大、可移除）+ 上传按钮；path 指向 images 文件名数组
function imagesRow(path) {
    const row = document.createElement('div');
    row.className = 'form-row image-row';

    const label = document.createElement('label');
    label.textContent = '图片';
    row.appendChild(label);

    const thumbs = document.createElement('div');
    thumbs.className = 'image-thumbs';
    row.appendChild(thumbs);

    // 刷新缩略图区（上传/移除后只重画这里，不影响同卡片其他输入框）
    const refresh = () => {
        thumbs.innerHTML = '';
        (getPath(state, path) || []).forEach((name, i) => {
            thumbs.appendChild(imageThumb(name, path, i, refresh));
        });
    };
    refresh();

    // 隐藏的文件选择框，支持一次选多张；选中即上传，文件名追加进 state，随「保存客户」一起提交
    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    fileInput.accept = 'image/jpeg,image/png,image/webp,image/gif';
    fileInput.multiple = true;
    fileInput.classList.add('hidden');
    fileInput.addEventListener('change', async () => {
        const files = Array.from(fileInput.files || []);
        fileInput.value = ''; // 先复位，允许下次重复选择同一文件
        if (files.length === 0) return;
        addBtn.disabled = true;
        try {
            const list = getPath(state, path) || [];
            for (const f of files) {
                if (f.size > 10 * 1024 * 1024) {
                    alert('「' + f.name + '」超过 10MB，已跳过');
                    continue;
                }
                const fd = new FormData();
                fd.append('file', f);
                const resp = await api('/api/images', { method: 'POST', body: fd });
                list.push(resp.name);
            }
            if (list.length) setPath(state, path, list);
            refresh();
            toast('图片已上传，保存客户后生效');
        } catch (e) {
            alert('上传失败：' + e.message);
        } finally {
            addBtn.disabled = false;
        }
    });
    row.appendChild(fileInput);

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'icon-btn';
    addBtn.textContent = '+ 上传图片';
    addBtn.addEventListener('click', () => fileInput.click());
    row.appendChild(addBtn);
    return row;
}

// 单张缩略图：点击放大查看，右上角 × 移除引用（保存后孤儿清理才真正删文件）
function imageThumb(name, path, index, refresh) {
    const wrap = document.createElement('div');
    wrap.className = 'image-thumb';

    const img = document.createElement('img');
    img.src = '/api/images/' + encodeURIComponent(name);
    img.alt = '模块截图';
    img.title = '点击放大';
    img.addEventListener('click', () => openLightbox(img.src));
    wrap.appendChild(img);

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'image-del';
    delBtn.textContent = '×';
    delBtn.title = '移除这张图片';
    delBtn.addEventListener('click', () => {
        if (!confirm('确认移除这张图片？（保存客户后生效）')) return;
        const list = (getPath(state, path) || []).slice();
        list.splice(index, 1);
        setPath(state, path, list.length ? list : null);
        refresh();
    });
    wrap.appendChild(delBtn);
    return wrap;
}

// 点击缩略图放大：全屏半透明遮罩 + 居中大图，点击任意处关闭
function openLightbox(src) {
    let box = document.getElementById('image-lightbox');
    if (!box) {
        box = document.createElement('div');
        box.id = 'image-lightbox';
        box.className = 'image-lightbox';
        box.addEventListener('click', () => { box.style.display = 'none'; });
        document.body.appendChild(box);
    }
    box.innerHTML = '';
    const img = document.createElement('img');
    img.src = src;
    box.appendChild(img);
    box.style.display = 'flex';
}

// 远程方式的「唤起」按钮：填入的类型与全局配置的唤起链接匹配时显示，
// 点击后浏览器通过自定义协议（如 sunlogin://）唤起访问者本机对应应用
function addLaunchButton(typeRow) {
    const actions = typeRow.querySelector('.field-actions');
    const input = typeRow.querySelector('input');
    if (!actions || !input) return;
    loadGlobalConfig().then(config => {
        const launchers = (config && config.launchers) || {};
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'icon-btn launch-btn'; // 醒目配色，区别于普通操作按钮
        btn.textContent = '唤起';
        btn.title = '唤起本机对应应用（映射在「全局配置」中维护）';
        btn.style.display = 'none';
        btn.addEventListener('click', () => {
            const url = launchers[input.value.trim()];
            if (url) location.href = url;
        });
        const sync = () => {
            const matched = launchers[input.value.trim()];
            btn.style.display = matched ? '' : 'none';
            // 按钮文案带上远程方式名，如「唤起向日葵」
            if (matched) btn.textContent = '唤起' + input.value.trim();
        };
        input.addEventListener('input', sync);
        sync();
        actions.appendChild(btn);
    });
}

function renderSunloginInto(box, p) {
    const sl = cardContainer();
    sharedDatalist('remote-type-options', ['向日葵', 'Todesk']);
    const typeRow = fieldRow('远程方式', p + '.sunlogin.type', {
        list: 'remote-type-options',
        actions: true // 预留操作区，供「唤起」按钮按类型显隐
    });
    addLaunchButton(typeRow);
    sl.appendChild(subSection('远程方式', [
        // 输入框 + 候选下拉：常见方式可直接选，也允许自行填写
        typeRow,
        fieldRow('连接码', p + '.sunlogin.id', { copy: true }),
        fieldRow('密码', p + '.sunlogin.password', { type: 'password', copy: true, reveal: true }),
        fieldRow('备注', p + '.sunlogin.notes', { type: 'textarea' }),
        imagesRow(p + '.sunlogin.images')
    ]));
    box.appendChild(sl);
}

function renderVpnInto(box, p) {
    const vpn = cardContainer();
    vpn.appendChild(subSection('VPN', [
        fieldRow('客户端类型', p + '.vpn.how'),
        fieldRow('接入地址', p + '.vpn.entry', { copy: true }),
        fieldRow('用户名', p + '.vpn.account.user'),
        fieldRow('密码', p + '.vpn.account.password', { type: 'password', copy: true, reveal: true }),
        fieldRow('动态码说明', p + '.vpn.account.otp'),
        fieldRow('备注', p + '.vpn.notes', { type: 'textarea' }),
        imagesRow(p + '.vpn.images')
    ]));
    box.appendChild(vpn);
}

function renderBastionInto(box, p) {
    const bastion = cardContainer();
    bastion.appendChild(subSection('堡垒机', [
        fieldRow('类型', p + '.bastion.type'),
        fieldRow('入口地址', p + '.bastion.entry', { copy: true }),
        fieldRow('用户名', p + '.bastion.account.user'),
        fieldRow('密码', p + '.bastion.account.password', { type: 'password', copy: true, reveal: true }),
        fieldRow('备注', p + '.bastion.notes', { type: 'textarea' }),
        imagesRow(p + '.bastion.images')
    ]));
    box.appendChild(bastion);
}

function renderQuirksInto(box, p) {
    box.appendChild(subSection('其他信息或备注', [
        fieldRow('', p + '.quirks', { type: 'textarea' })
    ]));
}

function renderClientsInto(box, p) {
    // 先清空本区域再收集，避免已删除行的残留输入框把数据写回 state
    box.innerHTML = '';
    collectAll();
    const basePath = p + '.clients';

    // 名称候选：常见服务器角色，也允许自定义输入
    sharedDatalist('client-name-options', ['app', 'db', '文档服务']);

    (getPath(state, basePath) || []).forEach((entry, i) => {
        const card = cardContainer();

        const headRow = document.createElement('div');
        headRow.className = 'form-row';
        const headLabel = document.createElement('label');
        headLabel.textContent = '服务器 ' + (i + 1);
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'icon-btn';
        delBtn.textContent = '删除此服务器';
        delBtn.addEventListener('click', () => {
            if (!confirm('确认删除这个服务器？')) return;
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
        card.appendChild(imagesRow(basePath + '.' + i + '.images'));
        box.appendChild(card);
    });
    addSectionButton(box, '+ 添加服务器', () => {
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
        document.getElementById('page-subtitle').textContent = '';
        state = {};
    } else {
        document.getElementById('page-title').textContent = '客户：' + envName;
        state = await api('/api/environments/' + encodeURIComponent(envName));
        // 更新时间以灰色小字展示在标题下方（保存后 init() 会重新渲染刷新）
        document.getElementById('page-subtitle').textContent =
            state.updated_at ? '更新时间：' + state.updated_at : '';
    }

    renderBasic();
    renderTags();
    renderFiles();
    renderTabs();
    renderModuleSide();
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

// 详情页左侧 tab：点击滚动定位 + 滚动时高亮当前区块
(function initDetailNav() {
    const nav = document.getElementById('detail-nav');
    if (!nav) return;
    const links = Array.prototype.slice.call(nav.querySelectorAll('a'));
    const sections = links
        .map(a => document.getElementById(a.getAttribute('href').slice(1)))
        .filter(Boolean);

    links.forEach(a => a.addEventListener('click', e => {
        e.preventDefault();
        const target = document.getElementById(a.getAttribute('href').slice(1));
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }));

    window.addEventListener('scroll', () => {
        let current = sections[0];
        sections.forEach(s => {
            if (s.getBoundingClientRect().top <= 120) current = s;
        });
        links.forEach(a => a.classList.toggle('active',
            current && a.getAttribute('href') === '#' + current.id));
    }, { passive: true });
})();
