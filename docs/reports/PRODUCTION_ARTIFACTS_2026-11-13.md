# ART-01 首版实现

2026-11-13。新增 ProductionArtifactService 和 /api/production-artifacts，复用 Task/Run 与 design_document，新增一张版本元数据表。已实现三类产物：ProductionBrief、GameSpec、AcceptanceContract。

行为：不可变 revision、精确历史读取、重复提交复用、修改时检查预期 revision；内容指纹和固定输入链；同任务/producer Run 所有权检查；唯一需求与验收引用校验；验收不能漏掉规格检查或更改 expected/method。旧 Markdown 不伪造版本身份。文档与元数据同事务保存，实际 API 见 [协议](../platform/PRODUCTION_ARTIFACTS.md)。

验证：后端全量运行 701 项，唯一失败为 TesterRepositoryTest 旧表集合未含新表；同步该集合后定向重跑 TesterRepositoryTest 与 ProductionArtifactServiceTest，共 14 项通过。最新 Surefire 库存 701 / 0 failures / 0 errors，但没有宣称又跑了一次修正后的全量。新增 6 项测试包含旧库增表保留原任务、版本/重试、字段顺序 hash 一致、错误 hash/跨任务拒绝、完整输入链及验收不可弱化。

实际使用独立 production-review.db，在回环 8082 启动本版服务，执行 scripts/production/submit-example.mjs，成功保存任务 #1 的简报→游戏规格→验收定义。API 返回证据见 [JSON](evidence/production-artifacts-2026-11-13/api-handoff.json)。样例为“热量管理生存游戏”的规格交接，不是可玩游戏生成，也未调用付费模型。脚本语法与 git diff --check 通过。前端未变，本轮未重复执行前端测试。

范围：这是 ART-01 的首个可用切片，未实现其他五类交付物、规格接受状态、Builder 身份/模板/制造编排，未保护磁盘测试程序。下一步可由 D-N1 消费结构化规格、B-01 建首模板；VAL-01 与 ADAPT-01 继续解决独立验收和源码构建关联。不能将本次 AcceptanceContract 保存当作已经执行验收。
