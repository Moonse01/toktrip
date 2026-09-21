/**
 * S2ResultPage.jsx
 * 
 * 🗺️ S2 — AI 초안 생성 결과 화면 (호스트용)
 * 
 * 호스트가 A/B안을 확인하고, [카카오톡 공유] 버튼으로 게스트에게 투표를 요청하는 화면.
 * 투표 버튼은 표시하지 않음 (→ S3VotePage에서 표시).
 * 
 * 라우트: /result/:planId
 * 위치: src/pages/S2ResultPage.jsx
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import PlanViewLayout from '../components/PlanViewLayout';
import DeletePlanSection from '../components/DeletePlanSection';
import VoteCardModal from '../components/VoteCardModal';
import { useAuth } from '../auth/AuthContext';
import { generatePlan, getPlan, getPlanStatus, getCandidates, sharePlan, claimPlan, updatePlaceOrders, deletePlace } from '../api/plans';
import { kakaoLogin } from '../api/auth';
import { buildReorder } from '../utils/reorder';
import { transformPlan, transformCandidate } from '../utils/planTransforms';
import { buildVoteInviteText, shareVoteRequest } from '../utils/kakaoShare';



function VoteQrModal({ voteUrl, onClose }) {
  return (
    <div className="fixed inset-0 z-50 hidden md:flex items-center justify-center bg-black/40 px-4">
      <div className="w-full max-w-sm rounded-2xl bg-white p-7 text-center shadow-2xl">
        <div className="text-sm font-semibold text-primary-500 mb-2">투표 참여 QR</div>
        <h3 className="text-xl font-black text-gray-900 mb-2">휴대폰으로 찍고 투표해요</h3>
        <p className="text-sm text-gray-500 leading-6 mb-5">
          링크도 복사해뒀어요. 가까이에 있는 친구는 QR로 바로 들어올 수 있어요.
        </p>
        <div className="mx-auto mb-4 flex h-56 w-56 items-center justify-center rounded-2xl border border-gray-200 bg-white p-3 shadow-sm">
          <QRCodeSVG
            value={voteUrl}
            size={196}
            level="M"
            includeMargin
            fgColor="#111827"
            bgColor="#FFFFFF"
            title="투표 링크 QR 코드"
          />
        </div>
        <div className="mb-5 rounded-xl bg-gray-50 px-3 py-2 text-xs text-gray-500 break-all">
          {voteUrl}
        </div>
        <button
          onClick={onClose}
          className="w-full rounded-xl bg-gray-900 px-4 py-3 text-sm font-bold text-white transition-colors hover:bg-gray-800"
        >
          닫기
        </button>
      </div>
    </div>
  );
}

export default function S2ResultPage() {
  const { uuid } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();

  // ── 상태 ──
  const [plan, setPlan] = useState(null);
  const [candidates, setCandidates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showLoginModal, setShowLoginModal] = useState(false);
  const [toast, setToast] = useState(null);
  const [pendingOrderRequests, setPendingOrderRequests] = useState([]);
  const [deleteMode, setDeleteMode] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState(null); // { candidateId, place }
  const [openedCard, setOpenedCard] = useState(null);
  const [qrModal, setQrModal] = useState(null);
  const [pollCycle, setPollCycle] = useState(0);
  const [retrying, setRetrying] = useState(false);
  const [loadingStep, setLoadingStep] = useState(0);
  const claimingPlanRef = useRef(false);
  const pendingOrderStorageKey = `triplan:pending-orders:${uuid}`;
  const canDeletePlaces = Boolean(
    user
    && Number(plan?.ownerId) === Number(user.id)
    && (plan?.status === 'AI_DONE' || plan?.status === 'VOTING')
  );

  // ── 데이터 로딩 (AI 생성 중이면 로딩 화면을 유지하며 충분히 폴링) ──
  useEffect(() => {
    const RETRY_DELAY_MS = 1000;
    const MAX_POLL_DURATION_MS = 6 * 60 * 1000;
    const startedAt = Date.now();
    let cancelled = false;

    async function fetchData() {
      setLoading(true);
      setError(null);
      try {
        const planRes = await getPlan(uuid);
        const rawPlan = planRes.data.data;
        setPlan(transformPlan(rawPlan));

        while (!cancelled) {
          if (cancelled) return;
          if (Date.now() - startedAt >= MAX_POLL_DURATION_MS) {
            setError('일정 생성이 예상보다 오래 걸리고 있습니다. 잠시 후 다시 확인해 주세요.');
            setLoading(false);
            return;
          }

          const latestPlanRes = await getPlan(uuid);
          const latestRawPlan = latestPlanRes.data.data;
          const latestPlan = transformPlan(latestRawPlan);
          setPlan(latestPlan);

          const status = latestRawPlan?.status;
          if (status === 'DRAFT') {
            setError('AI가 이번 요청을 안정적으로 검증하지 못했어요. 다시 시도하면 기존 입력 그대로 재생성하고, 처음으로 돌아가면 요청을 더 구체적으로 다시 작성할 수 있어요.');
            setLoading(false);
            return;
          }
          if (!['AI_DONE', 'VOTING', 'CONFIRMED'].includes(status)) {
            await new Promise(resolve => setTimeout(resolve, RETRY_DELAY_MS));
            continue;
          }

          const candidatesRes = await getCandidates(uuid);
          const raw = candidatesRes.data.data || [];
          if (raw.length > 0) {
            setCandidates(raw.map(transformCandidate));
            // 후보가 등장 = AI 완료. 진입 시점에 잡아둔 plan.status가 DRAFT/AI_GENERATING이라
            // 공유 버튼 가드가 잘못 발동하는 걸 막기 위해 plan을 다시 가져와 갱신한다.
            try {
              const fresh = await getPlan(uuid);
              if (!cancelled) setPlan(transformPlan(fresh.data.data));
            } catch { /* 갱신 실패는 화면 진입을 막지 않는다 */ }
            return;
          }
          await new Promise(resolve => setTimeout(resolve, RETRY_DELAY_MS));
        }
      } catch (err) {
        if (!cancelled) {
          console.error('데이터 로딩 실패:', err);
          setError('일정 데이터를 불러오는 데 실패했습니다.');
          setLoading(false);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    fetchData();
    return () => { cancelled = true; };
  }, [pollCycle, uuid]);

  // 실패한 요청은 같은 플랜으로 다시 생성하고, 아직 처리 중이면 상태 확인만 재개한다.
  const handleRetryGeneration = useCallback(async () => {
    if (retrying) return;
    setRetrying(true);
    try {
      const currentPlan = plan?.id ? plan : transformPlan((await getPlan(uuid)).data.data);
      const planId = currentPlan?.id;
      if (!planId) {
        throw new Error('재생성할 플랜 정보를 찾지 못했습니다.');
      }
      if (planId) {
        const statusRes = await getPlanStatus(planId);
        const status = statusRes.data.data?.status;
        if (status === 'DRAFT') {
          await generatePlan(planId);
        }
      }
      setError(null);
      setLoading(true);
      setLoadingStep(0);
      setPollCycle(prev => prev + 1);
    } catch (err) {
      console.error('AI 일정 재시도 실패:', err);
      setError(err.response?.data?.message || '일정 생성을 다시 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setRetrying(false);
    }
  }, [plan, retrying, uuid]);

  // ── 토스트 표시 헬퍼 ──
  const showToast = useCallback((msg) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  }, []);

  const savePendingOrderRequests = useCallback((requests) => {
    if (!requests?.length) return;
    setPendingOrderRequests(prev => {
      const changedCandidateIds = new Set(requests.map(request => Number(request.candidateId)));
      const merged = [
        ...prev.filter(request => !changedCandidateIds.has(Number(request.candidateId))),
        ...requests,
      ];
      sessionStorage.setItem(pendingOrderStorageKey, JSON.stringify(merged));
      return merged;
    });
  }, [pendingOrderStorageKey]);

  const clearPendingOrderRequests = useCallback(() => {
    setPendingOrderRequests([]);
    sessionStorage.removeItem(pendingOrderStorageKey);
  }, [pendingOrderStorageKey]);

  // 상단 로그인으로 돌아온 경우, 공유 상태 전환 없이 익명 플랜만 현재 계정에 귀속시킨다.
  useEffect(() => {
    if (!user || !plan || plan.ownerId) return;
    if (sessionStorage.getItem('pendingShareUuid') === uuid) return;
    if (claimingPlanRef.current) return;

    let cancelled = false;
    claimingPlanRef.current = true;
    claimPlan(uuid)
      .then(res => {
        if (cancelled) return;
        setPlan(transformPlan(res.data.data));
        showToast('로그인됐어요. 이 플랜을 내 플랜으로 저장했어요.');
      })
      .catch(err => {
        if (cancelled) return;
        console.error('로그인 후 플랜 귀속 실패:', err);
        showToast('로그인은 됐지만 이 플랜을 내 목록에 저장하지 못했어요. 공유 버튼을 다시 눌러 주세요.');
      })
      .finally(() => {
        claimingPlanRef.current = false;
      });

    return () => {
      cancelled = true;
    };
  }, [plan, showToast, user, uuid]);

  // 공유를 누른 뒤 로그인으로 다녀온 경우, 돌아온 S2에서 익명 플랜을 현재 호스트에게 먼저 귀속시킨다.
  useEffect(() => {
    if (!user) return;
    const pendingShareUuid = sessionStorage.getItem('pendingShareUuid');
    if (pendingShareUuid !== uuid) return;

    sessionStorage.removeItem('pendingShareUuid');
    let storedOrderRequests = [];
    try {
      storedOrderRequests = JSON.parse(sessionStorage.getItem(pendingOrderStorageKey) || '[]');
    } catch {
      storedOrderRequests = [];
    }

    Promise.resolve()
      .then(() => {
        if (!storedOrderRequests.length) return null;
        return updatePlaceOrders(uuid, storedOrderRequests);
      })
      .then(() => {
        clearPendingOrderRequests();
        return sharePlan(uuid);
      })
      .then(() => {
        setPlan(prev => prev ? { ...prev, status: 'VOTING' } : prev);
        showToast('로그인됐어요. 편집한 순서를 저장하고 투표 링크를 공유할 수 있어요.');
      })
      .catch(err => {
        console.error('로그인 후 플랜 공유 준비 실패:', err);
        showToast('로그인은 됐지만 편집 저장 또는 공유 준비에 실패했어요. 다시 시도해 주세요.');
      });
  }, [clearPendingOrderRequests, pendingOrderStorageKey, showToast, user, uuid]);

  const prepareVoteShare = async () => {
    if (!user) {
      sessionStorage.setItem('pendingShareUuid', uuid);
      setShowLoginModal(true);
      return null;
    }
    if (!['AI_DONE', 'VOTING'].includes(plan?.status)) {
      showToast('이미 확정된 플랜은 투표 링크를 다시 열 수 없어요.');
      return null;
    }

    if (pendingOrderRequests.length > 0) {
      await updatePlaceOrders(uuid, pendingOrderRequests);
      clearPendingOrderRequests();
    }
    await sharePlan(uuid);
    setPlan(prev => prev ? { ...prev, status: 'VOTING' } : prev);
    return `${window.location.origin}/vote/${uuid}`;
  };

  const copyVoteInvite = async (voteUrl) => {
    await navigator.clipboard.writeText(buildVoteInviteText(plan?.title, voteUrl));
  };

  const copyVoteLink = async (voteUrl) => {
    await navigator.clipboard.writeText(voteUrl);
  };

  const isLocalSharePreview = ['localhost', '127.0.0.1'].includes(window.location.hostname);
  const isDesktopViewport = () => window.matchMedia('(min-width: 768px)').matches;

  const openVoteQrModal = (voteUrl) => {
    if (!isDesktopViewport()) return;
    setQrModal({ voteUrl });
  };

  // ── 카카오톡 투표 요청 공유 ──
  const handleKakaoShareClick = async () => {
    let voteUrl = null;
    try {
      voteUrl = await prepareVoteShare();
      if (!voteUrl) return;

      await shareVoteRequest({
        planTitle: plan?.title,
        voteUrl,
      });
      if (isLocalSharePreview) {
        await copyVoteLink(voteUrl);
        showToast('카카오톡 열기를 요청했고, 로컬 시연용 투표 링크도 복사했어요.');
        return;
      }
      showToast('카카오톡 열기를 요청했어요. 열리지 않으면 초대 문구를 복사해 주세요.');
    } catch (err) {
      console.error('카카오톡 투표 요청 공유 실패:', err);
      if (!voteUrl) {
        showToast(err.response?.data?.message || err.message || '투표 요청 링크를 준비하지 못했어요.');
        return;
      }
      try {
        await copyVoteInvite(voteUrl);
        showToast('카카오톡 공유를 열지 못해 초대 문구를 복사했어요.');
      } catch (copyErr) {
        console.error('투표 초대 문구 복사 실패:', copyErr);
        showToast(err.response?.data?.message || err.message || '투표 요청 공유를 준비하지 못했어요.');
      }
    }
  };

  // ── 복사 fallback ──
  const handleCopyInviteClick = async () => {
    try {
      const voteUrl = await prepareVoteShare();
      if (!voteUrl) return;

      await copyVoteLink(voteUrl);
      showToast('투표 링크가 복사되었어요.');
      openVoteQrModal(voteUrl);
    } catch (err) {
      console.error('투표 초대 문구 복사 실패:', err);
      showToast(err.response?.data?.message || '투표 초대 문구를 복사하지 못했어요.');
    }
  };

  const handleGoVoteClick = async () => {
    try {
      if (plan?.status === 'VOTING' && pendingOrderRequests.length === 0) {
        navigate(`/vote/${uuid}`);
        return;
      }
      const voteUrl = await prepareVoteShare();
      if (!voteUrl) return;
      navigate(`/vote/${uuid}`);
    } catch (err) {
      console.error('투표 화면 이동 준비 실패:', err);
      showToast(err.response?.data?.message || '투표 화면으로 이동하지 못했어요.');
    }
  };

  // ── S2 일정 편집: 비로그인은 화면에 임시 반영하고, 로그인 후 공유 시 저장 ──
  const handleHostReorder = useCallback((targetCandId, fromPlaceId, toDayNumber, toOrderIndex) => {
    const outcome = buildReorder(candidates, targetCandId, fromPlaceId, toDayNumber, toOrderIndex);
    if (outcome?.blocked) {
      showToast('같은 안 안에서만 순서를 바꿀 수 있어요.');
      return;
    }
    if (!outcome?.changed || !outcome.requests?.length) return;

    setCandidates(outcome.nextCandidates);
    if (!user) {
      savePendingOrderRequests(outcome.requests);
      showToast('순서 변경을 화면에 반영했어요. 공유할 때 로그인하면 저장돼요.');
      return;
    }

    updatePlaceOrders(uuid, outcome.requests)
      .then(() => showToast('일정 순서가 저장되었어요.'))
      .catch(err => {
        console.error('호스트 일정 순서 저장 실패:', err);
        showToast('순서 저장에 실패했어요. 새로고침 후 다시 시도해 주세요.');
        getCandidates(uuid)
          .then(res => setCandidates((res.data.data || []).map(transformCandidate)))
          .catch(refreshErr => console.error('순서 저장 실패 후 후보안 재조회 실패:', refreshErr));
      });
  }, [candidates, savePendingOrderRequests, showToast, user, uuid]);

  // ── 장소 삭제 ──
  const handlePlaceClick = useCallback((candidateId, place) => {
    if (!deleteMode || !canDeletePlaces) return;
    if (place.category === '숙소' || place.category === '이동') {
      showToast('숙소와 이동 거점은 삭제할 수 없어요.');
      return;
    }
    setDeleteConfirm({ candidateId, place });
  }, [canDeletePlaces, deleteMode, showToast]);

  const confirmDelete = useCallback(async () => {
    if (!deleteConfirm || !canDeletePlaces) return;
    const { candidateId, place } = deleteConfirm;
    try {
      await deletePlace(uuid, candidateId, place.id);
      const res = await getCandidates(uuid);
      setCandidates((res.data.data || []).map(transformCandidate));
      showToast(`${place.name}을(를) 삭제했어요.`);
    } catch (err) {
      console.error('장소 삭제 실패:', err);
      showToast(err.response?.data?.message || '장소 삭제에 실패했어요.');
    } finally {
      setDeleteConfirm(null);
    }
  }, [canDeletePlaces, deleteConfirm, showToast, uuid]);

  // ── 로딩 메시지 순환 ──
  const loadingMessages = [
    '단톡방 대화를 읽고 있어요...',
    '여행 조건을 분석하고 있어요...',
    'A안을 치열하게 고민 중이에요...',
    'B안도 다른 방향으로 짜보고 있어요...',
    '동선을 지도 위에 그려보고 있어요...',
    '거의 다 됐어요! 마지막 검증 중...',
  ];

  useEffect(() => {
    if (!loading) return;
    const timer = setInterval(() => {
      setLoadingStep(prev => Math.min(prev + 1, loadingMessages.length - 1));
    }, 5000);
    return () => clearInterval(timer);
  }, [loading, loadingMessages.length]);

  // ── 에러 화면 ──
  if (error) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="w-full max-w-md mx-4 rounded-2xl border border-gray-200 bg-white p-8 text-center shadow-sm">
          <div className="text-4xl mb-4">⚠️</div>
          <h2 className="text-xl font-bold text-gray-900">일정을 준비하지 못했어요.</h2>
          <p className="mt-3 text-sm leading-6 text-gray-500">{error}</p>
          <p className="mt-2 text-xs leading-5 text-gray-400">
            다시 시도는 같은 미션/대화 내용으로 재생성합니다.
          </p>
          <div className="mt-6 flex justify-center gap-3">
            <button
              onClick={handleRetryGeneration}
              disabled={retrying}
              className="rounded-xl bg-accent-500 md:bg-primary-500 px-5 py-2.5 text-sm font-bold text-white transition-colors hover:bg-accent-600 md:hover:bg-primary-600 disabled:cursor-not-allowed disabled:bg-accent-300 md:disabled:bg-primary-300"
            >
              {retrying ? '확인 중...' : '다시 시도'}
            </button>
            <button
              onClick={() => navigate('/')}
              className="rounded-xl border border-gray-200 bg-white px-5 py-2.5 text-sm font-medium text-gray-500 transition-colors hover:bg-gray-50"
            >
              처음으로 돌아가기
            </button>
          </div>
        </div>
      </div>
    );
  }

  // ── 로딩 화면 ──
  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <div className="w-16 h-16 border-4 border-accent-200 border-t-accent-500 md:border-primary-200 md:border-t-primary-500 rounded-full animate-spin mx-auto mb-4" />
          <p className="text-lg font-medium text-gray-700 transition-opacity duration-500">
            {loadingMessages[loadingStep]}
          </p>
          <p className="text-sm text-gray-400 mt-1">검증과 보정이 필요한 경우 몇 분 정도 걸릴 수 있어요.</p>
        </div>
      </div>
    );
  }

  if (!plan || candidates.length === 0) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <p className="text-lg text-gray-500">일정 데이터를 불러올 수 없습니다.</p>
          <button onClick={() => navigate('/')} className="mt-4 text-accent-500 md:text-primary-500 hover:underline">
            처음으로 돌아가기
          </button>
        </div>
      </div>
    );
  }

  return (
    <>
      <PlanViewLayout
        plan={plan}
        candidates={candidates}
        headerTitle={plan.title}
        topBanner="AI 분석 완료! A안과 B안을 비교해보세요. 투표 링크를 공유하면 팀원들이 함께 선택할 수 있어요."
        renderCardFooter={null}
        renderBottomBar={null}
        onReorder={handleHostReorder}
        deleteMode={canDeletePlaces && deleteMode}
        onDeleteModeToggle={canDeletePlaces ? () => setDeleteMode(prev => !prev) : undefined}
        onPlaceClick={handlePlaceClick}
        onPlaceDetailClick={(place, candidateId) => setOpenedCard({ place, candidateId })}
      />

      {/* ── 하단 고정 바 ── */}
      <div className="fixed bottom-0 left-0 right-0 z-40 bg-white border-t border-gray-200 shadow-[0_-4px_16px_rgba(0,0,0,0.08)] px-3 md:px-4 py-3">
        {/* 데스크탑: absolute로 좌우 끝에 정렬 */}
        <div className="hidden md:flex relative items-center justify-center h-12">
          <div className="absolute left-8 flex items-center gap-2">
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
              onClick={handleKakaoShareClick}
              className="bg-yellow-300 hover:bg-yellow-400 active:bg-yellow-500 text-gray-900
                         font-bold text-base px-6 py-2.5 rounded-xl transition-colors
                         flex items-center gap-2 shadow-md"
            >
              💬 카카오톡으로 투표 요청 보내기
            </button>
            <button
              onClick={handleCopyInviteClick}
              title="투표 링크 복사"
              className="border border-gray-200 bg-white hover:bg-gray-50 active:bg-gray-100
                         text-gray-600 font-semibold text-sm px-4 py-2.5 rounded-xl
                         transition-colors shadow-sm"
            >
              🔗 링크·QR 공유
            </button>
          </div>
          <div className="absolute right-8 flex items-center gap-2">
            <button
              onClick={handleGoVoteClick}
              className="text-sm text-gray-600 hover:text-gray-800 border border-gray-200
                         rounded-xl px-4 py-2.5 transition-colors bg-gray-50 hover:bg-gray-100 whitespace-nowrap"
            >
              🗳 투표하러가기
            </button>
            <button
              onClick={() => navigate(`/dashboard/${uuid}`)}
              className="text-sm text-gray-600 hover:text-gray-800 border border-gray-200
                         rounded-xl px-4 py-2.5 transition-colors bg-gray-50 hover:bg-gray-100 whitespace-nowrap"
            >
              📊 투표 현황
            </button>
          </div>
        </div>

        {/* 모바일: 카카오 공유를 길게, 바로 투표는 작은 아이콘 CTA로 노출 */}
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
            onClick={handleKakaoShareClick}
            className="flex-1 h-11 bg-yellow-300 active:bg-yellow-400 text-gray-900 font-extrabold text-sm
                       rounded-xl shadow-md whitespace-nowrap flex items-center justify-center gap-2"
          >
            💬 카카오톡 공유
          </button>
          <button
            onClick={handleGoVoteClick}
            aria-label="투표하러가기"
            className="flex-shrink-0 w-12 h-11 flex items-center justify-center text-base font-bold
                       border border-teal-200 text-teal-700 rounded-xl bg-teal-50 active:bg-teal-100 whitespace-nowrap"
          >
            🗳
          </button>
        </div>
      </div>

      {/* ── 토스트 ── */}
      {toast && (
        <div className="fixed bottom-24 left-1/2 -translate-x-1/2 z-50
                        bg-gray-900 text-white text-sm font-medium
                        px-5 py-3 rounded-xl shadow-lg animate-fade-in">
          {toast}
        </div>
      )}

      {/* ── 장소 삭제 확인 모달 ── */}
      {canDeletePlaces && deleteConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl p-8 max-w-sm mx-4 shadow-2xl">
            <div className="text-center">
              <div className="text-4xl mb-4">🗑️</div>
              <h3 className="text-lg font-bold text-gray-900 mb-2">일정을 삭제할까요?</h3>
              <p className="text-sm text-gray-500 mb-6">
                {deleteConfirm.place.name}
              </p>
              <button
                onClick={confirmDelete}
                className="w-full bg-red-500 hover:bg-red-600 text-white font-bold py-3 px-6 rounded-xl transition-colors mb-3"
              >
                삭제
              </button>
              <button
                onClick={() => setDeleteConfirm(null)}
                className="text-sm text-gray-400 hover:text-gray-600 transition-colors"
              >
                취소
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── 카카오 로그인 강제 모달 ── */}
      {showLoginModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl p-8 max-w-sm mx-4 shadow-2xl">
            <div className="text-center">
              <div className="text-4xl mb-4">🔐</div>
              <h3 className="text-lg font-bold text-gray-900 mb-2">로그인이 필요해요!</h3>
              <p className="text-sm text-gray-500 mb-6">
                친구들에게 카카오톡으로 투표 링크를 보내기 위해 로그인이 필요해요!
              </p>
              <button
                onClick={() => {
                  sessionStorage.setItem('pendingShareUuid', uuid);
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
                나중에 할게요
              </button>
            </div>
          </div>
        </div>
      )}

      {qrModal && (
        <VoteQrModal
          voteUrl={qrModal.voteUrl}
          onClose={() => setQrModal(null)}
        />
      )}

      {openedCard && (() => {
        const { place, candidateId } = openedCard;
        const currentCandidate = candidates.find(candidate => Number(candidate.id) === Number(candidateId)) || null;
        const otherCandidate = candidates.find(candidate => Number(candidate.id) !== Number(candidateId)) || null;

        return (
          <VoteCardModal
            place={place}
            candidate={currentCandidate}
            otherCandidate={otherCandidate}
            showVoteControls={false}
            showMoveButton={Boolean(otherCandidate)}
            onMoveToOther={() => {
              showToast('후보안 간 이동은 투표 화면에서 선호 의견으로 반영돼요.');
            }}
            onClose={() => setOpenedCard(null)}
          />
        );
      })()}
    </>
  );
}
