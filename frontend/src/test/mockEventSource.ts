// 最小 EventSource mock（仅覆盖 SSE hook 用到的 onopen/onmessage/onerror/addEventListener/close）。
// 真实 SSE 由后端提供，前端单测只验证 hook 的"订阅 → 分发 → 去重 → 降级"逻辑。
export class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  private listeners: Record<string, Array<(e: MessageEvent) => void>> = {};

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(name: string, handler: (e: MessageEvent) => void) {
    (this.listeners[name] ||= []).push(handler);
  }

  removeEventListener(name: string, handler: (e: MessageEvent) => void) {
    this.listeners[name] = (this.listeners[name] || []).filter((h) => h !== handler);
  }

  close() {
    MockEventSource.instances = MockEventSource.instances.filter((i) => i !== this);
  }

  // 测试辅助：触发一个 named 事件
  emit(name: string, data: unknown) {
    const evt = new MessageEvent("message", { data: JSON.stringify(data) });
    (this.listeners[name] || []).forEach((h) => h(evt));
  }

  // 测试辅助：触发错误（连续触发会触发降级）
  fail() {
    this.onerror?.(new Event("error"));
  }

  static reset() {
    MockEventSource.instances = [];
  }
}
