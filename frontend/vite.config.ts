import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The build lands inside the Spring Boot jar (target/classes/static), so the app and the API share one
// origin: no CORS, first-party session cookie. During `npm run dev`, API calls go to the local backend.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
