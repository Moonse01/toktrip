import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { kakaoLogin } from '../api/auth'
import NavBar from '../components/NavBar'
import BottomTabBar from '../components/BottomTabBar'
import NotificationSettingRow from '../components/NotificationSettingRow'

const APP_VERSION = 'v2.4'

export default function SettingsPage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/')
  }

  return (
    <div className="min-h-screen bg-gray-50 pb-20 md:pb-0">
      <NavBar />

      <div className="max-w-2xl mx-auto px-4 md:px-6 pt-6 md:pt-10">
        <h2 className="text-base font-bold text-gray-800 mb-3">⚙️ 설정</h2>

        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 divide-y divide-gray-100">
          <div className="flex items-center justify-between px-5 py-4">
            <span className="text-sm text-gray-700">앱 버전</span>
            <span className="text-sm text-gray-400">{APP_VERSION}</span>
          </div>

          {user && <NotificationSettingRow />}

          {user ? (
            <button
              onClick={handleLogout}
              className="w-full text-left px-5 py-4 text-sm text-red-500 hover:bg-red-50 transition-colors"
            >
              로그아웃
            </button>
          ) : (
            <button
              onClick={() => kakaoLogin()}
              className="w-full text-left px-5 py-4 text-sm text-primary-500 hover:bg-primary-50 transition-colors"
            >
              💬 카카오로 로그인
            </button>
          )}
        </div>

        <p className="text-center text-xs text-gray-400 mt-8">
          🗺️ TokTrip
        </p>
      </div>

      <BottomTabBar />
    </div>
  )
}
