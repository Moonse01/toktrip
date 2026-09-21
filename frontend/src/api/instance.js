import axios from 'axios'

const GUEST_ROUTE_PREFIXES = ['/vote/']

function isGuestRoute() {
  return GUEST_ROUTE_PREFIXES.some(prefix => window.location.pathname.startsWith(prefix))
}

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
})

instance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && !isGuestRoute() && !error.config?.skipAuthRedirect) {
      const base = import.meta.env.VITE_API_BASE_URL || ''
      const redirect = `${window.location.pathname}${window.location.search}`
      sessionStorage.setItem('triplan:login-redirect', redirect)
      window.location.href = `${base}/api/v1/auth/kakao/login?redirect=${encodeURIComponent(redirect)}`
    }
    return Promise.reject(error)
  }
)

export default instance
