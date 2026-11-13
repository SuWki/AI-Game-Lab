# Tool 设计

> 职责边界（DOCUMENTATION_RULES §12）：Tool 必须遵守 Platform Tool System，禁止 Project 自建第二套 ToolRegistry。
> 参考：docs/architecture/02_共享基础设施规范.md §4、工作流 §33（Tool 开发特别规则）。

## Tool 清单

| Tool 名称 | 用途 | 输入 Schema 摘要 | 输出 Schema 摘要 | requiresApproval | 副作用 |
|---|---|---|---|---|---|
| | | | | | |

## 每个 Tool 的详细定义

### <tool_name>

- **唯一名称**：<name>（全局唯一，遵守 Platform 命名空间）
- **输入 Schema**：

```json
{ }
```

- **输出 Schema / ToolResult 语义**：

```json
{ }
```

- **权限要求**：<需要什么 ProjectContext / 审批级别>
- **是否需要审批**：<是/否；修改游戏配置类必须走 Proposal → Approval → Apply>
- **副作用**：<修改哪些系统状态；禁止偷偷修改其他系统状态>
- **错误处理**：<非法输入/依赖失败时返回什么>
