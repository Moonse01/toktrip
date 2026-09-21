package com.triplan.triplan.dto;

/**
 * 게스트가 S3 투표 화면에서 카드 순서를 바꾼 기록.
 * S2 원본 순서(from)와 게스트가 원하는 순서(to)를 함께 담아 호스트 대시보드에 보여준다.
 */
public record OrderChangeResponse(
        Long placeId,
        String placeName,
        String candidateLabel,
        Integer fromDayNumber,
        Integer fromOrderIndex,
        Integer toDayNumber,
        Integer toOrderIndex,
        String voterName
) {}
