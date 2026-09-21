// 백엔드 camelCase 응답 → PlanViewLayout이 기대하는 snake_case로 변환

export function transformPlace(p) {
  return {
    ...p,
    day_number: p.dayNumber,
    order_index: p.orderIndex,
    estimated_cost: p.estimatedCost,
    duration_minutes: p.durationMinutes,
    visit_time: p.visitTime ? p.visitTime.substring(0, 5) : '',
    kakao_place_id: p.kakaoPlaceId,
  };
}

export function transformCandidate(c) {
  return {
    ...c,
    estimated_cost_per_person: c.estimatedCostPerPerson,
    total_distance_km: c.totalDistanceKm,
    split_reason: c.splitReason,
    is_final: c.isFinal,
    highlights: c.highlights || [],
    places: (c.places || []).map(transformPlace),
  };
}

export function transformPlan(p) {
  return {
    ...p,
    start_date: p.startDate ?? p.start_date,
    end_date: p.endDate ?? p.end_date,
    participant_count: p.participantCount ?? p.participant_count,
  };
}
