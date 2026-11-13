import "@testing-library/jest-dom";
import { vi } from "vitest";
import { MockEventSource } from "@/test/mockEventSource";

vi.stubGlobal("EventSource", MockEventSource);
