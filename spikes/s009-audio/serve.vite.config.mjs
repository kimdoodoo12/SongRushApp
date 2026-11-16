// Serves the spike's release build (build/web) with the same proxy as the
// React dev server, so the page, /ws and /audio share one origin and Spring's
// same-origin WebSocket check passes (changeOrigin stays off, as in
// frontend/vite.config.js). `vite preview` serves the files as they are.
// Run from frontend/ (it has vite installed), with the backend on 8080:
//   npx vite preview --config ../spikes/s009-audio/serve.vite.config.mjs --host
import { fileURLToPath } from 'node:url'

const proxy = {
  '/ws': { target: 'http://localhost:8080', ws: true },
  '/audio': { target: 'http://localhost:8080' },
  '/cover': { target: 'http://localhost:8080' },
}

export default {
  root: fileURLToPath(new URL('.', import.meta.url)),
  build: { outDir: 'build/web' },
  preview: { port: 5180, strictPort: true, proxy },
}
