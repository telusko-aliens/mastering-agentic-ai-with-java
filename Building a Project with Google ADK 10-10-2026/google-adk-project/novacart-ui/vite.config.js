import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The dev server runs on 5180 and proxies /api to the backend on 8092.
 *
 * The proxy matters more than it looks: without it the browser calls a different origin and
 * every request becomes a CORS question. With it, the browser only ever talks to one origin,
 * which is also how this is deployed in production, where Spring Boot serves the built files.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5180,
    proxy: {
      '/api': {
        target: 'http://localhost:8092',
        changeOrigin: true,
      },
    },
  },
})
