/**
 * PlanViewLayout.jsx
 *
 * S2/S3 공용 레이아웃 컴포넌트
 *
 * S2(호스트 결과 확인)와 S3(게스트 투표)가 동일한 레이아웃을 공유하되,
 * renderCardFooter prop으로 투표 버튼 유무만 분기합니다.
 * onReorder prop을 전달하면 카드 드래그&드롭 재정렬을 활성화합니다.
 *
 * 명세 기준 레이아웃:
 * ┌──────────────────────────────────────────────────────────┐
 * │ 헤더: AI 여행 플래너 + ← 처음으로 + 투표 링크 공유       │
 * ├──────────────────────────────────────────────────────────┤
 * │ ✅ AI 분석 완료! 안내 배너                                │
 * │ 💡 AI 인사이트 (split_reason)                            │
 * ├──────────────────────────────────────────────────────────┤
 * │ 빠른 이동: [Day 1] [Day 2] [Day 3]                      │
 * ├───────────────────────────┬──────────────────────────────┤
 * │ [A안 카드] [B안 카드]      │  🗺️ 카카오맵                │
 * │ 📅 N일차 | 날짜           │  — 일정 동선 미리보기        │
 * │ ┌─────┐ ┌─────┐          │  마커 + 폴리라인             │
 * │ │카드1│ │카드2│ (2열)     │                              │
 * │ └─────┘ └─────┘          │                              │
 * │ ┌─────┐ ┌─────┐          │                              │
 * │ │카드3│ │카드4│          │                              │
 * │ └─────┘ └─────┘          │                              │
 * ├───────────────────────────┴──────────────────────────────┤
 * │ 📝 전체 투표 의견 (S3에서만 표시)                         │
 * └──────────────────────────────────────────────────────────┘
 *
 * 위치: src/components/PlanViewLayout.jsx
 */

import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import NavBar from './NavBar';
import useIsMobile from '../hooks/useIsMobile';
import {
  DndContext,
  pointerWithin,
  MouseSensor,
  TouchSensor,
  useSensor,
  useSensors,
  DragOverlay,
} from '@dnd-kit/core';
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

// ──────────────────────────────────────────────
// 상수
// ──────────────────────────────────────────────
const DAY_COLORS = [
  { bg: '#6B8EDB', text: 'text-blue-500', light: 'bg-blue-50', border: 'border-blue-200', marker: '#6B8EDB' },
  { bg: '#5BA89C', text: 'text-teal-600', light: 'bg-teal-50', border: 'border-teal-200', marker: '#5BA89C' },
  { bg: '#D4A76A', text: 'text-amber-600', light: 'bg-amber-50', border: 'border-amber-200', marker: '#D4A76A' },
  { bg: '#D47A8A', text: 'text-rose-400', light: 'bg-rose-50', border: 'border-rose-200', marker: '#D47A8A' },
  { bg: '#9B85CF', text: 'text-violet-500', light: 'bg-violet-50', border: 'border-violet-200', marker: '#9B85CF' },
];

const CANDIDATE_COLORS = ['#6B8EDB', '#E87B5E'];
const MOBILE_CANDIDATE_COLORS = ['#3b82f6', '#f97316'];
const MOBILE_DAY_ACCENT = '#10b981';

const CATEGORY_CONFIG = {
  '관광': { emoji: '🏛', color: 'bg-blue-100 text-blue-700' },
  '문화': { emoji: '🏛', color: 'bg-blue-100 text-blue-700' },
  '맛집': { emoji: '🍽', color: 'bg-amber-100 text-amber-700' },
  '식도락': { emoji: '🍽', color: 'bg-amber-100 text-amber-700' },
  '카페': { emoji: '☕', color: 'bg-pink-100 text-pink-700' },
  '숙소': { emoji: '🏨', color: 'bg-purple-100 text-purple-700' },
  '쇼핑': { emoji: '🛍', color: 'bg-yellow-100 text-yellow-700' },
  '액티비티': { emoji: '🎯', color: 'bg-green-100 text-green-700' },
  '자연': { emoji: '🌿', color: 'bg-teal-100 text-teal-700' },
  '이동': { emoji: '🚗', color: 'bg-gray-100 text-gray-600' },
};

function getCategoryStyle(category) {
  return CATEGORY_CONFIG[category] || { emoji: '📍', color: 'bg-gray-100 text-gray-600' };
}

function formatCost(cost) {
  if (!cost || cost === 0) return '무료';
  if (cost >= 10000) return `${(cost / 10000).toFixed(cost % 10000 === 0 ? 0 : 1)}만원`;
  return `${cost.toLocaleString()}원`;
}

function formatPerPersonCost(cost) {
  if (!cost) return '0원';
  if (cost >= 10000) return `${(cost / 10000).toFixed(0)}만원`;
  return `${cost.toLocaleString()}원`;
}

function hasValidCoordinate(place) {
  if (place?.lat === null || place?.lat === undefined || place?.lng === null || place?.lng === undefined) {
    return false;
  }
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

const MAP_OUTLIER_DISTANCE_METERS = 100000;

function mapPlaceKey(place) {
  return place?.id ?? `${place?.candidate_id ?? 'candidate'}-${place?.day_number}-${place?.order_index}-${place?.name}`;
}

function renderableCoordinatePlaceKeys(candidates, day) {
  const places = candidates
    .flatMap(candidate => candidate.places || [])
    .filter(place => place.day_number === day)
    .filter(hasValidCoordinate);

  if (places.length < 3) {
    return new Set(places.map(mapPlaceKey));
  }

  const keptPlaces = places.filter(place => {
    const placeKey = mapPlaceKey(place);
    const nearestDistance = Math.min(
      ...places
        .filter(other => mapPlaceKey(other) !== placeKey)
        .map(other => distanceMeters(place, other))
    );
    return nearestDistance <= MAP_OUTLIER_DISTANCE_METERS;
  });

  return new Set((keptPlaces.length >= 2 ? keptPlaces : places).map(mapPlaceKey));
}

function distanceMeters(a, b) {
  const lat1 = Number(a?.lat);
  const lng1 = Number(a?.lng);
  const lat2 = Number(b?.lat);
  const lng2 = Number(b?.lng);
  if (![lat1, lng1, lat2, lng2].every(Number.isFinite)) return Infinity;

  const radius = 6371000;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLng = (lng2 - lng1) * Math.PI / 180;
  const rLat1 = lat1 * Math.PI / 180;
  const rLat2 = lat2 * Math.PI / 180;
  const hav =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLng / 2) ** 2;
  return radius * 2 * Math.atan2(Math.sqrt(hav), Math.sqrt(1 - hav));
}

function pathDistanceMeters(path) {
  let total = 0;
  for (let i = 0; i < path.length - 1; i += 1) {
    total += distanceMeters(path[i], path[i + 1]);
  }
  return total;
}

function isReasonableRoute(path, origin, dest) {
  if (!Array.isArray(path) || path.length < 2) return false;
  if (path.some(p => !Number.isFinite(Number(p.lat)) || !Number.isFinite(Number(p.lng)))) {
    return false;
  }

  const directDistance = distanceMeters(origin, dest);
  if (!Number.isFinite(directDistance) || directDistance > 80000) {
    return false;
  }
  const routeDistance = pathDistanceMeters(path);
  const maxAllowedDistance = Math.max(20000, directDistance * 5);
  return Number.isFinite(routeDistance)
    && routeDistance <= maxAllowedDistance
    && path.every(p => {
      const lat = Number(p.lat);
      const lng = Number(p.lng);
      return lat >= 32.8 && lat <= 38.8 && lng >= 124.5 && lng <= 132.0;
    });
}

function toKakaoPath(path) {
  return path.map(p => new window.kakao.maps.LatLng(Number(p.lat), Number(p.lng)));
}

// ──────────────────────────────────────────────
// SortableItem 래퍼 — 카드를 길게 잡으면 드래그
// ──────────────────────────────────────────────
function SortableItem({ id, children }) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
    cursor: isDragging ? 'grabbing' : 'grab',
  };

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
  );
}

// ──────────────────────────────────────────────
// 빈 Day 드롭 존 (items가 없을 때 drop target)
// ──────────────────────────────────────────────
function EmptyDaySlot({ id }) {
  const { setNodeRef, isOver } = useSortable({ id });
  return (
    <div
      ref={setNodeRef}
      className={`h-14 rounded-xl border-2 border-dashed flex items-center justify-center transition-colors ${
        isOver ? 'border-blue-400 bg-blue-50 text-blue-500' : 'border-gray-200 text-gray-400'
      }`}
    >
      <span className="text-xs">{isOver ? '여기에 놓으세요' : '드래그해서 추가'}</span>
    </div>
  );
}

// ──────────────────────────────────────────────
// 장소 카드 (인라인 — 명세 레이아웃에 맞춤)
// ──────────────────────────────────────────────
function PlaceCardCompact({ place, candidateId, children, onPlaceSelect, deleteMode, protectedDeleteCategories }) {
  const cat = getCategoryStyle(place.category);
  const protectedCategories = protectedDeleteCategories ?? ['숙소', '이동'];
  const isDeletable = deleteMode && !protectedCategories.includes(place.category);
  const isProtected = deleteMode && !isDeletable;
  const shouldShowCost = place.category !== '숙소' && place.category !== '이동';

  return (
    <div
      className={`border rounded-lg p-3 transition-all duration-150 ${
        isDeletable
          ? 'border-red-300 bg-red-50 cursor-pointer hover:bg-red-100'
          : isProtected
            ? 'border-gray-200 bg-gray-100 opacity-50'
            : 'border-gray-200 bg-white hover:shadow-md'
      }`}
      onClick={() => onPlaceSelect && onPlaceSelect(candidateId, place)}
    >
      {/* 시간 + 카테고리 */}
      <div className="flex items-center justify-between mb-1.5">
        <span className="text-xs text-gray-400">{place.visit_time}</span>
        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${cat.color}`}>
          {cat.emoji} {place.category}
        </span>
      </div>

      {/* 장소명 */}
      <h4 className="font-bold text-sm text-gray-900 mb-1">{place.name}</h4>

      {/* 설명 */}
      {place.description && (
        <p className="text-xs text-gray-500 mb-2 leading-4 line-clamp-2" title={place.description}>
          {place.description}
        </p>
      )}

      {/* 비용 + 시간 */}
      <div className="flex items-center gap-3 text-xs text-gray-400 mb-2">
        {place.duration_minutes && (
          <span>⏱ {place.duration_minutes >= 60
            ? `${Math.floor(place.duration_minutes / 60)}시간${place.duration_minutes % 60 ? ` ${place.duration_minutes % 60}분` : ''}`
            : `${place.duration_minutes}분`
          }</span>
        )}
        {shouldShowCost && <span>💰 {formatCost(place.estimated_cost)}</span>}
      </div>

      {/* 투표 버튼 영역 (S3에서 주입) */}
      {children}
    </div>
  );
}


// ──────────────────────────────────────────────
// 메인 레이아웃 컴포넌트
// ──────────────────────────────────────────────
export default function PlanViewLayout({
  plan,
  candidates,
  headerTitle,        // string — NavBar 중앙 제목 (S2에서 전달, 클릭 시 홈)
  topBanner,          // string — 상단 안내 배너 (S2/S3에서 전달)
  // S2/S3 분기용 props
  renderCardFooter,   // (place, candidateId) => ReactNode — 투표 버튼 (S3에서 전달)
  renderBottomBar,    // () => ReactNode — 하단 코멘트 입력 (S3에서 전달)
  onReorder,          // (candidateId, fromPlaceId, toDayNumber, toOrderIndex) => void — 드래그&드롭 콜백
  deleteMode = false, // boolean — 삭제 모드 여부
  onDeleteModeToggle, // () => void — 삭제 모드 토글
  deleteModeLabel = '일정 삭제',
  deleteModeActiveLabel = '삭제 모드 끄기',
  protectedDeleteCategories = ['숙소', '이동'],
  onPlaceClick,       // (candidateId, place) => void — 삭제 모드에서 카드 클릭
  onPlaceDetailClick, // (place, candidateId) => void — 일반 카드 클릭 상세 동작
}) {
  // ── 상태 ──
  const [activeDay, setActiveDay] = useState(1);
  const [activeDragId, setActiveDragId] = useState(null); // dnd overlay용
  const [activeCandidateIdx, setActiveCandidateIdx] = useState(0); // 모바일 탭 (0=A, 1=B)
  const isMobile = useIsMobile();

  // ── 카카오맵 ──
  const mapContainerRef = useRef(null);
  const mapRef = useRef(null);
  const markersRef = useRef([]);
  const polylinesRef = useRef([]);
  const routeRenderSeqRef = useRef(0);
  const pendingFocusPlaceRef = useRef(null);

  // ── 공유 스크롤 컨테이너 + Day 섹션 refs ──
  const scrollRef = useRef(null);
  const dayRefs = useRef({});

  // ── 전체 일차 목록 (A+B 합집합) ──
  const dayNumbers = useMemo(() => {
    const allDays = candidates.flatMap(c => c.places.map(p => p.day_number));
    return [...new Set(allDays)].sort((a, b) => a - b);
  }, [candidates]);

  // ── 후보별 Day 장소 목록 헬퍼 ──
  const getCandidateDayPlaces = useCallback((candidate, day) => {
    if (!candidate) return [];
    return candidate.places
      .filter(p => p.day_number === day)
      .sort((a, b) => a.order_index - b.order_index);
  }, []);

  // ── 카드 클릭 → 지도 해당 장소로 이동 + 필요 시 상세 동작 호출 ──
  const handlePlaceClick = useCallback((candidateId, place) => {
    // 삭제 모드면 삭제 콜백 호출
    if (deleteMode && onPlaceClick) {
      onPlaceClick(candidateId, place);
      return;
    }

    const map = mapRef.current;
    if (map && hasValidCoordinate(place)) {
      const renderableKeys = renderableCoordinatePlaceKeys(candidates, place.day_number);
      if (renderableKeys.has(mapPlaceKey(place))) {
        pendingFocusPlaceRef.current = place;
        setActiveDay(place.day_number);

        // 같은 일차의 카드는 마커 재렌더가 없으므로 즉시 이동한다.
        // 다른 일차의 카드는 updateMapMarkers()가 해당 일차 마커를 그린 뒤 이동한다.
        if (activeDay === place.day_number) {
          pendingFocusPlaceRef.current = null;
          const position = new window.kakao.maps.LatLng(Number(place.lat), Number(place.lng));
          map.setLevel(3);
          map.setCenter(position);
        }
      }
    }

    if (isMobile) {
      onPlaceDetailClick?.(place, candidateId);
    }
  }, [activeDay, candidates, deleteMode, isMobile, onPlaceClick, onPlaceDetailClick]);

  // ── Day 버튼 클릭 → 스크롤 (모바일: window, 데스크탑: 내부 컨테이너) ──
  const handleDayClick = useCallback((day) => {
    setActiveDay(day);
    const dayEl = dayRefs.current[day];
    if (!dayEl) return;
    if (isMobile) {
      dayEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    const scrollEl = scrollRef.current;
    if (!scrollEl) return;
    const containerTop = scrollEl.getBoundingClientRect().top;
    const elementTop = dayEl.getBoundingClientRect().top;
    const relativeTop = elementTop - containerTop + scrollEl.scrollTop - 8;
    scrollEl.scrollTo({ top: relativeTop, behavior: 'smooth' });
  }, [isMobile]);

  const handleDayButtonClick = useCallback((event) => {
    const day = Number(event.currentTarget.dataset.day);
    if (Number.isFinite(day)) {
      handleDayClick(day);
    }
  }, [handleDayClick]);

  // ──────────────────────────────────────────────
  // 마커 + 폴리라인 업데이트 (A안 + B안 동시 표시)
  // ──────────────────────────────────────────────
  const updateMapMarkers = useCallback(() => {
    const map = mapRef.current;
    if (!map || candidates.length === 0) return;

    markersRef.current.forEach(m => m.setMap(null));
    polylinesRef.current.forEach(p => p.setMap(null));
    markersRef.current = [];
    polylinesRef.current = [];
    const renderSeq = routeRenderSeqRef.current + 1;
    routeRenderSeqRef.current = renderSeq;

    const bounds = new window.kakao.maps.LatLngBounds();
    const renderableKeys = renderableCoordinatePlaceKeys(candidates, activeDay);

    candidates.forEach((candidate, candIdx) => {
      // 모바일에선 현재 활성 탭의 candidate만 표시
      if (isMobile && candIdx !== activeCandidateIdx) return;

      const color = (isMobile ? MOBILE_CANDIDATE_COLORS : CANDIDATE_COLORS)[candIdx] ?? '#8b5cf6';
      const dayPlaces = candidate.places
        .filter(p => p.day_number === activeDay)
        .filter(hasValidCoordinate)
        .filter(p => renderableKeys.has(mapPlaceKey(p)))
        .sort((a, b) => a.order_index - b.order_index);

      dayPlaces.forEach((place, idx) => {
        const position = new window.kakao.maps.LatLng(Number(place.lat), Number(place.lng));
        bounds.extend(position);

        // 번호 마커
        const el = document.createElement('div');
        el.style.cssText = `
          width: 30px; height: 30px; border-radius: 50%;
          background: ${color}; color: white; font-size: 12px;
          font-weight: 700; display: flex; align-items: center;
          justify-content: center; border: 2.5px solid white;
          box-shadow: 0 2px 8px rgba(0,0,0,0.25);
        `;
        el.textContent = `${idx + 1}`;

        const overlay = new window.kakao.maps.CustomOverlay({
          position,
          content: el,
          yAnchor: 0.5,
        });
        overlay.setMap(map);
        markersRef.current.push(overlay);

        // A안/B안 라벨 + 장소명
        const label = document.createElement('div');
        label.style.cssText = `
          font-size: 10px; color: white; font-weight: 600;
          background: ${color}; padding: 1px 5px; border-radius: 4px;
          box-shadow: 0 1px 3px rgba(0,0,0,0.2); margin-top: 3px;
          white-space: nowrap;
        `;
        label.textContent = `${candidate.label}안 ${place.name}`;

        const labelOverlay = new window.kakao.maps.CustomOverlay({
          position,
          content: label,
          yAnchor: -0.5,
        });
        labelOverlay.setMap(map);
        markersRef.current.push(labelOverlay);
      });

      // 진짜 경로를 먼저 시도하되, null 좌표/외부 API 실패/비정상적으로 긴 경로는 직선으로 폴백한다.
      const drawRoutes = async () => {
        for (let i = 0; i < dayPlaces.length - 1; i++) {
          if (routeRenderSeqRef.current !== renderSeq) return;
          const origin = dayPlaces[i];
          const dest   = dayPlaces[i + 1];
          if (!hasValidCoordinate(origin) || !hasValidCoordinate(dest)) continue;
          let path = [
            { lat: Number(origin.lat), lng: Number(origin.lng) },
            { lat: Number(dest.lat), lng: Number(dest.lng) },
          ];
          const directDistance = distanceMeters(origin, dest);
          const shouldDrawFallback = Number.isFinite(directDistance) && directDistance <= 80000;

          try {
            const params = new URLSearchParams({
              originLat: String(origin.lat),
              originLng: String(origin.lng),
              destLat: String(dest.lat),
              destLng: String(dest.lng),
            });
            const res = await fetch(`/api/v1/route?${params.toString()}`, { credentials: 'include' });
            const json = await res.json();
            if (routeRenderSeqRef.current !== renderSeq) return;
            const routePath = (json.data || []).map(([lat, lng]) => ({ lat: Number(lat), lng: Number(lng) }));
            if (isReasonableRoute(routePath, origin, dest)) {
              path = routePath;
            } else if (!shouldDrawFallback) {
              continue;
            }
          } catch {
            // route API 장애는 미리보기 화면을 막지 않고 직선 폴백으로 처리한다.
            if (!shouldDrawFallback) {
              continue;
            }
          }
          if (routeRenderSeqRef.current !== renderSeq) return;

          const polyline = new window.kakao.maps.Polyline({
            map,
            path: toKakaoPath(path),
            strokeWeight: 4,
            strokeColor: color,
            strokeOpacity: 0.85,
            strokeStyle: 'solid',
          });
          polylinesRef.current.push(polyline);
        }
      };
      drawRoutes();
    });

    if (!bounds.isEmpty()) map.setBounds(bounds, 60);

    const pendingFocusPlace = pendingFocusPlaceRef.current;
    if (pendingFocusPlace?.day_number === activeDay && hasValidCoordinate(pendingFocusPlace)) {
      pendingFocusPlaceRef.current = null;
      const position = new window.kakao.maps.LatLng(
        Number(pendingFocusPlace.lat),
        Number(pendingFocusPlace.lng)
      );
      map.setLevel(3);
      map.setCenter(position);
    }
  }, [activeDay, candidates, isMobile, activeCandidateIdx]);

  // ──────────────────────────────────────────────
  // 카카오맵 초기화
  // ──────────────────────────────────────────────
  useEffect(() => {
    function initMap() {
      if (!mapContainerRef.current) return;

      const map = new window.kakao.maps.Map(mapContainerRef.current, {
        center: new window.kakao.maps.LatLng(35.1796, 129.0756),
        level: 7,
      });
      mapRef.current = map;

      const zoomControl = new window.kakao.maps.ZoomControl();
      map.addControl(zoomControl, window.kakao.maps.ControlPosition.RIGHT);

      updateMapMarkers();
    }

    if (window.kakao && window.kakao.maps) {
      window.kakao.maps.load(initMap);
    }

    return () => {
      routeRenderSeqRef.current += 1;
      pendingFocusPlaceRef.current = null;
      markersRef.current.forEach(m => m.setMap(null));
      polylinesRef.current.forEach(p => p.setMap(null));
      markersRef.current = [];
      polylinesRef.current = [];
      mapRef.current = null;
    };
  // 모바일↔데스크탑 전환 시 지도 컨테이너 DOM이 바뀌므로 재초기화
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isMobile]);

  useEffect(() => {
    if (window.kakao && window.kakao.maps && mapRef.current) {
      updateMapMarkers();
    }
  }, [updateMapMarkers]);

  // ──────────────────────────────────────────────
  // 스크롤 감지 → activeDay 자동 업데이트
  // ──────────────────────────────────────────────
  useEffect(() => {
    const scrollEl = scrollRef.current;
    if (!scrollEl) return;

    const handleScroll = () => {
      const atBottom = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight < 16;
      if (atBottom) {
        const lastDay = dayNumbers[dayNumbers.length - 1];
        setActiveDay(prev => prev !== lastDay ? lastDay : prev);
        return;
      }

      const containerTop = scrollEl.getBoundingClientRect().top;
      const threshold = containerTop + 80;

      let newActiveDay = dayNumbers[0];
      for (const day of dayNumbers) {
        const el = dayRefs.current[day];
        if (!el) continue;
        if (el.getBoundingClientRect().top <= threshold) {
          newActiveDay = day;
        }
      }
      setActiveDay(prev => prev !== newActiveDay ? newActiveDay : prev);
    };

    scrollEl.addEventListener('scroll', handleScroll, { passive: true });
    return () => scrollEl.removeEventListener('scroll', handleScroll);
  }, [dayNumbers]);

  // ──────────────────────────────────────────────
  // DnD 센서 설정
  // - 데스크탑(마우스): 4px 이동 시 드래그 시작 (즉시 반응)
  // - 모바일(터치): 250ms 길게 누르면 드래그 시작 (스크롤과 충돌 방지)
  // ──────────────────────────────────────────────
  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: { distance: 4 },
    }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 250, tolerance: 5 },
    }),
  );

  // ── dnd: 드래그 시작 ──
  const handleDragStart = useCallback((event) => {
    setActiveDragId(event.active.id);
  }, []);

  // ── dnd: 드래그 종료 → onReorder 호출 ──
  const handleDragEnd = useCallback((event) => {
    setActiveDragId(null);
    const { active, over } = event;
    if (!over || active.id === over.id || !onReorder) return;

    // active.id 형식: "displayCandId:placeId"
    const activeParts = active.id.split(':');
    const activeCandId = Number(activeParts[0]);
    const activePlaceId = Number(activeParts[1]);

    // over.id 형식: "candId:empty-slot:day" 또는 "candId:placeId"
    const overParts = over.id.split(':');
    const targetCandId = Number(overParts[0]); // 드롭된 칸의 후보 ID
    const overType = overParts[1];
    if (activeCandId !== targetCandId) return;

    // 빈 Day 슬롯에 드롭
    if (overType === 'empty-slot') {
      const toDayNumber = Number(overParts[2]);
      onReorder(targetCandId, activePlaceId, toDayNumber, 0);
      return;
    }

    // 일반 카드에 드롭 — over 카드의 day_number와 순서 파악
    const overPlaceId = overParts[1];
    let overPlace = null;
    for (const cand of candidates) {
      const found = cand.places.find(p => String(p.id) === overPlaceId);
      if (found) { overPlace = found; break; }
    }
    if (!overPlace) return;

    const toDayNumber = overPlace.day_number;

    // targetCandId 열에서 해당 day의 순서 계산
    const targetCand = candidates.find(c => c.id === targetCandId);
    if (!targetCand) return;

    const dayPlaces = targetCand.places
      .filter(p => p.day_number === toDayNumber)
      .sort((a, b) => a.order_index - b.order_index);

    const overIdx = dayPlaces.findIndex(p => String(p.id) === overPlaceId);
    const toOrderIndex = overIdx >= 0 ? overIdx : dayPlaces.length;

    onReorder(targetCandId, activePlaceId, toDayNumber, Math.max(0, toOrderIndex));
  }, [onReorder, candidates]);

  // ── activeDrag 대상 장소 찾기 (DragOverlay 렌더용) ──
  const activeDragPlace = useMemo(() => {
    if (!activeDragId) return null;
    const [, placeId] = activeDragId.split(':');
    for (const cand of candidates) {
      const found = cand.places.find(p => String(p.id) === placeId);
      if (found) return found;
    }
    return null;
  }, [activeDragId, candidates]);

  // 전체 후보 모든 아이템 ID (단일 SortableContext용 — A안+B안 합산)
  // 모바일은 활성 candidate만 sortable (A↔B 크로스 드래그는 데스크탑/탭 전환 안 한 상태에서만 의미)
  const allSortableIds = useMemo(() => {
    const visibleCandidates = isMobile
      ? candidates.filter((_, i) => i === activeCandidateIdx)
      : candidates;
    const ids = [];
    visibleCandidates.forEach(cand => {
      cand.places.forEach(p => ids.push(`${cand.id}:${p.id}`));
    });
    visibleCandidates.forEach(cand => {
      const daysWithPlaces = new Set(cand.places.map(p => p.day_number));
      dayNumbers.forEach(day => {
        if (!daysWithPlaces.has(day)) ids.push(`${cand.id}:empty-slot:${day}`);
      });
    });
    return ids;
  }, [candidates, dayNumbers, isMobile, activeCandidateIdx]);

  // ──────────────────────────────────────────────
  // 렌더링
  // ──────────────────────────────────────────────
  if (!plan || candidates.length === 0) return null;

  const isDraggable = Boolean(onReorder);

  const cardList = (
    <>
    <div className="min-h-screen md:h-screen flex flex-col bg-gray-50 md:overflow-hidden">
      <NavBar title={headerTitle} />

      {/* ── 안내 배너 + Day 버튼 ── */}
      <div className="flex-shrink-0 max-w-[1400px] mx-auto px-4 md:px-6 w-full pt-3 pb-2">
        {topBanner && (
          <div className="bg-accent-50 border border-accent-200 text-accent-700 md:bg-violet-50 md:border-violet-200 md:text-violet-700 rounded-xl px-4 py-2.5 mb-2 text-sm font-medium">
            {topBanner}
          </div>
        )}
        <div className="flex items-center gap-2 overflow-x-auto pb-1">
          <span className="flex-shrink-0 text-sm text-gray-500 mr-1">빠른 이동:</span>
          {dayNumbers.map(day => {
            const isActive = activeDay === day;
            return (
              <button
                key={day}
                data-day={day}
                onClick={handleDayButtonClick}
                className={`flex-shrink-0 px-4 py-1.5 rounded-full text-sm font-medium transition-all ${
                  isActive ? 'text-white shadow-md' : 'bg-white border border-gray-200 text-gray-600 hover:bg-gray-50'
                }`}
                style={isActive ? { backgroundColor: '#5BA89C' } : {}}
              >
                Day {day}
              </button>
            );
          })}
          {isDraggable && (
            <span className="flex-shrink-0 whitespace-nowrap ml-2 text-xs text-gray-400 border border-dashed border-gray-300 rounded-full px-3 py-1">
              ✦ 길게 잡아 순서 변경
            </span>
          )}
          {onDeleteModeToggle && (
            <button
              onClick={onDeleteModeToggle}
              title={deleteMode ? deleteModeActiveLabel : deleteModeLabel}
              className={`flex-shrink-0 whitespace-nowrap ml-auto text-xs border rounded-full px-3 py-1 transition-colors ${
                deleteMode
                  ? 'bg-red-50 border-red-300 text-red-600 font-bold'
                  : 'border-gray-300 text-gray-400 hover:text-red-500 hover:border-red-300'
              }`}
            >
              <span className="md:hidden">{deleteMode ? '✕' : '🗑'}</span>
              <span className="hidden md:inline">{deleteMode ? `✕ ${deleteModeActiveLabel}` : `🗑 ${deleteModeLabel}`}</span>
            </button>
          )}
        </div>
      </div>

      {/* ── 모바일: 지도 → 탭(sticky) → 카드 1열 ── */}
      {isMobile && (() => {
        const activeCand = candidates[activeCandidateIdx];
        return (
          <div className="md:hidden flex flex-col">
            {/* 지도 */}
            <div className="px-4 pt-1 pb-3">
              <div className="rounded-xl overflow-hidden border border-gray-200 shadow-sm">
                <div className="bg-gray-800 text-white px-3 py-2 text-xs flex items-center gap-2">
                  <span className="font-medium">🗺️ Day {activeDay} 동선</span>
                  {activeCand && (
                    <span
                      className="ml-auto px-2 py-0.5 rounded-full font-semibold text-[10px]"
                      style={{ background: MOBILE_CANDIDATE_COLORS[activeCandidateIdx] ?? CANDIDATE_COLORS[activeCandidateIdx], color: 'white' }}
                    >
                      {activeCand.label}안
                    </span>
                  )}
                </div>
                <div ref={mapContainerRef} className="h-56 bg-gray-100">
                  {(!window.kakao || !window.kakao.maps) && (
                    <div className="w-full h-full flex items-center justify-center text-gray-400 text-xs">
                      카카오맵 로딩 중...
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* 탭 + Day 빠른이동 (sticky) */}
            <div className="sticky top-0 z-30 bg-gray-50 border-b border-gray-200 px-4 py-2 space-y-2">
              {/* A/B 탭 */}
              <div className="flex gap-2">
                {candidates.map((cand, idx) => {
                  const color = MOBILE_CANDIDATE_COLORS[idx] ?? '#8b5cf6';
                  const isActive = activeCandidateIdx === idx;
                  return (
                    <button
                      key={cand.id}
                      onClick={() => setActiveCandidateIdx(idx)}
                      className="flex-1 py-2 px-2 rounded-lg text-sm font-bold border-2 transition-all min-w-0"
                      style={{
                        borderColor: isActive ? color : '#e5e7eb',
                        background: isActive ? color : 'white',
                        color: isActive ? 'white' : '#6b7280',
                      }}
                    >
                      <span className="block truncate">
                        {cand.label}안 · {cand.name}
                      </span>
                    </button>
                  );
                })}
              </div>
              {/* Day 빠른이동 */}
              <div className="flex gap-2 overflow-x-auto pb-1 -mx-4 px-4">
                {dayNumbers.map(day => {
                  const isActive = activeDay === day;
                  return (
                    <button
                      key={day}
                      data-day={day}
                      onClick={handleDayButtonClick}
                      className={`flex-shrink-0 px-3 py-1 rounded-full text-xs font-medium transition-all ${
                        isActive ? 'text-white' : 'bg-white border border-gray-200 text-gray-600'
                      }`}
                      style={isActive ? { backgroundColor: MOBILE_DAY_ACCENT } : {}}
                    >
                      Day {day}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* 카드 1열 - 활성 candidate의 모든 Day */}
            <div className="px-4 pt-3 pb-28 space-y-5">
              {dayNumbers.map(day => {
                const places = activeCand ? getCandidateDayPlaces(activeCand, day) : [];
                const emptySlotId = activeCand ? `${activeCand.id}:empty-slot:${day}` : `empty:${day}`;
                return (
                  <div key={day} ref={el => { dayRefs.current[day] = el }}>
                    <div className="flex items-center gap-2 mb-2">
                      <span className="text-sm font-bold text-gray-700">🗓️ {day}일차</span>
                    </div>
                    <div className="space-y-2">
                      {isDraggable && activeCand ? (
                        places.length > 0 ? (
                          places.map(place => (
                            <SortableItem key={place.id} id={`${activeCand.id}:${place.id}`}>
                              <PlaceCardCompact
                                place={place}
                                candidateId={activeCand.id}
                                onPlaceSelect={handlePlaceClick}
                                deleteMode={deleteMode}
                                protectedDeleteCategories={protectedDeleteCategories}
                              >
                                {renderCardFooter && renderCardFooter(place, activeCand.id)}
                              </PlaceCardCompact>
                            </SortableItem>
                          ))
                        ) : (
                          <EmptyDaySlot id={emptySlotId} />
                        )
                      ) : (
                        places.length > 0 ? (
                          places.map(place => (
                            <PlaceCardCompact
                              key={place.id}
                              place={place}
                              candidateId={activeCand.id}
                              onPlaceSelect={handlePlaceClick}
                              deleteMode={deleteMode}
                              protectedDeleteCategories={protectedDeleteCategories}
                            >
                              {renderCardFooter && renderCardFooter(place, activeCand?.id)}
                            </PlaceCardCompact>
                          ))
                        ) : (
                          <div className="h-10 rounded-xl border border-dashed border-gray-200 flex items-center justify-center">
                            <span className="text-xs text-gray-400">일정 없음</span>
                          </div>
                        )
                      )}
                    </div>
                  </div>
                );
              })}
              {renderBottomBar && <div className="mt-4">{renderBottomBar()}</div>}
            </div>
          </div>
        );
      })()}

      {/* ── 데스크탑: A안 | B안 | 지도 — 각 열 헤더 고정, 내부만 스크롤 ── */}
      {!isMobile && (
      <div className="hidden md:block flex-1 overflow-hidden max-w-[1400px] mx-auto px-6 w-full pb-4">
        <div className="h-full flex gap-4">

          {/* A안 + B안 묶음 (헤더 고정 + 내부 같이 스크롤) */}
          <div className="flex-1 min-w-0 flex flex-col">

            {/* 고정 헤더 행 */}
            <div className="flex-shrink-0 flex gap-4 mb-2 pr-[17px]">
              {candidates.map((cand, idx) => {
                const circleColor = idx === 0 ? 'bg-blue-400' : 'bg-primary-500';
                const borderColor = idx === 0 ? 'border-blue-200' : 'border-primary-200';
                const bgColor     = idx === 0 ? 'bg-blue-50'  : 'bg-primary-50';
                return (
                  <div key={cand.id} className={`flex-1 min-w-0 overflow-hidden flex items-start gap-3 p-3 rounded-xl border-2 ${borderColor} ${bgColor}`}>
                    <span className={`w-8 h-8 rounded-full ${circleColor} text-white text-sm font-bold
                                     flex items-center justify-center flex-shrink-0`}>
                      {cand.label}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p title={cand.name} className="text-sm font-bold text-gray-900 leading-5 line-clamp-2">{cand.name}</p>
                      <p title={cand.concept} className="text-xs text-gray-500 leading-4 line-clamp-3 mt-0.5">{cand.concept}</p>
                      <span className="inline-flex mt-1 text-xs font-bold whitespace-nowrap px-2 py-1 rounded-lg bg-amber-100 text-amber-700">
                        💰 1인 예상 {formatPerPersonCost(cand.estimated_cost_per_person)}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* 공유 스크롤 영역 — Day별 행으로 묶어 A/B 시작점 정렬 */}
            <div ref={scrollRef} className="flex-1 overflow-y-auto pr-1 space-y-5 pb-20">
              {dayNumbers.map(day => (
                <div
                  key={day}
                  ref={el => { dayRefs.current[day] = el }}
                >
                  {/* 일차 헤더 */}
                  <div className="flex items-center gap-2 mb-2">
                    <span className="text-sm font-bold text-gray-700">🗓️ {day}일차</span>
                  </div>
                  {/* A안 | B안 카드 — 같은 행이라 높이 자동 맞춤 */}
                  <div className="flex gap-4 items-start">
                    {candidates.map((cand, idx) => {
                      const places = getCandidateDayPlaces(cand, day);
                      const borderColor = idx === 0 ? 'border-blue-200' : 'border-primary-200';
                      const bgColor    = idx === 0 ? 'bg-blue-50/40'   : 'bg-primary-50/40';

                      const emptySlotId = `${cand.id}:empty-slot:${day}`;

                      return (
                        <div key={cand.id} className={`flex-1 rounded-xl border-2 ${borderColor} ${bgColor} p-2.5 space-y-2`}>
                          {isDraggable && !deleteMode ? (
                            places.length > 0 ? (
                              places.map(place => (
                                <SortableItem key={place.id} id={`${cand.id}:${place.id}`}>
                                  <PlaceCardCompact
                                    place={place}
                                    deleteMode={deleteMode}
                                    protectedDeleteCategories={protectedDeleteCategories}
                                    candidateId={cand.id}
                                    onPlaceSelect={handlePlaceClick}
                                  >
                                    {renderCardFooter && renderCardFooter(place, cand.id)}
                                  </PlaceCardCompact>
                                </SortableItem>
                              ))
                            ) : (
                              <EmptyDaySlot id={emptySlotId} />
                            )
                          ) : (
                            places.length > 0 ? (
                              places.map(place => (
                                <PlaceCardCompact
                                  key={place.id}
                                  place={place}
                                  deleteMode={deleteMode}
                                  protectedDeleteCategories={protectedDeleteCategories}
                                  candidateId={cand.id}
                                  onPlaceSelect={handlePlaceClick}
                                >
                                  {renderCardFooter && renderCardFooter(place, cand.id)}
                                </PlaceCardCompact>
                              ))
                            ) : (
                              <div className="h-10 rounded-xl border border-dashed border-gray-200 flex items-center justify-center">
                                <span className="text-xs text-gray-400">일정 없음</span>
                              </div>
                            )
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
              {renderBottomBar && <div className="mt-4">{renderBottomBar()}</div>}
            </div>
          </div>

          {/* ── 지도 열 — 헤더 고정, 지도 flex-1 ── */}
          <div className="w-[480px] flex-shrink-0 flex flex-col">
            <div className="flex-shrink-0 bg-gray-800 text-white px-4 py-2.5 rounded-t-xl flex items-center gap-3">
              <p className="text-sm font-medium">🗺️ Day {activeDay} 동선 미리보기</p>
              <span className="text-xs px-2 py-0.5 rounded-full font-semibold" style={{ background: CANDIDATE_COLORS[0], color: 'white' }}>A안</span>
              <span className="text-xs px-2 py-0.5 rounded-full font-semibold" style={{ background: CANDIDATE_COLORS[1], color: 'white' }}>B안</span>
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
    </div>

    {/* DragOverlay — 드래그 중인 카드 미리보기 */}
    <DragOverlay>
      {activeDragPlace ? (
        <div className="opacity-90 shadow-2xl rounded-lg">
          <PlaceCardCompact place={activeDragPlace} />
        </div>
      ) : null}
    </DragOverlay>
  </>
  );

  if (!isDraggable) {
    return cardList;
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={pointerWithin}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={allSortableIds} strategy={verticalListSortingStrategy}>
        {cardList}
      </SortableContext>
    </DndContext>
  );
}
