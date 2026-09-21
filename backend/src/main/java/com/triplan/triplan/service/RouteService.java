package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RouteService {

    @Value("${tmap.api.key}")
    private String tmapKey;

    @Value("${kakao.rest.api.key}")
    private String kakaoRestKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final double WALK_THRESHOLD_METERS = 1500.0;

    public List<double[]> getRoute(double originLat, double originLng, double destLat, double destLng) {
        double distance = haversine(originLat, originLng, destLat, destLng);
        try {
            if (distance < WALK_THRESHOLD_METERS) {
                return getWalkingRoute(originLat, originLng, destLat, destLng);
            } else {
                return getDrivingRoute(originLat, originLng, destLat, destLng);
            }
        } catch (Exception e) {
            // 실패 시 직선으로 폴백
            return List.of(
                new double[]{originLat, originLng},
                new double[]{destLat, destLng}
            );
        }
    }

    // Tmap 보행자 길찾기 (1.5km 미만)
    private List<double[]> getWalkingRoute(double originLat, double originLng,
                                            double destLat, double destLng) throws Exception {
        String url = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json";

        HttpHeaders headers = new HttpHeaders();
        headers.set("appKey", tmapKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
            "startX", String.valueOf(originLng),
            "startY", String.valueOf(originLat),
            "endX",   String.valueOf(destLng),
            "endY",   String.valueOf(destLat),
            "startName", "출발",
            "endName",   "도착"
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        JsonNode features = objectMapper.readTree(response.getBody()).get("features");
        List<double[]> coords = new ArrayList<>();
        for (JsonNode feature : features) {
            JsonNode geometry = feature.get("geometry");
            if ("LineString".equals(geometry.get("type").asText())) {
                for (JsonNode coord : geometry.get("coordinates")) {
                    // Tmap 좌표는 [lng, lat] 순서
                    coords.add(new double[]{coord.get(1).asDouble(), coord.get(0).asDouble()});
                }
            }
        }
        return coords;
    }

    // 카카오 모빌리티 자동차 길찾기 (1.5km 이상)
    private List<double[]> getDrivingRoute(double originLat, double originLng,
                                            double destLat, double destLng) throws Exception {
        // 카카오 모빌리티는 경도,위도 순서
        String url = String.format(
            "https://apis-navi.kakaomobility.com/v1/directions?origin=%f,%f&destination=%f,%f&priority=RECOMMEND",
            originLng, originLat, destLng, destLat
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + kakaoRestKey);

        ResponseEntity<String> response = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(headers), String.class
        );

        JsonNode routes = objectMapper.readTree(response.getBody()).get("routes");
        List<double[]> coords = new ArrayList<>();
        for (JsonNode section : routes.get(0).get("sections")) {
            for (JsonNode road : section.get("roads")) {
                JsonNode vertexes = road.get("vertexes");
                // vertexes는 [lng, lat, lng, lat, ...] 평탄 배열
                for (int i = 0; i < vertexes.size() - 1; i += 2) {
                    double lng = vertexes.get(i).asDouble();
                    double lat = vertexes.get(i + 1).asDouble();
                    coords.add(new double[]{lat, lng});
                }
            }
        }
        return coords;
    }

    // 두 좌표 간 거리 계산 (Haversine, 단위: 미터)
    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}