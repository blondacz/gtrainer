import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Separate bundle: the personal application never imports development execution controls.
export default defineConfig({
  root: new URL('./development', import.meta.url).pathname,
  plugins: [react()],
  build: { outDir: '../dist-development', emptyOutDir: true },
  server: { host: '127.0.0.1', strictPort: true, proxy: { '/api': 'http://127.0.0.1:8081', '/healthz': 'http://127.0.0.1:8081' } },
})
