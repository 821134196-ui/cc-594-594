import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// 构建产物直接输出到 Quarkus 静态资源目录：打包后由后端单端口提供页面。
// 开发时通过 proxy 把 /api 转发到 8080 的 Quarkus。
export default defineConfig({
  plugins: [svelte()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080'
    }
  },
  build: {
    outDir: '../src/main/resources/META-INF/resources',
    emptyOutDir: true
  }
});
