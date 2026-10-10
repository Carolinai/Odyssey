# 领域文档（Domain Docs）

工程技能在探索本仓库代码时，应按以下方式消费领域文档。

## 探索之前，先读这些

- 仓库根目录的 **`CONTEXT.md`**，或者
- 仓库根目录的 **`CONTEXT-MAP.md`**（如果存在）—— 它指向每个上下文各自的 `CONTEXT.md`。阅读与当前主题相关的每一个。
- **`docs/adr/`** —— 阅读与即将工作的领域相关的 ADR。在多上下文仓库中，还需检查 `src/<context>/docs/adr/` 中的上下文级决策。

如果这些文件不存在，**静默继续**。不要标记它们的缺失；不要建议提前创建它们。`/domain-modeling` 技能（通过 `/grill-with-docs` 和 `/improve-codebase-architecture` 进入）会在术语或决策真正确定时惰性创建它们。

## 文件结构

单上下文仓库（绝大多数仓库）：

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多上下文仓库（根目录存在 `CONTEXT-MAP.md` 时）：

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← 系统级决策
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← 上下文级决策
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## 使用词汇表中的术语

当你的输出涉及某个领域概念时（issue 标题、重构提案、假设、测试名），使用 `CONTEXT.md` 中定义的术语。不要漂移到词汇表明确避免的同义词。

如果你需要的概念还不在词汇表中，这是一个信号——要么你在发明项目不使用的语言（请重新考虑），要么存在真实的缺口（记下来，留给 `/domain-modeling` 处理）。

## 标记 ADR 冲突

如果你的输出与现有 ADR 矛盾，应显式指出，而不是静默覆盖：

> _与 ADR-0007（event-sourced orders）矛盾——但值得重新讨论，因为……_
