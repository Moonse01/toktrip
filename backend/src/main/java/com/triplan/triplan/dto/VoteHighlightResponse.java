package com.triplan.triplan.dto;

/**
 * S5 추천 일정 화면의 "친구들이 가장 좋아한 곳" 브리핑용 응답.
 *
 * <p>D안에 포함된 장소 중 투표 점수가 높은 상위 장소를 추려서 전달한다.
 * D안 장소는 원본 A/B 장소의 복사본이므로 장소명 기준으로 투표를 집계한다.
 */
public record VoteHighlightResponse(
        String placeName,
        String category,
        int pinCount,
        int superLikeCount,
        int likeCount
) {}
