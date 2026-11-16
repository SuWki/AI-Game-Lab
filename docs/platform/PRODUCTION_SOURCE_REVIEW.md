# 生产源码提案与独立引擎验收

2026-11-16。本轮为 VAL-01 与 Builder 受控修改的可用切片，复用现有生产任务、本地执行器、源码版本、审批及隔离回归，不增加业务表（仍 23 表）。

## 用户流程

生产完成后在 `/productions` 点击“执行独立验收”，或 POST `/api/productions/{id}/validate`。修改请求沿用本机来源与 `X-AGL-Local-Test: 1`。首次提交固定验收契约并启动受管执行；重复提交返回同一验收执行，不重复或重置基线。可通过现有取消生产接口取消进行中的验证。

页面显示 validationRunId、validationStatus、validationDecision，并链接本地测试证据页。在该页可导入失败 Finding、读取源码并生成修改提案，再到修改提案页面批准/拒绝。批准后复制固定版本源码到独立工作区，执行同一验证命令，保留原目录。通过后交付的是隔离工作区及其构建，不自动改写原项目或合并。

## 固定与保护

每生产任务保存一个工作区外的 acceptance-contract.json，包含原始构建的配置、规格指纹及受保护文件 hash。验证命令固定契约、平台验收程序与受信构建程序的 SHA256。程序或契约漂移会明确失败，不能替换判据继续宣称原实验修复。程序升级后需新生产/基线，未来再提供显式验收版本变更流程。

LocalTestService.Target 增加可选 protectedFiles（旧记录/旧目录配置默认空），LocalChangeService 拒绝对其中路径及子路径的编辑。生产验证将以下文件纳入版本清单并设保护：config.json、specification.json、production-manifest.json、build-project.mjs、serve-project.mjs。基线验证与隔离回归都保留该策略。模型可读这些文件，不能经提案修改；数值配置调整应提交新 GameSpec/生产，而不是改变固定测试输入。

可编辑范围为 engine.mjs、game.mjs、index.html、style.css。外部验收程序不位于游戏目录，不复制为可编辑游戏文件。读取、提案仍要求版本 hash 未漂移；后端权限是最终约束，前端同时将保护文件设为只读。

## 验收范围与产物

`survival-web-engine-v1` 的六项检查为初始/重新开始状态、移动与边界、确定性重放、自然终态及终态停止推进、碰撞伤害/护盾、配置资源机制。它们是独立程序执行的引擎检查，其中伤害/技能用受控状态夹具；不是浏览器自然通关、性能测试或全部 GameSpec 验收。

每次从本次源码重新构建，测试实际新构建的 engine.mjs，核对输出文件 hash，结果记录 validationBuild 与 contractHash。引擎逻辑失败时保留完整检查可形成 Finding；契约/程序/受保护输入/构建完整性失败时 coverageComplete=false，QA 为证据不足，不制造通过。后续同检查 ID 的提案回归还执行原 LocalChangeService 的漏测检测。

这是模板级验证配置，不自动消费任意 AcceptanceContract 产物中的自然语言/浏览器检查，不代表那些需求已验证。BUILT、引擎 PASS、源码提案 VALIDATED 与人类质量接受继续区分。

## Builder 工具

新 Run 使用 builder_p002，保留 p001 作为历史版本；冻结旧 Run 的 Prompt/工具不改写。新增三项，BUILDER 共六项工具，全平台注册工具共 40、Prompt 种子共 15：

| 工具 | 参数 | 行为 |
|---|---|---|
| production_validate | productionId | 仅验证本 Run 的生产，有界等待，父 Run 取消/超时停止验证 |
| production_source_read | productionId,path,offset?,limit? | 读取该生产验收基线的源码和 hash，分页最多 3000 字符 |
| production_change_propose | productionId,title,edits[{path,beforeSha256,afterText}] | 生成需审批的提案并挂起；人工决定及隔离回归通过原 tool 观察恢复 |

原 context/start/result 继续可用。验证观察返回 scope 及检查明细（expected/actual），供模型定位失败。修改提案遵守既有文件数/文本大小限制和 Run 步数/token/时限，没有自批工具或任意命令。

## 边界

旧本地目标未配置 protectedFiles 时维持原语义，不宣称已补齐所有目标的验收保护。受信本机执行仍不是 OS 沙箱：恶意源码有当前用户权限，不能用本轮应用层路径/hash 规则保证对任意恶意代码的隔离或形式化正确性。后续仍需浏览器验收、规格覆盖映射、正式接受与发布流程，以及完整自动修复预算编排。
