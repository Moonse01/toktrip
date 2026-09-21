/**
 * S4DashboardPage.jsx
 * 호스트 대시보드 — 2컬럼 레이아웃 (콘텐츠 + 사이드패널)
 * 라우트: /dashboard/:uuid
 */

import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { finalizePlan, getDashboard, getPlan, sharePlan } from '../api/plans'
import { transformPlan } from '../utils/planTransforms'
import useIsMobile from '../hooks/useIsMobile'
import BottomTabBar from '../components/BottomTabBar'
import DeletePlanSection from '../components/DeletePlanSection'

// ── 헬퍼 ────────────────────────────────────────────────────────
function getPreferenceScore(counts) {
  return Number(counts[2] || 0) + Number(counts[3] || 0) * 2 + Number(counts[4] || 0) * 3
}

function formatOrderChange(o) {
  if (o.fromDayNumber === o.toDayNumber) {
    return `${o.fromOrderIndex}번째 → ${o.toOrderIndex}번째`
  }
  return `${o.fromDayNumber}일차 ${o.fromOrderIndex}번째 → ${o.toDayNumber}일차 ${o.toOrderIndex}번째`
}

function buildParticipants(votedCount, totalParticipants) {
  const total = Math.max(totalParticipants || 0, votedCount || 0)
  return Array.from({ length: total }, (_, idx) => ({
    id: idx,
    name: `참여자 ${idx + 1}`,
    voted: idx < votedCount,
    voteCount: 0,
    votes: [],
  }))
}

function transformPlaceStats(placeStats = []) {
  const stats = placeStats.map(stat => {
    const counts = {
      1: Number(stat.dislikeCount || 0),
      2: Number(stat.likeCount || 0),
      3: Number(stat.superLikeCount || 0),
      4: Number(stat.pinCount || 0),
    }
    return {
      id: stat.placeId,
      name: stat.placeName,
      candidateLabel: stat.candidateLabel,
      dayNumber: stat.dayNumber,
      orderIndex: stat.orderIndex,
      counts,
      totalVotes: Number(stat.totalVotes || 0),
      isTop: false,
      isWarn: false,
    }
  })
  const maxPositive = Math.max(0, ...stats.map(p => p.counts[3] + p.counts[4]))
  const maxDislike  = Math.max(0, ...stats.map(p => p.counts[1]))
  return stats.map(p => ({
    ...p,
    isTop:  maxPositive > 0 && p.counts[3] + p.counts[4] === maxPositive,
    isWarn: maxDislike  > 0 && p.counts[1] === maxDislike,
  }))
}

function buildAiInsight(stats) {
  if (!stats.length) return null
  const topBy = sc => {
    const best = stats.reduce((b, p) => (!b || p.counts[sc] > b.counts[sc] ? p : b), null)
    return best && best.counts[sc] > 0 ? { name: best.name, count: best.counts[sc] } : null
  }
  const totals = stats.reduce((acc, p) => {
    const l = p.candidateLabel || '기타'
    acc[l] = (acc[l] || 0) + getPreferenceScore(p.counts)
    return acc
  }, {})
  const total = Object.values(totals).reduce((s, n) => s + n, 0)
  return {
    pin_top:  topBy(4),
    love_top: topBy(3),
    hate_top: topBy(1),
    a_ratio: total > 0 ? Math.round(((totals.A || 0) / total) * 100) : 0,
    b_ratio: total > 0 ? Math.round(((totals.B || 0) / total) * 100) : 0,
    has_preference_votes: total > 0,
  }
}

function formatReminderDate(startDate) {
  if (!startDate) return '여행 전날 오전 9시'
  const d = new Date(startDate)
  d.setDate(d.getDate() - 1)
  return `${d.getFullYear()}.${String(d.getMonth()+1).padStart(2,'0')}.${String(d.getDate()).padStart(2,'0')} 오전 9시`
}

const PLAN_COLORS = {
  A: { bg: '#EEF4FF', text: '#6B8EDB' },
  B: { bg: '#FFF6F2', text: '#E87B5E' },
}

const VOTE_SCORE_META = {
  1: { emoji: '😴', label: '싫어요', color: '#94A3B8' },
  2: { emoji: '🔥', label: '따봉',   color: '#FF9500' },
  3: { emoji: '💗', label: '왕따봉', color: '#FF69B4' },
  4: { emoji: '📌', label: '고정',   color: '#E87B5E' },
}

// ── 서브컴포넌트 ─────────────────────────────────────────────────

function PlaceCard({ place, rank }) {
  const planColor = PLAN_COLORS[place.candidateLabel] || PLAN_COLORS.A
  const reactions = [
    { key: 4, icon: '📌', count: place.counts[4], color: '#E87B5E' },
    { key: 3, icon: '💗', count: place.counts[3], color: '#FF69B4' },
    { key: 2, icon: '🔥', count: place.counts[2], color: '#FF9500' },
    { key: 1, icon: '😴', count: place.counts[1], color: '#94A3B8' },
  ].filter(r => r.count > 0)

  const totalVotes = place.totalVotes
  const barWidth = Math.min(totalVotes * 25, 100)

  return (
    <div
      style={{ background: '#fff', borderRadius: 14, border: '1px solid #EAECF0', padding: '16px 18px', transition: 'box-shadow 0.15s', cursor: 'default' }}
      onMouseEnter={e => e.currentTarget.style.boxShadow = '0 4px 20px rgba(0,0,0,0.08)'}
      onMouseLeave={e => e.currentTarget.style.boxShadow = 'none'}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ width: 28, height: 28, borderRadius: '50%', background: '#0F172A', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 800, flexShrink: 0 }}>{rank}</div>
          <span style={{ fontSize: 14, fontWeight: 700, color: '#0F172A' }}>{place.name}</span>
        </div>
        {place.isTop && (
          <span style={{ fontSize: 10, fontWeight: 700, color: '#E87B5E', background: '#FFF0ED', padding: '2px 8px', borderRadius: 99 }}>🔥 인기 TOP</span>
        )}
      </div>

      <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: planColor.text, background: planColor.bg, padding: '2px 8px', borderRadius: 99 }}>{place.candidateLabel}안</span>
        <span style={{ fontSize: 11, color: '#64748B', background: '#F8FAFC', padding: '2px 8px', borderRadius: 99 }}>Day {place.dayNumber}</span>
        {place.isWarn && <span style={{ fontSize: 11, color: '#EF4444', background: '#FEF2F2', padding: '2px 8px', borderRadius: 99 }}>⚠️ 주의</span>}
      </div>

      {reactions.length > 0 ? (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {reactions.map(r => (
            <div key={r.key} style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '4px 10px', background: r.color + '12', borderRadius: 99 }}>
              <span style={{ fontSize: 13 }}>{r.icon}</span>
              <span style={{ fontSize: 12, fontWeight: 700, color: r.color }}>{r.count}</span>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ fontSize: 12, color: '#CBD5E1' }}>아직 투표 없음</div>
      )}

      <div style={{ marginTop: 12, height: 4, background: '#F1F5F9', borderRadius: 99, overflow: 'hidden' }}>
        <div style={{ width: `${barWidth}%`, height: '100%', background: 'linear-gradient(90deg, #E87B5E, #F59478)', borderRadius: 99 }} />
      </div>
    </div>
  )
}

function ParticipantRow({ participant: p }) {
  const colorPalette = ['#7C5CBF', '#6B8EDB', '#E87B5E', '#EC4899', '#10B981', '#6366F1']
  const color = colorPalette[p.name.charCodeAt(0) % colorPalette.length]
  const initial = p.name.charAt(0)

  return (
    <div style={{ display: 'flex', gap: 16, padding: '14px 0', borderBottom: '1px solid #F1F5F9' }}>
      <div style={{ width: 38, height: 38, borderRadius: '50%', background: color, color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, fontWeight: 800, flexShrink: 0 }}>{initial}</div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 14, fontWeight: 700, color: '#0F172A', marginBottom: 8 }}>
          {p.name}
          <span style={{ fontSize: 12, color: '#94A3B8', fontWeight: 400, marginLeft: 6 }}>
            {p.voted ? `${p.voteCount || 0}개 장소 투표` : '아직 투표 전'}
          </span>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {(p.votes || []).map((v, i) => {
            const meta = VOTE_SCORE_META[v.voteScore] || { emoji: '•', color: '#94A3B8' }
            return (
              <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: '5px 10px', background: meta.color + '12', borderRadius: 12, fontSize: 12 }}>
                <span style={{ display: 'flex', alignItems: 'center', gap: 4, color: '#334155' }}>
                  <span>{meta.emoji}</span>
                  <span>{v.candidateLabel}안 · {v.placeName}</span>
                </span>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function ConfirmModal({ onConfirm, onClose, confirming }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-2xl p-8 max-w-md mx-4 shadow-2xl w-full text-center">
        <div className="text-5xl mb-4">🎯</div>
        <h3 className="text-lg font-black text-gray-900 mb-2">AI 추천 일정을 만들까요?</h3>
        <p className="text-sm text-gray-500 mb-6">친구들의 투표와 의견을 반영해서 A안+B안의 좋은 점을 합친 추천 일정을 만들어요.</p>
        <button onClick={onConfirm} disabled={confirming} className="w-full bg-primary-500 hover:bg-primary-600 text-white font-bold py-3.5 rounded-xl transition-colors mb-3">
          {confirming ? '추천 일정 만드는 중...' : '✅ AI 추천 일정 받기'}
        </button>
        <button onClick={onClose} disabled={confirming} className="text-sm text-gray-400 hover:text-gray-600 transition-colors">다시 생각해볼게요</button>
      </div>
    </div>
  )
}

function VoteReturnCard({ label, loading, error, onClick }) {
  return (
    <div style={{ background: '#fff', borderRadius: 16, border: '1px solid #EAECF0', padding: 20 }}>
      <div style={{ fontSize: 13, fontWeight: 800, color: '#0F172A', marginBottom: 6 }}>🗳 투표 화면으로 가기</div>
      <div style={{ fontSize: 12, color: '#64748B', lineHeight: 1.55, marginBottom: 14 }}>
        아직 투표하지 않았거나 의견을 바꾸고 싶다면 투표 화면으로 돌아갈 수 있어요.
      </div>
      <button
        onClick={onClick}
        disabled={loading}
        style={{
          width: '100%',
          border: '1px solid #FED7CC',
          background: loading ? '#FFF0ED' : '#FFF7F4',
          color: '#D0624A',
          borderRadius: 10,
          padding: '11px 0',
          fontSize: 14,
          fontWeight: 800,
          cursor: loading ? 'wait' : 'pointer',
          transition: 'background 0.15s',
        }}
      >
        {loading ? '투표 화면 준비 중...' : label}
      </button>
      {error && (
        <div style={{ marginTop: 8, fontSize: 11, color: '#EF4444', lineHeight: 1.45 }}>
          {error}
        </div>
      )}
    </div>
  )
}

const FINALIZE_MESSAGES = [
  'A안과 B안을 나란히 펼쳐보고 있어요...',
  '친구들의 투표와 고정 장소를 모으는 중...',
  '왕따봉·따봉이 많은 곳을 추려내고 있어요...',
  '동선을 자연스럽게 다시 잇는 중...',
  '거의 다 됐어요! 추천 일정을 정리하고 있어요...',
]

function FinalizingOverlay() {
  const [step, setStep] = useState(0)
  useEffect(() => {
    const id = setInterval(() => setStep(s => (s + 1) % FINALIZE_MESSAGES.length), 1800)
    return () => clearInterval(id)
  }, [])
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-white/95 backdrop-blur-sm">
      <div className="text-center px-6">
        <div className="w-16 h-16 border-4 border-primary-200 border-t-primary-500 rounded-full animate-spin mx-auto mb-5" />
        <p className="text-lg font-black text-gray-800 mb-1">AI가 추천 일정을 만들고 있어요</p>
        <p className="text-sm text-gray-500 transition-opacity duration-500">{FINALIZE_MESSAGES[step]}</p>
        <p className="text-xs text-gray-400 mt-3">A안·B안·투표 결과를 종합하는 중이에요. 잠시만요!</p>
      </div>
    </div>
  )
}

// ── 메인 ─────────────────────────────────────────────────────────
export default function S4DashboardPage() {
  const { uuid } = useParams()
  const navigate = useNavigate()
  const isMobile = useIsMobile()

  const [plan, setPlan]                 = useState(null)
  const [voteMeta, setVoteMeta]         = useState(null)
  const [placesStats, setPlacesStats]   = useState([])
  const [orderChanges, setOrderChanges] = useState([])
  const [aiInsight, setAiInsight]       = useState(null)
  const [myRole, setMyRole]             = useState('HOST')
  const [loading, setLoading]           = useState(true)
  const [error, setError]               = useState(null)
  const [confirming, setConfirming]     = useState(false)
  const [showConfirmModal, setShowConfirmModal] = useState(false)
  const [openingVote, setOpeningVote]   = useState(false)
  const [voteActionError, setVoteActionError] = useState(null)
  const [activeSort, setActiveSort]     = useState('total')
  const [activeCand, setActiveCand]     = useState('ALL')
  const [showAllPlaces, setShowAllPlaces] = useState(false)
  const [expandedVoters, setExpandedVoters] = useState(new Set())

  useEffect(() => {
    let cancelled = false
    async function fetchData() {
      setLoading(true)
      setError(null)
      try {
        const [planRes, dashboardRes] = await Promise.all([getPlan(uuid), getDashboard(uuid)])
        if (cancelled) return

        const fetchedPlan   = transformPlan(planRes.data.data)
        const dashboard     = dashboardRes.data.data
        const participantCount = Number(fetchedPlan.participant_count || dashboard.totalParticipants || dashboard.votedParticipants || 0)
        const votedCount    = Number(dashboard.votedParticipants || 0)
        const transformed   = transformPlaceStats(dashboard.placeStats || [])

        setPlan({ ...fetchedPlan, plan_id: dashboard.planId, uuid: dashboard.planUuid || fetchedPlan.uuid, status: dashboard.status || fetchedPlan.status, participant_count: Math.max(participantCount, votedCount) })

        const participants = (dashboard.participants || []).map(p => ({
          id: p.userId, name: p.displayName || '참여자', voted: true, voteCount: Number(p.voteCount || 0), votes: p.votes || [],
        }))
        setVoteMeta({
          voted_count: votedCount,
          link_visitors: votedCount,
          alarm_recipients: Math.max(participantCount - votedCount, 0),
          participant_count_known: dashboard.participantCountKnown === true,
          participants: participants.length > 0 ? participants : buildParticipants(votedCount, Math.max(participantCount, votedCount)),
          comments: (dashboard.comments || []).map((c, idx) => ({ id: c.commentId || idx, name: c.voterName || '게스트', text: c.comment, time: c.updatedAt || '' })),
        })
        setPlacesStats(transformed)
        setOrderChanges(dashboard.orderChanges || [])
        setMyRole(dashboard.myRole || 'HOST')
        setAiInsight(buildAiInsight(transformed))
      } catch (err) {
        if (!cancelled) {
          if (err.response?.status === 403) setError('이 플랜의 참여자만 대시보드에 접근할 수 있습니다.')
          else if (err.response?.status === 401) setError('대시보드를 보려면 호스트 로그인이 필요합니다.')
          else setError('투표 결과를 불러오는 데 실패했습니다.')
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchData()
    return () => { cancelled = true }
  }, [uuid])

  const handleConfirm = async () => {
    setConfirming(true)
    try {
      await finalizePlan(uuid)
      navigate(`/confirmed/${uuid}`)
    } catch {
      setError('추천 일정 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      setShowConfirmModal(false)
    } finally {
      setConfirming(false)
    }
  }

  const handleVotePageClick = async () => {
    setVoteActionError(null)
    if (plan?.status === 'CONFIRMED' || plan?.status === 'COMPLETED') {
      setVoteActionError('이미 확정된 일정은 투표 화면을 다시 열 수 없어요.')
      return
    }

    setOpeningVote(true)
    try {
      if (plan?.status === 'AI_DONE') {
        await sharePlan(uuid)
        setPlan(prev => prev ? { ...prev, status: 'VOTING' } : prev)
      }
      navigate(`/vote/${uuid}`)
    } catch (err) {
      console.error('투표 화면 이동 준비 실패:', err)
      setVoteActionError(err.response?.data?.message || '투표 화면을 여는 데 실패했어요. 잠시 후 다시 시도해 주세요.')
    } finally {
      setOpeningVote(false)
    }
  }

  if (loading) return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center">
      <div className="text-center">
        <div className="w-12 h-12 border-4 border-primary-200 border-t-primary-500 rounded-full animate-spin mx-auto mb-4" />
        <p className="text-gray-500 text-sm">투표 결과를 불러오고 있어요...</p>
      </div>
    </div>
  )

  if (error) return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center">
      <div className="text-center">
        <p className="text-red-500 text-sm mb-3">{error}</p>
        <button onClick={() => window.location.reload()} className="text-sm text-primary-500 hover:underline">다시 시도</button>
      </div>
    </div>
  )

  if (!plan || !voteMeta) return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center">
      <p className="text-gray-400 text-sm">데이터를 불러올 수 없습니다.</p>
    </div>
  )

  const participantCount = Math.max(plan.participant_count || 0, voteMeta.voted_count || 0, 1)
  const participantCountKnown = voteMeta.participant_count_known
  const pct        = participantCountKnown ? Math.round((voteMeta.voted_count / participantCount) * 100) : null
  const isMajority = participantCountKnown
    ? voteMeta.voted_count >= Math.ceil(participantCount / 2)
    : voteMeta.voted_count > 0
  const hasPreferenceVotes = aiInsight?.has_preference_votes === true
  const aPct = hasPreferenceVotes ? aiInsight.a_ratio : 0
  const bPct = hasPreferenceVotes ? aiInsight.b_ratio : 0
  const canReturnToVote = plan.status === 'AI_DONE' || plan.status === 'VOTING'
  const voteReturnLabel = plan.status === 'AI_DONE' ? '🗳 투표 열고 화면으로 가기' : '🗳 투표 화면으로 가기'

  const groupedOrderChanges = orderChanges.reduce((acc, o) => {
    const key = o.voterName || '게스트'
    if (!acc[key]) acc[key] = []
    acc[key].push(o)
    return acc
  }, {})
  const toggleVoter = (name) => {
    setExpandedVoters(prev => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  const SORT_SCORE = { total: null, pin: 4, superlike: 3, like: 2, dislike: 1 }
  const filteredPlaces = placesStats.filter(p =>
    (activeCand === 'ALL' || p.candidateLabel === activeCand) &&
    (SORT_SCORE[activeSort] === null || p.counts[SORT_SCORE[activeSort]] > 0)
  )
  const sortedPlaces = [...filteredPlaces].sort((a, b) => {
    const sc = SORT_SCORE[activeSort]
    if (sc === null) return (b.totalVotes - a.totalVotes)
    return (b.counts[sc] - a.counts[sc])
  })
  const PLACE_LIMIT = isMobile ? 3 : 6
  const visiblePlaces = showAllPlaces ? sortedPlaces : sortedPlaces.slice(0, PLACE_LIMIT)
  const hasMore = sortedPlaces.length > PLACE_LIMIT

  return (
    <div style={{ minHeight: '100vh', background: '#F7F8FC', fontFamily: "'Pretendard','Apple SD Gothic Neo',sans-serif" }}>

      {/* ── HEADER ── */}
      {isMobile ? (
        <header style={{ background: '#fff', borderBottom: '1px solid #EAECF0', padding: '10px 16px', display: 'flex', flexDirection: 'column', gap: 6, position: 'sticky', top: 0, zIndex: 50 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
            <span
              onClick={() => navigate(`/vote/${uuid}`)}
              title="투표 화면으로 이동"
              style={{ fontSize: 14, fontWeight: 700, color: '#0F172A', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1, minWidth: 0, cursor: 'pointer' }}
            >
              📋 {plan.title || '여행 플랜'} ›
            </span>
            <span style={{ fontSize: 11, fontWeight: 700, color: '#E87B5E', flexShrink: 0 }}>
              {participantCountKnown ? `${voteMeta.voted_count}/${participantCount}명` : `${voteMeta.voted_count}명 참여`}
            </span>
          </div>
        </header>
      ) : (
        <header style={{ background: '#fff', borderBottom: '1px solid #EAECF0', padding: '0 32px', height: 56, display: 'grid', gridTemplateColumns: '1fr auto 1fr', alignItems: 'center', position: 'sticky', top: 0, zIndex: 50 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <button onClick={() => navigate('/')} style={{ fontSize: 13, color: '#64748B', background: 'none', border: 'none', cursor: 'pointer' }}>← 홈으로</button>
            <span style={{ color: '#E2E8F0' }}>|</span>
            <span onClick={() => navigate('/')} style={{ fontSize: 15, fontWeight: 700, color: '#0F172A', cursor: 'pointer' }}>📋 TokTrip</span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 12, justifySelf: 'center' }}>
            <span
              onClick={() => navigate(`/vote/${uuid}`)}
              title="투표 화면으로 이동"
              style={{ display: 'inline-flex', alignItems: 'center', gap: 5, maxWidth: 460, fontSize: 18, fontWeight: 700, color: '#0F172A', cursor: 'pointer' }}
            >
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{plan.title || '여행 플랜'}</span>
              <span style={{ fontSize: 15, color: '#94A3B8', flexShrink: 0 }}>›</span>
            </span>
          </div>

          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 12, color: '#64748B', marginBottom: 2 }}>투표 현황</div>
            <div style={{ fontSize: 13, fontWeight: 700, color: '#E87B5E' }}>
              {participantCountKnown ? `${voteMeta.voted_count}명 / ${participantCount}명 완료` : `현재 ${voteMeta.voted_count}명 참여`}
            </div>
          </div>
        </header>
      )}

      {/* ── PROGRESS BAR ── */}
      <div style={{ background: '#fff', padding: isMobile ? '10px 16px 12px' : '10px 32px 12px', borderBottom: '1px solid #EAECF0' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <span style={{ fontSize: 12, color: '#64748B', fontWeight: 600 }}>투표 진행률</span>
          <span style={{ fontSize: 12, fontWeight: 700, color: '#E87B5E' }}>
            {participantCountKnown ? `${pct}%` : `${voteMeta.voted_count}명 참여 중`}
          </span>
          {participantCountKnown && isMajority && (
            <span style={{ fontSize: 11, color: '#22C55E', fontWeight: 600, background: '#F0FDF4', padding: '2px 8px', borderRadius: 99 }}>🔥 과반수 달성! 추천 일정을 생성할 수 있어요.</span>
          )}
          {!participantCountKnown && voteMeta.voted_count > 0 && (
            <span style={{ fontSize: 11, color: '#6B8EDB', fontWeight: 600, background: '#EFF6FF', padding: '2px 8px', borderRadius: 99 }}>참여 인원 미정 · 현재 투표 결과로 확정할 수 있어요.</span>
          )}
        </div>
        {participantCountKnown && (
          <div style={{ height: 6, background: '#F1F5F9', borderRadius: 99, overflow: 'hidden' }}>
            <div style={{ width: `${pct}%`, height: '100%', background: 'linear-gradient(90deg,#E87B5E,#F59478)', borderRadius: 99, transition: 'width 0.6s ease' }} />
          </div>
        )}
      </div>

      {/* ── MAIN 2-COL (데스크탑) / 1-COL (모바일) ── */}
      <main style={{ maxWidth: 1360, margin: '0 auto', padding: isMobile ? '16px 12px 0' : '28px 32px', display: 'grid', gridTemplateColumns: isMobile ? 'minmax(0, 1fr)' : 'minmax(0, 1fr) 380px', gap: isMobile ? 14 : 24 }}>

        {/* ── LEFT ── */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20, minWidth: 0 }}>

          {/* A/B 후보 필터 */}
          <div style={{ display: 'flex', gap: 8 }}>
            {[
              { key: 'ALL', label: '전체' },
              { key: 'A', label: 'A안', color: '#6B8EDB' },
              { key: 'B', label: 'B안', color: '#E87B5E' },
            ].map(c => {
              const active = activeCand === c.key
              const accent = c.color || '#0F172A'
              return (
                <button
                  key={c.key}
                  onClick={() => { setActiveCand(c.key); setShowAllPlaces(false) }}
                  style={{
                    flex: 1, padding: isMobile ? '7px 0' : '8px 0', borderRadius: 10,
                    border: active ? `1.5px solid ${accent}` : '1px solid #E2E8F0',
                    background: active ? accent + '12' : '#fff',
                    color: active ? accent : '#64748B',
                    fontSize: isMobile ? 13 : 14, fontWeight: 700, cursor: 'pointer', transition: 'all 0.15s',
                  }}
                >{c.label}</button>
              )
            })}
          </div>

          {/* 정렬 탭 */}
          <div style={{ display: 'flex', gap: 8, flexWrap: 'nowrap', overflowX: 'auto', WebkitOverflowScrolling: 'touch' }}>
            {[
              { key: 'total',    label: '전체' },
              { key: 'pin',      label: '📌 고정' },
              { key: 'superlike',label: '💗 왕따봉' },
              { key: 'like',     label: '🔥 따봉' },
              { key: 'dislike',  label: '😴 싫어요' },
              ...(orderChanges.length > 0 ? [{ key: 'reorder', label: '🔀 순서 변경' }] : []),
            ].map(t => (
              <button key={t.key} onClick={() => { setActiveSort(t.key); setShowAllPlaces(false) }} style={{ flexShrink: 0, padding: isMobile ? '6px 10px' : '6px 16px', borderRadius: 99, border: activeSort === t.key ? 'none' : '1px solid #E2E8F0', background: activeSort === t.key ? '#0F172A' : '#fff', color: activeSort === t.key ? '#fff' : '#64748B', fontSize: isMobile ? 12 : 13, fontWeight: 600, cursor: 'pointer', transition: 'all 0.15s', whiteSpace: 'nowrap' }}>{t.label}</button>
            ))}
          </div>

          {/* 장소 카드 / 순서 변경 뷰 */}
          {activeSort === 'reorder' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {Object.entries(groupedOrderChanges).map(([voterName, changes]) => (
                <div key={voterName} style={{ background: '#F0FDF4', border: '1px solid #BBF7D0', borderRadius: 12, overflow: 'hidden' }}>
                  <button
                    onClick={() => toggleVoter(voterName)}
                    style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', background: 'transparent', border: 'none', cursor: 'pointer' }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ fontSize: 13 }}>🔀</span>
                      <span style={{ fontSize: 13, fontWeight: 600, color: '#166534' }}>
                        {voterName}님이 {changes.length}건의 순서 변경을 제안했어요
                      </span>
                    </div>
                    <span style={{ fontSize: 12, color: '#15803D', transition: 'transform 0.2s', transform: expandedVoters.has(voterName) ? 'rotate(180deg)' : 'none', display: 'inline-block' }}>▼</span>
                  </button>
                  {expandedVoters.has(voterName) && (
                    <div style={{ padding: '0 16px 12px', display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {changes.map((o, i) => {
                        const planColor = PLAN_COLORS[o.candidateLabel] || PLAN_COLORS.A
                        return (
                          <div key={`${o.placeId}-${i}`} style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#fff', border: '1px solid #DCFCE7', borderRadius: 8, padding: '8px 12px' }}>
                            <span style={{ fontSize: 11, fontWeight: 600, color: planColor.text, background: planColor.bg, padding: '2px 6px', borderRadius: 6 }}>{o.candidateLabel}안</span>
                            <span style={{ fontSize: 12, fontWeight: 700, color: '#0F172A' }}>{o.placeName}</span>
                            <span style={{ fontSize: 12, color: '#15803D', fontWeight: 600 }}>{formatOrderChange(o)}</span>
                          </div>
                        )
                      })}
                    </div>
                  )}
                </div>
              ))}
            </div>
          ) : (
          <div>
            <div style={{ display: 'grid', gridTemplateColumns: isMobile ? '1fr' : 'repeat(auto-fill,minmax(280px,1fr))', gap: 14 }}>
              {visiblePlaces.length > 0 ? (
                visiblePlaces.map((place, i) => <PlaceCard key={place.id} place={place} rank={i + 1} />)
              ) : (
                <p style={{ fontSize: 13, color: '#CBD5E1', gridColumn: '1/-1', textAlign: 'center', padding: '32px 0' }}>아직 투표 데이터가 없습니다.</p>
              )}
            </div>
            {hasMore && (
              <button
                onClick={() => setShowAllPlaces(prev => !prev)}
                style={{ marginTop: 14, width: '100%', padding: '10px 0', borderRadius: 10, border: '1px solid #E2E8F0', background: '#fff', color: '#64748B', fontSize: 13, fontWeight: 600, cursor: 'pointer', transition: 'background 0.15s' }}
                onMouseEnter={e => e.currentTarget.style.background = '#F8FAFC'}
                onMouseLeave={e => e.currentTarget.style.background = '#fff'}
              >
                {showAllPlaces
                  ? `▲ 접기`
                  : `▼ 더보기 (${sortedPlaces.length - PLACE_LIMIT}개 더 있어요)`}
              </button>
            )}
          </div>
          )}

          {/* 순서 변경 알림 (전체 탭에서만) */}
          {activeSort === 'total' && orderChanges.length > 0 && (
            <button
              onClick={() => setActiveSort('reorder')}
              style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 16px', background: '#F0FDF4', border: '1px solid #BBF7D0', borderRadius: 10, cursor: 'pointer', transition: 'background 0.15s' }}
              onMouseEnter={e => e.currentTarget.style.background = '#DCFCE7'}
              onMouseLeave={e => e.currentTarget.style.background = '#F0FDF4'}
            >
              <span style={{ fontSize: 13, fontWeight: 600, color: '#166534' }}>🔀 순서 변경 제안 {orderChanges.length}건</span>
              <span style={{ fontSize: 12, color: '#15803D' }}>보기 →</span>
            </button>
          )}

          {/* 게스트 의견 모아보기 */}
          <section style={{ background: '#fff', borderRadius: 16, border: '1px solid #EAECF0', padding: 24 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <h3 style={{ fontSize: 15, fontWeight: 700, color: '#0F172A', margin: 0 }}>💬 게스트 의견 모아보기</h3>
              <span style={{ fontSize: 12, color: '#94A3B8' }}>최신순</span>
            </div>
            {voteMeta.comments.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '24px 0' }}>
                <div style={{ fontSize: 24, marginBottom: 8 }}>💬</div>
                <p style={{ fontSize: 13, color: '#CBD5E1', margin: 0 }}>아직 등록된 의견이 없어요.</p>
                <p style={{ fontSize: 12, color: '#E2E8F0', margin: '4px 0 0' }}>투표 화면에서 의견을 남길 수 있어요.</p>
              </div>
            ) : (
              voteMeta.comments.map(c => (
                <div key={c.id} style={{ padding: '10px 0', borderBottom: '1px solid #F8FAFC' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                    <span style={{ fontSize: 13, fontWeight: 700, color: '#0F172A' }}>{c.name}</span>
                    <span style={{ fontSize: 11, color: '#94A3B8' }}>{c.time}</span>
                  </div>
                  <p style={{ fontSize: 13, color: '#475569', margin: 0 }}>{c.text}</p>
                </div>
              ))
            )}
          </section>

          {/* 참여자 투표 현황 섹션은 장소 카드·우측 패널과 정보가 중복되어 제거 (투머치 피드백) */}
        </div>

        {/* ── RIGHT PANEL ── */}
        <aside style={{ display: 'flex', flexDirection: 'column', gap: 16, minWidth: 0 }}>

          {/* A vs B 비율 */}
          <div style={{ background: '#fff', borderRadius: 16, border: '1px solid #EAECF0', padding: 20 }}>
            <p style={{ fontSize: 13, fontWeight: 700, color: '#0F172A', margin: '0 0 14px' }}>🗳️ A안 vs B안 선호도</p>
            {hasPreferenceVotes ? (
              <>
                <div style={{ display: 'flex', borderRadius: 8, overflow: 'hidden', height: 28, marginBottom: 10 }}>
                  <div style={{ width: `${aPct}%`, background: '#6B8EDB', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 700, color: '#fff' }}>{aPct}%</div>
                  <div style={{ width: `${bPct}%`, background: '#E87B5E', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 700, color: '#fff' }}>{bPct}%</div>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ fontSize: 12, color: '#6B8EDB', fontWeight: 600 }}>A안 {aPct}%</span>
                  <span style={{ fontSize: 12, color: '#E87B5E', fontWeight: 600 }}>B안 {bPct}%</span>
                </div>
              </>
            ) : (
              <>
                <div style={{ height: 28, borderRadius: 8, background: '#F1F5F9', marginBottom: 10, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#94A3B8', fontSize: 11, fontWeight: 700 }}>
                  아직 선호 투표 없음
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ fontSize: 12, color: '#94A3B8', fontWeight: 600 }}>A안 0%</span>
                  <span style={{ fontSize: 12, color: '#94A3B8', fontWeight: 600 }}>B안 0%</span>
                </div>
              </>
            )}
          </div>

          {/* AI 분석 */}
          {aiInsight && (
            <div style={{ background: '#fff', borderRadius: 16, border: '1px solid #EAECF0', padding: 20 }}>
              <p style={{ fontSize: 13, fontWeight: 700, color: '#0F172A', margin: '0 0 14px' }}>🤖 AI 분석</p>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {[
                  { icon: '📌', color: '#E87B5E', label: '고정 최다',   data: aiInsight.pin_top },
                  { icon: '💗', color: '#FF69B4', label: '왕따봉 최다', data: aiInsight.love_top },
                  { icon: '😴', color: '#94A3B8', label: '싫어요 최다', data: aiInsight.hate_top, warn: true },
                ].map(item => (
                  <div key={item.label} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 10px', borderRadius: 8, background: '#F8FAFC' }}>
                    <span style={{ fontSize: 14 }}>{item.icon}</span>
                    <span style={{ fontSize: 12, color: '#64748B', flex: 1 }}>{item.label}</span>
                    {item.data ? (
                      <>
                        <span style={{ fontSize: 12, fontWeight: 600, color: item.warn ? '#EF4444' : item.color }}>{item.data.name} ({item.data.count}표)</span>
                        {item.warn && <span style={{ fontSize: 10 }}>⚠️</span>}
                      </>
                    ) : (
                      <span style={{ fontSize: 12, color: '#CBD5E1' }}>없음</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* CTA 카드 — 호스트만 */}
          {myRole === 'HOST' && (
          <div style={{ background: 'linear-gradient(135deg,#E87B5E 0%,#F59478 100%)', borderRadius: 16, padding: 20, color: '#fff' }}>
            <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 6 }}>✅ AI 추천 일정 받기</div>
            <div style={{ fontSize: 12, opacity: 0.85, marginBottom: 16 }}>투표 결과 + 고정 장소 + 의견을 반영한 추천</div>
            <button
              onClick={() => isMajority && setShowConfirmModal(true)}
              style={{ width: '100%', background: '#fff', color: '#D0624A', border: 'none', borderRadius: 10, padding: '11px 0', fontSize: 14, fontWeight: 800, cursor: isMajority ? 'pointer' : 'not-allowed', opacity: isMajority ? 1 : 0.5, boxShadow: '0 2px 8px rgba(0,0,0,0.15)' }}
            >{isMajority ? 'AI 추천 일정 받기 →' : '투표가 더 필요해요'}</button>
          </div>
          )}

          {/* 공유 + 리마인더 */}
          <div style={{ background: '#fff', borderRadius: 16, border: '1px solid #EAECF0', padding: 20, display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div>
              <div style={{ fontSize: 12, color: '#94A3B8', marginBottom: 6 }}>📤 공유 현황</div>
              <div style={{ display: 'flex', gap: 20 }}>
                <div>
                  <div style={{ fontSize: 20, fontWeight: 800, color: '#0F172A' }}>{voteMeta.link_visitors}명</div>
                  <div style={{ fontSize: 11, color: '#94A3B8' }}>투표 링크 접속자</div>
                </div>
                <div>
                  <div style={{ fontSize: 20, fontWeight: 800, color: '#0F172A' }}>{participantCountKnown ? `${voteMeta.alarm_recipients}명` : '-'}</div>
                  <div style={{ fontSize: 11, color: '#94A3B8' }}>푸시 알림 발송 예정</div>
                </div>
              </div>
            </div>

            <div style={{ height: 1, background: '#F1F5F9' }} />

            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <div style={{ fontSize: 12, color: '#94A3B8' }}>⏰ D-1 푸시 리마인더 예정</div>
                <button style={{ fontSize: 11, color: '#6B8EDB', background: 'none', border: '1px solid #EEF4FF', borderRadius: 6, padding: '2px 8px', cursor: 'pointer' }}>✏️ 수정</button>
              </div>
              <div style={{ background: '#FFFBEA', borderRadius: 10, padding: '10px 12px' }}>
                <div style={{ fontSize: 12, color: '#92400E', marginBottom: 4 }}>{formatReminderDate(plan.start_date)}에 자동 발송됩니다.</div>
                <div style={{ fontSize: 12, color: '#B45309', fontStyle: 'italic' }}>"{plan.destination || '여행'} 출발 하루 전! 준비물 챙기세요."</div>
              </div>
            </div>
          </div>

          {canReturnToVote && (
            <VoteReturnCard
              label={voteReturnLabel}
              loading={openingVote}
              error={voteActionError}
              onClick={handleVotePageClick}
            />
          )}

        </aside>
      </main>

      <div style={{ maxWidth: 1360, margin: '0 auto', padding: isMobile ? '0 16px 96px' : '0 32px 32px' }}>
        <DeletePlanSection uuid={uuid} status={plan.status} />
      </div>

      {showConfirmModal && <ConfirmModal onConfirm={handleConfirm} onClose={() => setShowConfirmModal(false)} confirming={confirming} />}
      {confirming && <FinalizingOverlay />}

      <BottomTabBar />
    </div>
  )
}
