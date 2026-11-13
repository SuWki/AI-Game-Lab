# Builder Runtime 与仓库交付

2026-11-13。在已有生产任务上接入模型身份与资源范围，实际行为见 [Builder Runtime](../platform/BUILDER_RUNTIME.md)。

## 本批代码

BUILDER 为第 9 个角色，新增版本化 Prompt（总 14）和 3 项生产工具（总 37），不扩大原角色工具白名单。绑定规格的任务从生产页面/API 创建，在共享 Workflow 启动。通用任务 API 拒绝未绑定 Builder；启动和控制绑定任务仍执行本地修改来源检查。

生产任务和本地子执行持久化 ownerRunId；同 Run 重复启动幂等，另一 Run 或人工生产不可被模型越权查询。生成前后检查父 Run 状态/时限，处理取消发生在子执行登记前的竞态；已登记的子执行由共享终态事件取消。BUILT 和模型 stop 都不代表 QA 通过。

未开放任意 shell、源码写入、自批、自动修复或公开发布；原六项 Builder 工具草案并非全部实现。没有将外部编码助手当平台内模型生产服务。

## 验证范围

BuilderRuntimeTest 覆盖：读取实际前轮工具观察后才能推进；共享 Loop 实际触发 Node 模板并获取 BUILT 证据；跨 Run/人工产物拒绝；同 Run 启动幂等；未绑定在模型调用前失败；其他角色隔离；已取消父 Run 不创建新生产。

全量构建/回归与 Node 模板测试在提交前执行。真实页面验证创建 Builder Task、精确规格绑定、未标记 Run 启动拒绝、通用入口未绑定角色拒绝，见 [页面/API 证据](evidence/builder-2026-11-13/builder-page.json) 和 [截图](evidence/builder-2026-11-13/builder-page.png)。模型流程使用检查实际请求的 Gateway 和真实 Node 执行；没有真实付费供应商调用记录，不能据此宣称真实模型效果已验收。

最终本地验收：后端全量 713 项（95 suites），0 failures/errors/skipped；前端构建及 69 项测试通过；Node 模板/执行器 8 项通过；git diff --check 与新增文档链接检查通过。

首轮 Linux CI 的前端/模板通过，后端发现旧 RepositoryTest 将事件游标硬编码为 2；SQLite 的自增序列不因测试删行而归零，因执行顺序不同暴露。修正为使用第二条插入结果的实际 ID，并预置另一 Run 的事件，确保非初始游标也能精确回放。未修改正确的生产查询或放宽结果断言；更新后再等待远端全量检查。

新增 GitHub Actions 对 Java 21/Node 20 环境运行后端、前端及模板检查。现有历史开发与测试调整、文档、模板和证据纳入本次交付快照；本地数据库、生成工作区、依赖/构建缓存和用户预置 PRE_BUILDER_FULL_CODE_AUDIT.md 不提交。

## GitHub 交付方式

远端 SuWki/AI-Game-Lab 的 main 只有初始 README，与本地开发提交历史不同。普通 Git 的 github.com 传输出现连接重置，但 api.github.com 访问和账号写权限可用，因此使用 GitHub Git Data API 上传验收快照并核对 Git tree SHA 完全一致，在保留远端初始提交的前提下建立交付 PR。原本地开发分支与提交历史保留，不强制覆盖 main。

合并通过 PR 与 CI 完成，不以本地提交或 API 请求已发出代替成功确认。最终 PR 与 merge SHA 由交付回复给出。
