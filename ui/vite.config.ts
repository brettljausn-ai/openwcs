import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080', // -> API gateway
      '/realms': 'http://localhost:8180', // -> Keycloak (login / OIDC)
      '/admin': 'http://localhost:8180', // -> Keycloak admin REST (user management)
      '/resources': 'http://localhost:8180', // -> Keycloak assets
    },
  },
})
