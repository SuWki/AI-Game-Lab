-- TA.1: 幂等迁移脚本
-- 修复 research_claim 表中 extract_claims 遗留的畸形 supporting_source_ids / contradicting_source_ids
-- 将 ["source:N] → [N]（canonical JSON 数字数组）。
-- 幂等：重复执行无副作用（LIKE 条件执行后无匹配行）。
-- 数据库：SQLite；非 schema DDL，仅 UPDATE 数据行。

UPDATE research_claim
SET supporting_source_ids = '['
    || substr(supporting_source_ids, 10, length(supporting_source_ids) - 10)
    || ']'
WHERE supporting_source_ids LIKE '["source:%';

UPDATE research_claim
SET contradicting_source_ids = '['
    || substr(contradicting_source_ids, 10, length(contradicting_source_ids) - 10)
    || ']'
WHERE contradicting_source_ids LIKE '["source:%';
