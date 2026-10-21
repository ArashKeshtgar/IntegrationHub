/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  // The admin web is :5701 (CLAUDE.md); the redirect URI registered in ClinicHub depends on it.
  server: { port: 5701, strictPort: true, host: '127.0.0.1' },
  preview: { port: 5701, strictPort: true, host: '127.0.0.1' },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
