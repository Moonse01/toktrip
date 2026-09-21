/**
 * S5ConfirmedPage.jsx
 *
 * 🎉 S5 — 투표 기반 추천 일정 화면
 *
 * S2 PlanViewLayout과 동일한 카카오맵 SDK 연동.
 * 좌측: 1컬럼 세로 타임라인 (고정 높이 + 스크롤)
 * 우측: 카카오맵 (Day 탭 전환 시 핀/동선 변경)
 *
 * 라우트: /confirmed/:planId
 */

import { useState, useEffect, useMemo, useRef, useCallback } from 'react'
import { useParams } from 'react-router-dom'
import { QRCodeSVG } from 'qrcode.react'
import NavBar from '../components/NavBar'
import BottomTabBar from '../components/BottomTabBar'
import DeletePlanSection from '../components/DeletePlanSection'
import VoteCardModal from '../components/VoteCardModal'
import useIsMobile from '../hooks/useIsMobile'
import { deletePlace, getFinalPlan, getPlan, updatePlaceOrders } from '../api/plans'
import { useAuth } from '../auth/AuthContext'
import { transformCandidate, transformPlan } from '../utils/planTransforms'
import { buildConfirmedInviteText, shareConfirmedPlan } from '../utils/kakaoShare'
import {
  DndContext,
  DragOverlay,
  MouseSensor,
  TouchSensor,
  pointerWithin,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'

// ── 상수 ──────────────────────────────────────────────────────
const CATEGORY_CONFIG = {
  '관광':     { emoji: '🏛', color: 'bg-blue-100 text-blue-700' },
  '관광지':   { emoji: '🏛', color: 'bg-blue-100 text-blue-700' },
  '맛집':     { emoji: '🍽', color: 'bg-amber-100 text-amber-700' },
  '식당':     { emoji: '🍽', color: 'bg-amber-100 text-amber-700' },
  '카페':     { emoji: '☕', color: 'bg-pink-100 text-pink-700' },
  '숙소':     { emoji: '🏨', color: 'bg-purple-100 text-purple-700' },
  '쇼핑':     { emoji: '🛍', color: 'bg-yellow-100 text-yellow-700' },
  '액티비티': { emoji: '🎯', color: 'bg-green-100 text-green-700' },
  '자연':     { emoji: '🌿', color: 'bg-teal-100 text-teal-700' },
  '공원':     { emoji: '🌿', color: 'bg-teal-100 text-teal-700' },
}

// 추천 일정은 단일 안이라 Day별로 색을 나누지 않고 브랜드 색 하나로 통일한다.
const DESKTOP_DAY_COLOR = '#E87B5E'
const MOBILE_DAY_COLOR = '#EF4444'

function getCategoryStyle(c) {
  return CATEGORY_CONFIG[c] || { emoji: '📍', color: 'bg-gray-100 text-gray-600' }
}

function formatCost(cost) {
  if (!cost || cost === 0) return '무료'
  return cost >= 10000 ? `${(cost / 10000).toFixed(0)}만원` : `${cost.toLocaleString()}원`
}

function formatPerPersonCost(n) {
  if (!n) return '0원'
  return n >= 10000 ? `${(n / 10000).toFixed(0)}만원` : `${n.toLocaleString()}원`
}

function getWeekday(dateStr, dayOffset) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  d.setDate(d.getDate() + dayOffset)
  const days = ['일', '월', '화', '수', '목', '금', '토']
  return `${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} (${days[d.getDay()]})`
}

function formatDateRange(startDate, endDate) {
  if (!startDate || !endDate) return '날짜 미정'
  return `${startDate.slice(5)}~${endDate.slice(5)}`
}

function buildConfirmedData(plan, candidate) {
  return {
    ...plan,
    status: 'CONFIRMED',
    participant_count: plan.participant_count || 0,
    confirmed_candidate: candidate,
    weather: [],
    excluded_place: null,
  }
}

function hasValidCoordinate(place) {
  if (place?.lat === null || place?.lat === undefined || place?.lng === null || place?.lng === undefined) {
    return false
  }
  const lat = Number(place.lat)
  const lng = Number(place.lng)
  return Number.isFinite(lat)
    && Number.isFinite(lng)
    && lat >= -90
    && lat <= 90
    && lng >= -180
    && lng <= 180
    && !(lat === 0 && lng === 0)
}

function distanceMeters(a, b) {
  const lat1 = Number(a?.lat)
  const lng1 = Number(a?.lng)
  const lat2 = Number(b?.lat)
  const lng2 = Number(b?.lng)
  if (![lat1, lng1, lat2, lng2].every(Number.isFinite)) return Infinity

  const radius = 6371000
  const dLat = (lat2 - lat1) * Math.PI / 180
  const dLng = (lng2 - lng1) * Math.PI / 180
  const rLat1 = lat1 * Math.PI / 180
  const rLat2 = lat2 * Math.PI / 180
  const hav =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLng / 2) ** 2
  return radius * 2 * Math.atan2(Math.sqrt(hav), Math.sqrt(1 - hav))
}

function pathDistanceMeters(path) {
  let total = 0
  for (let i = 0; i < path.length - 1; i += 1) {
    total += distanceMeters(path[i], path[i + 1])
  }
  return total
}

function isReasonableRoute(path, origin, dest) {
  if (!Array.isArray(path) || path.length < 2) return false
  if (path.some(p => !Number.isFinite(Number(p.lat)) || !Number.isFinite(Number(p.lng)))) {
    return false
  }

  const directDistance = distanceMeters(origin, dest)
  if (!Number.isFinite(directDistance) || directDistance > 80000) {
    return false
  }
  const routeDistance = pathDistanceMeters(path)
  const maxAllowedDistance = Math.max(20000, directDistance * 5)
  return Number.isFinite(routeDistance)
    && routeDistance <= maxAllowedDistance
    && path.every(p => {
      const lat = Number(p.lat)
      const lng = Number(p.lng)
      return lat >= 32.8 && lat <= 38.8 && lng >= 124.5 && lng <= 132.0
    })
}

function toKakaoPath(path) {
  return path.map(p => new window.kakao.maps.LatLng(Number(p.lat), Number(p.lng)))
}

function dayPinColor(_day, isMobile) {
  return isMobile ? MOBILE_DAY_COLOR : DESKTOP_DAY_COLOR
}

function buildFinalReorder(candidate, fromPlaceId, toDayNumber, toOrderIndex) {
  if (!candidate) return { changed: false }

  const moved = candidate.places.find(place => Number(place.id) === Number(fromPlaceId))
  if (!moved) return { changed: false }

  const targetDay = Math.max(1, Number(toDayNumber) || moved.day_number || 1)
  const remaining = candidate.places.filter(place => Number(place.id) !== Number(fromPlaceId))
  const targetDayPlaces = remaining
    .filter(place => Number(place.day_number) === targetDay)
    .sort((a, b) => Number(a.order_index || 0) - Number(b.order_index || 0))
  const otherPlaces = remaining.filter(place => Number(place.day_number) !== targetDay)

  const insertAt = Math.max(0, Math.min(Number(toOrderIndex) || 0, targetDayPlaces.length))
  targetDayPlaces.splice(insertAt, 0, { ...moved, day_number: targetDay })
  const normalizedTargetDayPlaces = targetDayPlaces.map((place, index) => ({
    ...place,
    day_number: targetDay,
    order_index: index + 1,
  }))

  const grouped = new Map()
  ;[...otherPlaces, ...normalizedTargetDayPlaces].forEach(place => {
    const day = Math.max(1, Number(place.day_number) || 1)
    const list = grouped.get(day) || []
    list.push({ ...place, day_number: day })
    grouped.set(day, list)
  })

  const normalizedPlaces = [...grouped.entries()]
    .sort(([leftDay], [rightDay]) => leftDay - rightDay)
    .flatMap(([, places]) =>
      places
        .sort((a, b) => Number(a.order_index || 0) - Number(b.order_index || 0))
        .map((place, index) => ({ ...place, order_index: index + 1 }))
    )

  const nextCandidate = { ...candidate, places: normalizedPlaces }
  return {
    changed: true,
    nextCandidate,
    requests: normalizedPlaces.map(place => ({
      candidateId: candidate.id,
      placeId: place.id,
      preferredDayNumber: place.day_number,
      preferredOrderIndex: place.order_index,
    })),
  }
}

function SortableFinalItem({ id, children }) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.45 : 1,
    cursor: isDragging ? 'grabbing' : 'grab',
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className="select-none active:cursor-grabbing"
      {...attributes}
      {...listeners}
    >
      {children}
    </div>
  )
}

function EmptyFinalDaySlot({ id }) {
  const { setNodeRef, isOver } = useSortable({ id })
  return (
    <div
      ref={setNodeRef}
      className={`h-14 rounded-xl border-2 border-dashed flex items-center justify-center transition-colors ${
        isOver ? 'border-blue-400 bg-blue-50 text-blue-500' : 'border-gray-200 text-gray-400'
      }`}
    >
      <span className="text-xs">{isOver ? '여기에 놓으세요' : '드래그해서 추가'}</span>
    </div>
  )
}

// ── 서브: 장소 카드 ──────────────────────────────────────────
function PlaceCard({ place, onClick, deleteMode = false }) {
  const cat = getCategoryStyle(place.category)
  const shouldShowCost = place.category !== '숙소' && place.category !== '이동'
  const isProtected = deleteMode && (place.category === '숙소' || place.category === '이동')
  const isDeletable = deleteMode && !isProtected

  return (
    <div
      className={`border rounded-lg p-3 transition-all cursor-pointer ${
        isDeletable
          ? 'border-red-300 bg-red-50 hover:bg-red-100'
          : isProtected
            ? 'border-gray-200 bg-gray-100 opacity-50'
            : 'border-gray-200 bg-white hover:shadow-sm'
      }`}
      onClick={onClick}
    >
      <div className="flex items-center justify-between mb-1.5">
        <span className="text-xs text-gray-400">{place.visit_time}</span>
        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${cat.color}`}>{cat.emoji} {place.category}</span>
      </div>
      <h4 className="font-bold text-sm text-gray-900 mb-1">{place.name}</h4>
      {place.description && <p className="text-xs text-gray-500 mb-2 line-clamp-1">{place.description}</p>}
      <div className="flex items-center gap-3 text-xs text-gray-400 mb-2">
        {place.duration_minutes && (
          <span>⏱ {place.duration_minutes >= 60
            ? `${Math.floor(place.duration_minutes / 60)}시간${place.duration_minutes % 60 ? ` ${place.duration_minutes % 60}분` : ''}`
            : `${place.duration_minutes}분`}</span>
        )}
        {shouldShowCost && <span>💰 {formatCost(place.estimated_cost)}</span>}
      </div>
    </div>
  )
}

// ── 서브: AI 브리핑 카드 ──────────────────────────────────────
function highlightBadge(h) {
  if (h.pinCount > 0) return '📌'
  if (h.superLikeCount > 0) return '💖'
  return '👍'
}

function highlightDesc(h) {
  if (h.pinCount > 0 && h.superLikeCount > 0) return '꼭 가자고 했고 반응도 뜨거웠어요'
  if (h.pinCount > 0) return '꼭 가자고 고정한 곳이에요'
  if (h.superLikeCount > 0 && h.likeCount > 0) return '반응이 뜨거웠어요'
  if (h.superLikeCount > 0) return '크게 공감한 곳이에요'
  if (h.likeCount > 1) return `${h.likeCount}명이 좋아했어요`
  return '좋아요를 받았어요'
}

function humanizeSplitReason(reason) {
  const text = (reason || '').trim()
  if (!text) return text

  const hasVoteLabels = /(왕따봉|따봉|고정|싫어요|핀\s*장소|핀\s*고정)/.test(text)
  if (!hasVoteLabels) return text

  const mentionsFood = /(식당|맛집|밀면|국밥|시장|카페|숙소)/.test(text)
  if (mentionsFood) {
    return '친구들이 선호한 식사와 숙소를 중심으로 잡고, 반응이 낮았던 후보는 덜어내 동선과 쉬는 흐름이 자연스럽게 이어지도록 조정했어요.'
  }
  return '친구들이 선호한 장소를 중심으로 구성하고, 반응이 낮았던 후보는 줄여 동선과 분위기의 균형을 맞췄어요.'
}

function AiBriefingCard({ candidate }) {
  const concept = candidate?.concept
  const splitReason = humanizeSplitReason(candidate?.split_reason || candidate?.splitReason)
  const highlights = candidate?.highlights || []

  return (
    <div className="bg-white border-2 border-accent-200 md:border-primary-200 rounded-xl overflow-hidden">
      <div className="bg-gradient-to-r from-accent-500 to-accent-400 md:from-primary-500 md:to-primary-400 px-4 py-2.5 flex items-center gap-2">
        <span className="text-base">✨</span>
        <h3 className="text-sm font-bold text-white">AI가 정리한 추천 이유</h3>
      </div>
      <div className="p-4 space-y-3">
        {concept && (
          <p className="text-sm text-gray-800 leading-relaxed font-medium">{concept}</p>
        )}
        {splitReason && (
          <div className="bg-accent-50 md:bg-primary-50 rounded-lg px-3 py-2.5 flex gap-2">
            <span className="text-sm flex-shrink-0">🗳️</span>
            <p className="text-xs text-gray-600 leading-relaxed">{splitReason}</p>
          </div>
        )}
        {highlights.length > 0 && (
          <div>
            <p className="text-xs font-bold text-gray-700 mb-2">🔥 친구들이 가장 좋아한 곳</p>
            <div className="space-y-1.5">
              {highlights.map((h, i) => (
                <div key={i} className="flex items-center gap-2 bg-gray-50 rounded-lg px-3 py-2">
                  <span className="text-base flex-shrink-0">{highlightBadge(h)}</span>
                  <span className="text-sm font-medium text-gray-900 truncate">{h.placeName}</span>
                  <span className="text-[11px] text-gray-400 flex-shrink-0">{highlightDesc(h)}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

// ── 서브: 공유 바 ─────────────────────────────────────────────
function ShareBar({ onKakao, onCopy, copied }) {
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-4 shadow-sm">
      <div className="flex items-center gap-2 mb-3">
        <span className="text-base">📤</span>
        <h3 className="text-sm font-bold text-gray-800">친구들에게 확정 일정 공유하기</h3>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <button
          onClick={onKakao}
          className="bg-yellow-300 hover:bg-yellow-400 text-gray-900 font-bold py-3 rounded-xl text-sm flex items-center justify-center gap-2 transition-colors"
        >
          💬 카카오로 공유
        </button>
        <button
          onClick={onCopy}
          className="border-2 border-gray-200 hover:border-gray-300 text-gray-700 font-bold py-3 rounded-xl text-sm flex items-center justify-center gap-2 transition-colors"
        >
          {copied ? '✅ 복사됐어요!' : '🔗 링크·QR 공유'}
        </button>
      </div>
    </div>
  )
}

function ConfirmedQrModal({ confirmedUrl, onClose }) {
  return (
    <div className="fixed inset-0 z-50 hidden md:flex items-center justify-center bg-black/40 px-4">
      <div className="w-full max-w-sm rounded-2xl bg-white p-7 text-center shadow-2xl">
        <div className="text-sm font-semibold text-primary-500 mb-2">확정 일정 QR</div>
        <h3 className="text-xl font-black text-gray-900 mb-2">휴대폰으로 찍고 일정을 확인해요</h3>
        <p className="text-sm text-gray-500 leading-6 mb-5">
          링크도 복사해뒀어요. 가까이에 있는 친구는 QR로 바로 볼 수 있어요.
        </p>
        <div className="mx-auto mb-4 flex h-56 w-56 items-center justify-center rounded-2xl border border-gray-200 bg-white p-3 shadow-sm">
          <QRCodeSVG
            value={confirmedUrl}
            size={196}
            level="M"
            includeMargin
            fgColor="#111827"
            bgColor="#FFFFFF"
            title="확정 일정 QR 코드"
          />
        </div>
        <div className="mb-5 rounded-xl bg-gray-50 px-3 py-2 text-xs text-gray-500 break-all">
          {confirmedUrl}
        </div>
        <button
          onClick={onClose}
          className="w-full rounded-xl bg-gray-900 px-4 py-3 text-sm font-bold text-white transition-colors hover:bg-gray-800"
        >
          닫기
        </button>
      </div>
    </div>
  )
}

// ── 메인 ─────────────────────────────────────────────────────
export default function S5ConfirmedPage() {
  const { uuid } = useParams()
  const isMobile = useIsMobile()
  const { user } = useAuth()

  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [activeDay, setActiveDay] = useState(1)
  const [copied, setCopied] = useState(false)
  const [toast, setToast] = useState(null)
  const [reorderMode, setReorderMode] = useState(false)
  const [deleteMode, setDeleteMode] = useState(false)
  const [deleteConfirm, setDeleteConfirm] = useState(null)
  const [openedPlace, setOpenedPlace] = useState(null)
  const [qrModal, setQrModal] = useState(null)
  const [editSaving, setEditSaving] = useState(false)
  const [activeDragId, setActiveDragId] = useState(null)

  // 카카오맵 refs
  const mapContainerRef = useRef(null)
  const mapRef = useRef(null)
  const markersRef = useRef([])
  const polylinesRef = useRef([])
  const routeRenderSeqRef = useRef(0)
  const pendingFocusPlaceRef = useRef(null)

  // 스크롤 refs
  const scrollRef = useRef(null)
  const dayRefs = useRef({})

  const showToast = useCallback((message, tone = 'success') => {
    setToast({ message, tone })
    window.setTimeout(() => setToast(null), 3000)
  }, [])

  const loadConfirmedData = useCallback(async ({ silent = false } = {}) => {
    if (!silent) {
      setLoading(true)
    }
    setError(null)
    try {
      const [planRes, finalRes] = await Promise.all([
        getPlan(uuid),
        getFinalPlan(uuid),
      ])

      setData(buildConfirmedData(
        transformPlan(planRes.data.data),
        transformCandidate(finalRes.data.data)
      ))
    } catch (err) {
      console.error('데이터 로딩 실패:', err)
      setError('추천 일정을 불러오는 데 실패했습니다.')
    } finally {
      if (!silent) {
        setLoading(false)
      }
    }
  }, [uuid])

  useEffect(() => {
    const timerId = window.setTimeout(() => loadConfirmedData(), 0)
    return () => window.clearTimeout(timerId)
  }, [loadConfirmedData])

  const dayNumbers = useMemo(() => {
    if (!data) return []
    const all = data.confirmed_candidate.places.map(p => p.day_number)
    return [...new Set(all)].sort((a, b) => a - b)
  }, [data])

  useEffect(() => {
    if (dayNumbers.length === 0) return
    setActiveDay(prev => dayNumbers.includes(prev) ? prev : dayNumbers[0])
  }, [dayNumbers])

  const canEditFinal = Boolean(
    user
    && data
    && Number(data.ownerId) === Number(user.id)
    && data.status === 'CONFIRMED'
  )
  const dndEnabled = canEditFinal && reorderMode && !deleteMode

  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: { distance: 4 },
    }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 250, tolerance: 5 },
    }),
  )

  const allSortableIds = useMemo(() => {
    if (!dndEnabled || !data) return []
    const candidate = data.confirmed_candidate
    const ids = candidate.places.map(place => `${candidate.id}:${place.id}`)
    const daysWithPlaces = new Set(candidate.places.map(place => place.day_number))
    dayNumbers.forEach(day => {
      if (!daysWithPlaces.has(day)) {
        ids.push(`${candidate.id}:empty-slot:${day}`)
      }
    })
    return ids
  }, [data, dayNumbers, dndEnabled])

  const activeDragPlace = useMemo(() => {
    if (!activeDragId || !data) return null
    const [, placeId] = activeDragId.split(':')
    return data.confirmed_candidate.places.find(place => String(place.id) === placeId) || null
  }, [activeDragId, data])

  const confirmedUrl = `${window.location.origin}/confirmed/${uuid}`

  const handleKakaoShare = async () => {
    try {
      await shareConfirmedPlan({
        planTitle: data?.title,
        confirmedUrl,
      })
    } catch (err) {
      console.error('카카오 추천 일정 공유 실패:', err)
      try {
        await navigator.clipboard.writeText(buildConfirmedInviteText(data?.title, confirmedUrl))
        setCopied(true)
        setTimeout(() => setCopied(false), 2000)
      } catch (copyErr) {
        console.error('추천 일정 공유 문구 복사 실패:', copyErr)
        alert(err.message || '카카오 공유를 준비하지 못했습니다.')
      }
    }
  }

  // ── 카카오맵 마커 업데이트 ──
  const updateMapMarkers = useCallback(() => {
    const map = mapRef.current
    if (!map || !data) return

    // 기존 마커/폴리라인 제거
    markersRef.current.forEach(m => m.setMap(null))
    polylinesRef.current.forEach(p => p.setMap(null))
    markersRef.current = []
    polylinesRef.current = []
    const renderSeq = routeRenderSeqRef.current + 1
    routeRenderSeqRef.current = renderSeq

    const pinColor = dayPinColor(activeDay, isMobile)
    const dayPlaces = data.confirmed_candidate.places
      .filter(p => p.day_number === activeDay)
      .filter(hasValidCoordinate)
      .sort((a, b) => a.order_index - b.order_index)

    const bounds = new window.kakao.maps.LatLngBounds()
    dayPlaces.forEach((place, idx) => {
      const position = new window.kakao.maps.LatLng(place.lat, place.lng)
      bounds.extend(position)

      // 번호 핀
      const el = document.createElement('div')
      el.style.cssText = `
        width: 32px; height: 32px; border-radius: 50%;
        background: ${pinColor}; color: white; font-size: 13px;
        font-weight: 700; display: flex; align-items: center;
        justify-content: center; border: 3px solid white;
        box-shadow: 0 2px 8px rgba(0,0,0,0.25);
      `
      el.textContent = `${idx + 1}`

      const overlay = new window.kakao.maps.CustomOverlay({
        position, content: el, yAnchor: 0.5,
      })
      overlay.setMap(map)
      markersRef.current.push(overlay)

      // 장소명 라벨
      const label = document.createElement('div')
      label.style.cssText = `
        font-size: 11px; color: white; font-weight: 600;
        background: ${pinColor}; padding: 2px 6px; border-radius: 4px;
        box-shadow: 0 1px 3px rgba(0,0,0,0.2); margin-top: 3px;
        white-space: nowrap;
      `
      label.textContent = place.name

      const labelOverlay = new window.kakao.maps.CustomOverlay({
        position, content: label, yAnchor: -0.5,
      })
      labelOverlay.setMap(map)
      markersRef.current.push(labelOverlay)
    })

    // S2/S3와 동일하게 실제 길찾기 경로를 그린다.
    const drawRoutes = async () => {
      for (let i = 0; i < dayPlaces.length - 1; i++) {
        if (routeRenderSeqRef.current !== renderSeq) return
        const origin = dayPlaces[i]
        const dest = dayPlaces[i + 1]
        if (!hasValidCoordinate(origin) || !hasValidCoordinate(dest)) continue
        let path = [
          { lat: Number(origin.lat), lng: Number(origin.lng) },
          { lat: Number(dest.lat), lng: Number(dest.lng) },
        ]
        const directDistance = distanceMeters(origin, dest)
        const shouldDrawFallback = Number.isFinite(directDistance) && directDistance <= 80000

        try {
          const res = await fetch(
            `/api/v1/route?originLat=${origin.lat}&originLng=${origin.lng}&destLat=${dest.lat}&destLng=${dest.lng}`,
            { credentials: 'include' }
          )
          const json = await res.json()
          if (routeRenderSeqRef.current !== renderSeq) return
          const routePath = (json.data || []).map(([lat, lng]) => ({ lat: Number(lat), lng: Number(lng) }))
          if (isReasonableRoute(routePath, origin, dest)) {
            path = routePath
          } else if (!shouldDrawFallback) {
            continue
          }
        } catch {
          if (!shouldDrawFallback) {
            continue
          }
        }
        if (routeRenderSeqRef.current !== renderSeq) return

        const polyline = new window.kakao.maps.Polyline({
          map,
          path: toKakaoPath(path),
          strokeWeight: 4,
          strokeColor: pinColor,
          strokeOpacity: 0.85,
          strokeStyle: 'solid',
        })
        polylinesRef.current.push(polyline)
      }
    }
    drawRoutes()

    if (!bounds.isEmpty()) map.setBounds(bounds, 60)

    const pendingFocusPlace = pendingFocusPlaceRef.current
    if (pendingFocusPlace?.day_number === activeDay && hasValidCoordinate(pendingFocusPlace)) {
      pendingFocusPlaceRef.current = null
      const position = new window.kakao.maps.LatLng(
        Number(pendingFocusPlace.lat),
        Number(pendingFocusPlace.lng)
      )
      map.setLevel(3)
      map.setCenter(position)
    }
  }, [activeDay, data, isMobile])

  // ── 카카오맵 초기화 ──
  useEffect(() => {
    if (!data) return

    function initMap() {
      if (!mapContainerRef.current) return

      const map = new window.kakao.maps.Map(mapContainerRef.current, {
        center: new window.kakao.maps.LatLng(35.1796, 129.0756),
        level: 7,
      })
      mapRef.current = map
      const zoomControl = new window.kakao.maps.ZoomControl()
      map.addControl(zoomControl, window.kakao.maps.ControlPosition.RIGHT)

      updateMapMarkers()
    }

    if (window.kakao && window.kakao.maps) {
      window.kakao.maps.load(initMap)
    }

    return () => {
      routeRenderSeqRef.current += 1
      pendingFocusPlaceRef.current = null
      markersRef.current.forEach(m => m.setMap(null))
      polylinesRef.current.forEach(p => p.setMap(null))
      markersRef.current = []
      polylinesRef.current = []
      mapRef.current = null
    }
  // isMobile 변경 시 지도 DOM 위치가 바뀌므로 재초기화
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, isMobile])

  // ── Day 전환 시 마커 업데이트 ──
  useEffect(() => {
    if (window.kakao && window.kakao.maps && mapRef.current) {
      updateMapMarkers()
    }
  }, [updateMapMarkers])

  // ── Day 탭 클릭 → 스크롤 (모바일: window, 데스크탑: 내부 컨테이너) ──
  const handleDayClick = useCallback((day) => {
    setActiveDay(day)
    const dayEl = dayRefs.current[day]
    if (!dayEl) return
    if (isMobile) {
      dayEl.scrollIntoView({ behavior: 'smooth', block: 'start' })
      return
    }
    const scrollEl = scrollRef.current
    if (!scrollEl) return
    const containerTop = scrollEl.getBoundingClientRect().top
    const elementTop = dayEl.getBoundingClientRect().top
    scrollEl.scrollTo({ top: elementTop - containerTop + scrollEl.scrollTop - 8, behavior: 'smooth' })
  }, [isMobile])

  // ── 스크롤 감지 → activeDay 자동 업데이트 ──
  useEffect(() => {
    const scrollEl = scrollRef.current
    if (!scrollEl || dayNumbers.length === 0) return

    const handleScroll = () => {
      const atBottom = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight < 16
      if (atBottom) {
        const lastDay = dayNumbers[dayNumbers.length - 1]
        setActiveDay(prev => prev !== lastDay ? lastDay : prev)
        return
      }

      const containerTop = scrollEl.getBoundingClientRect().top
      const threshold = containerTop + 80
      let newActive = dayNumbers[0]
      for (const day of dayNumbers) {
        const el = dayRefs.current[day]
        if (el && el.getBoundingClientRect().top <= threshold) newActive = day
      }
      setActiveDay(prev => prev !== newActive ? newActive : prev)
    }

    scrollEl.addEventListener('scroll', handleScroll, { passive: true })
    return () => scrollEl.removeEventListener('scroll', handleScroll)
  }, [dayNumbers])

  const handleCopyLink = () => {
    navigator.clipboard.writeText(confirmedUrl).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
      if (!isMobile) {
        setQrModal({ confirmedUrl })
      }
    }).catch((err) => {
      console.error('확정 일정 링크 복사 실패:', err)
      showToast('링크 복사에 실패했어요.', 'error')
    })
  }

  const handleFinalReorder = useCallback(async (fromPlaceId, toDayNumber, toOrderIndex) => {
    if (!canEditFinal || !data || editSaving) return

    const outcome = buildFinalReorder(data.confirmed_candidate, fromPlaceId, toDayNumber, toOrderIndex)
    if (!outcome.changed || !outcome.requests?.length) return

    setData(prev => prev
      ? { ...prev, confirmed_candidate: outcome.nextCandidate }
      : prev
    )
    setEditSaving(true)
    try {
      await updatePlaceOrders(uuid, outcome.requests)
      showToast('추천 일정 순서가 저장되었어요.')
    } catch (err) {
      console.error('추천 일정 순서 저장 실패:', err)
      showToast(err.response?.data?.message || '순서 저장에 실패했어요.', 'error')
      await loadConfirmedData({ silent: true })
    } finally {
      setEditSaving(false)
    }
  }, [canEditFinal, data, editSaving, loadConfirmedData, showToast, uuid])

  const handleDragStart = useCallback((event) => {
    setActiveDragId(event.active.id)
  }, [])

  const handleDragEnd = useCallback((event) => {
    setActiveDragId(null)
    const { active, over } = event
    if (!over || active.id === over.id || !dndEnabled || !data) return

    const [, activePlaceId] = active.id.split(':')
    const overParts = over.id.split(':')
    const overType = overParts[1]

    if (overType === 'empty-slot') {
      handleFinalReorder(Number(activePlaceId), Number(overParts[2]), 0)
      return
    }

    const overPlaceId = overParts[1]
    const overPlace = data.confirmed_candidate.places.find(place => String(place.id) === overPlaceId)
    if (!overPlace) return

    const dayPlaces = data.confirmed_candidate.places
      .filter(place => Number(place.day_number) === Number(overPlace.day_number))
      .sort((a, b) => Number(a.order_index || 0) - Number(b.order_index || 0))
    const overIndex = dayPlaces.findIndex(place => String(place.id) === overPlaceId)

    handleFinalReorder(Number(activePlaceId), overPlace.day_number, Math.max(0, overIndex))
  }, [data, dndEnabled, handleFinalReorder])

  const handlePlaceClick = useCallback((place) => {
    if (deleteMode && canEditFinal) {
      if (place.category === '숙소' || place.category === '이동') {
        showToast('숙소와 이동 거점은 삭제할 수 없어요.', 'error')
        return
      }
      setDeleteConfirm(place)
      return
    }

    if (isMobile && !reorderMode) {
      setOpenedPlace(place)
    }

    const map = mapRef.current
    if (!map || !hasValidCoordinate(place)) return
    pendingFocusPlaceRef.current = place
    setActiveDay(place.day_number)

    if (activeDay !== place.day_number) return

    pendingFocusPlaceRef.current = null
    const position = new window.kakao.maps.LatLng(Number(place.lat), Number(place.lng))
    map.setLevel(3)
    map.setCenter(position)
  }, [activeDay, canEditFinal, deleteMode, isMobile, reorderMode, showToast])

  const confirmDeletePlace = useCallback(async () => {
    if (!deleteConfirm || !canEditFinal || !data) return
    setEditSaving(true)
    try {
      await deletePlace(uuid, data.confirmed_candidate.id, deleteConfirm.id)
      setDeleteConfirm(null)
      await loadConfirmedData({ silent: true })
      showToast(`${deleteConfirm.name}을(를) 삭제했어요.`)
    } catch (err) {
      console.error('추천 일정 장소 삭제 실패:', err)
      showToast(err.response?.data?.message || '장소 삭제에 실패했어요.', 'error')
    } finally {
      setEditSaving(false)
    }
  }, [canEditFinal, data, deleteConfirm, loadConfirmedData, showToast, uuid])

  // ── 로딩/에러 ──
  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <div className="w-12 h-12 border-4 border-accent-200 border-t-accent-500 md:border-primary-200 md:border-t-primary-500 rounded-full animate-spin mx-auto mb-4" />
          <p className="text-gray-500 text-sm">추천 일정을 불러오고 있어요...</p>
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <p className="text-red-500 text-sm mb-3">{error}</p>
          <button onClick={() => window.location.reload()} className="text-sm text-accent-500 md:text-primary-500 hover:underline">
            다시 시도
          </button>
        </div>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <p className="text-gray-400 text-sm">일정 데이터를 불러올 수 없습니다.</p>
      </div>
    )
  }

  const { confirmed_candidate: cand } = data

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={pointerWithin}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={allSortableIds} strategy={verticalListSortingStrategy}>
    <div className="min-h-screen md:h-screen flex flex-col bg-gray-50 md:overflow-hidden pb-20 md:pb-0">
      <NavBar />

      {/* ── 축하 배너 + Day 탭 ── */}
      <div className="flex-shrink-0 max-w-[1400px] mx-auto px-4 md:px-6 w-full pt-3 pb-2">
        {/* 축하 배너 */}
        <div className="bg-gradient-to-r from-accent-500 to-accent-400 md:from-primary-500 md:to-primary-400 rounded-xl px-4 md:px-5 py-3 md:py-4 mb-2 text-white">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-2">
            <div className="min-w-0 flex items-start gap-2">
              <span className="text-base md:text-lg leading-6 md:leading-7 flex-shrink-0">🎉</span>
              <div className="min-w-0">
                <p className="font-black text-base md:text-lg truncate">{cand.name || data.title} — 추천 일정이 준비됐어요!</p>
                <p className="text-accent-100 md:text-primary-100 text-xs mt-1">친구들의 투표 결과와 고정 장소를 반영한 여행 계획이에요.</p>
              </div>
            </div>
            <div className="flex gap-1.5 md:gap-2 flex-wrap md:flex-shrink-0">
              <span className="bg-white/15 text-white text-[11px] md:text-xs px-2 md:px-3 py-1 md:py-1.5 rounded-full">📍 {data.destination}</span>
              <span className="bg-white/15 text-white text-[11px] md:text-xs px-2 md:px-3 py-1 md:py-1.5 rounded-full">🗓️ {formatDateRange(data.start_date, data.end_date)}</span>
              <span className="bg-white/15 text-white text-[11px] md:text-xs px-2 md:px-3 py-1 md:py-1.5 rounded-full">👥 {data.participant_count}명</span>
              <span className="bg-white/15 text-white text-[11px] md:text-xs px-2 md:px-3 py-1 md:py-1.5 rounded-full">💰 1인 예상 {formatPerPersonCost(cand.estimated_cost_per_person)}</span>
            </div>
          </div>
        </div>

        {/* Day 탭 + 호스트 컨트롤 (S2/S3와 통일) */}
        <div className="flex items-center gap-2 overflow-x-auto pb-1">
          <span className="hidden md:inline text-sm text-gray-500 mr-1 flex-shrink-0">빠른 이동:</span>
          {dayNumbers.map(day => (
            <button
              key={day}
              onClick={() => handleDayClick(day)}
              className={`flex-shrink-0 px-3 md:px-4 py-1.5 rounded-full text-xs md:text-sm font-medium transition-all whitespace-nowrap ${
                activeDay === day
                  ? 'text-white shadow-md'
                  : 'bg-white border border-gray-200 text-gray-600 hover:bg-gray-50'
              }`}
              style={activeDay === day ? { backgroundColor: dayPinColor(day, isMobile) } : {}}
            >
              Day {day}
            </button>
          ))}
          {canEditFinal && (
            <div className="flex items-center gap-1.5 ml-auto flex-shrink-0">
              <button
                onClick={() => { setDeleteMode(false); setReorderMode(prev => !prev) }}
                disabled={editSaving}
                className={`text-xs font-medium px-3 py-1.5 rounded-full border transition-colors disabled:opacity-50 whitespace-nowrap ${
                  reorderMode
                    ? 'border-blue-300 bg-blue-50 text-blue-600'
                    : 'border-gray-200 bg-white text-gray-500 hover:bg-gray-50'
                }`}
              >
                {reorderMode ? '✓ 순서 편집 중' : '↕ 순서 편집'}
              </button>
              <button
                onClick={() => { setReorderMode(false); setDeleteMode(prev => !prev) }}
                disabled={editSaving}
                className={`text-xs font-medium px-3 py-1.5 rounded-full border transition-colors disabled:opacity-50 whitespace-nowrap ${
                  deleteMode
                    ? 'border-red-300 bg-red-50 text-red-600'
                    : 'border-gray-200 bg-white text-gray-500 hover:bg-gray-50'
                }`}
              >
                {deleteMode ? '✓ 삭제 중' : '🗑 일정 삭제'}
              </button>
            </div>
          )}
        </div>
        {canEditFinal && (reorderMode || deleteMode) && (
          <p className="text-xs text-gray-500 mt-1.5 px-1">
            {reorderMode ? '카드를 드래그해서 순서를 바꾸거나 다른 Day로 옮길 수 있어요.' : '삭제할 장소를 누르세요. 숙소·이동 거점은 삭제할 수 없어요.'}
          </p>
        )}
      </div>

      {/* ── 모바일 메인 (지도 → 타임라인 → 하단 카드) ── */}
      {isMobile && (
        <div className="px-4 pb-4 space-y-4">
          {/* 지도 */}
          <div className="rounded-xl overflow-hidden border border-gray-200 shadow-sm">
            <div className="bg-gray-800 text-white px-3 py-2 text-xs flex items-center gap-2">
              <span className="font-medium">🗺️ 추천 동선 — Day {activeDay}</span>
              <span
                className="ml-auto px-2 py-0.5 rounded-full font-semibold text-[10px]"
                style={{ background: dayPinColor(activeDay, true), color: 'white' }}
              >
                Day{activeDay}
              </span>
            </div>
            <div ref={mapContainerRef} className="h-56 bg-gray-100">
              {(!window.kakao || !window.kakao.maps) && (
                <div className="w-full h-full flex items-center justify-center text-gray-400 text-xs">
                  카카오맵 로딩 중...
                </div>
              )}
            </div>
          </div>

          {/* AI 브리핑 카드 */}
          <AiBriefingCard candidate={cand} />

          {/* Day별 타임라인 */}
          {dayNumbers.map(day => {
            const dayPlaces = cand.places.filter(p => p.day_number === day).sort((a, b) => a.order_index - b.order_index)
            const dayCost = dayPlaces.reduce((s, p) => s + (p.estimated_cost || 0), 0)
            const pinColor = dayPinColor(day, true)
            return (
              <div key={day} ref={el => { dayRefs.current[day] = el }}>
                <div className="flex items-center gap-2 mb-2">
                  <span className="text-white text-xs font-bold px-3 py-1 rounded-full" style={{ backgroundColor: pinColor }}>Day {day}</span>
                  <span className="text-xs text-gray-500">{getWeekday(data.start_date, day - 1) || '날짜 미정'}</span>
                  <span className="ml-auto text-xs text-gray-400">일비 {formatCost(dayCost)}</span>
                </div>
                <div className="rounded-xl border-2 p-2.5 space-y-2" style={{ borderColor: pinColor + '60', backgroundColor: pinColor + '08' }}>
                  {dayPlaces.map(place => (
                    dndEnabled ? (
                      <SortableFinalItem key={place.id} id={`${cand.id}:${place.id}`}>
                        <PlaceCard
                          place={place}
                          deleteMode={deleteMode}
                          onClick={() => handlePlaceClick(place)}
                        />
                      </SortableFinalItem>
                    ) : (
                      <PlaceCard
                        key={place.id}
                        place={place}
                        deleteMode={deleteMode}
                        onClick={() => handlePlaceClick(place)}
                      />
                    )
                  ))}
                  {dndEnabled && dayPlaces.length === 0 && (
                    <EmptyFinalDaySlot id={`${cand.id}:empty-slot:${day}`} />
                  )}
                </div>
              </div>
            )
          })}

          {/* 배제 장소 */}
          {data.excluded_place && (
            <div className="bg-red-50 border border-red-100 rounded-lg p-3">
              <p className="text-xs text-red-600 font-medium">👎 {data.excluded_place.name}: {data.excluded_place.reason}</p>
            </div>
          )}

          {/* 공유 바 */}
          <ShareBar onKakao={handleKakaoShare} onCopy={handleCopyLink} copied={copied} />

          <DeletePlanSection uuid={uuid} status={data.status} />
        </div>
      )}

      {/* ── 데스크탑 메인: 좌 타임라인(스크롤) + 우 카카오맵 ── */}
      {!isMobile && (
      <div className="flex-1 overflow-hidden max-w-[1400px] mx-auto px-6 w-full pb-4">
        <div className="h-full flex gap-4">

          {/* 좌측: 추천 일정 타임라인 (브리핑 카드도 함께 스크롤) */}
          <div className="flex-1 min-w-0 max-w-[640px] flex flex-col">
            {/* 스크롤 영역 — AI 추천 이유를 일정과 같이 스크롤 */}
            <div ref={scrollRef} className="flex-1 overflow-y-auto pr-1 space-y-4">
              {/* AI 추천 이유 (스크롤에 포함) */}
              <AiBriefingCard candidate={cand} />
              {dayNumbers.map(day => {
                const dayPlaces = cand.places.filter(p => p.day_number === day).sort((a, b) => a.order_index - b.order_index)
                const dayCost = dayPlaces.reduce((s, p) => s + (p.estimated_cost || 0), 0)
                const pinColor = dayPinColor(day, false)

                return (
                  <div key={day} ref={el => { dayRefs.current[day] = el }}>
                    {/* Day 헤더 */}
                    <div className="flex items-center gap-2 mb-2">
                      <span className="text-white text-xs font-bold px-3 py-1 rounded-full" style={{ backgroundColor: pinColor }}>
                        Day {day}
                      </span>
                      <span className="text-xs text-gray-500">{getWeekday(data.start_date, day - 1) || '날짜 미정'}</span>
                      <span className="ml-auto text-xs text-gray-400">일비 {formatCost(dayCost)}</span>
                    </div>

                    {/* 장소 카드 리스트 */}
                    <div className="rounded-xl border-2 p-2.5 space-y-2" style={{ borderColor: pinColor + '60', backgroundColor: pinColor + '08' }}>
                      {dayPlaces.map(place => (
                        dndEnabled ? (
                          <SortableFinalItem key={place.id} id={`${cand.id}:${place.id}`}>
                            <PlaceCard
                              place={place}
                              deleteMode={deleteMode}
                              onClick={() => handlePlaceClick(place)}
                            />
                          </SortableFinalItem>
                        ) : (
                          <PlaceCard
                            key={place.id}
                            place={place}
                            deleteMode={deleteMode}
                            onClick={() => handlePlaceClick(place)}
                          />
                        )
                      ))}
                      {dndEnabled && dayPlaces.length === 0 && (
                        <EmptyFinalDaySlot id={`${cand.id}:empty-slot:${day}`} />
                      )}
                    </div>
                  </div>
                )
              })}

              {/* 배제 장소 */}
              {data.excluded_place && (
                <div className="bg-red-50 border border-red-100 rounded-lg p-3">
                  <p className="text-xs text-red-600 font-medium">👎 {data.excluded_place.name}: {data.excluded_place.reason}</p>
                </div>
              )}

              {/* 공유 바 */}
              <div className="pt-2 pb-4">
                <ShareBar onKakao={handleKakaoShare} onCopy={handleCopyLink} copied={copied} />
              </div>

              <DeletePlanSection uuid={uuid} status={data.status} />
            </div>
          </div>

          {/* 우측: 카카오맵 ── */}
          <div className="flex-1 min-w-[480px] flex flex-col">
            <div className="flex-shrink-0 bg-gray-800 text-white px-4 py-2.5 rounded-t-xl flex items-center gap-3">
              <p className="text-sm font-medium">🗺️ 추천 동선 — Day {activeDay}</p>
              {dayNumbers.map(d => (
                <span
                  key={d}
                  className={`text-xs px-2 py-0.5 rounded-full font-semibold ${activeDay === d ? '' : 'opacity-40'}`}
                  style={{ background: dayPinColor(d, false), color: 'white' }}
                >
                  Day{d}
                </span>
              ))}
            </div>
            <div
              ref={mapContainerRef}
              className="flex-1 rounded-b-xl bg-gray-100"
            >
              {(!window.kakao || !window.kakao.maps) && (
                <div className="w-full h-full flex items-center justify-center text-gray-400">
                  <div className="text-center">
                    <p className="text-2xl mb-2">🗺️</p>
                    <p className="text-sm font-medium">카카오맵 SDK를 로드해주세요</p>
                    <p className="text-xs mt-1">index.html에 스크립트 태그 추가 필요</p>
                  </div>
                </div>
              )}
            </div>
          </div>

        </div>
      </div>
      )}

      {toast && (
        <div className={`fixed bottom-24 left-1/2 -translate-x-1/2 z-50 text-white text-sm font-medium px-5 py-3 rounded-xl shadow-lg ${
          toast.tone === 'error' ? 'bg-red-600' : 'bg-gray-900'
        }`}>
          {toast.message}
        </div>
      )}

      {deleteConfirm && canEditFinal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl p-8 max-w-sm mx-4 shadow-2xl">
            <div className="text-center">
              <div className="text-4xl mb-4">🗑️</div>
              <h3 className="text-lg font-bold text-gray-900 mb-2">일정을 삭제할까요?</h3>
              <p className="text-sm text-gray-500 mb-6">{deleteConfirm.name}</p>
              <button
                onClick={confirmDeletePlace}
                disabled={editSaving}
                className="w-full bg-red-500 hover:bg-red-600 disabled:bg-red-300 text-white font-bold py-3 px-6 rounded-xl transition-colors mb-3"
              >
                {editSaving ? '삭제 중...' : '삭제'}
              </button>
              <button
                onClick={() => setDeleteConfirm(null)}
                disabled={editSaving}
                className="text-sm text-gray-400 hover:text-gray-600 transition-colors disabled:opacity-50"
              >
                취소
              </button>
            </div>
          </div>
        </div>
      )}

      {openedPlace && (
        <VoteCardModal
          place={openedPlace}
          candidate={null}
          otherCandidate={null}
          showMoveButton={false}
          showVoteControls={false}
          onClose={() => setOpenedPlace(null)}
        />
      )}

      {qrModal && (
        <ConfirmedQrModal
          confirmedUrl={qrModal.confirmedUrl}
          onClose={() => setQrModal(null)}
        />
      )}

      <BottomTabBar />
    </div>
      </SortableContext>
      <DragOverlay>
        {activeDragPlace ? (
          <div className="opacity-90 shadow-2xl rounded-lg">
            <PlaceCard place={activeDragPlace} />
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  )
}
