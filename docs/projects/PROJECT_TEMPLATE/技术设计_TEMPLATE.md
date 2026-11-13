# 技术设计

> 职责边界（DOCUMENTATION_RULES §10）：模块结构 / 数据流 / API / 数据模型 / Platform 集成。
> 必须引用 Platform 文档；已有 Platform 能力禁止重新设计一套。

## 1. 模块结构

<本 Project 的模块划分及与 Platform 包的关系>

```text
<ASCII 结构图>
```

## 2. 数据流

<从输入到输出的完整数据路径>

## 3. 复用的 Platform 能力

| 能力 | 来源 | 使用方式 |
|---|---|---|
| Agent Runtime | `com.aigamelab.runtime` | |
| LlmGateway | `com.aigamelab.llm` | |
| ToolRegistry | `com.aigamelab.tool` | |
| Event System | `com.aigamelab.event` | |

## 4. 数据模型（本 Project 特有）

> 只列本 Project 特有的表/字段；公共模型见 DOCUMENTATION_RULES §21 唯一权威来源。
> 所有表通过 project_id 与平台隔离（DOCUMENTATION_RULES §26）。

| 表 | 用途 | 关键字段 |
|---|---|---|
| | | |

## 5. API 设计

| 端点 | 方法 | 说明 |
|---|---|---|
| | | |

遵循统一 ApiResp 响应封装。

## 6. 错误处理与并发

<错误码、重试策略、并发限制点>
