package com.aigamelab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aigamelab.config.ConfigDiffer.DiffEntry;

/**
 * P6-2 验收测试（03 文档）：diff 正确识别新增/删除/修改；空 diff；
 * 多字段修改；嵌套结构（数组下标）；数值归一化。
 */
@DisplayName("P6-2 ConfigDiffer")
class ConfigDifferTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void detectsModifyNestedNumericWithNormalization() {
        // 蓝图 §14 示例：critMultiplier 2.0 → 1.7
        JsonNode before = read("{\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":2.0}]}");
        JsonNode after = read("{\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":1.7}]}");

        List<DiffEntry> diff = ConfigDiffer.diff(before, after);
        assertThat(diff).hasSize(1);
        DiffEntry e = diff.get(0);
        assertThat(e.path()).isEqualTo("weapons[0].critMultiplier");
        assertThat(e.before().text()).isEqualTo("2");
        assertThat(e.after().text()).isEqualTo("1.7");
        assertThat(e.toLine()).contains("- weapons[0].critMultiplier: 2")
                .contains("+ weapons[0].critMultiplier: 1.7");
    }

    @Test
    void detectsAddedField() {
        JsonNode before = read("{\"a\":1}");
        JsonNode after = read("{\"a\":1,\"b\":2}");
        List<DiffEntry> diff = ConfigDiffer.diff(before, after);
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).path()).isEqualTo("b");
        assertThat(diff.get(0).before()).isNull();
        assertThat(diff.get(0).after().text()).isEqualTo("2");
    }

    @Test
    void detectsRemovedField() {
        JsonNode before = read("{\"a\":1,\"b\":2}");
        JsonNode after = read("{\"a\":1}");
        List<DiffEntry> diff = ConfigDiffer.diff(before, after);
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).path()).isEqualTo("b");
        assertThat(diff.get(0).after()).isNull();
        assertThat(diff.get(0).before().text()).isEqualTo("2");
    }

    @Test
    void detectsMultipleFieldModifications() {
        JsonNode before = read("{\"a\":1,\"b\":2,\"c\":3}");
        JsonNode after = read("{\"a\":1,\"b\":20,\"c\":30}");
        List<DiffEntry> diff = ConfigDiffer.diff(before, after);
        assertThat(diff).hasSize(2);
        assertThat(diff.stream().map(DiffEntry::path)).containsExactly("b", "c");
    }

    @Test
    void emptyDiffForIdenticalConfig() {
        JsonNode before = read("{\"a\":1,\"nested\":{\"x\":[1,2,3]}}");
        JsonNode after = read("{\"a\":1,\"nested\":{\"x\":[1,2,3]}}");
        assertThat(ConfigDiffer.diff(before, after)).isEmpty();
        // 2.0 与 2 视为相同（数值归一化）
        assertThat(ConfigDiffer.diff(read("{\"a\":2}"), read("{\"a\":2.0}"))).isEmpty();
    }

    @Test
    void flattensNestedArrayAndObject() {
        JsonNode before = read("{\"player\":{\"hp\":100},\"weapons\":[{\"id\":\"w1\",\"dmg\":10},{\"id\":\"w2\",\"dmg\":20}]}");
        JsonNode after = read("{\"player\":{\"hp\":90},\"weapons\":[{\"id\":\"w1\",\"dmg\":10},{\"id\":\"w2\",\"dmg\":25}]}");
        List<DiffEntry> diff = ConfigDiffer.diff(before, after);
        assertThat(diff.stream().map(DiffEntry::path))
                .containsExactly("player.hp", "weapons[1].dmg");
    }

    @Test
    void unifiedTextContainsPlusAndMinusLines() {
        JsonNode before = read("{\"k\":1}");
        JsonNode after = read("{\"k\":2}");
        String text = ConfigDiffer.toUnifiedText(ConfigDiffer.diff(before, after));
        assertThat(text).contains("- k: 1").contains("+ k: 2");
    }
}
