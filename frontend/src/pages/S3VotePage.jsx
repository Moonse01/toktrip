/**
 * S3VotePage.jsx
 *
 * S3 — 게스트 투표 화면
 *
 * 게스트가 링크를 타고 들어와 각 장소에 [싫어요/따봉/왕따봉/고정] 투표하는 화면.
 * PlanViewLayout을 공유하되, renderCardFooter로 투표 버튼을 주입합니다.
 *
 * 라우트: /vote/:uuid
 * 위치: src/pages/S3VotePage.jsx
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import PlanViewLayout from '../components/PlanViewLayout';
import VoteCardModal from '../components/VoteCardModal';
import { getPlan, getCandidates, submitPlanComment, submitVotes } from '../api/plans';
import { kakaoLogin } from '../api/auth';
import { transformPlan, transformCandidate } from '../utils/planTransforms';
import { useAuth } from '../auth/AuthContext';
import { buildReorder } from '../utils/reorder';
import { buildVoteInviteText, shareVoteRequest } from '../utils/kakaoShare';

// ──────────────────────────────────────────────
// 투표 버튼 정의
// ──────────────────────────────────────────────
const VOTE_OPTIONS = [
  { score: 1, emoji: '👎', label: '싫어요', color: 'bg-red-50 hover:bg-red-100 text-red-600 border-red-200' },
  { score: 2, emoji: '👍', label: '따봉', color: 'bg-blue-50 hover:bg-blue-100 text-blue-600 border-blue-200' },
  { score: 3, emoji: '💖', label: '왕따봉', color: 'bg-pink-50 hover:bg-pink-100 text-pink-600 border-pink-200' },
  { score: 4, emoji: '📌', label: '고정', color: 'bg-amber-50 hover:bg-amber-100 text-amber-600 border-amber-200' },
];



function buildOrderSnapshot(candidates) {
  const snapshot = new Map();
  candidates.forEach(candidate => {
    candidate.places.forEach(place => {
      snapshot.set(Number(place.id), {
        candidateId: candidate.id,
        dayNumber: Number(place.day_number),
        orderIndex: Number(place.order_index),
      });
    });
  });
  return snapshot;
}

// S2 원본 순서 대비 실제로 위치가 바뀐 장소만 추린다.
function diffOrderPreferences(candidates, originalSnapshot) {
  const changed = [];
  candidates.forEach(candidate => {
    candidate.places.forEach(place => {
      const original = originalSnapshot.get(Number(place.id));
      if (!original) return;
      const displayCandidateChanged = Number(candidate.id) !== Number(original.candidateId);
      const dayChanged = Number(place.day_number) !== original.dayNumber;
      const orderChanged = Number(place.order_index) !== original.orderIndex;
      if (displayCandidateChanged || dayChanged || orderChanged) {
        changed.push({
          candidateId: original.candidateId,
          placeId: place.id,
          preferredDayNumber: place.day_number,
          preferredOrderIndex: place.order_index,
          displayCandidateId: candidate.id,
        });
      }
    });
  });
  return changed;
}

// 실제로 드래그한 카드만 남긴다 — 밀려난 카드는 순서 변경으로 보고하지 않음
function keepMovedOnly(changed, movedPlaceIds) {
  return changed.filter(pref => movedPlaceIds.has(Number(pref.placeId)));
}

function mergeOrderPreferences(previous, requests) {
  const merged = new Map();
  previous.forEach(request => merged.set(Number(request.placeId), request));
  requests.forEach(request => merged.set(Number(request.placeId), request));
  return [...merged.values()];
}

function orderPreferencesByPlaceId(orderPreferences) {
  const result = new Map();
  orderPreferences.forEach(pref => result.set(Number(pref.placeId), pref));
  return result;
}

function applyOrderPreferencesToCandidates(candidates, orderPreferences) {
  const prefByPlaceId = orderPreferencesByPlaceId(orderPreferences);
  const nextCandidates = candidates.map(candidate => ({ ...candidate, places: [] }));
  const nextById = new Map(nextCandidates.map(candidate => [Number(candidate.id), candidate]));

  candidates.forEach(candidate => {
    candidate.places.forEach(place => {
      const pref = prefByPlaceId.get(Number(place.id));
      const displayCandidateId = pref?.displayCandidateId ?? candidate.id;
      const targetCandidate = nextById.get(Number(displayCandidateId)) ?? nextById.get(Number(candidate.id));
      if (!targetCandidate) return;
      targetCandidate.places.push({
        ...place,
        day_number: pref?.preferredDayNumber ?? place.day_number,
        order_index: pref?.preferredOrderIndex ?? place.order_index,
      });
    });
  });

  return nextCandidates.map(candidate => ({
    ...candidate,
    places: candidate.places
      .sort((a, b) => {
        if (Number(a.day_number) !== Number(b.day_number)) {
          return Number(a.day_number) - Number(b.day_number);
        }
        return Number(a.order_index) - Number(b.order_index);
      }),
  }));
}

function toBatchOrderPreferences(orderPreferences) {
  return orderPreferences.map(({ displayCandidateId, ...request }) => request);
}

function normalizeCandidatePlaces(candidate) {
  const grouped = new Map();
  candidate.places.forEach(place => {
    const day = Math.max(1, Number(place.day_number) || 1);
    const list = grouped.get(day) || [];
    list.push({ ...place, day_number: day });
    grouped.set(day, list);
  });

  return [...grouped.entries()]
    .sort(([leftDay], [rightDay]) => leftDay - rightDay)
    .flatMap(([, places]) =>
      places
        .sort((a, b) => Number(a.order_index || 0) - Number(b.order_index || 0))
        .map((place, index) => ({ ...place, order_index: index + 1 }))
    );
}



export default function S3VotePage() {
  const { uuid: planUuid } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const activePlanUuid = planUuid;

  const [plan, setPlan] = useState(null);
  const [candidates, setCandidates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [comment, setComment] = useState('');
  const [commentSubmitting, setCommentSubmitting] = useState(false);
  const [voteSubmitting, setVoteSubmitting] = useState(false);
  const [showLoginModal, setShowLoginModal] = useState(false);
  const [openedCard, setOpenedCard] = useState(null);
  const [toast, setToast] = useState(null);
  const [votes, setVotes] = useState({});
  const originalOrderRef = useRef(new Map()); // S2 원본 순서 스냅샷 (placeId -> {dayNumber, orderIndex})
  const orderPreferencesRef = useRef([]);
  // 사용자가 실제로 드래그한 카드 id만 모음 — 자동으로 밀려난 카드는 '순서 변경'으로 치지 않음
  const movedPlaceIdsRef = useRef(new Set());

  const showToast = useCallback((message, tone = 'success') => {
    setToast({ message, tone });
    window.setTimeout(() => setToast(null), 3000);
  }, []);

  useEffect(() => {
    async function fetchData() {
      if (!activePlanUuid) {
        setError('유효한 투표 링크가 아닙니다.');
        setLoading(false);
        return;
      }

      setLoading(true);
      setError(null);
      try {
        const [planRes, candidatesRes] = await Promise.all([
          getPlan(activePlanUuid),
          getCandidates(activePlanUuid),
        ]);
        const fetchedPlan = transformPlan(planRes.data.data);
        const fetchedCandidates = (candidatesRes.data.data || []).map(transformCandidate);

        let restoredOrderPreferences = [];
        try {
          restoredOrderPreferences = JSON.parse(localStorage.getItem(`order_${activePlanUuid}`) || '[]');
        } catch {
          restoredOrderPreferences = [];
        }

        let restoredMovedIds = [];
        try {
          restoredMovedIds = JSON.parse(localStorage.getItem(`moved_${activePlanUuid}`) || '[]');
        } catch {
          restoredMovedIds = [];
        }

        originalOrderRef.current = buildOrderSnapshot(fetchedCandidates);
        orderPreferencesRef.current = restoredOrderPreferences;
        movedPlaceIdsRef.current = new Set(restoredMovedIds.map(Number));
        setPlan(fetchedPlan);
        setCandidates(applyOrderPreferencesToCandidates(fetchedCandidates, restoredOrderPreferences));

        // ★ votes localStorage 복원
        const savedVotes = localStorage.getItem(`votes_${activePlanUuid}`);
        if (savedVotes) {
          setVotes(JSON.parse(savedVotes));
        } else {
          const initialVotes = {};
          fetchedCandidates.forEach(cand => {
            cand.places.forEach(place => {
              initialVotes[place.id] = {
                myVote: null,
                counts: { 1: 0, 2: 0, 3: 0, 4: 0 },
              };
            });
          });
          setVotes(initialVotes);
        }
      } catch (err) {
        console.error('데이터 로딩 실패:', err);
        setError('투표 데이터를 불러오는 데 실패했습니다.');
      } finally {
        setLoading(false);
      }
    }
    fetchData();
  }, [activePlanUuid]);

  const handleVote = useCallback((placeId, candidateId, score) => {
    const current = votes[placeId] || { myVote: null, counts: { 1: 0, 2: 0, 3: 0, 4: 0 } };
    const newCounts = { ...current.counts };

    if (current.myVote !== null) {
      newCounts[current.myVote] = Math.max(0, newCounts[current.myVote] - 1);
    }

    const isToggleOff = current.myVote === score;
    const newVote = isToggleOff ? null : score;
    if (newVote !== null) {
      newCounts[newVote] = newCounts[newVote] + 1;
    }

    const updated = {
      ...votes,
      [placeId]: { myVote: newVote, counts: newCounts },
    };

    setVotes(updated);
    localStorage.setItem(`votes_${activePlanUuid}`, JSON.stringify(updated));
  }, [activePlanUuid, votes]);

  const handleLocalReorder = useCallback((targetCandId, fromPlaceId, toDayNumber, toOrderIndex) => {
    const outcome = buildReorder(candidates, targetCandId, fromPlaceId, toDayNumber, toOrderIndex);
    if (!outcome.changed) return;
    setCandidates(outcome.nextCandidates);
    // 실제로 드래그한 카드만 '순서 변경'으로 기록 (밀려난 카드는 제외)
    movedPlaceIdsRef.current.add(Number(fromPlaceId));
    localStorage.setItem(`moved_${activePlanUuid}`, JSON.stringify([...movedPlaceIdsRef.current]));
    const normalizedRequests = (outcome.requests || []).map(request => {
      const original = originalOrderRef.current.get(Number(request.placeId));
      return {
        ...request,
        candidateId: original?.candidateId ?? request.candidateId,
        displayCandidateId: targetCandId,
      };
    });
    const merged = mergeOrderPreferences(orderPreferencesRef.current, normalizedRequests);
    orderPreferencesRef.current = merged;
    localStorage.setItem(`order_${activePlanUuid}`, JSON.stringify(merged));
    showToast('순서 선호를 반영했어요.');
  }, [activePlanUuid, candidates, showToast]);

  const handleMoveToOtherCandidate = useCallback((place, targetCandidateId) => {
    const original = originalOrderRef.current.get(Number(place.id));
    if (!original) return;

    let movedPlace = null;
    const nextCandidates = candidates.map(candidate => {
      const remaining = [];
      candidate.places.forEach(candidatePlace => {
        if (Number(candidatePlace.id) === Number(place.id)) {
          movedPlace = candidatePlace;
          return;
        }
        remaining.push(candidatePlace);
      });
      return { ...candidate, places: remaining };
    });

    const targetCandidate = nextCandidates.find(candidate => Number(candidate.id) === Number(targetCandidateId));
    if (!targetCandidate || !movedPlace) return;

    const targetDay = Math.max(1, Number(place.day_number) || 1);
    const targetDayPlaces = targetCandidate.places.filter(candidatePlace =>
      Number(candidatePlace.day_number) === targetDay
    );
    const preferredOrderIndex = targetDayPlaces.length + 1;

    targetCandidate.places.push({
      ...movedPlace,
      day_number: targetDay,
      order_index: preferredOrderIndex,
    });

    const normalizedCandidates = nextCandidates.map(candidate => ({
      ...candidate,
      places: normalizeCandidatePlaces(candidate),
    }));

    setCandidates(normalizedCandidates);
    movedPlaceIdsRef.current.add(Number(place.id));
    localStorage.setItem(`moved_${activePlanUuid}`, JSON.stringify([...movedPlaceIdsRef.current]));

    const request = {
      candidateId: original.candidateId,
      placeId: place.id,
      preferredDayNumber: targetDay,
      preferredOrderIndex,
      displayCandidateId: targetCandidateId,
    };
    const merged = mergeOrderPreferences(orderPreferencesRef.current, [request]);
    orderPreferencesRef.current = merged;
    localStorage.setItem(`order_${activePlanUuid}`, JSON.stringify(merged));
    showToast(`${place.name}을(를) ${targetCandidate.label}안으로 옮겨 표시했어요.`);
  }, [activePlanUuid, candidates, showToast]);

  // 로그인 후 돌아왔을 때 pending 투표 일괄 전송
  // pendingKey는 전송 성공 시에만 제거한다 — 도중 리로드돼도 다음 로드에서 재시도 가능.
  // 중복 전송은 백엔드 UPSERT로 안전하므로 localStorage 잠금 대신 ref로 이중 실행만 막는다.
  const pendingSubmitRef = useRef(false);
  useEffect(() => {
    const pendingVoteKey = `pending_submit_${activePlanUuid}`;
    const pendingCommentKey = `pending_comment_${activePlanUuid}`;
    const hasPendingVotes = Boolean(localStorage.getItem(pendingVoteKey));
    const savedComment = (localStorage.getItem(pendingCommentKey) || '').trim();
    if (!user || !candidates.length || (!hasPendingVotes && !savedComment)) return;
    if (pendingSubmitRef.current) return;
    pendingSubmitRef.current = true;

    const savedVotes = localStorage.getItem(`votes_${activePlanUuid}`);
    const parsedVotes = savedVotes ? JSON.parse(savedVotes) : {};
    const changedOrder = keepMovedOnly(diffOrderPreferences(candidates, originalOrderRef.current), movedPlaceIdsRef.current);
    const prefByPlaceId = orderPreferencesByPlaceId(changedOrder);
    const voteEntries = [];
    candidates.forEach(cand => {
      cand.places.forEach(place => {
        const voteState = parsedVotes[place.id];
        if (voteState && voteState.myVote !== null) {
          const original = originalOrderRef.current.get(Number(place.id));
          const pref = prefByPlaceId.get(Number(place.id));
          voteEntries.push({
            candidateId: original?.candidateId ?? cand.id,
            placeId: place.id,
            voteScore: voteState.myVote,
            preferredDayNumber: pref ? pref.preferredDayNumber : null,
            preferredOrderIndex: pref ? pref.preferredOrderIndex : null,
          });
        }
      });
    });
    const votedPlaceIds = new Set(voteEntries.map(entry => Number(entry.placeId)));
    const orderOnlyEntries = changedOrder.filter(pref => !votedPlaceIds.has(Number(pref.placeId)));

    (async () => {
      try {
        if (hasPendingVotes && (voteEntries.length > 0 || orderOnlyEntries.length > 0)) {
          await submitVotes(activePlanUuid, {
            votes: voteEntries,
            orderPreferences: toBatchOrderPreferences(orderOnlyEntries),
          }, null);
          localStorage.removeItem(pendingVoteKey);
          localStorage.removeItem(`votes_${activePlanUuid}`);
          localStorage.removeItem(`order_${activePlanUuid}`);
          localStorage.removeItem(`moved_${activePlanUuid}`);
          movedPlaceIdsRef.current = new Set();
          setShowLoginModal(true);
        }
        if (savedComment) {
          await submitPlanComment(activePlanUuid, savedComment, null);
          localStorage.removeItem(pendingCommentKey);
          showToast('의견이 등록되었습니다.');
        }
      } catch (err) {
        console.error('로그인 후 저장 실패:', err);
        showToast('저장에 실패했습니다. 잠시 후 다시 시도해주세요.', 'error');
      } finally {
        pendingSubmitRef.current = false;
      }
    })();
  }, [user, candidates, activePlanUuid, showToast]);

  const handleVoteComplete = async () => {
    if (voteSubmitting) return;

    const changedOrder = keepMovedOnly(diffOrderPreferences(candidates, originalOrderRef.current), movedPlaceIdsRef.current);
    const votedCount = Object.values(votes).filter(v => v.myVote !== null).length;
    const trimmedComment = comment.trim();
    if (votedCount === 0 && changedOrder.length === 0 && !trimmedComment) {
      showToast('장소에 투표하거나 순서를 바꾸거나 의견을 남겨주세요.', 'error');
      return;
    }

    if (user) {
      const voteEntries = [];
      const prefByPlaceId = orderPreferencesByPlaceId(changedOrder);
      candidates.forEach(cand => {
        cand.places.forEach(place => {
          const voteState = votes[place.id];
          if (voteState && voteState.myVote !== null) {
            const original = originalOrderRef.current.get(Number(place.id));
            const pref = prefByPlaceId.get(Number(place.id));
            voteEntries.push({
              candidateId: original?.candidateId ?? cand.id,
              placeId: place.id,
              voteScore: voteState.myVote,
              preferredDayNumber: pref ? pref.preferredDayNumber : null,
              preferredOrderIndex: pref ? pref.preferredOrderIndex : null,
            });
          }
        });
      });
      const votedPlaceIds = new Set(voteEntries.map(entry => Number(entry.placeId)));
      const orderOnlyEntries = changedOrder.filter(pref => !votedPlaceIds.has(Number(pref.placeId)));

      setVoteSubmitting(true);
      try {
        if (voteEntries.length > 0 || orderOnlyEntries.length > 0) {
          await submitVotes(activePlanUuid, {
            votes: voteEntries,
            orderPreferences: toBatchOrderPreferences(orderOnlyEntries),
          }, null);
          localStorage.removeItem(`votes_${activePlanUuid}`);
          localStorage.removeItem(`order_${activePlanUuid}`);
          localStorage.removeItem(`moved_${activePlanUuid}`);
          movedPlaceIdsRef.current = new Set();
        }
        if (trimmedComment) {
          await submitPlanComment(activePlanUuid, trimmedComment, null);
          setComment('');
        }
        setShowLoginModal(true);
      } catch (err) {
        console.error('투표 전송 실패:', err);
        showToast('투표 저장에 실패했습니다. 다시 시도해주세요.', 'error');
      } finally {
        setVoteSubmitting(false);
      }
    } else {
      // 비로그인 → pending 플래그 저장 후 로그인 모달
      if (votedCount > 0 || changedOrder.length > 0) {
        localStorage.setItem(`pending_submit_${activePlanUuid}`, 'true');
      }
      if (trimmedComment) {
        localStorage.setItem(`pending_comment_${activePlanUuid}`, trimmedComment);
      }
      setShowLoginModal(true);
    }
  };

  const handleKakaoShareClick = async () => {
    const voteUrl = `${window.location.origin}/vote/${activePlanUuid}`;
    try {
      await shareVoteRequest({
        planTitle: plan?.title,
        voteUrl,
      });
      showToast('카카오톡 공유창을 열었어요.');
    } catch (err) {
      console.error('카카오톡 투표 링크 공유 실패:', err);
      try {
        await navigator.clipboard.writeText(buildVoteInviteText(plan?.title, voteUrl));
        showToast('카카오톡 공유를 열지 못해 투표 초대 문구를 복사했어요.');
      } catch (copyErr) {
        console.error('투표 초대 문구 복사 실패:', copyErr);
        showToast('투표 링크 공유를 준비하지 못했어요.', 'error');
      }
    }
  };

  const handleCommentSubmit = async () => {
    const trimmedComment = comment.trim();
    if (!trimmedComment || commentSubmitting) return;

    if (!user) {
      localStorage.setItem(`pending_comment_${activePlanUuid}`, trimmedComment);
      setShowLoginModal(true);
      return;
    }

    setCommentSubmitting(true);
    try {
      await submitPlanComment(activePlanUuid, trimmedComment, null);
      showToast('의견이 등록되었습니다.');
      setComment('');
    } catch (err) {
      console.error('의견 서버 저장 실패:', err);
      showToast('의견 등록 중 오류가 발생했습니다. 다시 시도해주세요.', 'error');
    } finally {
      setCommentSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <div className="w-16 h-16 border-4 border-accent-200 border-t-accent-500 md:border-primary-200 md:border-t-primary-500 rounded-full animate-spin mx-auto mb-4" />
          <p className="text-lg font-medium text-gray-700">투표 화면을 불러오고 있어요...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <p className="text-lg text-red-500">{error}</p>
      </div>
    );
  }

  if (!plan || candidates.length === 0) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <p className="text-lg text-gray-500">일정 데이터를 불러올 수 없습니다.</p>
      </div>
    );
  }

  return (
    <>
      <PlanViewLayout
        plan={plan}
        candidates={candidates}
        topBanner="A안과 B안을 비교하고, 각 장소에 투표해주세요! 여러분의 선택이 팀 맞춤 추천 일정에 반영됩니다."
        onReorder={handleLocalReorder}
        protectedDeleteCategories={[]}
        onPlaceDetailClick={(place, candidateId) => setOpenedCard({ place, candidateId })}
        renderCardFooter={(place, candidateId) => {
          const voteState = votes[place.id] || { myVote: null, counts: { 1: 0, 2: 0, 3: 0, 4: 0 } };
          const origPos = originalOrderRef.current.get(Number(place.id));
          const orderMoved = movedPlaceIdsRef.current.has(Number(place.id)) && origPos && (
            Number(place.day_number) !== origPos.dayNumber
            || Number(place.order_index) !== origPos.orderIndex
          );

          return (
            <div className="space-y-1.5">
              {orderMoved && (
                <div className="flex items-center gap-1 text-[11px] font-medium text-violet-600 bg-violet-50 border border-violet-200 rounded-md px-2 py-1">
                  <span>🔀</span>
                  <span>
                    {origPos.dayNumber === Number(place.day_number)
                      ? `${origPos.orderIndex}번째 → ${place.order_index}번째`
                      : `${origPos.dayNumber}일차 ${origPos.orderIndex}번째 → ${place.day_number}일차 ${place.order_index}번째`}
                  </span>
                </div>
              )}
              <div className="flex gap-1.5">
                {VOTE_OPTIONS.map(opt => {
                  const isSelected = voteState.myVote === opt.score;
                  const count = voteState.counts[opt.score] || 0;

                  return (
                    <button
                      key={opt.score}
                      onPointerDown={(e) => e.stopPropagation()}
                      onClick={(e) => {
                        e.stopPropagation();
                        handleVote(place.id, candidateId, opt.score);
                      }}
                      className={`flex-1 flex items-center justify-center gap-1 py-1.5 px-1
                                 rounded-lg border text-xs font-medium transition-all
                                 ${isSelected
                                   ? `${opt.color} border-current ring-1 ring-current scale-105`
                                   : 'bg-gray-50 border-gray-200 text-gray-400 hover:bg-gray-100'
                                 }`}
                    >
                      <span>{opt.emoji}</span>
                      <span>{count}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          );
        }}
        renderBottomBar={() => (
          <div className="bg-white border border-gray-200 rounded-xl p-4 shadow-sm">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-base">📝</span>
              <h3 className="font-bold text-gray-900">전체 투표 의견</h3>
              <span className="text-xs text-gray-400">— 전체 일정에 대한 의견이나 바꾸고 싶은 점을 자유롭게 적어주세요</span>
            </div>
            <div className="flex gap-3">
              <input
                type="text"
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleCommentSubmit()}
                placeholder={`"A안 명소 일정이 너무 빡빡해요" · "B안 힐링 코스가 훨씬 편할 것 같아요 👍"`}
                className="flex-1 border border-gray-200 rounded-lg px-4 py-2.5 text-sm
                           placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-accent-200 md:focus:ring-primary-200
                           focus:border-accent-400 md:focus:border-primary-400 transition-all"
              />
              <button
                onClick={handleCommentSubmit}
                disabled={!comment.trim() || commentSubmitting}
                className="bg-accent-500 hover:bg-accent-600 md:bg-primary-500 md:hover:bg-primary-600
                           disabled:bg-gray-200 disabled:text-gray-400
                           text-white font-bold px-6 py-2.5 rounded-lg transition-colors text-sm"
              >
                {commentSubmitting ? '등록 중...' : '의견 남기기'}
              </button>
            </div>
          </div>
        )}
      />

      {/* ── 하단 고정 바 ── */}
      <div className="fixed bottom-0 left-0 right-0 z-40 bg-white border-t border-gray-200 shadow-[0_-4px_16px_rgba(0,0,0,0.08)] px-4 py-3">
        <div className="flex md:hidden items-center gap-2">
          <button
            onClick={() => navigate('/')}
            aria-label="처음으로"
            className="flex-shrink-0 w-11 h-11 flex items-center justify-center text-sm text-gray-500
                       border border-gray-200 rounded-xl bg-gray-50 active:bg-gray-100"
          >
            ←
          </button>
          <button
            onClick={handleVoteComplete}
            disabled={voteSubmitting}
            className="flex-1 bg-accent-500 active:bg-accent-700
                       disabled:bg-gray-300 disabled:cursor-not-allowed text-white font-bold text-sm py-3 rounded-xl
                       transition-colors flex items-center gap-2 justify-center shadow-md"
          >
            {voteSubmitting ? '저장 중...' : '✅ 투표 완료'}
          </button>
          <button
            onClick={handleKakaoShareClick}
            aria-label="카카오톡 공유"
            className="flex-shrink-0 w-12 h-11 bg-yellow-300 active:bg-yellow-400
                       rounded-xl transition-colors flex items-center justify-center shadow-sm"
          >
            <span className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-gray-900 text-[11px] font-black text-yellow-300">톡</span>
          </button>
        </div>

        <div className="hidden md:flex relative items-center justify-center h-12">
          <div className="absolute left-8">
            <button
              onClick={() => navigate('/')}
              className="text-sm text-gray-500 hover:text-gray-700 border border-gray-200
                         rounded-xl px-4 py-2.5 transition-colors bg-gray-50 hover:bg-gray-100"
            >
              ← 처음으로
            </button>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={handleVoteComplete}
              disabled={voteSubmitting}
              className="min-w-[280px] bg-primary-500 hover:bg-primary-600 active:bg-primary-700
                         disabled:bg-gray-300 disabled:cursor-not-allowed text-white font-bold text-base
                         px-8 py-2.5 rounded-xl transition-colors flex items-center gap-2 justify-center shadow-md"
            >
              {voteSubmitting ? '저장 중...' : '✅ 투표 완료'}
            </button>
            <button
              onClick={handleKakaoShareClick}
              className="bg-yellow-300 hover:bg-yellow-400 active:bg-yellow-500 text-gray-900
                         font-extrabold text-sm px-5 py-2.5 rounded-xl transition-colors
                         flex items-center gap-2 shadow-sm whitespace-nowrap"
            >
              <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-gray-900 text-[10px] font-black text-yellow-300">톡</span>
              카카오톡 공유
            </button>
            <button
              onClick={() => navigate(`/dashboard/${activePlanUuid}`)}
              className="text-sm text-gray-600 hover:text-gray-800 border border-gray-200
                         rounded-xl px-4 py-2.5 transition-colors bg-gray-50 hover:bg-gray-100 whitespace-nowrap"
            >
              📊 투표 현황
            </button>
          </div>
        </div>
      </div>

      {toast && (
        <div className={`fixed bottom-24 left-1/2 -translate-x-1/2 z-50
                        text-white text-sm font-medium px-5 py-3 rounded-xl shadow-lg
                        ${toast.tone === 'error' ? 'bg-red-600' : 'bg-gray-900'}`}>
          {toast.message}
        </div>
      )}

      {showLoginModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl p-8 max-w-sm mx-4 shadow-2xl">
            <div className="text-center">
              {user ? (
                <>
                  <div className="text-4xl mb-4">🎉</div>
                  <h3 className="text-lg font-bold text-gray-900 mb-2">투표 완료!</h3>
                  <p className="text-sm text-gray-500 mb-6">
                    {user.nickname || '회원'}님의 투표가 저장되었습니다.
                  </p>
                  <button
                    onClick={() => navigate(`/dashboard/${activePlanUuid}`)}
                    className="w-full bg-accent-500 hover:bg-accent-600 md:bg-primary-500 md:hover:bg-primary-600 text-white font-bold py-3 px-6 rounded-xl transition-colors"
                  >
                    확인
                  </button>
                  <p className="mt-3 text-xs text-gray-400">
                    확인 후 투표 현황에서 결과를 볼 수 있어요.
                  </p>
                </>
              ) : (
                <>
                  <div className="text-4xl mb-4">🔐</div>
                  <h3 className="text-lg font-bold text-gray-900 mb-6">카카오톡 로그인이 필요합니다!</h3>
                  <button
                    onClick={() => {
                      kakaoLogin(window.location.pathname);
                    }}
                    className="w-full bg-yellow-300 hover:bg-yellow-400 text-gray-900
                               font-bold py-3 px-6 rounded-xl transition-colors mb-3"
                  >
                    💬 카카오로 로그인
                  </button>
                  <button
                    onClick={() => setShowLoginModal(false)}
                    className="text-sm text-gray-400 hover:text-gray-600 transition-colors"
                  >
                    닫기
                  </button>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {openedCard && (() => {
        const { place, candidateId } = openedCard;
        const currentCandidate = candidates.find(candidate => Number(candidate.id) === Number(candidateId)) || null;
        const otherCandidate = candidates.find(candidate => Number(candidate.id) !== Number(candidateId)) || null;
        const original = originalOrderRef.current.get(Number(place.id));
        const voteCandidateId = original?.candidateId ?? candidateId;
        const voteState = votes[place.id] || { myVote: null, counts: { 1: 0, 2: 0, 3: 0, 4: 0 } };

        return (
          <VoteCardModal
            place={place}
            candidate={currentCandidate}
            otherCandidate={otherCandidate}
            voteState={voteState}
            onVote={(score) => handleVote(place.id, voteCandidateId, score)}
            onMoveToOther={() => {
              if (!otherCandidate) return;
              handleMoveToOtherCandidate(place, otherCandidate.id);
              setOpenedCard(null);
            }}
            onClose={() => setOpenedCard(null)}
          />
        );
      })()}
    </>
  );
}
