# Odyssey 部署文档

生产环境：公司内网服务器 **10.0.106.110**（Ubuntu + Docker），服务端口 **8787**。

部署本质是三步：**本地 Maven 打包 → 上传 jar → 服务器上重建容器**。
本文提供两种方式：MCP 自动部署（推荐，AI 一句话完成）和手动部署。

## 前提条件

| 条件 | 说明 |
|------|------|
| SSH 免密登录 | 本机已配置到 `mingdu@10.0.106.110` 的密钥认证，且该账号有 sudo NOPASSWD 权限 |
| Maven | 位于 `D:\apache-maven-3.8.3-bin\apache-maven-3.8.3\bin\mvn.cmd`（不在 PATH） |
| Node.js | ≥ 18（MCP 方式需要） |
| 服务器部署目录 | `~/odyssey/`，含 `Dockerfile`、`docker-compose.yml`、`app.jar`、`data/` |

数据目录 `~/odyssey/data/` 通过 volume 挂载进容器，**重建容器不会丢数据**。

## 方式一：MCP 自动部署（推荐）

### 1. 安装依赖（首次）

```bash
cd D:\projects\vp_controller\Odyssey\deploy-mcp
npm install
```

### 2. 注册到 Claude Code

项目根目录已包含 `.mcp.json`（内容如下），在项目里启动 Claude Code 时会自动识别，
首次使用时确认信任即可：

```json
{
  "mcpServers": {
    "odyssey-deploy": {
      "command": "node",
      "args": ["deploy-mcp/index.js"]
    }
  }
}
```

若未被识别，也可以手动注册：

```bash
claude mcp add odyssey-deploy --scope project -- node deploy-mcp/index.js
```

注册后在 Claude Code 里输入 `/mcp` 应能看到 `odyssey-deploy` 处于 connected 状态。

### 3. 使用

直接对 AI 说：

- 「部署到服务器」—— 跑测试 → 打包 → 备份 → 上传 → 重建 → 健康检查
- 「改的是静态资源，跳过测试直接部署」—— 传 `skip_tests=true`，快几分钟
- 「看看服务器状态」—— 容器状态、健康度、本地/服务器 jar 时间对比

### MCP 工具说明

| 工具 | 参数 | 做什么 |
|------|------|--------|
| `deploy_odyssey` | `skip_tests`（可选，默认 false） | ① Maven 打包（mvn package）② 服务器旧包备份为 `app.jar.bak` ③ scp 上传覆盖 `app.jar` ④ `sudo docker compose up -d --build` ⑤ 健康检查（80s 内 8787 端口需返回 200） |
| `odyssey_status` | 无 | 查看容器运行状态、服务健康度、本地与服务器 jar 构建时间对比 |

任一步骤失败时，工具会返回**已完成步骤清单 + 具体报错**，据此排查或回滚。

### 回滚

部署工具每回合会自动把上一版 jar 备份为服务器 `~/odyssey/app.jar.bak`。
需要回滚时对 AI 说「回滚到上一版」，或手动执行：

```bash
ssh mingdu@10.0.106.110 "cd ~/odyssey && cp app.jar.bak app.jar && sudo docker compose up -d --build"
```

## 方式二：手动部署

在项目根目录（`D:\projects\vp_controller\Odyssey`）按顺序执行：

### 1. Maven 打包

```bat
cmd.exe /c "D:\apache-maven-3.8.3-bin\apache-maven-3.8.3\bin\mvn.cmd package"
```

- 产物：`target\Odyssey-1.0-SNAPSHOT.jar`
- 改的是静态资源/不放心测试时可加 `-DskipTests`

### 2. 上传 jar

```bash
scp target/Odyssey-1.0-SNAPSHOT.jar mingdu@10.0.106.110:~/odyssey/app.jar
```

（稳妥起见可先在服务器上 `cp ~/odyssey/app.jar ~/odyssey/app.jar.bak` 留回滚备份）

### 3. 重建容器

```bash
ssh mingdu@10.0.106.110 "cd ~/odyssey && sudo docker compose up -d --build"
```

### 4. 验证

```bash
ssh mingdu@10.0.106.110 "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8787/"
```

返回 `200` 即部署成功；浏览器访问 `http://10.0.106.110:8787/` 强刷（Ctrl+F5）确认页面为新版。

## 常见问题

| 现象 | 处理 |
|------|------|
| `mvn` 不是内部或外部命令 | mvn 不在 PATH，必须用上面的完整路径（或经 cmd.exe 调用） |
| 健康检查 60s 仍失败 | 上服务器看日志：`ssh mingdu@10.0.106.110 "sudo docker logs --tail 100 odyssey"`；需要时按上文回滚 |
| scp/ssh 要求输密码 | 免密登录失效，检查 `~/.ssh` 密钥配置 |
| 页面改动没生效 | 浏览器缓存，Ctrl+F5 强刷 |
| `docker compose` 报权限 | 该账号 sudo NOPASSWD，命令前需加 `sudo` |
