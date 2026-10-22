import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api/chats': { target: 'http://127.0.0.1:8082', ws: true },
      '/api': 'http://127.0.0.1:8081',
    },
  },
})
