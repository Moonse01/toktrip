import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { getMyPlans, updatePlanTitle, deletePlan, leavePlan } from '../api/plans'
import { useAuth } from '../auth/AuthContext'
import NavBar from '../components/NavBar'
import BottomTabBar from '../components/BottomTabBar'

// ── 상수 ──────────────────────────────────────────────────
const MAX_MISSION_EXTRA_LENGTH = 2000

const STATUS_META = {
  DRAFT:         { step: 1, label: '업로드 완료',   color: 'bg-slate-400',    text: 'text-slate-500',   badge: 'bg-slate-50 text-slate-600' },
  AI_GENERATING: { step: 2, label: 'AI 분석 중',    color: 'bg-amber-400',    text: 'text-amber-600',   badge: 'bg-amber-50 text-amber-700' },
  AI_DONE:       { step: 2, label: 'AI 완료',       color: 'bg-amber-500',    text: 'text-amber-600',   badge: 'bg-amber-50 text-amber-700' },
  VOTING:        { step: 3, label: '투표 진행 중',  color: 'bg-teal-400',     text: 'text-teal-600',    badge: 'bg-teal-50 text-teal-700' },
  CONFIRMED:     { step: 4, label: '확정 완료',     color: 'bg-violet-400',   text: 'text-violet-500',  badge: 'bg-violet-50 text-violet-600' },
  COMPLETED:     { step: 4, label: '여행 완료',     color: 'bg-gray-400',     text: 'text-gray-500',    badge: 'bg-gray-100 text-gray-500' },
}

const STEPS = ['업로드', 'AI 분석', '투표', '확정']

// 다가오는 여행은 가까운 5개만 먼저 보여주고 나머지는 '더 보기'로 펼친다.
const UPCOMING_LIMIT = 5

function planHref(plan) {
  if (['DRAFT', 'AI_GENERATING', 'AI_DONE'].includes(plan.status)) return `/result/${plan.uuid}`
  if (plan.status === 'VOTING') {
    return `/vote/${plan.uuid}`
  }
  return `/confirmed/${plan.uuid}`
}

function primaryActionMeta(plan) {
  switch (plan.status) {
    case 'AI_DONE':
      return {
        label: 'A/B안 보기',
        href: `/result/${plan.uuid}`,
        className: 'border-amber-200 bg-amber-50/70 text-amber-700 hover:bg-amber-100 hover:border-amber-300',
      }
    case 'VOTING':
      return {
        label: '투표하러 가기',
        href: `/vote/${plan.uuid}`,
        className: 'border-teal-200 bg-teal-50/70 text-teal-700 hover:bg-teal-100 hover:border-teal-300',
      }
    case 'CONFIRMED':
      return {
        label: '추천안 보기',
        href: `/confirmed/${plan.uuid}`,
        className: 'border-violet-200 bg-violet-50/70 text-violet-700 hover:bg-violet-100 hover:border-violet-300',
      }
    default:
      return null
  }
}

function planRemoveMeta(plan) {
  if (plan.myRole === 'HOST') {
    return {
      title: '플랜 삭제',
      modalTitle: '플랜을 삭제할까요?',
      description: '후보안, 투표, 의견이 모두 삭제되며 되돌릴 수 없어요.',
      actionLabel: '삭제하기',
      busyLabel: '삭제 중...',
      errorMessage: '삭제에 실패했어요.',
      run: () => deletePlan(plan.uuid),
    }
  }
  if (plan.myRole === 'GUEST') {
    return {
      title: '내 목록에서 제거',
      modalTitle: '초대받은 여행에서 나갈까요?',
      description: '내 플래너 목록에서만 사라지고, 호스트의 여행 일정은 삭제되지 않아요.',
      actionLabel: '나가기',
      busyLabel: '나가는 중...',
      errorMessage: '내 목록에서 제거하지 못했어요.',
      run: () => leavePlan(plan.uuid),
    }
  }
  return null
}

function TrashIcon({ size = 14 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="3 6 5 6 21 6" />
      <path d="M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" />
      <path d="M10 11v6" />
      <path d="M14 11v6" />
      <path d="M9 6V4a1 1 0 011-1h4a1 1 0 011 1v2" />
    </svg>
  )
}

// ── 헬퍼: 기간 태그 ──────────────────────────────────────
function durationTag(startDate, endDate) {
  if (!startDate || !endDate) return null
  const s = new Date(startDate)
  const e = new Date(endDate)
  const nights = Math.round((e - s) / 86400000)
  if (nights === 0) return '당일치기'
  return `${nights}박 ${nights + 1}일`
}

// ── 서브 컴포넌트: 플랜 카드 ──────────────────────────────
function PlanCard({ plan, onTitleUpdate, onDelete }) {
  const navigate = useNavigate()
  const meta = STATUS_META[plan.status] ?? STATUS_META.DRAFT
  const progress = (meta.step / 4) * 100

  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const inputRef = useRef(null)

  const displayTitle = plan.title || (plan.destination ? `${plan.destination} 여행` : '제목 없는 플랜')
  const duration = durationTag(plan.startDate, plan.endDate)
  const candA = plan.candidates?.find(c => c.label === 'A')
  const candB = plan.candidates?.find(c => c.label === 'B')
  const hasCandidates = candA || candB
  const primaryAction = primaryActionMeta(plan)
  const showDashboardAction = ['AI_DONE', 'VOTING'].includes(plan.status)
  const removeMeta = planRemoveMeta(plan)

  const startEdit = (e) => {
    e.stopPropagation()
    setDraft(displayTitle)
    setEditing(true)
    setTimeout(() => inputRef.current?.select(), 0)
  }

  const saveTitle = async () => {
    setEditing(false)
    const trimmed = draft.trim()
    if (!trimmed || trimmed === displayTitle) return
    try {
      await updatePlanTitle(plan.uuid, trimmed)
      onTitleUpdate?.(plan.uuid, trimmed)
    } catch { /* 실패 시 무시 */ }
  }

  const handleDelete = async () => {
    if (!removeMeta) return
    setDeleting(true)
    try {
      await removeMeta.run()
      onDelete?.(plan.uuid)
    } catch (err) {
      alert(err.response?.data?.message || removeMeta.errorMessage)
      setDeleting(false)
      setDeleteOpen(false)
    }
  }

  return (
    <>
    <div
      onClick={() => !editing && navigate(planHref(plan))}
      className="relative bg-white rounded-2xl p-5 shadow-sm border border-gray-100 hover:shadow-md hover:border-primary-200 cursor-pointer transition-all"
    >
      {plan.myRole === 'HOST' && (
        <div className="absolute -top-2.5 -left-2.5 w-7 h-7 bg-amber-400 rounded-full flex items-center justify-center shadow-sm border-2 border-white z-10">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="white"><path d="M5 16L2 6l6 5 4-7 4 7 6-5-3 10H5zm-1 2h16v3H4v-3z"/></svg>
        </div>
      )}
      {/* 상단: 제목 + 상태 뱃지 */}
      <div className="flex items-start justify-between mb-2">
        <div className="flex-1 min-w-0">
          {editing ? (
            <input
              ref={inputRef}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onBlur={saveTitle}
              onKeyDown={(e) => { if (e.key === 'Enter') saveTitle(); if (e.key === 'Escape') setEditing(false) }}
              onClick={(e) => e.stopPropagation()}
              maxLength={100}
              className="w-full font-bold text-gray-800 text-sm border border-primary-300 rounded-lg px-2 py-1 focus:outline-none focus:ring-2 focus:ring-primary-200"
            />
          ) : (
            <p className="font-bold text-gray-800 text-sm truncate group/title flex items-center gap-1.5">
              <span>{displayTitle}</span>
              <button
                onClick={startEdit}
                className="opacity-0 group-hover/title:opacity-100 text-gray-300 hover:text-gray-500 transition-opacity flex-shrink-0"
                title="제목 수정"
              >
                ✏️
              </button>
            </p>
          )}

          {/* 메타 정보: 목적지 · 기간 · 날짜 */}
          <div className="flex items-center gap-1.5 mt-1 text-xs text-gray-400 flex-wrap">
            {plan.destination && <span>📍 {plan.destination}</span>}
            {plan.destination && duration && <span>·</span>}
            {duration && <span>{duration}</span>}
            {plan.startDate && (
              <>
                <span>·</span>
                <span>{plan.startDate}{plan.endDate ? ` ~ ${plan.endDate}` : ''}</span>
              </>
            )}
          </div>
        </div>
        <div className="ml-3 flex-shrink-0 flex items-center gap-1.5">
          {plan.myRole === 'HOST' && (
            <span className="text-[10px] text-amber-600 bg-amber-50 px-2 py-0.5 rounded-full font-medium whitespace-nowrap">내가 만든 여행</span>
          )}
          {plan.myRole === 'GUEST' && (
            <span className="text-[10px] text-teal-600 bg-teal-50 px-2 py-0.5 rounded-full font-medium whitespace-nowrap">초대받은 여행</span>
          )}
          {removeMeta && (
            <button
              onClick={(e) => { e.stopPropagation(); setDeleteOpen(true) }}
              className="w-7 h-7 flex items-center justify-center rounded-full text-gray-300 hover:text-red-500 hover:bg-red-50 transition-colors flex-shrink-0"
              title={removeMeta.title}
            >
              <TrashIcon />
            </button>
          )}
        </div>
      </div>

      {/* A/B안 요약 (후보안이 있을 때만) */}
      {hasCandidates && (
        <div className="space-y-1.5 my-3">
          {candA && (
            <div className="flex items-center gap-2">
              <span className="flex-shrink-0 w-5 h-5 rounded-full bg-blue-400 text-white text-[10px] font-bold flex items-center justify-center">A</span>
              <span className="text-xs font-medium text-gray-700 truncate">{candA.name}</span>
            </div>
          )}
          {candB && (
            <div className="flex items-center gap-2">
              <span className="flex-shrink-0 w-5 h-5 rounded-full bg-primary-500 text-white text-[10px] font-bold flex items-center justify-center">B</span>
              <span className="text-xs font-medium text-gray-700 truncate">{candB.name}</span>
            </div>
          )}
        </div>
      )}

      {/* 요약 코멘트 */}
      {plan.summary && (
        <p className="text-xs text-gray-400 mb-3 line-clamp-1">💬 {plan.summary}</p>
      )}

      {/* 프로그레스 바 */}
      <div className="mb-2">
        <div className="flex justify-between mb-1">
          {STEPS.map((s, i) => (
            <span
              key={s}
              className={`text-xs ${i + 1 <= meta.step ? meta.text : 'text-gray-300'} font-medium`}
            >
              {s}
            </span>
          ))}
        </div>
        <div className="h-1.5 bg-gray-100 rounded-full overflow-hidden">
          <div
            className={`h-full ${meta.color} rounded-full transition-all`}
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      {/* 액션 버튼 */}
      {primaryAction && (
        <div className="flex gap-2 mt-3">
          <button
            onClick={(e) => {
              e.stopPropagation()
              navigate(primaryAction.href)
            }}
            className={`flex-1 border font-semibold text-xs py-2 rounded-lg transition-colors ${primaryAction.className}`}
          >
            {primaryAction.label}
          </button>
          {showDashboardAction && (
            <button
              onClick={(e) => {
                e.stopPropagation()
                navigate(`/dashboard/${plan.uuid}`)
              }}
              className="flex-1 bg-accent-500 md:bg-primary-500 text-white font-semibold text-xs py-2 rounded-lg hover:bg-accent-600 md:hover:bg-primary-600 transition-colors"
            >
              투표 현황
            </button>
          )}
        </div>
      )}
    </div>

    {/* 삭제 확인 모달 */}
    {deleteOpen && (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4" onClick={() => !deleting && setDeleteOpen(false)}>
        <div className="bg-white rounded-2xl p-6 max-w-sm w-full shadow-2xl" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="text-4xl mb-3">🗑️</div>
            <h3 className="text-lg font-bold text-gray-900 mb-2">{removeMeta?.modalTitle}</h3>
            <p className="text-sm text-gray-600 mb-1 font-medium">{displayTitle}</p>
            <p className="text-xs text-gray-400 mb-6">{removeMeta?.description}</p>
            <button
              onClick={handleDelete}
              disabled={deleting}
              className="w-full bg-red-500 hover:bg-red-600 disabled:bg-red-300 text-white font-bold py-3 rounded-xl transition-colors mb-2"
            >
              {deleting ? removeMeta?.busyLabel : removeMeta?.actionLabel}
            </button>
            <button
              onClick={() => setDeleteOpen(false)}
              disabled={deleting}
              className="w-full text-sm text-gray-500 hover:text-gray-700 py-2"
            >
              취소
            </button>
          </div>
        </div>
      </div>
    )}
    </>
  )
}

// ── 서브 컴포넌트: 과거 플랜 행 ────────────────────────────
function PastPlanRow({ plan }) {
  const navigate = useNavigate()
  const meta = STATUS_META[plan.status] ?? STATUS_META.COMPLETED

  return (
    <div
      onClick={() => navigate(planHref(plan))}
      className="flex items-center justify-between py-3 border-b border-gray-50 last:border-0 cursor-pointer hover:bg-gray-50 rounded-lg px-2 -mx-2 transition-colors"
    >
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-gray-700 truncate">
          {plan.title || (plan.destination ? `${plan.destination} 여행` : '제목 없는 플랜')}
        </p>
        {plan.startDate && (
          <p className="text-xs text-gray-400 mt-0.5">
            {plan.startDate}{plan.endDate ? ` ~ ${plan.endDate}` : ''}
          </p>
        )}
      </div>
      <span className={`ml-3 flex-shrink-0 text-xs font-semibold px-2 py-1 rounded-full ${meta.badge}`}>
        {meta.label}
      </span>
    </div>
  )
}

// ── 로그인 홈 화면 ─────────────────────────────────────────
function LoggedInHome() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [plans, setPlans] = useState(null)
  const [loading, setLoading] = useState(true)
  const [showAllUpcoming, setShowAllUpcoming] = useState(false)

  useEffect(() => {
    getMyPlans()
      .then(res => setPlans(res.data.data ?? []))
      .catch(() => setPlans([]))
      .finally(() => setLoading(false))
  }, [])

  const handleTitleUpdate = (uuid, newTitle) => {
    setPlans(prev => prev?.map(p => p.uuid === uuid ? { ...p, title: newTitle } : p))
  }

  const handlePlanDelete = (uuid) => {
    setPlans(prev => prev?.filter(p => p.uuid !== uuid))
  }

  const onNewPlan = () => navigate('/new')

  const today = new Date()
  today.setHours(0, 0, 0, 0)

  // 3단 분류
  const upcomingTrips = [] // 여행 예정 (CONFIRMED + startDate 미래)
  const activePlans   = [] // 진행 중 (DRAFT~VOTING)
  const pastPlans     = [] // 지난 여행

  plans?.forEach(p => {
    const tripEnd = p.endDate || p.startDate
    const ended = tripEnd ? new Date(tripEnd) < today : false
    if (p.status === 'CONFIRMED' && !ended) {
      // 아직 끝나지 않은 확정 여행 (여행 중인 경우도 포함)
      upcomingTrips.push(p)
    } else if (['DRAFT', 'AI_GENERATING', 'AI_DONE', 'VOTING'].includes(p.status)) {
      activePlans.push(p)
    } else {
      // D-day가 지난 확정/완료 여행
      pastPlans.push(p)
    }
  })

  // 여행 예정은 가까운 날짜순
  upcomingTrips.sort((a, b) => new Date(a.startDate) - new Date(b.startDate))
  const visibleUpcoming = showAllUpcoming ? upcomingTrips : upcomingTrips.slice(0, UPCOMING_LIMIT)

  function daysUntil(dateStr) {
    if (!dateStr) return null
    const diff = Math.ceil((new Date(dateStr) - today) / 86400000)
    return diff >= 0 ? diff : null
  }

  return (
    <div className="min-h-screen bg-gray-50 pb-20 md:pb-0">
      <NavBar />

      <div className="max-w-2xl mx-auto px-4 md:px-6 pt-6 md:pt-10 pb-20">
        <div className="bg-gradient-to-r from-accent-500 to-accent-400 md:from-primary-500 md:to-primary-400 rounded-2xl p-5 md:p-6 mb-6 md:mb-8 text-white flex items-center justify-between">
          <div className="min-w-0">
            <p className="text-accent-100 md:text-primary-100 text-sm mb-1">안녕하세요 👋</p>
            <p className="text-lg md:text-xl font-black truncate">{user?.nickname}님의 여행 플래너</p>
          </div>
          <button
            onClick={onNewPlan}
            className="bg-white text-accent-500 md:text-primary-500 font-bold px-3 md:px-4 py-2 md:py-2.5 rounded-xl text-xs md:text-sm hover:bg-accent-50 md:hover:bg-primary-50 transition-colors flex-shrink-0 shadow-sm whitespace-nowrap"
          >
            + 새 플랜
          </button>
        </div>

        {/* ── 여행 예정 (D-day 카운트다운) ── */}
        {!loading && upcomingTrips.length > 0 && (
          <section className="mb-8">
            <h2 className="text-base font-bold text-gray-800 mb-3 flex items-center gap-2">🧳 다가오는 여행</h2>
            <div className="space-y-3">
              {visibleUpcoming.map(plan => {
                const dDay = daysUntil(plan.startDate)
                return (
                  <div
                    key={plan.uuid}
                    onClick={() => navigate(planHref(plan))}
                    className="bg-white rounded-2xl p-5 shadow-sm border border-primary-200 hover:shadow-md cursor-pointer transition-all"
                  >
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex-1 min-w-0">
                        <p className="font-bold text-gray-800 text-sm truncate">
                          {plan.title || plan.destination || '여행'}
                        </p>
                        <p className="text-xs text-gray-400 mt-0.5">
                          📍 {plan.destination} · {plan.startDate}{plan.endDate ? ` ~ ${plan.endDate}` : ''}
                        </p>
                      </div>
                      <div className="ml-3 flex-shrink-0 text-center">
                        {dDay === null ? (
                          <span className="block text-base font-black text-primary-500">여행 중</span>
                        ) : (
                          <>
                            <span className="block text-2xl font-black text-primary-500">D-{dDay}</span>
                            <span className="text-[10px] text-gray-400">
                              {dDay === 0 ? '오늘 출발!' : dDay === 1 ? '내일 출발!' : ''}
                            </span>
                          </>
                        )}
                      </div>
                    </div>
                    {plan.myRole === 'GUEST' && (
                      <span className="text-[10px] text-teal-600 bg-teal-50 px-2 py-0.5 rounded-full font-medium">초대받은 여행</span>
                    )}
                  </div>
                )
              })}
            </div>
            {upcomingTrips.length > UPCOMING_LIMIT && (
              <button
                onClick={() => setShowAllUpcoming(v => !v)}
                className="mt-3 w-full text-sm font-semibold text-primary-500 hover:text-primary-600 py-2"
              >
                {showAllUpcoming ? '접기' : `+ ${upcomingTrips.length - UPCOMING_LIMIT}개 더 보기`}
              </button>
            )}
          </section>
        )}

        {/* ── 진행 중인 플랜 ── */}
        <section className="mb-8">
          <h2 className="text-base font-bold text-gray-800 mb-3 flex items-center gap-2">
            🚀 진행 중인 플랜
            {!loading && activePlans.length > 0 && (
              <span className="bg-primary-100 text-primary-600 text-xs font-bold px-2 py-0.5 rounded-full">
                {activePlans.length}
              </span>
            )}
          </h2>

          {loading ? (
            <div className="space-y-3">
              {[1, 2].map(i => (
                <div key={i} className="bg-white rounded-2xl p-5 shadow-sm border border-gray-100 animate-pulse">
                  <div className="h-4 bg-gray-100 rounded w-2/3 mb-3" />
                  <div className="h-1.5 bg-gray-100 rounded-full" />
                </div>
              ))}
            </div>
          ) : activePlans.length === 0 ? (
            <div className="bg-white rounded-2xl p-8 shadow-sm border border-gray-100 text-center">
              <p className="text-3xl mb-2">✈️</p>
              <p className="text-gray-500 text-sm">진행 중인 플랜이 없어요</p>
              <button
                onClick={onNewPlan}
                className="mt-3 text-primary-500 font-semibold text-sm hover:underline"
              >
                새 플랜 만들기 →
              </button>
            </div>
          ) : (
            <div className="space-y-3">
              {activePlans.map(plan => <PlanCard key={plan.uuid} plan={plan} onTitleUpdate={handleTitleUpdate} onDelete={handlePlanDelete} />)}
            </div>
          )}
        </section>

        {/* ── 지난 여행 ── */}
        {!loading && pastPlans.length > 0 && (
          <section>
            <h2 className="text-base font-bold text-gray-800 mb-3">📁 지난 여행</h2>
            <div className="bg-white rounded-2xl shadow-sm border border-gray-100 px-5 py-2">
              {pastPlans.map(plan => <PastPlanRow key={plan.uuid} plan={plan} />)}
            </div>
          </section>
        )}
      </div>

      <BottomTabBar />
    </div>
  )
}

// ── 비로그인 랜딩 ──────────────────────────────────────────
function LandingHero() {
  const navigate = useNavigate()
  const onStart = () => navigate('/new')

  return (
    <div className="min-h-screen bg-gray-50 pb-20 md:pb-0">
      <NavBar />

      <div className="max-w-4xl mx-auto px-4 md:px-6 pt-12 md:pt-24 pb-16">
        <h1 className="text-3xl md:text-5xl font-black text-gray-900 leading-tight mb-4">
          여행 대화방 파일 하나로<br />
          <span className="text-accent-500 md:text-primary-500">맞춤 일정이 완성됩니다</span>
        </h1>
        <p className="text-gray-500 text-base md:text-lg mb-6">
          카카오톡 단체방 대화를 업로드하면<br />
          AI가 A안·B안 일정을 자동 생성해요.
        </p>
        <div className="flex flex-wrap gap-2 md:gap-3 mb-8">
          <span className="bg-white border border-gray-200 rounded-full px-3 md:px-4 py-1.5 md:py-2 text-xs md:text-sm text-gray-600">🤖 AI 자동 분석</span>
          <span className="bg-white border border-gray-200 rounded-full px-3 md:px-4 py-1.5 md:py-2 text-xs md:text-sm text-gray-600">🗳️ 투표로 확정</span>
          <span className="bg-white border border-gray-200 rounded-full px-3 md:px-4 py-1.5 md:py-2 text-xs md:text-sm text-gray-600">📍 카카오맵</span>
        </div>
        <button
          onClick={onStart}
          className="bg-accent-500 hover:bg-accent-600 md:bg-primary-500 md:hover:bg-primary-600 text-white font-bold px-6 md:px-8 py-3 md:py-4 rounded-xl text-base md:text-lg transition-colors mb-3"
        >
          🚀 일정 초안 만들기
        </button>
        <p className="text-gray-400 text-sm">↓ 어떻게 만들어지는지 궁금하다면</p>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 md:gap-4 mt-12 md:mt-16">
          {[
            { num: '01', color: 'bg-slate-400',   title: '파일 업로드', desc: '카카오톡 .txt 업로드' },
            { num: '02', color: 'bg-amber-400',   title: 'AI 자동 분석', desc: '취향·예산 자동 파악' },
            { num: '03', color: 'bg-teal-400',    title: 'A·B안 비교',  desc: '상반된 두 일정 비교' },
            { num: '04', color: 'bg-violet-400',  title: '투표 → 추천', desc: '투표 기반 추천 일정 제안' },
          ].map((card) => (
            <div key={card.num} className="bg-white rounded-2xl p-4 md:p-5 shadow-sm">
              <div className={`${card.color} text-white text-xs font-bold w-8 h-8 rounded-lg flex items-center justify-center mb-3`}>
                {card.num}
              </div>
              <p className="font-bold text-gray-800 mb-1 text-sm md:text-base">{card.title}</p>
              <p className="text-gray-400 text-xs md:text-sm">{card.desc}</p>
            </div>
          ))}
        </div>
      </div>

      <BottomTabBar />
    </div>
  )
}

// ── 메인 페이지 ────────────────────────────────────────────
export default function S1LandingPage() {
  const { user } = useAuth()

  if (user === undefined) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <p className="text-3xl mb-3">🗺️</p>
          <p className="text-gray-400 text-sm">불러오는 중...</p>
        </div>
      </div>
    )
  }

  if (!user) return <LandingHero />
  return <LoggedInHome />
}
