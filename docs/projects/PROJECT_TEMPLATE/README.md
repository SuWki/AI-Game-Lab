# Project 模板使用说明

新 Project 启动时，复制本目录为 `docs/projects/<编号>_<Project名>/`，然后按需创建文件。

规则（依据 [../../architecture/DOCUMENTATION_RULES.md](../../architecture/DOCUMENTATION_RULES.md) §7、§22）：

1. **禁止为了结构完整创建空文档**——只复制当前阶段需要的文件。
2. 文件名遵循 [../../architecture/DOCUMENTATION_RULES.md](../../architecture/DOCUMENTATION_RULES.md) §19：英文项目名 + 中文说明，禁止"最终版/最新版/临时"类命名。
3. 各文档职责边界见模板内注释头。
4. 技术设计必须引用 Platform 能力，禁止重复设计 LLM Gateway / Runtime / Tool Registry。

## 推荐的最小起步组合

- 阶段 1（立项）：README.md + 产品定义.md
- 阶段 2（设计）：技术设计.md + Agent设计.md + Tool设计.md
- 阶段 3（开发）：开发任务树.md
- 阶段 4（验收前）：验收标准.md

## 可用模板

| 文件 | 用途 |
|---|---|
| README.md | Project 门面：是什么/输入/输出/完成度/如何启动/依赖的 Platform 能力 |
| 产品定义.md | 产品目标/用户/场景/MVP/非目标 |
| 技术设计.md | 模块结构/数据流/API/数据模型/Platform 集成 |
| Agent设计.md | Persona/System Prompt 索引/Decision Loop/停止条件/循环保护 |
| Tool设计.md | Tool 清单/Schema/权限/审批要求 |
| 开发任务树.md | Task ID/依赖/验收标准/并行关系 |
| 验收标准.md | 功能/API/Agent 行为/回归验收 |
