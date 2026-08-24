/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],

  // Dark mode is driven by a class on <html>, set by the theme script in
  // index.html. That script also honours the OS preference, so the three states
  // (explicit light, explicit dark, follow-system) all resolve correctly.
  darkMode: 'class',

  theme: {
    extend: {
      colors: {
        // Neutrals biased slightly blue rather than pure grey, so they read as
        // chosen next to the accent rather than inherited.
        ground: { light: '#F7F8FA', dark: '#0B0E14' },
        surface: { light: '#FFFFFF', dark: '#121722' },
        raised: { light: '#EEF1F6', dark: '#182031' },
        line: { light: '#DCE2EC', dark: '#232C3D' },
        'line-strong': { light: '#C2CBDA', dark: '#33405A' },
        ink: { light: '#10141C', dark: '#EDF1F8' },
        'ink-2': { light: '#414B5E', dark: '#AAB6CA' },
        'ink-3': { light: '#6B7689', dark: '#7A8699' },

        // The orb's own blue, taken from the app.
        accent: { light: '#2E5FD0', dark: '#6E9BFF' },

        // Semantic, kept separate from the accent hue.
        low: { light: '#1F8F66', dark: '#3FB98C' },
        med: { light: '#A9701A', dark: '#E0A33E' },
        high: { light: '#C0432C', dark: '#E0654E' },
      },

      fontFamily: {
        display: ['Fraunces', 'Georgia', 'serif'],
        body: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['"Space Mono"', 'ui-monospace', 'SF Mono', 'Menlo', 'monospace'],
      },

      fontSize: {
        hero: ['clamp(2.6rem, 6.6vw, 4.6rem)', { lineHeight: '1.12', letterSpacing: '-0.022em' }],
        section: ['clamp(1.7rem, 3.4vw, 2.4rem)', { lineHeight: '1.15', letterSpacing: '-0.015em' }],
        lede: ['clamp(1.05rem, 2vw, 1.24rem)', { lineHeight: '1.6' }],
      },

      maxWidth: {
        measure: '66ch',
        shell: '1080px',
      },

      keyframes: {
        breathe: {
          '0%, 100%': { transform: 'scale(1)' },
          '50%': { transform: 'scale(1.07)' },
        },
      },
      animation: {
        breathe: 'breathe 4.2s ease-in-out infinite',
      },
    },
  },

  plugins: [],
}
