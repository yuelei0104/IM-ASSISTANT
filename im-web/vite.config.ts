import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'
import type { ProxyOptions } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const gateway = loadEnv(mode, process.cwd(), 'VITE_IM_').VITE_IM_GATEWAY_URL
  const proxy: Record<string, string | ProxyOptions> = gateway
    ? { '/api': { target: gateway, ws: true } }
    : {
        '/api/chats': { target: 'http://127.0.0.1:8082', ws: true },
        '/api': 'http://127.0.0.1:8081',
      }
  return {
    plugins: [vue()],
    server: { proxy },
  }
})
