import { defineConfig } from "vite";

export default defineConfig({
  clearScreen: false,
  server: { port: 1420, strictPort: true, watch: { ignored: ["**/src-tauri/**", "**/core/**", "**/target/**"] } },
  build: { target: "safari15", outDir: "dist" },
});
