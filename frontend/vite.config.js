import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Tests that read the file system through import.meta.url run in the node
// environment: under jsdom, Vite's client transform rewrites
// new URL('..', import.meta.url) into a location-based http URL.
const NODE_TESTS = ['src/noTypeScript.test.js', 'src/i18n/keys.test.js', 'src/roomCss.test.js', 'src/indexCss.test.js', 'src/wideCss.test.js', 'src/startCss.test.js', 'src/choiceCss.test.js', 'src/scrollCss.test.js']

// The dev server proxies to the Spring Boot backend on port 8080.
// changeOrigin is deliberately left off: the browser's Origin header must
// stay the dev-server origin so Spring's same-origin WebSocket check passes
// (cycle 037 review).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/ws': { target: 'http://localhost:8080', ws: true },
      '/audio': { target: 'http://localhost:8080' },
      '/cover': { target: 'http://localhost:8080' },
    },
  },
  test: {
    environment: 'jsdom',
    projects: [
      {
        extends: true,
        test: {
          name: 'dom',
          include: ['src/**/*.test.{js,jsx}'],
          exclude: NODE_TESTS,
        },
      },
      {
        extends: true,
        test: {
          name: 'node',
          environment: 'node',
          include: NODE_TESTS,
        },
      },
    ],
  },
})
