// RSA 解密工具页：粘贴密文 → 解密 → 复制（私钥内置在后端，无需选择）

const cipherInput = document.getElementById('cipher-input');
const plainOutput = document.getElementById('plain-output');
const decryptBtn = document.getElementById('decrypt-btn');

decryptBtn.addEventListener('click', async () => {
    const cipher = cipherInput.value.trim();
    if (!cipher) {
        alert('请粘贴密文');
        return;
    }
    try {
        const result = await api('/api/decrypt', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ cipher: cipher })
        });
        plainOutput.value = result.plain;
        toast('解密成功');
    } catch (e) {
        plainOutput.value = '';
        alert('解密失败：' + e.message);
    }
});

document.getElementById('reveal-btn').addEventListener('click', () => {
    plainOutput.type = plainOutput.type === 'password' ? 'text' : 'password';
});

document.getElementById('copy-btn').addEventListener('click', () => {
    if (!plainOutput.value) {
        toast('还没有明文');
        return;
    }
    copyText(plainOutput.value);
});
