# 开发任务树

> 职责边界（DOCUMENTATION_RULES §14）：Task Tree 是开发计划，不是代码实现记录。
> 执行方式遵循 docs/state/工作流.md（v2 自主推进 + Sprint Checkpoint）。

## Sprint 分组

| Sprint | 目标 | 包含 Task |
|---|---|---|
| Sprint <X>-0 | | |
| Sprint <X>-1 | | |

## Task 列表

| Task ID | 目标 | 前置依赖 | 修改文件 | 验收标准 | 测试方式 | 可并行 |
|---|---|---|---|---|---|---|
| <P>-0 | | 无 | | | | 否 |
| <P>-1 | | <P>-0 | | | | 是 |

## 依赖图

```text
<P>-0 → <P>-1 → <P>-2
```

## Checkpoint 设置

<本 Project 的 Sprint 在哪些节点设置人工验收闸门>
