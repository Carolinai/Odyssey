// 菜单管理页：编辑个人菜单（内置项可改名/改地址/排序/显隐，可添加自定义菜单项）
// 编辑基于当前生效配置的副本（个人覆盖或全局默认），保存后整体写入 localStorage 个人覆盖

let items = JSON.parse(JSON.stringify(loadMenuOverride() || DEFAULT_MENUS));

const listBox = document.getElementById('menu-list');

// 一行菜单项编辑：显示开关 + 名称 + 链接 + 上移/下移 + 删除
function menuRow(item, index) {
    const row = document.createElement('div');
    row.className = 'form-row menu-editor-row';

    const visible = document.createElement('input');
    visible.type = 'checkbox';
    visible.checked = item.visible !== false;
    visible.title = '是否在菜单中显示';
    visible.addEventListener('change', () => { item.visible = visible.checked; });
    row.appendChild(visible);

    const labelInput = document.createElement('input');
    labelInput.className = 'menu-name';
    labelInput.placeholder = '菜单名称';
    labelInput.value = item.label || '';
    labelInput.addEventListener('input', () => { item.label = labelInput.value; });
    row.appendChild(labelInput);

    const urlInput = document.createElement('input');
    urlInput.placeholder = '链接，如 /decrypt.html 或 https://…';
    urlInput.value = item.url || '';
    urlInput.addEventListener('input', () => { item.url = urlInput.value; });
    row.appendChild(urlInput);

    const upBtn = document.createElement('button');
    upBtn.type = 'button';
    upBtn.className = 'icon-btn';
    upBtn.textContent = '↑';
    upBtn.title = '上移';
    upBtn.disabled = index === 0;
    upBtn.addEventListener('click', () => {
        [items[index - 1], items[index]] = [items[index], items[index - 1]];
        renderList();
    });
    row.appendChild(upBtn);

    const downBtn = document.createElement('button');
    downBtn.type = 'button';
    downBtn.className = 'icon-btn';
    downBtn.textContent = '↓';
    downBtn.title = '下移';
    downBtn.disabled = index === items.length - 1;
    downBtn.addEventListener('click', () => {
        [items[index + 1], items[index]] = [items[index], items[index + 1]];
        renderList();
    });
    row.appendChild(downBtn);

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'icon-btn';
    delBtn.textContent = '删除';
    delBtn.addEventListener('click', () => {
        if (!confirm('确认删除菜单项「' + (item.label || '') + '」？（保存后生效，可用恢复默认找回内置菜单）')) return;
        items.splice(index, 1);
        renderList();
    });
    row.appendChild(delBtn);
    return row;
}

function renderList() {
    listBox.innerHTML = '';
    if (items.length === 0) {
        const hint = document.createElement('div');
        hint.className = 'empty-hint';
        hint.style.padding = '8px 0';
        hint.textContent = '暂无菜单项，点击下方「添加自定义菜单」';
        listBox.appendChild(hint);
        return;
    }
    items.forEach((item, i) => listBox.appendChild(menuRow(item, i)));
}

document.getElementById('add-menu').addEventListener('click', () => {
    items.push({ label: '', url: '', visible: true });
    renderList();
});

document.getElementById('save-menus').addEventListener('click', () => {
    // 丢弃未填名称或地址的行（内置项的 key 保留，供菜单管理识别与高亮）
    items = items.filter(m => (m.label || '').trim() && (m.url || '').trim());
    saveMenuOverride(items);
    renderSidebar(); // 左侧菜单即时生效
    renderList();
    toast('已保存（仅对本浏览器生效）');
});

document.getElementById('reset-menus').addEventListener('click', () => {
    if (!confirm('确认恢复为全局默认菜单？将丢弃本浏览器中的自定义。')) return;
    clearMenuOverride();
    items = JSON.parse(JSON.stringify(DEFAULT_MENUS));
    renderSidebar();
    renderList();
    toast('已恢复默认');
});

renderList();
