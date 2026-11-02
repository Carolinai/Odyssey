# Odyssey

Java Maven 项目（vp_controller/Odyssey）。

## 部署

生产服务器 10.0.106.110:8787（Docker）。MCP 工具 `odyssey-deploy`（`deploy_odyssey` / `odyssey_status`）可自动部署；流程与手动方式见 `docs/deploy.md`。AI 需要自行执行 mvn 时仍须先征得用户同意。

## Agent skills

### Issue tracker

Issue 以本地 markdown 文件形式跟踪在 `.scratch/<feature>/` 目录下。详见 `docs/agents/issue-tracker.md`。

### Domain docs

单上下文布局：根目录 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。
