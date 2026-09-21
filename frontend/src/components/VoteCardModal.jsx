import { useEffect, useRef } from 'react';
import ModalBackdrop from './ModalBackdrop';

const VOTE_OPTIONS = [
  { score: 1, emoji: '👎', label: '싫어요', color: 'bg-red-50 text-red-600 border-red-300' },
  { score: 2, emoji: '👍', label: '따봉', color: 'bg-blue-50 text-blue-600 border-blue-300' },
  { score: 3, emoji: '💖', label: '왕따봉', color: 'bg-pink-50 text-pink-600 border-pink-300' },
  { score: 4, emoji: '📌', label: '고정', color: 'bg-amber-50 text-amber-600 border-amber-300' },
];

function hasCoord(place) {
  if (!place) return false;
  const lat = Number(place.lat);
  const lng = Number(place.lng);
  return Number.isFinite(lat)
    && Number.isFinite(lng)
    && lat >= -90
    && lat <= 90
    && lng >= -180
    && lng <= 180
    && !(lat === 0 && lng === 0);
}

function formatCost(cost) {
  if (!cost || Number(cost) <= 0) return null;
  return `${Number(cost).toLocaleString()}원`;
}

export default function VoteCardModal({
  place,
  candidate,
  otherCandidate,
  voteState,
  onVote,
  onMoveToOther,
  onClose,
  showMoveButton = Boolean(onMoveToOther),
  showVoteControls = true,
}) {
  const mapContainerRef = useRef(null);

  useEffect(() => {
    if (!place || !window.kakao?.maps || !mapContainerRef.current) return;

    const kakao = window.kakao;
    const initMap = () => {
      const lat = hasCoord(place) ? Number(place.lat) : 37.5665;
      const lng = hasCoord(place) ? Number(place.lng) : 126.9780;
      const center = new kakao.maps.LatLng(lat, lng);
      const map = new kakao.maps.Map(mapContainerRef.current, {
        center,
        level: 3,
        draggable: true,
      });

      if (hasCoord(place)) {
        new kakao.maps.Marker({ position: center, map });
      }
    };

    if (kakao.maps.load) {
      kakao.maps.load(initMap);
    } else {
      initMap();
    }
  }, [place]);

  if (!place) return null;

  const currentLabel = candidate?.label || '';
  const otherLabel = otherCandidate?.label || (currentLabel === 'A' ? 'B' : 'A');
  const costLabel = formatCost(place.estimated_cost);
  const headerMeta = [currentLabel ? `${currentLabel}안` : null, `Day ${place.day_number}`]
    .filter(Boolean)
    .join(' · ');

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="bg-white rounded-2xl w-full max-w-md shadow-2xl flex flex-col overflow-hidden"
        style={{ maxHeight: '78vh' }}
      >
        <div className="flex items-start justify-between px-5 pt-5 pb-3 border-b border-gray-100">
          <div className="min-w-0 flex-1 pr-3">
            <p className="text-[11px] text-gray-400 mb-0.5">
              {headerMeta}
            </p>
            <h3 className="text-lg font-bold text-gray-900 leading-snug break-keep">
              {place.name}
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="text-gray-400 hover:text-gray-700 text-xl w-9 h-9 flex items-center justify-center flex-shrink-0 rounded-full hover:bg-gray-100"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          <div
            ref={mapContainerRef}
            className="w-full h-44 rounded-lg overflow-hidden border border-gray-200 mb-3 bg-gray-100"
          >
            {!window.kakao?.maps && (
              <div className="w-full h-full flex items-center justify-center text-xs text-gray-400">
                지도를 불러오는 중...
              </div>
            )}
          </div>

          <div className="flex items-center gap-2 flex-wrap text-[11px] text-gray-500 mb-3">
            {place.category && (
              <span className="bg-gray-100 text-gray-600 px-2 py-1 rounded-full font-medium">
                {place.category}
              </span>
            )}
            {place.visit_time && <span>⏰ {place.visit_time}</span>}
            {place.duration_minutes && <span>⏱ {place.duration_minutes}분</span>}
            {costLabel && <span>💰 {costLabel}</span>}
          </div>

          {place.description && (
            <p className="text-sm text-gray-700 leading-relaxed mb-4">
              {place.description}
            </p>
          )}

          {showMoveButton && (
            <button
              type="button"
              onClick={onMoveToOther}
              disabled={!otherCandidate}
              className="w-full bg-gray-100 hover:bg-gray-200 disabled:bg-gray-50 disabled:text-gray-300 text-gray-700 font-semibold py-3 rounded-xl text-sm mb-5 transition-colors flex items-center justify-center gap-2"
            >
              ↔ {otherLabel}안으로 이 일정 옮기기
            </button>
          )}

          {showVoteControls && (
            <>
              <p className="text-xs text-gray-500 mb-2 font-semibold">이 장소에 대한 의견</p>
              <div className="grid grid-cols-2 gap-2">
                {VOTE_OPTIONS.map(option => {
                  const isSelected = voteState?.myVote === option.score;
                  const count = voteState?.counts?.[option.score] || 0;

                  return (
                    <button
                      key={option.score}
                      type="button"
                      onClick={() => onVote(option.score)}
                      className={`flex flex-col items-center gap-0.5 py-3 rounded-xl border-2 transition-all text-sm font-semibold ${
                        isSelected
                          ? `${option.color} ring-2 ring-current/30`
                          : 'bg-white border-gray-200 text-gray-500 hover:bg-gray-50'
                      }`}
                    >
                      <span className="text-2xl leading-none">{option.emoji}</span>
                      <span className="mt-1">{option.label}</span>
                      <span className="text-[10px] text-gray-400">{count}표</span>
                    </button>
                  );
                })}
              </div>
            </>
          )}
        </div>
      </div>
    </ModalBackdrop>
  );
}
