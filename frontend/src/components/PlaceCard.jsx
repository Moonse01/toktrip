/**
 * PlaceCard.jsx
 * 
 * 📍 장소 카드 공용 컴포넌트
 * 
 * - S2(A/B안 렌더링)와 S3(투표 화면)에서 재사용
 * - S3에서는 children prop으로 투표 버튼을 주입하면 됨
 * 
 * 위치: src/components/PlaceCard.jsx
 */

const CATEGORY_CONFIG = {
  '관광':    { emoji: '🏛️', color: 'bg-blue-50 text-blue-600 border-blue-200' },
  '맛집':    { emoji: '🍽️', color: 'bg-amber-50 text-amber-600 border-amber-200' },
  '카페':    { emoji: '☕', color: 'bg-amber-50 text-amber-700 border-amber-200' },
  '숙소':    { emoji: '🏨', color: 'bg-purple-50 text-purple-600 border-purple-200' },
  '쇼핑':    { emoji: '🛍️', color: 'bg-pink-50 text-pink-600 border-pink-200' },
  '액티비티': { emoji: '🎯', color: 'bg-green-50 text-green-600 border-green-200' },
  '이동':    { emoji: '🚗', color: 'bg-gray-50 text-gray-600 border-gray-200' },
};

function getCategoryStyle(category) {
  return CATEGORY_CONFIG[category] || { emoji: '📌', color: 'bg-gray-50 text-gray-600 border-gray-200' };
}

function formatCost(cost) {
  if (!cost || cost === 0) return '무료';
  if (cost >= 10000) return `${(cost / 10000).toFixed(cost % 10000 === 0 ? 0 : 1)}만원`;
  return `${cost.toLocaleString()}원`;
}

function formatDuration(minutes) {
  if (!minutes) return '';
  if (minutes < 60) return `${minutes}분`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}시간 ${m}분` : `${h}시간`;
}

export default function PlaceCard({ place, orderIndex, children }) {
  const { emoji, color } = getCategoryStyle(place.category);

  return (
    <div className="group relative flex gap-3 py-3">
      {/* 순서 번호 원형 */}
      <div className="flex-shrink-0 w-8 h-8 rounded-full bg-indigo-500 text-white 
                      flex items-center justify-center text-sm font-bold shadow-sm
                      group-hover:bg-indigo-600 transition-colors">
        {orderIndex}
      </div>

      {/* 카드 본체 */}
      <div className="flex-1 bg-white border border-gray-100 rounded-xl p-4 
                      shadow-sm hover:shadow-md transition-shadow">
        {/* 상단: 시간 + 카테고리 */}
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs text-gray-400 font-medium tracking-wide">
            {place.visit_time || '시간 미정'}
          </span>
          <span className={`text-xs px-2 py-0.5 rounded-full border font-medium ${color}`}>
            {emoji} {place.category}
          </span>
        </div>

        {/* 장소명 */}
        <h4 className="text-base font-bold text-gray-900 mb-1">
          {place.name}
        </h4>

        {/* 설명 */}
        {place.description && (
          <p className="text-sm text-gray-500 mb-2 line-clamp-2">
            {place.description}
          </p>
        )}

        {/* 하단: 소요시간 + 예상비용 */}
        <div className="flex items-center gap-3 text-xs text-gray-400">
          {place.duration_minutes > 0 && (
            <span className="flex items-center gap-1">
              ⏱️ {formatDuration(place.duration_minutes)}
            </span>
          )}
          {place.estimated_cost != null && (
            <span className="flex items-center gap-1">
              💰 {formatCost(place.estimated_cost)}
            </span>
          )}
        </div>

        {/* S3 투표 버튼 슬롯 — children으로 주입 */}
        {children && (
          <div className="mt-3 pt-3 border-t border-gray-50">
            {children}
          </div>
        )}
      </div>
    </div>
  );
}