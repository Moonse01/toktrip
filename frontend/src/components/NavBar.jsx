import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { kakaoLogin } from '../api/auth'

export default function NavBar({ children, title }) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/')
  }

  return (
    <nav className="relative bg-white border-b border-gray-100 px-6 py-4 flex justify-between items-center sticky top-0 z-30">
      <span
        onClick={() => navigate('/')}
        className="text-lg font-bold text-gray-800 cursor-pointer hover:text-primary-500 transition-colors"
      >
        🗺️ TokTrip
      </span>

      {title && (
        <button
          onClick={() => navigate('/')}
          title="홈으로"
          className="hidden md:block absolute left-1/2 -translate-x-1/2 max-w-[40%] truncate text-base md:text-lg font-bold text-gray-800 hover:text-primary-500 transition-colors"
        >
          {title}
        </button>
      )}

      <div className="hidden md:flex items-center gap-3">
        {children}
        {user ? (
          <>
            {user.profileImageUrl && (
              <img src={user.profileImageUrl} alt="프로필" className="w-8 h-8 rounded-full object-cover" />
            )}
            <span className="text-sm text-gray-700 font-medium">{user.nickname}</span>
            <button
              onClick={handleLogout}
              className="bg-gray-100 hover:bg-gray-200 text-gray-600 font-medium px-3 py-2 rounded-lg text-sm transition-colors"
            >
              로그아웃
            </button>
          </>
        ) : (
          <button
            onClick={() => kakaoLogin()}
            className="bg-yellow-400 hover:bg-yellow-500 text-gray-900 font-bold px-4 py-2 rounded-lg text-sm transition-colors"
          >
            카카오 로그인
          </button>
        )}
      </div>
    </nav>
  )
}
