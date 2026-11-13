# QA 团队模块

负责汇总测试与角色分析，形成有证据的质量建议，服务已有游戏迭代和新游戏交付。输入候选版本、验收契约、测试证据与 Finding；输出缺项、阻断问题、质量建议和人类裁决记录。

现有 Phase 1–3 已有实现，人工最终验收以 [task-state](../../state/task-state.md) 为准；本地 QA 判定已有独立入口，尚未完整接入生产候选的统一门禁。用户通过既有 Workflow/QA API 进入，不能把本文新设计当已上线流程。

- [产品定义](产品定义.md)
- [技术与执行设计](技术设计.md)
- [任务与验收](开发任务树.md)

历史权威链：[Phase 2](../../QA_PHASE2_ARCHITECTURE.md)、[Phase 3](../../QA_PHASE3_ARCHITECTURE_DESIGN.md)、[Phase 3 收口审计](../../reports/QA_PHASE3_CLOSURE_AUDIT.md)。这些文件保留阶段契约；Runtime 变化以 [新 ADR](../../decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md) 为准。
