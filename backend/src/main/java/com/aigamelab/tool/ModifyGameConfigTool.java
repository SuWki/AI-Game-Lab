package com.aigamelab.tool;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.config.ConfigDiffer;
import com.aigamelab.config.MergePatchApplier;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.ChangeProposalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-3 写类工具：modify_game_config（03 文档 P3-3 / 02 文档 §11 #3）。
 *
 * <p><b>不直接写盘</b>：提交 JSON Merge Patch → 服务端校验路径存在（防结构篡改）→
 * apply 到 before 得 after → {@link ConfigDiffer#diff} 生成 diff_json →
 * 落 {@code change_proposal(PENDING)} → 发 {@code PROPOSAL_CREATED} + {@code APPROVAL_REQUIRED} 事件。
 * Run 挂起（WAITING_APPROVAL）由 ToolExecutor 在 {@link #requiresApproval()} 返回 true 时统一处理，
 * 本工具只负责"产生提案 + 发事件"，不重复实现挂起逻辑。
 *
 * <p><b>patch 形态（Agent 契约，§11 示例）</b>：支持两种写法，工具内部统一翻译为
 * {@link MergePatchApplier} 接受的路径式 Merge Patch：
 * <ul>
 *   <li>① 带 id 数组（推荐）：{@code {"weapons":[{"id":"nova_sniper","critMultiplier":2.3}]}} —
 *       按 base 配置索引翻译为 {@code weapons[i].critMultiplier}；</li>
 *   <li>② 路径式对象：{@code {"player":{"maxHp":120}}} — 直接递归 merge。</li>
 * </ul>
 * 数组整体替换（无 id 的裸数组）亦被接受（RFC7386 子集）。
 */
@Component
public class ModifyGameConfigTool implements GameTool {

    private final ConfigVersionService configService;
    private final ChangeProposalRepository proposalRepo;
    private final AgentEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModifyGameConfigTool(ConfigVersionService configService,
            ChangeProposalRepository proposalRepo, AgentEventPublisher events) {
        this.configService = configService;
        this.proposalRepo = proposalRepo;
        this.events = events;
    }

    @Override
    public String name() {
        return "modify_game_config";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> baseVersion = new LinkedHashMap<>();
        baseVersion.put("type", "string");
        baseVersion.put("description", "基于哪个版本，缺省当前");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("type", "object");
        patch.put("description", "RFC7386 子集；推荐带 id 数组写法，如 {\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":2.3}]}");
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "六问浓缩：问题/证据/改什么/为什么/预期/验证");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("type", "array");
        evidence.put("items", Map.of("type", "integer"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("baseVersion", baseVersion);
        props.put("patch", patch);
        props.put("reason", reason);
        props.put("evidenceExperimentIds", evidence);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("patch", "reason"));
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return true; // ToolExecutor 据此置 PENDING + 挂起 Run
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String baseVersion = args.has("baseVersion") && !args.get("baseVersion").isNull()
                ? args.get("baseVersion").asText() : null;
        String reason = args.has("reason") ? args.get("reason").asText() : "";
        JsonNode rawPatch = args.get("patch");
        if (rawPatch == null || rawPatch.isNull()) {
            throw new ToolException("patch 不能为空");
        }
        String evidence = toIntArrayJson(args.get("evidenceExperimentIds"));

        // 取 base 配置
        ConfigVersion base = baseVersion != null
                ? configService.findByVersion(baseVersion)
                        .orElseThrow(() -> new ToolException("基础配置版本不存在: " + baseVersion))
                : configService.getCurrent();
        JsonNode before = readTree(base.configJson());

        // 将 Agent 友好 patch（带 id 数组）翻译为路径式 Merge Patch
        JsonNode normalizedPatch = normalizePatch(before, rawPatch);

        // apply（路径不存在抛 BusinessException 40901，由 ToolExecutor 转 FAILED observation）
        JsonNode after = MergePatchApplier.apply(before, normalizedPatch);

        // diff（unified 风格文本）
        String diffJson = ConfigDiffer.toUnifiedText(ConfigDiffer.diff(before, after));

        // 落 PENDING 提案
        ChangeProposal saved = proposalRepo.insert(new ChangeProposal(
                null, ctx.runId(), base.version(), null,
                "修改配置: " + summarizePatch(rawPatch), reason,
                evidence, diffJson, after.toString(), "PENDING", null, null, null));

        // 发事件（与 P6-3 解耦，仅广播事实）
        events.publish("PROPOSAL_CREATED", ctx.runId() == null ? 0L : ctx.runId(),
                "{\"proposalId\":" + saved.id() + ",\"baseVersion\":\"" + base.version() + "\"}");
        events.publish("APPROVAL_REQUIRED", ctx.runId() == null ? 0L : ctx.runId(),
                "{\"proposalId\":" + saved.id() + ",\"diff\":\"" + escape(diffJson) + "\"}");

        ObjectNode payload = new ObjectNode(JsonNodeFactory.instance);
        payload.put("proposalId", saved.id());
        payload.put("status", "PENDING");
        payload.put("baseVersion", base.version());
        payload.put("diff", diffJson);
        // 注意：实际 Run 挂起由 ToolExecutor 基于 requiresApproval=true 完成
        return ToolResult.success(payload.toString(),
                "已落 PENDING 提案 #" + saved.id() + "（base=" + base.version()
                        + "），等待人工批准；Run 进入 WAITING_APPROVAL");
    }

    /**
     * 将 Agent 友好 patch 翻译为 MergePatchApplier 接受的路径式 patch。
     * 规则：若某顶层字段值为"含 id 的数组"，则按 base 配置在该数组中的索引展开为
     * field[i].subKey 形式；其余原样保留（路径式对象递归 merge / 裸数组整体替换）。
     */
    private JsonNode normalizePatch(JsonNode base, JsonNode patch) {
        ObjectNode out = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = patch.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            JsonNode value = e.getValue();
            if (value.isArray() && !value.isEmpty() && isIdDiscriminatedArray(value)
                    && base.has(key) && base.get(key).isArray()) {
                out.set(key, expandIdArray(base.get(key), value));
            } else {
                out.set(key, value.deepCopy());
            }
        }
        return out;
    }

    private boolean isIdDiscriminatedArray(JsonNode arr) {
        for (JsonNode item : arr) {
            if (!item.isObject() || !item.has("id")) {
                return false;
            }
        }
        return true;
    }

    /** 将 [{"id":X, "field":V, ...}] 按 base 数组索引展开为 [idx0..idxN] 路径式对象数组。 */
    private JsonNode expandIdArray(JsonNode baseArray, JsonNode patchArray) {
        // MergePatchApplier 对数组整体覆盖，故这里构造一个"按索引的对象"会被当作普通对象递归 merge 失败；
        // 因此改为返回"带索引下标的路径对象"——但 RFC7386 树不支持 [i]，故采用"整体数组重建"策略：
        // 复制 base 数组，按 id 匹配项就地应用其余字段，作为整个数组的替换值。
        ArrayNode replacement = mapper.createArrayNode();
        for (JsonNode baseItem : baseArray) {
            replacement.add(baseItem.deepCopy());
        }
        for (JsonNode patchItem : patchArray) {
            String id = patchItem.get("id").asText();
            int idx = indexOfById(baseArray, id);
            if (idx < 0) {
                throw new ToolException("patch 引用了不存在的 " + id + "（结构篡改防护）");
            }
            ObjectNode target = (ObjectNode) replacement.get(idx);
            Iterator<Map.Entry<String, JsonNode>> f = patchItem.fields();
            while (f.hasNext()) {
                Map.Entry<String, JsonNode> fe = f.next();
                if (!"id".equals(fe.getKey())) {
                    target.set(fe.getKey(), fe.getValue().deepCopy());
                }
            }
        }
        return replacement;
    }

    private int indexOfById(JsonNode arr, String id) {
        for (int i = 0; i < arr.size(); i++) {
            JsonNode item = arr.get(i);
            if (item.has("id") && id.equals(item.get("id").asText())) {
                return i;
            }
        }
        return -1;
    }

    private String summarizePatch(JsonNode patch) {
        StringBuilder sb = new StringBuilder();
        patch.fieldNames().forEachRemaining(f -> sb.append(f).append(" "));
        return sb.toString().trim();
    }

    private static String toIntArrayJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "[]";
        }
        ArrayNode arr = new ArrayNode(JsonNodeFactory.instance);
        if (node.isArray()) {
            node.forEach(n -> arr.add(n.asInt()));
        }
        return arr.toString();
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new ToolException("基础配置 JSON 解析失败: " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"").replace("\n", "\\n");
    }
}
