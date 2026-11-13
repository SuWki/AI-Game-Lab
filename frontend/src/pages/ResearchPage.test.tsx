import { describe, it, expect } from "vitest";
import { countJsonArray } from "./ResearchPage";

describe("countJsonArray (TA: claim supportingSourceIds 解析)", () => {
  it("canonical 单来源 [1]", () => {
    expect(countJsonArray("[1]")).toBe(1);
  });

  it("canonical 多来源 [1,2]", () => {
    expect(countJsonArray("[1,2]")).toBe(2);
  });

  it("空数组 [] → 0", () => {
    expect(countJsonArray("[]")).toBe(0);
  });

  it("null/空串 → 0", () => {
    expect(countJsonArray("")).toBe(0);
  });

  it("legacy 畸形字符串 [\"source:1] → 0（不崩溃）", () => {
    expect(countJsonArray('["source:1]')).toBe(0);
  });

  it("非 JSON → 0", () => {
    expect(countJsonArray("abc")).toBe(0);
  });
});
