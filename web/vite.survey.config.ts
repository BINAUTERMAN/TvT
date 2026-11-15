import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({
  plugins: [vue()],
  publicDir: false,
  build: {
    outDir: '../build/survey-web',
    emptyOutDir: true,
    rollupOptions: { input: 'web/survey.html' },
  },
});
