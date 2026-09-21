/**
 * DayTimeline.jsx
 * 
 * 📅 일자별 타임라인 컴포넌트
 * 
 * - places 배열을 day_number 기준으로 그룹핑
 * - 각 일자 내에서 order_index 순으로 정렬
 * - PlaceCard를 렌더링하고, 카드 사이에 연결선 표시
 * 
 * 위치: src/components/DayTimeline.jsx
 */

import PlaceCard from './PlaceCard';

// day_number별 테마 색상 (최대 5일까지, 그 이상은 순환)
const DAY_COLORS = [
  { bg: 'bg-indigo-500', text: 'text-indigo-700', light: 'bg-indigo-50', border: 'border-indigo-200' },
  { bg: 'bg-emerald-500', text: 'text-emerald-700', light: 'bg-emerald-50', border: 'border-emerald-200' },
  { bg: 'bg-amber-500', text: 'text-amber-700', light: 'bg-amber-50', border: 'border-amber-200' },
  { bg: 'bg-rose-500', text: 'text-rose-700', light: 'bg-rose-50', border: 'border-rose-200' },
  { bg: 'bg-violet-500', text: 'text-violet-700', light: 'bg-violet-50', border: 'border-violet-200' },
];

function getDayColor(dayNumber) {
  return DAY_COLORS[(dayNumber - 1) % DAY_COLORS.length];
}

function groupByDay(places) {
  const groups = {};
  places.forEach((place) => {
    const day = place.day_number;
    if (!groups[day]) groups[day] = [];
    groups[day].push(place);
  });

  // 각 일자 내에서 order_index 순 정렬
  Object.keys(groups).forEach((day) => {
    groups[day].sort((a, b) => a.order_index - b.order_index);
  });

  return groups;
}

function DaySection({ dayNumber, places, renderCardChildren }) {
  const dayColor = getDayColor(dayNumber);

  // 해당 일자의 총 예상 비용
  const totalCost = places.reduce((sum, p) => sum + (p.estimated_cost || 0), 0);

  return (
    <div className="mb-8">
      {/* 일자 헤더 */}
      <div className={`flex items-center justify-between mb-4 px-4 py-3 rounded-xl ${dayColor.light} ${dayColor.border} border`}>
        <div className="flex items-center gap-3">
          <span className={`${dayColor.bg} text-white text-sm font-bold px-3 py-1 rounded-lg shadow-sm`}>
            DAY {dayNumber}
          </span>
          <span className="text-sm text-gray-500">
            {places.length}개 장소
          </span>
        </div>
        {totalCost > 0 && (
          <span className="text-sm text-gray-400">
            예상 💰 {totalCost >= 10000 
              ? `${(totalCost / 10000).toFixed(totalCost % 10000 === 0 ? 0 : 1)}만원`
              : `${totalCost.toLocaleString()}원`
            }
          </span>
        )}
      </div>

      {/* 장소 카드 리스트 + 연결선 */}
      <div className="relative ml-4">
        {/* 세로 연결선 */}
        {places.length > 1 && (
          <div className="absolute left-4 top-8 bottom-8 w-px bg-gray-200" />
        )}

        {places.map((place, idx) => (
          <PlaceCard
            key={place.id}
            place={place}
            orderIndex={idx + 1}
            children={renderCardChildren ? renderCardChildren(place) : null}
          />
        ))}
      </div>
    </div>
  );
}

export default function DayTimeline({ places, renderCardChildren }) {
  if (!places || places.length === 0) {
    return (
      <div className="text-center py-12 text-gray-400">
        <p className="text-lg">아직 장소가 없어요</p>
        <p className="text-sm mt-1">AI가 일정을 생성하면 여기에 표시됩니다</p>
      </div>
    );
  }

  const dayGroups = groupByDay(places);
  const sortedDays = Object.keys(dayGroups)
    .map(Number)
    .sort((a, b) => a - b);

  return (
    <div>
      {sortedDays.map((dayNumber) => (
        <DaySection
          key={dayNumber}
          dayNumber={dayNumber}
          places={dayGroups[dayNumber]}
          renderCardChildren={renderCardChildren}
        />
      ))}
    </div>
  );
}