import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    allowedHosts:true,
    port: 5173,
    proxy: {
      // 开发环境将 /api 代理到本地 Java 后端（server/ 模块）
      '/api': 'http://localhost:8080',
    },
  },
});
