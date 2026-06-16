import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// PWA layer for RF handheld use. The service worker PRECACHES the built app shell (JS/CSS/HTML +
// icons) so a Wi-Fi blip on a rugged scanner never white-screens the device — the SPA boots from
// cache and the picking flow keeps running, queueing confirms locally (see src/picking/offlineQueue).
// API calls are served NetworkFirst with a tiny timeout and are NOT cached stale: picking needs
// fresh data, so on a real network failure we fall through to the offline queue rather than to a
// stale cached queue. registerType 'autoUpdate' rolls new builds out silently (no operator prompt).
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: 'auto',
      includeAssets: ['favicon.png', 'Logo_white_solo.png'],
      manifest: {
        name: 'openWCS',
        short_name: 'openWCS',
        description: 'openWCS — warehouse control system. RF picking on handheld scanners.',
        theme_color: '#0E2820', // --forest-deep
        background_color: '#061812', // --forest-abyss
        display: 'standalone',
        orientation: 'portrait',
        start_url: '/',
        scope: '/',
        icons: [
          { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          // The icons are full-bleed (background to the edges) so they double as maskable.
          { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png', purpose: 'maskable' },
          { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Precache the whole app shell so the SPA boots offline.
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff,woff2}'],
        // The main bundle is ~2.25 MB (3D/topology deps); raise the precache size cap so the WHOLE
        // shell is precached — a partly-precached shell would still white-screen offline, defeating
        // the point. (Code-splitting that bundle is a separate, pre-existing optimisation.)
        maximumFileSizeToCacheInBytes: 5 * 1024 * 1024,
        navigateFallback: '/index.html',
        // Never serve a cached SPA shell for API routes — those go to the network handlers below.
        navigateFallbackDenylist: [/^\/api\//, /^\/realms\//, /^\/admin\//, /^\/resources\//],
        cleanupOutdatedCaches: true,
        clientsClaim: true,
        skipWaiting: true,
        runtimeCaching: [
          {
            // API: always try the network first with a short timeout. We deliberately keep the
            // cache TTL tiny (a few seconds) so picking never acts on stale queue data; on a true
            // outage the app falls through to the durable offline confirm queue, not to old data.
            urlPattern: ({ url }) => url.pathname.startsWith('/api/'),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'owcs-api',
              networkTimeoutSeconds: 4,
              expiration: { maxEntries: 50, maxAgeSeconds: 5 },
              cacheableResponse: { statuses: [200] },
            },
          },
        ],
      },
      devOptions: {
        // Keep the SW out of `npm run dev` (it caches aggressively and confuses HMR); it is only
        // generated for the production build, which is what gets installed on devices.
        enabled: false,
      },
    }),
  ],
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
