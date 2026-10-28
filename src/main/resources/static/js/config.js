// 全局配置页：编辑「远程方式 → 唤起链接」映射，整体保存到服务端（全站共享）
// 保存以页面当前行为准全量覆盖；行编辑不实时写回 state，收集发生在保存时

const listBox = document.getElementById('launcher-list');

// 一行映射：远程方式名 + 唤起链接 + 删除
function launcherRow(name, url) {
    const row = document.createElement('div');
    row.className = 'form-row';

    const nameInput = document.createElement('input');
    nameInput.placeholder = '远程方式名，如：向日葵';
    nameInput.value = name || '';
    row.appendChild(nameInput);

    const urlInput = document.createElement('input');
    urlInput.placeholder = '唤起链接，如：sunlogin://';
    urlInput.value = url || '';
    row.appendChild(urlInput);

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'icon-btn';
    delBtn.textContent = '删除';
    delBtn.addEventListener('click', () => row.remove());
    row.appendChild(delBtn);
    return row;
}

function renderRows(launchers) {
    listBox.innerHTML = '';
    const keys = Object.keys(launchers || {});
    if (keys.length === 0) {
        const hint = document.createElement('div');
        hint.className = 'empty-hint';
        hint.style.padding = '8px 0';
        hint.textContent = '暂无映射，点击下方「添加映射」';
        listBox.appendChild(hint);
        return;
    }
    keys.forEach(key => listBox.appendChild(launcherRow(key, launchers[key])));
}

document.getElementById('add-launcher').addEventListener('click', () => {
    // 移除「暂无映射」占位提示后追加一行
    listBox.querySelectorAll('.empty-hint').forEach(h => h.remove());
    listBox.appendChild(launcherRow('', ''));
});

document.getElementById('save-config').addEventListener('click', async () => {
    const launchers = {};
    let duplicated = false;
    listBox.querySelectorAll('.form-row').forEach(row => {
        const inputs = row.querySelectorAll('input');
        const key = inputs[0].value.trim();
        const url = inputs[1].value.trim();
        if (!key || !url) return; // 空行直接丢弃
        if (launchers[key]) duplicated = true;
        launchers[key] = url;
    });
    if (duplicated && !confirm('存在重复的远程方式名，将只保留其中一行，确认保存？')) return;
    try {
        await api('/api/config', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ launchers: launchers })
        });
        toast('已保存');
        renderRows(launchers); // 服务端归一化后重渲染（清掉空行）
    } catch (e) {
        alert('保存失败：' + e.message);
    }
});

loadGlobalConfig().then(config => renderRows(config.launchers));
