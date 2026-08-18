import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Built assets are served by Quarkus/Quinoa. In `quarkus dev`, Quinoa proxies
// the Vite dev server and forwards /api calls to the backend on port 8080.
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
