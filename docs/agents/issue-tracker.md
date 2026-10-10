# Issue 跟踪器：本地 Markdown

本仓库的 Issue 和规格（spec）以 markdown 文件形式存放在 `.scratch/` 目录下。

## 约定

- 每个功能一个目录：`.scratch/<feature-slug>/`
- 规格文件为 `.scratch/<feature-slug>/spec.md`
- 实现 Issue 每个工单一个文件，路径为 `.scratch/<feature-slug>/issues/<NN>-<slug>.md`，编号从 `01` 开始——禁止使用单个合并的工单文件
- 分类状态记录在每个 issue 文件顶部附近的 `Status:` 行
- 评论和讨论历史追加到文件底部的 `## Comments` 标题之下

## 当技能要求 "publish to the issue tracker"（发布到 Issue 跟踪器）时

在 `.scratch/<feature-slug>/` 下创建新文件（必要时先创建目录）。

## 当技能要求 "fetch the relevant ticket"（获取相关工单）时

读取引用路径处的文件。用户通常会直接给出路径或 issue 编号。

## 寻路操作（Wayfinding operations）

由 `/wayfinder` 使用。**地图（map）**是一个文件，每个工单对应一个**子文件**。

- **地图**：`.scratch/<effort>/map.md` —— 正文包含 Notes / Decisions-so-far / Fog。
- **子工单**：`.scratch/<effort>/issues/NN-<slug>.md`，编号从 `01` 开始，正文写明问题。`Type:` 行记录工单类型（`research`/`prototype`/`grilling`/`task`）；`Status:` 行记录 `claimed`/`resolved`。
- **阻塞（Blocking）**：文件顶部附近的 `Blocked by: NN, NN` 行。当其列出的所有文件均为 `resolved` 时，该工单解除阻塞。
- **前沿（Frontier）**：扫描 `.scratch/<effort>/issues/` 中处于打开、未阻塞、未认领状态的文件；编号最小者优先。
- **认领（Claim）**：在做任何工作之前，先设置 `Status: claimed` 并保存。
- **解决（Resolve）**：在 `## Answer` 标题下追加答案，设置 `Status: resolved`，然后在 `map.md` 的 Decisions-so-far 中追加一条上下文指针（要点 + 链接）。
