import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  root: new URL('./live-intake-pilot', import.meta.url).pathname,
  plugins: [react()],
  build: { outDir: '../dist-live-intake-pilot', emptyOutDir: true },
})
