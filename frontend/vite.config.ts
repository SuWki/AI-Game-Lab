import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// 本地工作台通过同源代理访问回环后端。
export default defineConfig({
  base: process.env.VITE_BASE_PATH || "/",
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: {
      "/api": { target: "http://127.0.0.1:8080", changeOrigin: false },
      "/sse": { target: "http://127.0.0.1:8080", changeOrigin: false },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});
