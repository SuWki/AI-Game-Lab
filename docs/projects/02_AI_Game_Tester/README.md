# Tester 模块

职责与输入输出见 [产品定义](产品定义.md)，当前技术与执行契约、后续任务和验收见 [演进设计.md](演进设计.md)。历史阶段任务见 [开发任务树](开发任务树.md)，实际完成状态统一在 [task-state](../../state/task-state.md)。

通过既有 Workflow 创建任务；本地目标任务从本地测试证据页创建。所需接入与凭据见 [运行说明](../../../README.md)，未实现的生产功能不能通过当前页面直接启动。

依赖共享 Runtime、工具注册、SQLite、事件和产物，模块既服务已有游戏改进，也服务 [游戏生产链](../../platform/GAME_PRODUCTION_CONTRACT.md)。不部署独立 Agent 服务。
