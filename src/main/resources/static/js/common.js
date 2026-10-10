// 公共工具：请求封装、复制、toast 提示（列表页、表单页、RSA 工具页共用）

/** 请求 JSON 接口，非 2xx 时抛出含 message 的 Error */
async function api(url, options) {
    const resp = await fetch(url, options);
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
