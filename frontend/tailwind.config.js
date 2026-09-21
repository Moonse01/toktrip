/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // PC 파스텔톤 (기존 primary — md: 이상에서 사용)
        primary: {
          50:  '#FFF6F2',
          100: '#FFE8DF',
          200: '#FFD0C1',
          300: '#FFB5A0',
          400: '#F59478',
          500: '#E87B5E',
          600: '#D0624A',
          700: '#B04E3A',
        },
        // 모바일 쨍한 색감 (v2.2 기조 — 모바일 기본에서 사용)
        accent: {
          50:  '#FEF2F2',
          100: '#FEE2E2',
          200: '#FECACA',
          300: '#FCA5A5',
          400: '#F87171',
          500: '#EF4444',
          600: '#DC2626',
          700: '#B91C1C',
        },
      },
    },
  },
  plugins: [],
}
