package com.aigamelab.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.repository.ChangeProposalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-3 写类工具：create_change_proposal（03 文档 P3-3 / 02 文档 §11 #8）。
 *
 * <p>产出"方案级"提案（多候选方案 + 理由 + 推荐），<b>不碰配置、不挂起 Run</b>。
 * 用于满足"重大修改提多方案"的论证需求；与 modify_game_config 的区别：
 * 本工具只落论证型 proposal（afterConfigJson 留空），审批流 P6-3 对无 afterConfigJson 的提案不会建版本。
 *
 * <p>requiresApproval=false：本工具仅记录论证，不触发挂起；若后续要落地则另行调用 modify_game_config。
 */
@Component
public class CreateChangeProposalTool implements GameTool {

    private final ChangeProposalRepository proposalRepo;

    public CreateChangeProposalTool(ChangeProposalRepository proposalRepo) {
        this.proposalRepo = proposalRepo;
    }

    @Override
    public String name() {
        return "create_change_proposal";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("type", "string");
        title.put("description", "提案标题");
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "推荐方案理由 + 六问浓缩");
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("type", "array");
        candidates.put("description", "候选方案列表（≥2，含优缺点/风险/玩家体验影响）");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("type", "array");
        evidence.put("items", Map.of("type", "integer"));
        evidence.put("description", "依据的实验 id 列表");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", title);
        props.put("reason", reason);
        props.put("candidates", candidates);
        props.put("evidenceExperimentIds", evidence);
        props.put("baseVersion", Map.of("type", "string", "description", "基于哪个版本，可空"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("title", "reason"));
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String title = args.has("title") ? args.get("title").asText() : "";
        String reason = args.has("reason") ? args.get("reason").asText() : "";
        String baseVersion = args.has("baseVersion") && !args.get("baseVersion").isNull()
                ? args.get("baseVersion").asText() : null;
        String evidence = toIntArrayJson(args.get("evidenceExperimentIds"));

        // 候选方案原样序列化进 afterConfigJson 之外的备注：复用 diffJson 字段承载论证文本（不碰配置）
        String rationale = "candidates=" + args.get("candidates").toString();

        // afterConfigJson 承载论证占位（{} 表示不修改配置，满足 NOT NULL 约束；
        // 论证型提案不进入 P6-3 的版本创建路径）
        ChangeProposal saved = proposalRepo.insert(new ChangeProposal(
                null, ctx.runId(), baseVersion, null, title, reason,
                evidence, rationale, "{}", "PENDING", null, null, null));

        ObjectNode payload = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        payload.put("proposalId", saved.id());
        payload.put("status", saved.status());
        return ToolResult.success(payload.toString(),
                "已落论证型提案 #" + saved.id() + "（title=" + title + "，不修改配置、不挂起）");
    }

    private static String toIntArrayJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "[]";
        }
        ArrayNode arr = new com.fasterxml.jackson.databind.node.ArrayNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        if (node.isArray()) {
            node.forEach(n -> arr.add(n.asInt()));
        }
        return arr.toString();
    }

}
