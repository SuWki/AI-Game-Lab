# 生产任务与中台首版交付

2026-11-13。实现 B-02/03 的受控模板生产切片：选择精确规格 → 后端持久任务 → 共享执行器运行 Node 模板 → 独立工作区与构建 → 页面展示结果/证据。没有增加第二 Agent Runtime 或新的进程队列。

## 实现

- production_job 增量表（总 23 表），固定 task/spec revision/hash、模板 hash 与 request key；同 task/key 重复提交复用原任务，换规格冲突。
- ProductionJobService/Controller 提供模板信息、创建、查询、取消。LocalTestService 新增仅供可信内部服务调用的 startManaged，HTTP 不接受任意命令/目录。
- run-production.mjs 执行前后验证模板整体 hash，验证生成构建文件指纹，使用现有 runId/result 协议；错误保留具体检查原因。
- `/productions` 页面选择任务与精确规格、提交、取消、刷新，链接执行证据，展示工作区和构建。网络失败重试保留请求 key。
- 启动时不重放未关联进程的准备记录；已关联执行使用共享执行器的重启中断结果。BUILT 是模板构建结果，不是 QA PASS 或全规格达标。

实际设置和 API 见 [生产任务协议](../platform/PRODUCTION_JOBS.md)。新 API 为本机单用户使用，尚无内嵌预览/候选下载、Builder 身份、模型写代码/修复循环或规格批准状态。

## 测试与实际证据

后端最新一次全量 **707 tests / 0 failures / 0 errors**，94 suites。新增 6 项生产任务测试含真实 Node 构建/请求幂等、跨任务拒绝、参数错误证据、共享执行器失败、取消委托、准备阶段中断。取消的服务边界用显式测试执行器控制，真实进程的取消行为继续由共享执行器既有测试覆盖；未使用生产代码豁免。

前端全量 **68 tests / 12 files** 通过，类型检查/production build 通过。之后补明确的 select 标签，重跑构建和该页面 3 项测试通过。Node 模板与 runner **8 项**通过，包括模板漂移在创建项目之前拒绝。

初次后端新测试用 SpyBean 遇到 JDK24/ByteBuddy 不兼容，已改用测试专用显式执行器；首次浏览器脚本对隐藏 option 等待可见超时，修正为等待挂载；随后发现标签歧义并修复页面。最终流程完整重跑通过，未以修改断言绕过执行失败。

真实本地验收使用独立 `data/production-jobs-review.db`，保留原业务数据库。由网页创建生产 `02e13d6c-5ea3-435b-a037-db393c6baa0c`，生成构建 `build-16133b99-f119-4bcb-b9ef-5e52891e7dd5`。实际 API 确认重复提交返回原 ID，缺修改请求头为 400。受控重启后状态、构建 ID 与规格 hash 保持一致。

该项目另经 Edge/Playwright 真实点击和按键，20 秒自然推进至胜负，再重开成功；4 项检查通过，无 pageerror，未使用强制结算 hook。这只是指定参数下的烟雾验收，不证明玩家体验或任意规格覆盖。

证据：[页面/API/完整生产记录](evidence/production-jobs-2026-11-13/page-and-api.json)、[页面截图](evidence/production-jobs-2026-11-13/production-page.png)、[游戏运行结果](evidence/production-jobs-2026-11-13/gameplay/browser.json)、[游戏截图](evidence/production-jobs-2026-11-13/gameplay/settlement.png)、[重启留存](evidence/production-jobs-2026-11-13/restart.json)。

## 剩余范围

后续需把独立验收程序和生产接受状态真正接入，才可对规格完成下结论；再接 Builder 身份、受控差异、预算与修复循环。模板只解释 systems.survivalWeb 参数，不自动实现自然语言需求。当前源/产物指纹不包含完整 OS/Node 环境闭包，非恶意代码沙箱。没有将该首版当作完整 B-02..07 或真实模型制造验收。
