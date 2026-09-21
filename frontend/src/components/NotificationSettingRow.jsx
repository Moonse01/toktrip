import { useEffect, useState } from 'react'
import {
  disablePush,
  enablePush,
  getPushState,
  isIos,
  isPushSupported,
  isStandalone,
} from '../utils/webPush'

export default function NotificationSettingRow() {
  const [state, setState] = useState('loading')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [showHowToUnblock, setShowHowToUnblock] = useState(false)

  useEffect(() => {
    let cancelled = false
    getPushState().then((s) => {
      if (!cancelled) setState(s)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const refresh = async () => setState(await getPushState())

  const handleEnable = async () => {
    setBusy(true)
    setError('')
    try {
      await enablePush()
      await refresh()
    } catch (e) {
      setError(e.message || '알림을 켜지 못했어요.')
    } finally {
      setBusy(false)
    }
  }

  const handleDisable = async () => {
    setBusy(true)
    setError('')
    try {
      await disablePush()
      await refresh()
    } catch (e) {
      setError(e.message || '알림을 끄지 못했어요.')
    } finally {
      setBusy(false)
    }
  }

  if (state === 'loading') return null

  const subscribed = state === 'granted-subscribed'
  const denied = state === 'denied'
  const unsupported = state === 'unsupported'
  const needsIosInstall = isPushSupported() && isIos() && !isStandalone()

  return (
    <div className="px-5 py-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="text-sm text-gray-700 font-medium">🔔 푸시 알림</div>
          <div className="text-xs text-gray-400 mt-0.5">
            최종 일정 확정 / 출발 하루 전 정오에 알려드려요
          </div>
        </div>

        {/* 활성 상태별 버튼 */}
        {!unsupported && !needsIosInstall && !denied && (
          <button
            onClick={subscribed ? handleDisable : handleEnable}
            disabled={busy}
            className={`shrink-0 text-sm px-4 py-2 rounded-full border font-medium transition-colors ${
              subscribed
                ? 'border-gray-200 text-gray-500 hover:bg-gray-50'
                : 'border-primary-500 bg-primary-500 text-white hover:bg-primary-600'
            } disabled:opacity-50`}
          >
            {busy ? '...' : subscribed ? '끄기' : '알림 받기'}
          </button>
        )}

        {/* 차단된 경우: 안내 토글 버튼 */}
        {denied && (
          <button
            onClick={() => setShowHowToUnblock((v) => !v)}
            className="shrink-0 text-sm px-4 py-2 rounded-full border border-orange-400 text-orange-500 hover:bg-orange-50 font-medium"
          >
            허용하는 법
          </button>
        )}
      </div>

      {unsupported && (
        <p className="text-xs text-gray-400 mt-3">
          이 브라우저는 푸시 알림을 지원하지 않아요. Chrome, Edge, Firefox 등을 사용해 주세요.
        </p>
      )}

      {denied && (
        <div className="mt-3 text-xs text-gray-500 bg-orange-50 border border-orange-100 rounded-xl p-3">
          <p className="text-orange-700 font-medium mb-1">⚠️ 알림이 차단된 상태예요</p>
          <p>한 번 거부하면 브라우저 정책상 자동으로 다시 물어볼 수 없어요. 직접 허용해 주셔야 해요.</p>
          {showHowToUnblock && (
            <div className="mt-3 space-y-2">
              <div>
                <p className="font-medium text-gray-700">📱 모바일 (Chrome / Samsung Internet)</p>
                <p className="text-gray-500">
                  주소창 왼쪽 자물쇠 🔒 (또는 ⓘ) 아이콘 → 권한 → 알림 → <strong>허용</strong>
                </p>
              </div>
              <div>
                <p className="font-medium text-gray-700">💻 데스크탑 Chrome</p>
                <p className="text-gray-500">
                  주소창 왼쪽 자물쇠 🔒 → 사이트 설정 → 알림 → <strong>허용</strong> 으로 변경 → 페이지 새로고침
                </p>
              </div>
              <div>
                <p className="font-medium text-gray-700">🦊 Firefox</p>
                <p className="text-gray-500">
                  주소창 왼쪽 자물쇠 🔒 → 권한 옆 차단 표시 ❌ 클릭하여 제거 → 새로고침
                </p>
              </div>
              <p className="text-gray-400 mt-2">설정 변경 후 이 페이지를 새로고침하면 "알림 받기" 버튼이 나타나요.</p>
            </div>
          )}
        </div>
      )}

      {needsIosInstall && (
        <div className="mt-3 text-xs text-gray-500 bg-blue-50 border border-blue-100 rounded-xl p-3">
          <p className="text-blue-700 font-medium mb-1">📱 아이폰 사용 안내</p>
          <p>
            iOS Safari는 <strong>"홈 화면에 추가"</strong> 후 그 아이콘으로 열어야 알림을 받을 수 있어요.
          </p>
          <p className="mt-2 text-gray-500">
            Safari 하단 공유 버튼 <strong>⬆️</strong> → "홈 화면에 추가" → 추가된 아이콘으로 다시 접속
          </p>
        </div>
      )}

      {error && <p className="text-xs text-red-500 mt-2">{error}</p>}
    </div>
  )
}