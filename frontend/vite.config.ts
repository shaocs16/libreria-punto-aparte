import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// El build se sirve desde el propio Spring Boot (mismo origen, sin CORS).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../backend/src/main/resources/static",
    emptyOutDir: true,
    chunkSizeWarningLimit: 900,
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      // Spring valida Host y Origin: el proxy los presenta como el propio origen del backend.
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyReq", (req) => {
            if (req.getHeader("origin"))
              req.setHeader("origin", "http://localhost:8080");
          });
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["tests/unit/setup.ts"],
    include: ["tests/unit/**/*.test.{ts,tsx}"],
  },
});
