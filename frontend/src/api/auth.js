import instance from './instance'

// 카카오 로그인 페이지로 이동
export const kakaoLogin = (redirectPath) => {
  const base = import.meta.env.VITE_API_BASE_URL || ''
  const currentPath = `${window.location.pathname}${window.location.search}`
  const safeRedirectPath = typeof redirectPath === 'string' && redirectPath.startsWith('/')
    ? redirectPath
    : currentPath
  sessionStorage.setItem('triplan:login-redirect', safeRedirectPath)
  const redirectQuery = safeRedirectPath ? `?redirect=${encodeURIComponent(safeRedirectPath)}` : ''
  window.location.href = `${base}/api/v1/auth/kakao/login${redirectQuery}`
}

// 내 정보 조회
export const getMe = () =>
  instance.get('/api/v1/users/me/dashboard', { skipAuthRedirect: true })

// 로그아웃
export const logout = () =>
  instance.post('/api/v1/auth/logout')
