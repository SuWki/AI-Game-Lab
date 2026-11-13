# 模板生产任务 API 与页面

2026-11-13，B-02/03 的首个受控切片。任务消费已有精确 GameSpec revision，经共享 LocalTestService 有界队列运行注册模板命令，生成独立项目。生产状态 BUILT 只证明固定模板成功实例化与构建指纹一致，不等于完整需求或玩法验收通过。

## 配置

从 backend 目录启动默认使用 `production.repository-root=..`（Lab 仓库）、`production.workspace-root=./data/productions`（任务根）、`production.node=node`。解释器不在 PATH 时设为 node.exe 绝对路径。启动目录不同时显式配置 repository-root，不能从浏览器覆盖目录、解释器、命令或超时。

模板固定为仓库中的 survival-web；模板清单和四个源码文件的整体指纹在提交时固定，执行前后核对。每任务单独 UUID 目录，input.json 保存精确规格与模板指纹；输出目录中再生成独立项目。实际进程由现有 LocalTestService 管理，复用两线程/八排队位、60 秒任务时限、取消、日志与重启中断机制。

新增 production_job 表保存 task_id、request_key 与记录 JSON；共 23 张业务表，schema.sql 增量初始化，既有列不改。此为单后端单用户本机功能，不提供操作系统沙箱或多实例执行权。

## 接口

| 方法 | 路径 | 含义 |
|---|---|---|
| GET | `/api/productions/template` | 模板 id/version/当前整体 hash |
| POST | `/api/productions` | `{taskId,specId,specRevision,requestKey}` |
| GET | `/api/productions` | 最近 100 条记录及最新执行状态 |
| GET | `/api/productions/{id}` | 固定规格、模板 hash、执行 ID、结果或错误 |
| POST | `/api/productions/{id}/cancel` | 委托共享执行器取消，已终态结果保留 |

修改接口要求本机 Host/回环来源与 `X-AGL-Local-Test: 1`。规格必须属于请求 task，类型为 GameSpec，并支持 web/survivalWeb 机制。详细模板参数由固定实例化程序校验，非法参数留下失败执行与具体原因。

同 task/requestKey 重复提交返回原记录；同 key 换规格拒绝，失败后要创建新尝试则使用新 key。界面网络失败重试保留该 key，成功提交后再次生成是新的请求。

PREPARING→QUEUED/RUNNING→BUILT，失败分为 FAILED/TIMEOUT/CANCELLED/REJECTED/INTERRUPTED/INCONCLUSIVE，沿用执行器原因。准备时退出且未关联执行的记录转 INTERRUPTED，不盲目重放；已关联执行重启后读取共享执行器的中断结果。查询会同步最终状态并持久化。

## 页面与范围

中台 `/productions` 选择任务、精确规格版本，提交、取消、刷新，查看执行日志/证据链接与生成目录/构建 hash。当前需要先通过 ART-01 接口或样例脚本保存规格。生成后在该目录运行 `node serve-project.mjs 4180` 试玩；尚无平台内嵌游戏预览或候选下载。

输出仍是 [可玩模板](PLAYABLE_TEMPLATE.md) 明确支持的参数。后续已接 [受限 BUILDER 身份](BUILDER_RUNTIME.md)，尚无自主代码修改、生产批准状态、修复循环、QA 接受或 B-07 全量验收。框架完整规格中的自然语言需求不会自动变为实现。
