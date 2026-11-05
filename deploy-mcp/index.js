// Odyssey 自动部署 MCP 服务器（stdio）
// 提供两个工具：
//   deploy_odyssey  —— 打包（Maven）→ 服务器上备份旧包 → scp 上传 → docker compose 重建 → 健康检查
//   odyssey_status  —— 查看服务器容器状态与本机/服务器 jar 的版本差异
// 前提：本机已配置到 mingdu@10.0.106.110 的 SSH 免密登录（密钥认证 + sudo NOPASSWD）
'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const { z } = require('zod');

// ---------- 部署常量（如环境变化，改这里即可） ----------
const PROJECT_DIR = path.resolve(__dirname, '..');                    // 项目根目录
const MVN_CMD = 'D:\\apache-maven-3.8.3-bin\\apache-maven-3.8.3\\bin\\mvn.cmd'; // mvn 不在 PATH，用完整路径
const SSH_TARGET = 'mingdu@10.0.106.110';                             // 部署目标（密钥认证）
const SERVER_DIR = '~/odyssey';                                       // 服务器上的部署目录
const SERVER_PORT = 8787;                                             // 服务端口（健康检查用）
const LOCAL_JAR = path.join(PROJECT_DIR, 'target', 'Odyssey-1.0-SNAPSHOT.jar');

// ---------- 子进程封装：带超时，收集输出，非零退出码抛错 ----------
function run(cmd, args, { timeout = 600000, cwd } = {}) {
    return new Promise((resolve, reject) => {
        // .cmd/.bat 批处理不能被 spawn 直接启动（Node ≥18.20 出于安全报 EINVAL），
        // 须经 shell 启动；含空格的参数补引号
        const quote = a => (/\s/.test(a) ? `"${a}"` : a);
        const needsShell = /\.(cmd|bat)$/i.test(cmd);
        const child = needsShell
            ? spawn(`"${cmd}" ${args.map(quote).join(' ')}`, { cwd, shell: true, windowsHide: true })
            : spawn(cmd, args, { cwd, shell: false, windowsHide: true });
        let out = '', err = '';
        child.stdout.on('data', d => { out += d; });
        child.stderr.on('data', d => { err += d; });
        const timer = setTimeout(() => {
            child.kill();
            reject(new Error(`命令超时（${timeout / 1000}s）：${cmd} ${args.join(' ')}\n${out}\n${err}`));
        }, timeout);
        child.on('error', e => { clearTimeout(timer); reject(e); });
        child.on('close', code => {
            clearTimeout(timer);
            if (code === 0) resolve((out + err).trim());
            else reject(new Error(`命令失败（退出码 ${code}）：${cmd} ${args.join(' ')}\n${out}\n${err}`));
        });
    });
}

// ---------- 部署步骤 ----------
async function mavenPackage(skipTests) {
    const args = ['-q', '-f', path.join(PROJECT_DIR, 'pom.xml'), 'package'];
    if (skipTests) args.push('-DskipTests');
    await run(MVN_CMD, args, { timeout: 600000 });
    if (!fs.existsSync(LOCAL_JAR)) throw new Error('打包完成但未找到产物：' + LOCAL_JAR);
    const size = fs.statSync(LOCAL_JAR).size;
    return `打包成功：Odyssey-1.0-SNAPSHOT.jar（${(size / 1024 / 1024).toFixed(1)} MB，测试${skipTests ? '已跳过' : '已通过'}）`;
}

async function backupOldJar() {
    // 服务器上保留一份上一版 jar，失败时可手动回滚（见 docs/deploy.md）
    try {
        await run('ssh', [SSH_TARGET, `if [ -f ${SERVER_DIR}/app.jar ]; then cp ${SERVER_DIR}/app.jar ${SERVER_DIR}/app.jar.bak; fi`], { timeout: 30000 });
        return '旧包已备份为服务器 ~/odyssey/app.jar.bak';
    } catch (e) {
        return '旧包备份失败（不阻断部署）：' + e.message;
    }
}

async function uploadJar() {
    await run('scp', [LOCAL_JAR, `${SSH_TARGET}:${SERVER_DIR}/app.jar`], { timeout: 300000 });
    return 'jar 已上传到服务器';
}

async function rebuildContainer() {
    const out = await run('ssh', [SSH_TARGET,
        `cd ${SERVER_DIR} && sudo docker compose up -d --build 2>&1 | tail -3`], { timeout: 600000 });
    return '容器重建完成：\n' + out;
}

async function healthCheck() {
    // 等容器启动后探测健康状态；最多等 60 秒
    for (let i = 0; i < 12; i++) {
        await new Promise(r => setTimeout(r, 5000));
        try {
            const out = await run('ssh', [SSH_TARGET,
                `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:${SERVER_PORT}/`], { timeout: 30000 });
            if (out.includes('200')) return `健康检查通过（HTTP 200，等待 ${(i + 1) * 5}s）`;
        } catch (e) { /* 服务未就绪，继续等 */ }
    }
    throw new Error(`健康检查失败：${SERVER_PORT} 端口 60s 内未返回 200，请上服务器查看容器日志（sudo docker logs --tail 100 odyssey）。如需回滚：cd ~/odyssey && cp app.jar.bak app.jar && sudo docker compose up -d --build`);
}

// ---------- MCP 服务与工具注册 ----------
const server = new McpServer({ name: 'odyssey-deploy', version: '1.0.0' });

server.registerTool('deploy_odyssey', {
    title: '部署 Odyssey 到服务器',
    description: '把 Odyssey 打包并部署到生产服务器 10.0.106.110：'
        + 'Maven 打包 → 服务器备份旧包（app.jar.bak）→ scp 上传 → docker compose 重建 → 健康检查。'
        + '全程约 2~5 分钟。静态资源改动可传 skip_tests=true 跳过测试加速。',
    inputSchema: { skip_tests: z.boolean().optional().describe('是否跳过 Maven 测试（默认 false，先跑测试再打包）') }
}, async ({ skip_tests }) => {
    const logs = [];
    try {
        logs.push(await mavenPackage(!!skip_tests));
        logs.push(await backupOldJar());
        logs.push(await uploadJar());
        logs.push(await rebuildContainer());
        logs.push(await healthCheck());
        return { content: [{ type: 'text', text: '✅ 部署成功\n' + logs.map(l => '- ' + l).join('\n') }] };
    } catch (e) {
        return {
            content: [{ type: 'text', text: '❌ 部署失败\n已完成步骤：\n' + logs.map(l => '- ' + l).join('\n')
                + '\n\n错误：' + e.message }],
            isError: true
        };
    }
});

server.registerTool('odyssey_status', {
    title: '查看 Odyssey 服务器状态',
    description: '查看生产服务器上 odyssey 容器运行状态、服务健康度，以及本地与服务器 jar 的构建时间对比（判断服务器是否落后于本地代码）。',
    inputSchema: {}
}, async () => {
    try {
        const out = await run('ssh', [SSH_TARGET,
            `sudo docker ps -a --filter name=odyssey --format '{{.Names}} | {{.Status}}';`
            + ` echo "健康检查: $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:${SERVER_PORT}/)";`
            + ` ls -l ${SERVER_DIR}/app.jar | awk '{print "服务器 jar:", $6, $7, $8}'`], { timeout: 60000 });
        let localLine = '本地 jar: 尚未打包';
        if (fs.existsSync(LOCAL_JAR)) {
            const s = fs.statSync(LOCAL_JAR);
            localLine = '本地 jar: ' + new Date(s.mtime).toLocaleString('zh-CN');
        }
        return { content: [{ type: 'text', text: out + '\n' + localLine }] };
    } catch (e) {
        return { content: [{ type: 'text', text: '查询失败：' + e.message }], isError: true };
    }
});

// stdio 启动
(async () => {
    await server.connect(new StdioServerTransport());
    // 连接建立后保持进程存活，等待 JSON-RPC 请求
})();
