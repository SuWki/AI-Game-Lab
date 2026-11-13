package com.aigamelab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aigamelab.common.BusinessException;

/**
 * P6-2 验收测试（03 文档）：MergePatch 结构符合冻结设计；
 * 不应修改原始 before 快照；非法 path（不存在字段/凭空新增结构）被拒。
 */
@DisplayName("P6-2 MergePatchApplier")
class MergePatchApplierTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void appliesScalarOverride() {
        JsonNode before = read("{\"critMultiplier\":2.0,\"name\":\"w\"}");
        JsonNode patch = read("{\"critMultiplier\":1.7}");
        JsonNode after = MergePatchApplier.apply(before, patch);
        assertThat(after.get("critMultiplier").asDouble()).isEqualTo(1.7);
        assertThat(after.get("name").asText()).isEqualTo("w");
    }

    @Test
    void appliesNestedObjectMerge() {
        // 蓝图 §11 示例：patch 只含被改嵌套字段
        JsonNode before = read("{\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":2.8}]}");
        JsonNode patch = read("{\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":2.3}]}");
        JsonNode after = MergePatchApplier.apply(before, patch);
        assertThat(after.at("/weapons/0/critMultiplier").asDouble()).isEqualTo(2.3);
    }

    @Test
    void appliesDeleteWhenNullValueAndFieldExists() {
        JsonNode before = read("{\"a\":1,\"b\":2}");
        JsonNode patch = read("{\"b\":null}");
        JsonNode after = MergePatchApplier.apply(before, patch);
        assertThat(after.has("a")).isTrue();
        assertThat(after.has("b")).isFalse();
    }

    @Test
    void doesNotMutateOriginalSnapshot() {
        JsonNode before = read("{\"k\":1}");
        JsonNode patch = read("{\"k\":2}");
        MergePatchApplier.apply(before, patch);
        // before 原对象未被改写
        assertThat(before.get("k").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsPatchIntroducingNonexistentPath() {
        JsonNode before = read("{\"a\":1}");
        JsonNode patch = read("{\"nonexistent\":5}");
        assertThatThrownBy(() -> MergePatchApplier.apply(before, patch))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(40901));
    }

    @Test
    void rejectsDeleteOfNonexistentField() {
        JsonNode before = read("{\"a\":1}");
        JsonNode patch = read("{\"z\":null}");
        assertThatThrownBy(() -> MergePatchApplier.apply(before, patch))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsArrayElementLevelMergeInFavorOfWholeArrayOverride() {
        // 数组整体覆盖（本项目不采用数组元素级 merge）
        JsonNode before = read("{\"w\":[1,2,3]}");
        JsonNode patch = read("{\"w\":[9]}");
        JsonNode after = MergePatchApplier.apply(before, patch);
        assertThat(after.get("w").isArray()).isTrue();
        assertThat(after.get("w").size()).isEqualTo(1);
        assertThat(after.get("w").get(0).asInt()).isEqualTo(9);
    }
}
