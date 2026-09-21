package com.triplan.triplan.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 카카오 검색 결과가 이름만 맞고 다른 지역 좌표로 튀는 것을 막기 위한 넓은 여행지 geofence.
 * 정밀 행정구역 polygon이 아니라 S2 지도 붕괴 방지용 1차 방어선이다.
 */
final class DestinationGeoFence {

    private static final List<Fence> FENCES = List.of(
            fence(33.05, 33.65, 126.10, 127.05, "제주", "제주도", "제주시", "서귀포"),
            fence(34.85, 35.40, 128.75, 129.35, "부산"),
            fence(37.35, 37.75, 126.75, 127.20, "서울"),
            fence(35.70, 35.95, 127.00, 127.25, "전주"),
            fence(37.55, 37.90, 128.75, 129.10, "강릉"),
            fence(35.65, 36.05, 129.00, 129.45, "경주"),
            fence(34.55, 35.10, 127.50, 128.10, "여수"),
            fence(37.45, 38.05, 127.20, 127.75, "가평"),
            fence(37.70, 38.05, 127.55, 128.05, "춘천"),
            fence(38.05, 38.30, 128.45, 128.65, "속초"),
            fence(37.85, 38.25, 128.45, 128.85, "양양"),
            fence(37.20, 37.65, 126.35, 126.95, "인천"),
            fence(37.15, 37.40, 126.90, 127.15, "수원"),
            fence(36.20, 36.55, 127.25, 127.55, "대전"),
            fence(35.70, 36.05, 128.35, 128.80, "대구"),
            fence(35.05, 35.30, 126.70, 127.00, "광주"),
            fence(35.35, 35.75, 129.00, 129.50, "울산"),
            fence(34.70, 35.05, 128.30, 128.75, "통영"),
            fence(34.65, 35.05, 128.35, 128.95, "거제"),
            fence(34.65, 34.95, 126.20, 126.55, "목포"),
            fence(34.85, 35.20, 127.30, 127.65, "순천"),
            fence(35.20, 35.55, 126.80, 127.10, "담양"),
            fence(35.35, 35.60, 126.60, 126.90, "군산"),
            fence(35.25, 35.55, 127.25, 127.55, "남원"),
            fence(36.00, 36.35, 129.15, 129.55, "포항"),
            fence(36.45, 36.75, 128.60, 128.90, "안동")
    );

    private DestinationGeoFence() {
    }

    static boolean allows(BigDecimal lat, BigDecimal lng, String destination) {
        return allows(lat, lng, destination, null);
    }

    static boolean allows(BigDecimal lat, BigDecimal lng, String destination, String areaHint) {
        if (lat == null || lng == null) {
            return false;
        }
        double latValue = lat.doubleValue();
        double lngValue = lng.doubleValue();
        if (!Double.isFinite(latValue) || !Double.isFinite(lngValue)) {
            return false;
        }
        if (latValue < -90 || latValue > 90 || lngValue < -180 || lngValue > 180) {
            return false;
        }
        if (latValue == 0.0 && lngValue == 0.0) {
            return false;
        }

        List<Fence> fences = findAllowedFences(destination, areaHint);
        return fences.isEmpty() || fences.stream().anyMatch(fence -> fence.contains(latValue, lngValue));
    }

    private static List<Fence> findAllowedFences(String destination, String areaHint) {
        List<Fence> destinationFences = findMatches(destination);
        List<Fence> areaFences = findMatches(areaHint);

        if (destinationFences.isEmpty()) {
            return areaFences;
        }
        if (areaFences.isEmpty()) {
            return destinationFences;
        }

        List<Fence> allowed = new ArrayList<>(destinationFences);
        for (Fence areaFence : areaFences) {
            if (destinationFences.stream().anyMatch(destinationFence -> isSameOrAllowedAdjacent(destinationFence, areaFence))
                    && !allowed.contains(areaFence)) {
                allowed.add(areaFence);
            }
        }
        return allowed;
    }

    private static List<Fence> findMatches(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        return FENCES.stream()
                .filter(fence -> fence.matches(normalized))
                .toList();
    }

    private static boolean isSameOrAllowedAdjacent(Fence destinationFence, Fence areaFence) {
        if (destinationFence.equals(areaFence)) {
            return true;
        }
        // 강릉 여행에 양양/속초(죽도해변 등) 장소가 섞이지 않도록 강릉은 인접 권역을 열어주지 않는다.
        // 주문진항은 강릉시라 강릉 fence 안에 직접 포함되므로 인접 허용이 필요 없다.
        return (destinationFence.hasAlias("가평") && areaFence.hasAlias("춘천"))
                || (destinationFence.hasAlias("춘천") && areaFence.hasAlias("가평"))
                || (destinationFence.hasAlias("경주") && (areaFence.hasAlias("포항") || areaFence.hasAlias("울산")));
    }

    private static Fence fence(double minLat, double maxLat, double minLng, double maxLng, String... aliases) {
        return new Fence(minLat, maxLat, minLng, maxLng, List.of(aliases).stream()
                .map(DestinationGeoFence::normalize)
                .toList());
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("특별자치도", "")
                .replace("특별시", "")
                .replace("광역시", "");
    }

    private record Fence(double minLat, double maxLat, double minLng, double maxLng, List<String> aliases) {
        private boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }

        private boolean matches(String value) {
            return aliases.stream().anyMatch(value::contains);
        }

        private boolean hasAlias(String alias) {
            return aliases.contains(normalize(alias));
        }
    }
}
