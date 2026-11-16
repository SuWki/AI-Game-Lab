# 生产源码提案与独立验收交付

2026-11-16，平台能力增量；基于 main `05c73157`。设计入口：[生产源码审阅](../platform/PRODUCTION_SOURCE_REVIEW.md)。

## 交付内容

- `/productions` 可对已构建生产执行独立引擎验收，固定同一基线，显示判定并链接证据页。
- 工作区外契约固定原配置、规格引用、受保护输入 hash；验证程序和受信构建程序也固定 hash。每次重建当前源码并测试实际新构建，引擎失败与证据不足分开。
- 通用目标新增可选 protectedFiles；源码提案拒绝修改保护路径，隔离回归保留同策略。旧目标默认空，兼容历史记录。
- BUILDER 新 Run 使用 builder_p002 与六项工具，可验证、分页读源码、生成需人类批准的源码提案。验收观察包含六项检查的 expected/actual，便于定位失败。人工审批和隔离结果通过原 Runtime 继续恢复，不新增循环或执行服务。
- 复用 23 张业务表；全平台注册工具 40，Prompt 种子 15。历史 Run 的冻结上下文与旧 Prompt 保留。

## 验证

后端全量 716 项、前端 71 项及生产构建通过；Node 模板/验收测试共 10 项通过。追加验收检查明细输出后，定向重跑 ProductionJobServiceTest 与 BuilderRuntimeTest。集成测试真实运行 Node，包含失败引擎→Finding→隔离修复，以及请求驱动 Gateway 的分页读取→审批挂起→真实回归→恢复完成；不声称已验证真实模型供应商。

真实 Edge 页面/API 在独立 scratch 数据库完成如下流程：

1. 页面选择精确规格生成项目；人为将移动速度改为零，作为受控故障夹具。
2. 点击独立验收：6 项中移动失败，判定 FAIL，导入 Finding #1。
3. 查看保护文件时编辑框只读；绕过前端直接提交 config.json 提案返回 409。
4. 页面提交恢复移动的源码提案，查看差异并批准。独立工作区六项检查 PASS，提案 VALIDATED。
5. 原故障工作区未改变；两次构建 ID 不同、契约 hash 相同，浏览器无脚本错误。

这不是模型发现的真实产品缺陷，故障为测试人员刻意注入。完整运行 ID、检查及 hash 见 [机器记录](evidence/production-source-2026-11-16/workflow.json)；页面证据：[受保护输入](evidence/production-source-2026-11-16/protected-input.png)、[隔离回归](evidence/production-source-2026-11-16/isolated-regression.png)。独立浏览器 smoke 另检查修复候选的加载、开始、自然结算与重开，记录见 [游戏浏览器证据](evidence/production-source-2026-11-16/game/browser.json)。

## 未完成与后续顺序

当前 survival-web-engine-v1 是六项固定引擎检查，伤害/资源检查使用受控状态夹具。它不消费全部自然语言 AcceptanceContract，不等于完整 GameSpec、界面或玩法质量通过；浏览器 smoke 也是额外样本，而非覆盖证明。动态源码以当前用户权限执行，工作区隔离不是 OS 沙箱。

下一批优先 B-05：修复轮次与费用累计、阶段持久化、取消/重启恢复及预算耗尽终态。随后 B-06 固定候选源码/构建/规格/测试清单与预览交付，再推进规格到浏览器验收映射、第二个真实目标和真实供应商的 B-07 验收。不将本轮切片标成完整自动制造平台。
