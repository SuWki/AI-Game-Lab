import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// 后端地址：开发期由 Vite 代理 /api 与 /sse 到 :8080，生产由网关统一暴露。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/sse": "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});
