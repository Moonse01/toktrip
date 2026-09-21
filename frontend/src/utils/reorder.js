/**
 * A/B안 내부에서 장소를 다른 위치로 이동하고, order_index를 재정렬한다.
 * S2(호스트)와 S3(게스트)에서 공유한다.
 *
 * @param {Array} candidates - 현재 후보안 배열
 * @param {number} targetCandId - 드롭 대상 후보안 ID
 * @param {number} fromPlaceId - 이동할 장소 ID
 * @param {number} toDayNumber - 목표 day_number
 * @param {number} toOrderIndex - 목표 order_index (삽입 위치)
 * @returns {{ changed: boolean, blocked?: boolean, nextCandidates?: Array, requests?: Array }}
 */
export function buildReorder(candidates, targetCandId, fromPlaceId, toDayNumber, toOrderIndex) {
  const sourceCandidate = candidates.find(cand =>
    cand.places.some(place => Number(place.id) === Number(fromPlaceId))
  );
  if (!sourceCandidate) return { changed: false };
  if (Number(sourceCandidate.id) !== Number(targetCandId)) {
    return { changed: false, blocked: true };
  }

  const nextCandidates = candidates.map(cand => {
    if (Number(cand.id) !== Number(sourceCandidate.id)) return cand;

    const moved = cand.places.find(place => Number(place.id) === Number(fromPlaceId));
    if (!moved) return cand;

    const targetDay = Math.max(1, Number(toDayNumber) || moved.day_number || 1);
    const remaining = cand.places.filter(place => Number(place.id) !== Number(fromPlaceId));
    const targetDayPlaces = remaining
      .filter(place => Number(place.day_number) === targetDay)
      .sort((a, b) => Number(a.order_index || 0) - Number(b.order_index || 0));
    const otherPlaces = remaining.filter(place => Number(place.day_number) !== targetDay);

    const insertAt = Math.max(0, Math.min(Number(toOrderIndex) || 0, targetDayPlaces.length));
    targetDayPlaces.splice(insertAt, 0, { ...moved, day_number: targetDay });
    const normalizedTargetDayPlaces = targetDayPlaces.map((place, index) => ({
      ...place,
      day_number: targetDay,
      order_index: index + 1,
    }));

    const grouped = new Map();
    [...otherPlaces, ...normalizedTargetDayPlaces].forEach(place => {
      const day = Math.max(1, Number(place.day_number) || 1);
      const list = grouped.get(day) || [];
      list.push({ ...place, day_number: day });
      grouped.set(day, list);
    });

    const normalizedPlaces = [...grouped.entries()]
      .sort(([leftDay], [rightDay]) => leftDay - rightDay)
      .flatMap(([, places]) =>
        places
          .sort((a, b) => Number(a.order_index || 0) - Number(b.order_index || 0))
          .map((place, index) => ({ ...place, order_index: index + 1 }))
      );

    return { ...cand, places: normalizedPlaces };
  });

  const updatedCandidate = nextCandidates.find(cand => Number(cand.id) === Number(sourceCandidate.id));
  return {
    changed: true,
    nextCandidates,
    requests: (updatedCandidate?.places || []).map(place => ({
      candidateId: updatedCandidate.id,
      placeId: place.id,
      preferredDayNumber: place.day_number,
      preferredOrderIndex: place.order_index,
    })),
  };
}
