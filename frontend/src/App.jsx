import { BrowserRouter, Routes, Route, useLocation, useNavigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { getMe, logout as logoutApi } from './api/auth'
import { AuthContext } from './auth/AuthContext'
import { autoEnablePushOnLogin, registerServiceWorker } from './utils/webPush'
import S1LandingPage from './pages/S1LandingPage'
import PlanNewPage from './pages/PlanNewPage'
import S2ResultPage from './pages/S2ResultPage'
import S3VotePage from './pages/S3VotePage'
import S4DashboardPage from './pages/S4DashboardPage'
import S5ConfirmedPage from './pages/S5ConfirmedPage'
import MePage from './pages/MePage'
import SettingsPage from './pages/SettingsPage'

function AuthBootstrap({ setUser }) {
  const location = useLocation()
  const navigate = useNavigate()

  useEffect(() => {
    let cancelled = false

    getMe()
      .then(res => {
        if (cancelled) return

        const authUser = res.data?.authenticated === false ? null : res.data
        setUser(authUser)

        const pendingRedirect = sessionStorage.getItem('triplan:login-redirect')
        if (!authUser || !pendingRedirect) return

        const currentPath = `${location.pathname}${location.search}`
        sessionStorage.removeItem('triplan:login-redirect')
        if (
          pendingRedirect.startsWith('/')
          && !pendingRedirect.startsWith('//')
          && !pendingRedirect.includes('://')
          && currentPath !== pendingRedirect
          && location.pathname === '/'
        ) {
          navigate(pendingRedirect, { replace: true })
        }
      })
      .catch(() => {
        if (!cancelled) setUser(null)
      })

    return () => {
      cancelled = true
    }
  }, [location.pathname, location.search, navigate, setUser])

  return null
}

function App() {
  const [user, setUser] = useState(undefined)

  useEffect(() => {
    if (!user || !user.id) return
    registerServiceWorker().then(() => {
      autoEnablePushOnLogin()
    })
  }, [user])

  const logout = async () => {
    await logoutApi()
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, logout }}>
      <BrowserRouter>
        <AuthBootstrap setUser={setUser} />
        <Routes>
          <Route path="/" element={<S1LandingPage />} />
          <Route path="/new" element={<PlanNewPage />} />
          <Route path="/result/:uuid" element={<S2ResultPage />} />
          <Route path="/vote/:uuid" element={<S3VotePage />} />
          <Route path="/dashboard/:uuid" element={<S4DashboardPage />} />
          <Route path="/confirmed/:uuid" element={<S5ConfirmedPage />} />
          <Route path="/me" element={<MePage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </BrowserRouter>
    </AuthContext.Provider>
  )
}

export default App
