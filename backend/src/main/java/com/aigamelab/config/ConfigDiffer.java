package com.aigamelab.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P6-2：字段级 Diff（蓝图 §14 / 03 文档 P6-2）。
 *
 * <p>递归展平 JSON 为 {@code path→value}（数组用 {@code [i]} 下标表示），
 * 数值归一化后比较，输出结构化 {@link DiffEntry} 列表与 unified-diff 风格文本行。
 *
 * <p>不引第三方 diff 库；纯 Jackson 实现。
 */
public final class ConfigDiffer {

    private ConfigDiffer() {
    }

    /** 单条字段级差异。 */
    public record DiffEntry(String path, JsonValue before, JsonValue after) {
        /** unified-diff 文本行（unified diff 风格，前端 {@code <pre>} 渲染）。 */
        public String toLine() {
            if (before == null) {
                return "+ " + path + ": " + after.text();
            }
            if (after == null) {
                return "- " + path + ": " + before.text();
            }
            return "- " + path + ": " + before.text() + "\n+ " + path + ": " + after.text();
        }
    }

    /** 归一化后的 JSON 标量值（文本形态，便于比较与展示）。 */
    public record JsonValue(String text, JsonNode raw) {
        static JsonValue of(JsonNode n) {
            if (n == null || n.isNull()) {
                return new JsonValue("null", n);
            }
            if (n.isTextual()) {
                return new JsonValue(n.asText(), n);
            }
            if (n.isNumber()) {
                // 数值归一化：统一以 BigDecimal 字符串呈现，消除 2.0 vs 2 形式差异
                return new JsonValue(n.decimalValue().stripTrailingZeros().toPlainString(), n);
            }
            // 布尔/其他标量
            return new JsonValue(n.asText(), n);
        }
    }

    /**
     * 计算 before / after 的字段级差异。
     *
     * @param before 修改前全量配置（JSON 树）
     * @param after  修改后全量配置（JSON 树）
     * @return 按 path 字典序排列的 DiffEntry 列表（空列表表示无差异）
     */
    public static List<DiffEntry> diff(JsonNode before, JsonNode after) {
        Map<String, JsonValue> bFlat = flatten("", before);
        Map<String, JsonValue> aFlat = flatten("", after);
        List<DiffEntry> entries = new ArrayList<>();

        // 删除 / 修改
        for (Map.Entry<String, JsonValue> e : bFlat.entrySet()) {
            String path = e.getKey();
            JsonValue a = aFlat.get(path);
            if (a == null) {
                entries.add(new DiffEntry(path, e.getValue(), null));
            } else if (!e.getValue().text().equals(a.text())) {
                entries.add(new DiffEntry(path, e.getValue(), a));
            }
        }
        // 新增
        for (Map.Entry<String, JsonValue> e : aFlat.entrySet()) {
            if (!bFlat.containsKey(e.getKey())) {
                entries.add(new DiffEntry(e.getKey(), null, e.getValue()));
            }
        }
        entries.sort(Comparator.comparing(DiffEntry::path));
        return entries;
    }

    /** 将 DiffEntry 列表渲染为 unified-diff 风格文本（每行一条）。 */
    public static String toUnifiedText(List<DiffEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (DiffEntry e : entries) {
            sb.append(e.toLine()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 递归展平 JSON。
     * - 对象：{@code parent.key}
     * - 数组：{@code parent[key]}（方括号下标），元素逐一下标展开
     * 空对象/数组展平为空映射（不生成占位项）。
     */
    private static Map<String, JsonValue> flatten(String prefix, JsonNode node) {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            out.put(prefix, JsonValue.of(node));
            return out;
        }
        if (node.isObject()) {
            if (prefix.isEmpty()) {
                // 根对象不生成自身占位，递归其字段
                node.fieldNames().forEachRemaining(f ->
                        out.putAll(flatten(f, node.get(f))));
            } else {
                out.put(prefix, JsonValue.of(node)); // 整个对象作为值（非叶子时不单独占位，但保留整值以便嵌套对象整体被改时呈现）
                node.fieldNames().forEachRemaining(f ->
                        out.putAll(flatten(prefix + "." + f, node.get(f))));
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                out.putAll(flatten(prefix + "[" + i + "]", arr.get(i)));
            }
        } else {
            out.put(prefix, JsonValue.of(node));
        }
        return out;
    }

    /** 构造一个空 ObjectNode（工具方法，供 MergePatchApplier 复用工厂）。 */
    static ObjectNode emptyObject(JsonNodeFactory factory) {
        return factory.objectNode();
    }
}
