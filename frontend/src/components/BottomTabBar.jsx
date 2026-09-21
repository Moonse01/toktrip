import { useLocation, useNavigate } from 'react-router-dom'

const TABS = [
  { path: '/', icon: '🏠', label: '홈' },
  { path: '/new', icon: '➕', label: '새 플랜' },
  { path: '/me', icon: '👤', label: '내정보' },
  { path: '/settings', icon: '⚙️', label: '설정' },
]

export default function BottomTabBar() {
  const location = useLocation()
  const navigate = useNavigate()

  return (
    <nav className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-white border-t border-gray-200 shadow-[0_-2px_8px_rgba(0,0,0,0.05)]">
      <ul className="flex">
        {TABS.map(tab => {
          const isActive = location.pathname === tab.path
          return (
            <li key={tab.path} className="flex-1">
              <button
                onClick={() => navigate(tab.path)}
                className={`w-full flex flex-col items-center justify-center gap-0.5 py-2.5 transition-colors ${
                  isActive ? 'text-accent-500' : 'text-gray-400'
                }`}
              >
                <span className="text-lg leading-none">{tab.icon}</span>
                <span className="text-[10px] font-medium">{tab.label}</span>
              </button>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
