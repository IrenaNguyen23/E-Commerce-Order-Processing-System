import path from 'node:path';

import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  // Use the same .env as Compose. Docker builds receive only public VITE_* build args.
  const envDir = path.resolve(__dirname, '..');
  const env = loadEnv(mode, envDir, 'VITE_');
  const gateway = env.VITE_API_PROXY_TARGET;

  return {
    envDir,
    plugins: [react()],

    resolve: {
      alias: { '@': path.resolve(__dirname, './src') },
    },

    server: {
      port: Number(env.VITE_DEV_PORT) || 5173,
      // Proxying in dev keeps the browser same-origin, so no CORS preflight and no divergence
      // from production, where nginx serves the bundle and forwards /api to the gateway.
      proxy: {
        '/api': { target: gateway, changeOrigin: true },
        '/actuator': { target: gateway, changeOrigin: true },
      },
    },

    preview: { port: Number(env.VITE_PREVIEW_PORT) || 4173 },

    build: {
      target: 'es2022',
      sourcemap: mode !== 'production',
      // Warn early: any single chunk over 600 kB means a route forgot to lazy-load.
      chunkSizeWarningLimit: 600,
      rollupOptions: {
        output: {
          // Vendors split by change cadence, so editing a feature does not invalidate the
          // whole vendor cache for returning visitors.
          manualChunks: {
            'vendor-react': ['react', 'react-dom'],
            'vendor-router': ['react-router-dom'],
            'vendor-query': ['@tanstack/react-query'],
            'vendor-forms': ['react-hook-form', '@hookform/resolvers', 'zod'],
          },
        },
      },
    },
  };
});
