# 生产交付物 API v1

2026-11-13，ART-01 首版实现。提供 ProductionBrief、GameSpec、AcceptanceContract 的版本保存和输入追溯，不包含制造调度、执行独立测试或游戏生成。

新增 production_artifact 元数据表，内容继续写入 design_document；该批时点共 22 张业务表，后续生产任务表使当前总数为 23。schema.sql 增表幂等，旧任务/文档不改写。每次保存及其文档写入在一个事务内。尚未接模型产物工具；后续生产页面已可消费这里保存的规格。

## 接口

- POST `/api/production-artifacts`：`{taskId,producerRunId?,artifactId?,expectedRevision?,kind,content,inputRefs?:[{id,revision,hash}]}`。本机 Host/来源及 `X-AGL-Local-Test: 1` 与现有本地修改约束相同。
- GET `/api/production-artifacts?taskId=...`：该任务全部 revision，按保存顺序倒序。
- GET `/api/production-artifacts/{id}/revisions/{revision}`：精确读取历史，不隐式选择最新版本。

响应复用 ApiResp。缺字段为参数错误；任务/引用不存在为 not found；跨任务、输入 hash 错误、预期 revision 过期为 conflict。producerRunId 若提供，必须属于该任务。当前单用户本机运行，不宣称具备远程用户授权。

首次保存分配 UUID/revision=1。同任务同 kind/content/inputRefs 的重复保存复用已有记录；更新传 artifactId 和 expectedRevision，新内容产生新 revision。旧 revision 不覆盖；精确重复当前内容可复用结果，不因重试创建新版本。inputRefs 数组顺序属于交付语义。

## 内容与指纹

内容最大 256 KiB，对象键递归排序、数组保序，以 Jackson 紧凑 JSON 的 UTF-8 编码计算 SHA256；文本保留原始换行，数字保留 JSON 节点表达，不承诺跨语言 JCS 规范等价。内容 hash 只覆盖 content；输入链另外校验并保存。调用者使用服务返回 hash，不自行猜测序列化。此规则为 schemaVersion=1。

ProductionBrief 要求 title、mode（NEW/DERIVED/MODIFY）、genre、targetPlatform、targetPlayer、scope。

GameSpec 要求 title、genre、targetPlatform、coreLoop、endConditions、restartBehavior；controls/states/requirements/acceptanceCriteria 非空数组，contentScope/assetNeeds/exclusions/openQuestions 为数组，systems 为对象。requirements 包含唯一 id/description/acceptanceIds；acceptanceCriteria 包含唯一 id/requirementId/expected/method，双向引用必须一致，无孤立条目。briefRef 为已绑定的 ProductionBrief artifactId，并通过 inputRefs 固定 revision/hash。

AcceptanceContract 要求 title、specHash 和非空 checks[{id,expected,method}]，绑定同任务 GameSpec revision/hash；检查集合及 expected/method 必须与其规格一致。它是不可变的验收定义，尚未冻结磁盘中的测试脚本，也没有执行这些检查。保护独立验收程序仍属 VAL-01 后续实现。

## Designer 草稿与制造范围确认

在生产页输入设计目标并启动 Designer 规格任务。该任务通过共享 Runtime 请求 `{brief, spec}`，完成后可刷新并选择具体 Run，补齐或改写模型 JSON，再保存规格草稿。模型原文留在 Run 记录里，未通过校验时编辑器保留当前输入。保存使用一个事务，非法规格不会留下半份简报。重复提交相同 Run、简报与规格会复用已经保存的修订。编辑该规格生成后续修订时继续引用原始 Run。

- `GET /api/production-artifacts/designer-drafts?taskId=...` 返回该任务最近可导入的规格设计结果。
- `POST /api/production-artifacts/designer-drafts/{runId}` 接收 `{taskId, brief, spec}`，校验已完成的专用 Designer Run 所属任务，原子保存简报与规格。服务端注入固定的简报引用。
- `POST /api/production-artifacts/manufacturing-scope` 接收 `{taskId, specId, revision, contentHash}`。确认前需解决全部 `openQuestions`，且规格须能用于当前 survival-web 模板。同一版本重复确认返回原回执。
- `GET /api/production-artifacts/{id}/revisions/{revision}/manufacturing-scope` 读取该版本的回执。新建 Builder 任务时后端再次检查精确修订和内容指纹。确认 r1 后，r2 仍需单独确认，旧生产照常追溯到当时的固定版本。

生产页的“生成游戏项目”保留为直接模板试做。制造范围确认记录进入 Builder 的人类决定，候选交付仍须经过独立证据与人工评审。原 Markdown 设计报告仍可通过既有入口查看。

## 本地样例

启动包含本版代码的后端后执行：

```powershell
node scripts/production/submit-example.mjs http://127.0.0.1:8080
```

创建一个任务及其简报、热量机制游戏规格、验收定义，输出版本引用。不调用付费模型、不声称产出了可玩游戏。

2026-11-17 后续：已接入生产页规格编辑器，支持简报/规格新建、原版本修订、需求/验收与模板参数编辑、成功后选择精确版本。详见 [交付与边界](../reports/SPECIFICATION_EDITOR_2026-11-17.md)。复用当前 API，未新增规格接受状态或模型产物工具。
