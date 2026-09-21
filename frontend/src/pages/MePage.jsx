import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { getMyPlans } from '../api/plans'
import { kakaoLogin } from '../api/auth'
import NavBar from '../components/NavBar'
import BottomTabBar from '../components/BottomTabBar'

export default function MePage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [planCount, setPlanCount] = useState(null)

  useEffect(() => {
    if (!user) return
    getMyPlans()
      .then(res => setPlanCount((res.data.data ?? []).length))
      .catch(() => setPlanCount(0))
  }, [user])

  if (user === undefined) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <p className="text-gray-400 text-sm">불러오는 중...</p>
      </div>
    )
  }

  if (!user) {
    return (
      <div className="min-h-screen bg-gray-50 pb-20">
        <NavBar />
        <div className="max-w-2xl mx-auto px-6 pt-20 text-center">
          <p className="text-4xl mb-4">🔐</p>
          <h2 className="text-lg font-bold text-gray-800 mb-2">로그인이 필요해요</h2>
          <p className="text-sm text-gray-500 mb-6">내 정보를 보려면 카카오 로그인이 필요합니다.</p>
          <button
            onClick={() => kakaoLogin()}
            className="bg-yellow-300 hover:bg-yellow-400 text-gray-900 font-bold py-3 px-6 rounded-xl"
          >
            💬 카카오로 로그인
          </button>
        </div>
        <BottomTabBar />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gray-50 pb-20 md:pb-0">
      <NavBar />

      <div className="max-w-2xl mx-auto px-4 md:px-6 pt-6 md:pt-10">
        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 mb-4 flex items-center gap-4">
          {user.profileImageUrl ? (
            <img
              src={user.profileImageUrl}
              alt="프로필"
              className="w-16 h-16 rounded-full object-cover flex-shrink-0"
            />
          ) : (
            <div className="w-16 h-16 rounded-full bg-primary-100 flex items-center justify-center flex-shrink-0 text-2xl">
              👤
            </div>
          )}
          <div className="min-w-0">
            <p className="text-xs text-gray-400 mb-1">카카오 계정</p>
            <p className="text-lg font-bold text-gray-900 truncate">{user.nickname || '이름 없음'}</p>
          </div>
        </div>

        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <h3 className="text-sm font-bold text-gray-700 mb-3">📊 나의 활동</h3>
          <div className="flex items-center justify-between py-3 border-t border-gray-100">
            <span className="text-sm text-gray-600">총 만든 플랜</span>
            <span className="text-lg font-bold text-primary-500">
              {planCount === null ? '...' : `${planCount}개`}
            </span>
          </div>
        </div>

        <button
          onClick={() => navigate('/')}
          className="hidden md:block w-full mt-6 text-sm text-gray-500 py-3 rounded-xl border border-gray-200 bg-white hover:bg-gray-50"
        >
          ← 홈으로
        </button>
      </div>

      <BottomTabBar />
    </div>
  )
}
