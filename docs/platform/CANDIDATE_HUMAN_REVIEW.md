# 候选人工评审与规格覆盖 v1

2026-11-17，B-06 后续增量。新增 candidate_review(candidate_id, revision, record_json) 追加记录，共 26 表。原候选 ZIP、manifest 和其 humanAccepted=false 打包时快照不改写；评审记录/回执另存并固定 candidateId、manifestHash、archiveSha256。

GET /api/production-candidates/{id}/review 从固定包内 specification.json 读取精确规格，返回全部 acceptanceCriteria、需求覆盖汇总、独立引擎检查和最新人工意见。引擎检查与规格项不自动对应。初始人工项为 NOT_TESTED；PASS/FAIL 必须给观察说明，evidenceKind=HUMAN_OBSERVATION，不伪装自动浏览器证据。

POST 同一路径提交 expectedRevision、manifestHash、reviewer、summary、decision（DRAFT/ACCEPTED/REJECTED）、checks[{criterionId,outcome,evidenceNote}]。必须完整包含当前冻结规格的检查 ID，不能更改要求/预期/方法或漏项；ACCEPTED 要求全 PASS 且当前生产仍指向该候选、无未决提案、引擎 REVIEW。REJECTED 可保留未测试项，但必须说明原因。修订使用乐观版本并追加，历史不覆盖；精确重试幂等。

GET /{id}/review/history 按 revision 倒序返回最多 100 条；可用 beforeRevision 游标读取更早记录。GET /{id}/review/receipt 下载最新评审 JSON。写入/回执下载要求本机和 X-AGL-Local-Test: 1。没有 Agent 审批/接受工具；reviewer 是本机操作者填写的标签，不是账户认证或数字签名。

门禁实时重算：仅最新 ACCEPTED、完整 PASS、固定包校验通过且仍是当前 REVIEW 候选时 eligibleForDelivery=true。新候选、未决修改、拒绝或后续草稿会关闭门禁，过去接受保留为历史。READY、引擎 PASS、人工接受、实际交付/公开发布继续分开；本轮不自动发布或伪造 DELIVERED。

评审事务只覆盖版本校验和追加写入；文件验证与生产服务读在事务外，避免单连接 SQLite 的锁顺序问题。接受事务内再次核对持久的当前候选及未决提案，防止并发评审错误接受旧版本。门禁查询再次验证固定文件和最新生产状态。

本轮提供人工覆盖记录，不是自然语言需求自动测试器。任意填写的人工观察无法由平台证明真实性；真实浏览器/玩法证据适配、双作品与真实供应商验收继续后续开发。

回执 reviewHash 为记录移除自身 reviewHash 后按服务端紧凑 JSON 序列化的 UTF-8 字节 SHA-256，包含 inputHash/baseRevision 与记录时间。它是完整性指纹，不是认证签名。回执记录一次历史意见，不是永远有效的门禁证书；交付时重新查询实时 gate。
