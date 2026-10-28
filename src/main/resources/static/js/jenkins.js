// Jenkins 视图页：过滤展示 Jenkins 视图列表

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

loadJenkinsViews();
