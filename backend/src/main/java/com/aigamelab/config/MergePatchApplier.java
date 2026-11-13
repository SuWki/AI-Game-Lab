package com.aigamelab.config;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P6-2：JSON Merge Patch 应用器（蓝图 §14 / 03 文档 P6-2）。
 *
 * <p>实现 RFC 7386 的"子集"：patch 仅包含被改动的嵌套字段。
 * 应用规则：
 * <ul>
 *   <li>patch 中某字段值为 JSON null → 从 before 删除该字段；</li>
 *   <li>patch 中某字段为非对象标量 → 覆盖 before 同路径；</li>
 *   <li>patch 中某字段为对象 → 递归合并到 before 同路径对象；</li>
 *   <li>patch 中数组 → 整体覆盖（本项目不采用数组元素级 merge，避免歧义）。</li>
 * </ul>
 *
 * <p><b>防结构篡改</b>：patch 中的每个路径都必须已存在于 {@code before} 中——
 * AI 不得凭空新增不存在的顶层结构或字段。校验失败抛 {@link BusinessException}(40901)。
 * （蓝图 §14：「校验 patch 中每个 path 在 before 中存在（防 AI 篡改结构）」，
 * 技设 §23 风险项 3：「服务端校验路径存在，失败回传错误让 Agent 重试」）。
 */
public final class MergePatchApplier {

    private MergePatchApplier() {
    }

    /**
     * 将 patch 应用到 before 的<b>深拷贝</b>上，返回 after（不修改原始 before 快照）。
     *
     * @param before 修改前全量配置
     * @param patch  JSON Merge Patch（RFC7386 子集）
     * @return 应用后的全量配置（新对象）
     * @throws BusinessException 当 patch 引入 before 中不存在的路径（40901）
     */
    public static JsonNode apply(JsonNode before, JsonNode patch) {
        JsonNode working = before.deepCopy();
        validateAndMerge(working, patch, "");
        return working;
    }

    /**
     * 递归校验 + 合并。
     *
     * @param target 工作副本（可变）
     * @param patch  当前层 patch
     * @param path   当前路径（用于错误信息与校验）
     */
    private static void validateAndMerge(JsonNode target, JsonNode patch, String path) {
        if (!patch.isObject()) {
            // patch 顶层必须是对象
            throw new BusinessException(40901,
                    "Merge Patch 顶层必须是对象，非法路径: " + (path.isEmpty() ? "<root>" : path));
        }
        var it = patch.fieldNames();
        while (it.hasNext()) {
            String field = it.next();
            String childPath = path.isEmpty() ? field : path + "." + field;
            JsonNode patchValue = patch.get(field);

            if (patchValue.isNull()) {
                // 删除语义：仅当目标存在该字段时允许删除
                if (!target.has(field)) {
                    throw new BusinessException(40901,
                            "patch 删除不存在的字段（结构篡改防护）: " + childPath);
                }
                ((ObjectNode) target).remove(field);
                continue;
            }

            if (!target.has(field)) {
                // before 中不存在该路径 → 拒绝（防 AI 凭空新增结构）
                throw new BusinessException(40901,
                        "patch 引入 before 中不存在的路径（结构篡改防护）: " + childPath);
            }

            JsonNode targetValue = target.get(field);
            if (patchValue.isObject() && targetValue.isObject()) {
                // 递归合并
                validateAndMerge(targetValue, patchValue, childPath);
            } else {
                // 标量覆盖 / 数组整体覆盖
                ((ObjectNode) target).set(field, patchValue.deepCopy());
            }
        }
    }
}
