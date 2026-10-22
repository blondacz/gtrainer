import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Built separately and served only by the opt-in test-classpath loopback harness.
export default defineConfig({
  root: new URL('./synthetic-intake-pilot', import.meta.url).pathname,
  plugins: [react()],
  build: { outDir: '../dist-synthetic-intake-pilot', emptyOutDir: true },
})
