import { fetchVapidPublicKey, subscribePush, unsubscribePush } from '../api/notifications'

const SUBSCRIBED_KEY = 'triplan:push-subscribed'
const AUTO_PROMPT_KEY = 'triplan:push-auto-prompted'

function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4)
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(base64)
  const output = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) output[i] = raw.charCodeAt(i)
  return output
}

function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
  return btoa(binary)
}

export function isPushSupported() {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

export function isStandalone() {
  return (
    window.matchMedia?.('(display-mode: standalone)').matches ||
    window.navigator.standalone === true
  )
}

export function isIos() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent)
}

export async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return null
  try {
    const reg = await navigator.serviceWorker.register('/sw.js')
    return reg
  } catch (e) {
    console.warn('[push] SW 등록 실패', e)
    return null
  }
}

/**
 * 현재 권한 상태 + 구독 여부 조회.
 * 'unsupported' | 'denied' | 'granted-subscribed' | 'granted-unsubscribed' | 'default'
 */
export async function getPushState() {
  if (!isPushSupported()) return 'unsupported'
  if (Notification.permission === 'denied') return 'denied'
  if (Notification.permission !== 'granted') return 'default'
  try {
    const reg = await navigator.serviceWorker.ready
    const sub = await reg.pushManager.getSubscription()
    return sub ? 'granted-subscribed' : 'granted-unsubscribed'
  } catch {
    return 'granted-unsubscribed'
  }
}

/**
 * 권한 요청 → 구독 생성 → 백엔드 저장까지 한 번에.
 * 이미 구독돼 있으면 그 구독을 그대로 백엔드에 동기화.
 */
export async function enablePush() {
  if (!isPushSupported()) throw new Error('이 브라우저는 푸시 알림을 지원하지 않아요.')
  if (isIos() && !isStandalone()) {
    throw new Error('아이폰은 "홈 화면에 추가" 후 사용할 수 있어요.')
  }

  const permission = await Notification.requestPermission()
  if (permission !== 'granted') throw new Error('알림 권한이 필요해요.')

  const reg = (await navigator.serviceWorker.ready) || (await registerServiceWorker())
  if (!reg) throw new Error('Service Worker 등록에 실패했어요.')

  const publicKey = await fetchVapidPublicKey()
  if (!publicKey) throw new Error('서버 푸시 설정이 준비되지 않았어요.')

  let sub = await reg.pushManager.getSubscription()
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(publicKey),
    })
  }

  const json = sub.toJSON()
  await subscribePush({
    endpoint: sub.endpoint,
    p256dh: json.keys?.p256dh || arrayBufferToBase64(sub.getKey('p256dh')),
    auth: json.keys?.auth || arrayBufferToBase64(sub.getKey('auth')),
  })
  localStorage.setItem(SUBSCRIBED_KEY, '1')
  return true
}

export async function disablePush() {
  try {
    const reg = await navigator.serviceWorker.ready
    const sub = await reg.pushManager.getSubscription()
    if (sub) {
      await unsubscribePush({ endpoint: sub.endpoint }).catch(() => {})
      await sub.unsubscribe()
    }
  } finally {
    localStorage.removeItem(SUBSCRIBED_KEY)
  }
}

export function hasUserOptedIn() {
  return localStorage.getItem(SUBSCRIBED_KEY) === '1'
}

/**
 * 로그인 직후 자동 호출. 한 브라우저당 1회만 권한 요청을 시도하고
 * 결과(허용/거부) 무관하게 플래그를 남겨 재요청을 막는다.
 * 아이폰 미설치 PWA 상태는 권한 자체가 요청되지 않으므로 플래그도 남기지 않음.
 */
export async function autoEnablePushOnLogin() {
  if (!isPushSupported()) return
  if (Notification.permission === 'denied') return
  if (isIos() && !isStandalone()) return
  if (localStorage.getItem(AUTO_PROMPT_KEY) === '1') {
    // 이미 시도한 적이 있더라도, 권한이 허용된 상태이고 구독이 비어 있으면 조용히 동기화
    if (Notification.permission === 'granted') {
      try {
        const reg = await navigator.serviceWorker.ready
        const existing = await reg.pushManager.getSubscription()
        if (!existing) await enablePush().catch(() => {})
      } catch {
        // ignore
      }
    }
    return
  }
  try {
    await enablePush()
  } catch {
    // 거부/실패해도 사용자 흐름을 막지 않는다
  } finally {
    localStorage.setItem(AUTO_PROMPT_KEY, '1')
  }
}