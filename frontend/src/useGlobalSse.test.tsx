import "@testing-library/jest-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "react-query";
import { useGlobalSse, useRunSse, useSseStream } from "@/useGlobalSse";
import { MockEventSource } from "@/test/mockEventSource";

// useGlobalSse 内部调用 useQueryClient()（默认恢复动作为全量 invalidateQueries），
// 因此所有渲染 useGlobalSse 的测试必须包 QueryClientProvider。
function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return { qc, wrapper: ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  ) };
}

function payload(over: Partial<{ dbId: number; eventId: string; type: string; runId: string; payloadJson: string }> = {}) {
  return { dbId: 1, eventId: "e1", type: "RUN_COMPLETED", runId: "1", payloadJson: "{}", ...over };
}

describe("P7-2 SSE hooks", () => {
  beforeEach(() => {
    MockEventSource.reset();
    vi.restoreAllMocks();
  });

  it("useGlobalSse 收到事件后回调并去重（相同 dbId 只触发一次）", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    renderHook(() => useGlobalSse(onEvent), { wrapper });

    const es = MockEventSource.instances[0];
    expect(es.url).toBe("/sse/global");

    act(() => {
      es.emit("RUN_COMPLETED", payload());
      es.emit("RUN_COMPLETED", payload()); // 重复 dbId → 应去重
    });

    await waitFor(() => expect(onEvent).toHaveBeenCalledTimes(1));
  });

  it("useRunSse 订阅 /sse/runs/{id}", () => {
    const { wrapper } = makeWrapper();
    renderHook(() => useRunSse(12, vi.fn()), { wrapper });
    expect(MockEventSource.instances[0].url).toBe("/sse/runs/12");
  });

  it("useRunSse 传 null 时不订阅（空 url）", () => {
    const { wrapper } = makeWrapper();
    renderHook(() => useRunSse(null, vi.fn()), { wrapper });
    expect(MockEventSource.instances.length).toBe(0);
  });

  it("SSE 连续失败超过阈值后 degraded=true（触发降级轮询）", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const { result } = renderHook(() => useSseStream("/sse/global", onEvent), { wrapper });

    const es = MockEventSource.instances[0];
    act(() => {
      es.fail();
      es.fail();
      es.fail();
      es.fail(); // 默认 maxErrors=3 → 第 4 次开始 degraded
    });

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.connected).toBe(false);
  });

  it("SSE 恢复（onopen）后 degraded 复位", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const { result } = renderHook(() => useSseStream("/sse/global", onEvent), { wrapper });

    const es = MockEventSource.instances[0];
    act(() => {
      es.fail();
      es.fail();
      es.fail();
      es.fail();
    });
    await waitFor(() => expect(result.current.degraded).toBe(true));

    act(() => {
      es.onopen?.(new Event("open"));
    });
    await waitFor(() => expect(result.current.degraded).toBe(false));
    expect(result.current.connected).toBe(true);
  });
});

describe("R3-C1 REPLAY_TRUNCATED", () => {
  beforeEach(() => {
    MockEventSource.reset();
    vi.restoreAllMocks();
  });

  it("Truncation: REPLAY_TRUNCATED → truncated=true；且不当作普通业务事件回调 onEvent", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const { result } = renderHook(() => useSseStream("/sse/global", onEvent), { wrapper });

    const es = MockEventSource.instances[0];
    act(() => {
      // 先一个正常领域事件
      es.emit("RUN_COMPLETED", payload());
      // 再推 REPLAY_TRUNCATED（data 形状 = {"hint":"full-refresh"}）
      es.emit("REPLAY_TRUNCATED", { hint: "full-refresh" });
    });

    await waitFor(() => expect(result.current.truncated).toBe(true));
    // onEvent 只收到 RUN_COMPLETED，REPLAY_TRUNCATED 不得进入业务回调
    expect(onEvent).toHaveBeenCalledTimes(1);
  });

  it("Recovery: REPLAY_TRUNCATED → onTruncated 触发一次（默认恢复=全量 invalidateQueries）", async () => {
    const { wrapper, qc } = makeWrapper();
    const spy = vi.spyOn(qc, "invalidateQueries").mockReturnValue(Promise.resolve());
    renderHook(() => useGlobalSse(() => {}), { wrapper });

    const es = MockEventSource.instances[0];
    act(() => es.emit("REPLAY_TRUNCATED", { hint: "full-refresh" }));

    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
  });

  it("Duplicate: 同一次连接内重复 REPLAY_TRUNCATED → onTruncated 只触发一次（防重复恢复）", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const onTruncated = vi.fn();
    const { result } = renderHook(
      () => useSseStream("/sse/global", onEvent, { onTruncated }),
      { wrapper },
    );

    const es = MockEventSource.instances[0];
    act(() => {
      es.emit("REPLAY_TRUNCATED", { hint: "full-refresh" });
      es.emit("REPLAY_TRUNCATED", { hint: "full-refresh" }); // 重复
    });

    await waitFor(() => expect(result.current.truncated).toBe(true));
    expect(onTruncated).toHaveBeenCalledTimes(1);
  });

  it("Reconnect: onopen 后 truncated 复位，新一次截断再次触发恢复（可再消费）", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const onTruncated = vi.fn();
    const { result } = renderHook(
      () => useSseStream("/sse/global", onEvent, { onTruncated }),
      { wrapper },
    );

    const es = MockEventSource.instances[0];
    act(() => es.emit("REPLAY_TRUNCATED", { hint: "full-refresh" }));
    await waitFor(() => expect(result.current.truncated).toBe(true));
    expect(onTruncated).toHaveBeenCalledTimes(1);

    // 断线重连（onopen）→ truncated 复位
    act(() => es.onopen?.(new Event("open")));
    await waitFor(() => expect(result.current.truncated).toBe(false));

    // 重连后又发生一次截断（增量仍超限）→ 再次恢复
    act(() => es.emit("REPLAY_TRUNCATED", { hint: "full-refresh" }));
    await waitFor(() => expect(result.current.truncated).toBe(true));
    expect(onTruncated).toHaveBeenCalledTimes(2);
  });

  it("Disconnect: 断开后既有 degraded 降级轮询机制仍然有效（与 truncation 互不干扰）", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const { result } = renderHook(() => useSseStream("/sse/global", onEvent), { wrapper });

    const es = MockEventSource.instances[0];
    // 先截断
    act(() => es.emit("REPLAY_TRUNCATED", { hint: "full-refresh" }));
    await waitFor(() => expect(result.current.truncated).toBe(true));

    // 再连续断开 → degraded
    act(() => {
      es.fail();
      es.fail();
      es.fail();
      es.fail();
    });
    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.truncated).toBe(true); // 截断状态保留

    // 重连 → 两者都复位（truncated + degraded）
    act(() => es.onopen?.(new Event("open")));
    await waitFor(() => expect(result.current.degraded).toBe(false));
    expect(result.current.truncated).toBe(false);
  });

  it("Cleanup: 页面卸载 → EventSource.close() 被调用、实例清空", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const closeSpy = vi.spyOn(MockEventSource.prototype, "close");
    const { result, unmount } = renderHook(() => useGlobalSse(onEvent), { wrapper });

    expect(MockEventSource.instances).toHaveLength(1);
    act(() => MockEventSource.instances[0].emit("REPLAY_TRUNCATED", { hint: "full-refresh" }));
    await waitFor(() => expect(result.current.truncated).toBe(true));

    unmount();
    expect(closeSpy).toHaveBeenCalledTimes(1);
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it("Invariant: 非 JSON 的 REPLAY_TRUNCATED 负载不崩溃（后端只发 {hint}）", async () => {
    const { wrapper } = makeWrapper();
    const onEvent = vi.fn();
    const { result } = renderHook(() => useSseStream("/sse/global", onEvent), { wrapper });

    const es = MockEventSource.instances[0];
    act(() => es.emit("REPLAY_TRUNCATED", "not-json"));

    await waitFor(() => expect(result.current.truncated).toBe(true));
    expect(onEvent).not.toHaveBeenCalled();
  });
});
