import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Single source of truth: the Maven project version drives the displayed app version.
const pom = readFileSync(fileURLToPath(new URL('../../../pom.xml', import.meta.url)), 'utf8');
const appVersion = pom.match(/<artifactId>wallet-viewer<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1] ?? '0.0.0';

// Built assets are served by Quarkus/Quinoa. In `quarkus dev`, Quinoa proxies
// the Vite dev server and forwards /api calls to the backend on port 8080.
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  base: './',
  define: {
    __APP_VERSION__: JSON.stringify(appVersion),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', ws: true },
    },
  },
});
