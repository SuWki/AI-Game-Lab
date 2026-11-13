import { useEffect, useRef, useState, useCallback } from "react";
import { useQueryClient } from "react-query";
import type { SsePayload } from "@/types";

/**
 * P7-2：全局 SSE Hook + 降级轮询。
 *
 * 设计红线（03 文档 P7-2 / §17）：
 * - 仅消费既有 `/sse/global` 与 `/sse/runs/{id}`（P1-6 已冻结），不修改后端 SSE 协议；
 * - EventSource 封装 + eventId 去重；
 * - 收到事件后由调用方决定是否 refetch（增量刷新）；
 * - SSE 连续失败自动降级为 refetchInterval 轮询，恢复后停止轮询。
 *
 * 语义（项目约定 "首屏 REST 拉取 + 实时 SSE 增量"）：
 * - 首屏数据由 REST list 接口获取（见各 Page 的 useQuery）。
 * - 本 hook 只在收到事件时回调 onEvent，由调用方 invalidateQueries。
 */

/** 后端 SSE 负载去重：以 dbId 去重（lastEventId / id 字段）。 */
function dedupeKey(e: SsePayload): string {
  return String(e.dbId);
}

interface SseStreamState {
  connected: boolean;
  /** SSE 连续失败次数超过阈值后置 true，调用方应降级为轮询。 */
  degraded: boolean;
  /**
   * 服务端 REPLAY_TRUNCATED：断线重连补发超 REPLAY_LIMIT（1000）条被截断，
   * 客户端缓存的增量状态已不可信，须全量刷新（蓝图 §16 "整页刷新"）。
   */
  truncated: boolean;
}

/**
 * 底层 SSE 流订阅（不绑定具体页面）。
 * @param url SSE endpoint 绝对路径（如 /sse/global 或 /sse/runs/12）
 * @param onEvent 事件回调（已去重）
 * @param opts.maxErrors 连续失败多少次触发降级（默认 3）
 */
export function useSseStream(
  url: string,
  onEvent: (e: SsePayload) => void,
  opts: { maxErrors?: number; onTruncated?: () => void } = {},
) {
  const maxErrors = opts.maxErrors ?? 3;
  const [state, setState] = useState<SseStreamState>({
    connected: false,
    degraded: false,
    truncated: false,
  });
  const cbRef = useRef(onEvent);
  cbRef.current = onEvent;
  const cbTruncatedRef = useRef(opts.onTruncated ?? (() => {}));
  cbTruncatedRef.current = opts.onTruncated ?? (() => {});
  const seen = useRef<Set<string>>(new Set());
  const errorCount = useRef(0);
  /** REPLAY_TRUNCATED 每次连接只消费一次（防重复恢复）。 */
  const truncatedFired = useRef(false);

  useEffect(() => {
    // 防御：非浏览器 / 测试 / SSR 环境无 EventSource 时不连接（不修改后端行为）
    // url 为空时也不订阅（useRunSse(null) 等场景）
    if (typeof EventSource === "undefined" || !url) {
      return;
    }

    truncatedFired.current = false;
    const es = new EventSource(url);
    es.onopen = () => {
      truncatedFired.current = false;
      errorCount.current = 0;
      setState({ connected: true, degraded: false, truncated: false });
    };
    es.onerror = () => {
      errorCount.current += 1;
      const degraded = errorCount.current > maxErrors;
      setState((prev) => ({ connected: false, degraded, truncated: prev.truncated }));
    };

    // 兜底注释事件（后端 "connected ..." 系列）
    es.onmessage = (ev) => {
      if (ev.data && ev.data.includes("connected")) return;
    };

    // 领域事件以 named event 推送：监听所有可能事件名
    const named = [
      "TASK_CREATED",
      "RUN_STARTED",
      "STEP_STARTED",
      "LLM_REQUEST_SENT",
      "LLM_RESPONSE_RECEIVED",
      "TOOL_CALLED",
      "TOOL_RETURNED",
      "SIMULATION_COMPLETED",
      "PROPOSAL_CREATED",
      "APPROVAL_REQUIRED",
      "CONFIG_VERSION_CREATED",
      "PROPOSAL_REJECTED",
      "RUN_RESUMED",
      "RUN_CANCELLED",
      "RUN_COMPLETED",
      "RUN_FAILED",
      "RUN_TIMEOUT",
      "TEST_PLAN_CREATED",
      "TEST_PLAN_ACTIVATED",
      "GAME_SESSION_STARTED",
      "INVARIANT_VIOLATED",
      "FINDING_CREATED",
      "TEST_REPORT_CREATED",
      "SOURCE_FETCHED",
      "CLAIM_EXTRACTED",
      "CLAIM_VALIDATED",
      "RESEARCH_REPORT_CREATED",
    ];

    // REPLAY_TRUNCATED 元事件：data 形状 = {"hint":"full-refresh"}，非 SsePayload。
    // 它不属于任何业务域——不得回调 onEvent；只更新同步状态并触发一次恢复动作。
    const truncatedHandler = () => {
      if (truncatedFired.current) return; // 同一次连接内防重复消费
      truncatedFired.current = true;
      setState((prev) => ({ ...prev, truncated: true }));
      cbTruncatedRef.current();
    };
    es.addEventListener("REPLAY_TRUNCATED", truncatedHandler as EventListener);

    const handlers: Array<[string, (e: MessageEvent) => void]> = named.map((name) => {
      const h = (e: MessageEvent) => {
        try {
          const data = JSON.parse(e.data) as SsePayload;
          const key = dedupeKey(data);
          if (seen.current.has(key)) return;
          // 限制内存：保留最近 4096 个去重键
          if (seen.current.size > 4096) seen.current.clear();
          seen.current.add(key);
          cbRef.current(data);
        } catch {
          /* 非 JSON 负载忽略 */
        }
      };
      es.addEventListener(name, h as EventListener);
      return [name, h];
    });

    return () => {
      es.removeEventListener("REPLAY_TRUNCATED", truncatedHandler as EventListener);
      handlers.forEach(([name, h]) => es.removeEventListener(name, h as EventListener));
      es.close();
      setState({ connected: false, degraded: false, truncated: false });
    };
  }, [url, maxErrors]);

  return state;
}

/**
 * 订阅 `/sse/global`（后端既有能力，本 hook 只读消费）。
 *
 * 默认恢复动作：REPLAY_TRUNCATED 代表全局增量已截断（超 REPLAY_LIMIT 丢失）→
 * 全量刷新所有 React Query 查询（蓝图 §16 "整页刷新"的缓存等价物）。
 * 调用方可传 opts.onTruncated 覆盖默认动作。
 */
export function useGlobalSse(
  onEvent: (e: SsePayload) => void,
  opts?: { onTruncated?: () => void },
) {
  const qc = useQueryClient();
  return useSseStream("/sse/global", onEvent, {
    ...opts,
    onTruncated: opts?.onTruncated ?? (() => qc.invalidateQueries()),
  });
}

/**
 * 订阅 `/sse/runs/{id}`（P1-6 既有能力）。
 * REPLAY_TRUNCATED 只会出现在该端点（SseController.replay 超限路径）。
 */
export function useRunSse(
  runId: number | null,
  onEvent: (e: SsePayload) => void,
  opts?: { onTruncated?: () => void },
) {
  return useSseStream(runId == null ? "" : `/sse/runs/${runId}`, onEvent, opts);
}

/**
 * 结合 React Query 的便捷 hook：SSE 驱动 invalidate，SSE 降级时自动轮询。
 *
 * @param queryKey 受 SSE 事件控制的 React Query key（如 ["findings"]）
 * @param filter 事件过滤：只有匹配的事件才触发 invalidate（避免无关事件频繁刷新）
 * @param pollMs 降级轮询间隔（默认 5000ms）
 */
export function useSseDrivenRefresh(
  queryKey: unknown[],
  filter: (e: SsePayload) => boolean,
  pollMs = 5000,
) {
  const qc = useQueryClient();
  const [fallbackPoll, setFallbackPoll] = useState(false);

  const onEvent = useCallback(
    (e: SsePayload) => {
      if (filter(e)) {
        qc.invalidateQueries({ queryKey });
      }
    },
    [qc, queryKey, filter],
  );

  // onTruncated 默认由 useGlobalSse 提供（全量 invalidateQueries），
  // 这里只需把 truncated 状态透传给调用方做提示/其它处理。
  const { degraded, truncated } = useGlobalSse(onEvent);

  // SSE 降级 → 开启轮询；SSE 恢复 → 停止轮询
  useEffect(() => {
    setFallbackPoll(degraded);
  }, [degraded]);

  return { degraded, truncated, fallbackPoll, pollMs };
}

/**
 * 基于 TEST_* 事件触发 refetch 的便捷 hook。
 * 仅当事件类型属于 Tester 相关域时调用 onSignal，避免无关事件频繁刷新。
 * 返回 SSE 连接状态 + truncated 状态，供调用方实现"断线时降级轮询"和"截断时提示刷新"。
 */
export function useTesterEventSignal(onSignal: () => void) {
  const isTesterEvent = (type: string) =>
    type.startsWith("TEST_") ||
    type === "INVARIANT_VIOLATED" ||
    type === "GAME_SESSION_STARTED" ||
    type === "FINDING_CREATED";

  const state = useGlobalSse((e) => {
    if (isTesterEvent(e.type)) onSignal();
  });

  return { connected: state.connected, truncated: state.truncated };
}
