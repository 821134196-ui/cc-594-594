import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// 开发：/api 代理到 Quarkus 8080；
// 构建：产物直接写入后端静态资源目录，随 jar 同源提供（生产环境只有一个端口）。
export default defineConfig({
  plugins: [svelte()],
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  build: {
    outDir: '../backend/src/main/resources/META-INF/resources',
    emptyOutDir: true
  }
});
