import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// `base: './'` keeps asset URLs relative, so a production build works from a
// subpath (GitHub Pages project sites) as well as from a domain root.
export default defineConfig({
  plugins: [react()],
  base: './',
})
