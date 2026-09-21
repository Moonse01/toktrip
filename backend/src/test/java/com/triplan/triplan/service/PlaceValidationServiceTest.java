package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceValidationServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private PlaceValidationService service;

    @BeforeEach
    void setUp() {
        service = new PlaceValidationService(restTemplate, new ObjectMapper());
    }

    @Test
    void rejectsAccommodationWhenKakaoResultIsChargingStation() {
        mockKakaoSearch("""
                {
                  "documents": [
                    {
                      "id": "1",
                      "place_name": "아난티 앳 부산코브 (기장힐튼) 전기차충전소",
                      "y": "35.197000",
                      "x": "129.228000",
                      "address_name": "부산 기장군 기장읍",
                      "road_address_name": "부산 기장군 기장읍",
                      "category_name": "교통,수송 > 자동차 > 전기자동차 충전소"
                    }
                  ]
                }
                """);

        var result = service.validate(
                place("아난티 앳 부산코브", "숙소", "부산 기장", "부산 아난티 앳 부산코브"),
                "부산"
        );

        assertThat(result).isEmpty();
    }

    @Test
    void skipsUnavailableCandidateNames() {
        mockKakaoSearch("""
                {
                  "documents": [
                    {
                      "id": "1",
                      "place_name": "부산시립미술관 (휴관중)",
                      "y": "35.168900",
                      "x": "129.137700",
                      "address_name": "부산 해운대구",
                      "road_address_name": "부산 해운대구",
                      "category_name": "문화,예술 > 미술관"
                    },
                    {
                      "id": "2",
                      "place_name": "부산박물관",
                      "y": "35.129600",
                      "x": "129.093000",
                      "address_name": "부산 남구",
                      "road_address_name": "부산 남구",
                      "category_name": "문화,예술 > 박물관"
                    }
                  ]
                }
                """);

        var result = service.validate(
                place("부산시립미술관", "관광지", "부산", "부산 부산시립미술관"),
                "부산"
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("부산박물관");
    }

    @Test
    void rejectsCoordinateOutsideDestinationBounds() {
        mockKakaoSearch("""
                {
                  "documents": [
                    {
                      "id": "1",
                      "place_name": "신성바다횟집",
                      "y": "35.228110",
                      "x": "128.677102",
                      "address_name": "경남 창원시 성산구",
                      "road_address_name": "경남 창원시 성산구",
                      "category_name": "음식점 > 한식 > 해물,생선"
                    }
                  ]
                }
                """);

        var result = service.validate(
                place("신성바다횟집", "식당", "전주", "전주 신성바다횟집"),
                "전주"
        );

        assertThat(result).isEmpty();
    }

    @Test
    void usesSearchKeywordAsLocalityHintWhenDestinationIsWeak() {
        mockKakaoSearch("""
                {
                  "documents": [
                    {
                      "id": "1",
                      "place_name": "신성바다횟집",
                      "y": "35.228110",
                      "x": "128.677102",
                      "address_name": "경남 창원시 성산구",
                      "road_address_name": "경남 창원시 성산구",
                      "category_name": "음식점 > 한식 > 해물,생선"
                    }
                  ]
                }
                """);

        var result = service.validate(
                place("신성바다횟집", "식당", null, "전주 신성바다횟집"),
                null
        );

        assertThat(result).isEmpty();
    }

    @Test
    void prefersSpecificAreaHintOverDestinationWhenReplacingGapyeongChuncheonFood() {
        mockKakaoSearch("""
                {
                  "documents": [
                    {
                      "id": "1",
                      "place_name": "남이섬꼬꼬춘천닭갈비",
                      "y": "37.804100",
                      "x": "127.526300",
                      "address_name": "경기 가평군 가평읍",
                      "road_address_name": "경기 가평군 가평읍",
                      "category_name": "음식점 > 한식"
                    },
                    {
                      "id": "2",
                      "place_name": "명동우미닭갈비",
                      "y": "37.880300",
                      "x": "127.727800",
                      "address_name": "강원 춘천시 조양동",
                      "road_address_name": "강원 춘천시 금강로62번길",
                      "category_name": "음식점 > 한식"
                    }
                  ]
                }
                """);

        var result = service.validate(
                place("춘천 닭갈비", "식당", "춘천", "춘천 닭갈비"),
                "가평"
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("명동우미닭갈비");
    }

    @Test
    void acceptsPackagedBakeryAsShoppingCandidate() {
        mockKakaoSearch("""
                {
                  "documents": [
                    {
                      "id": "1",
                      "place_name": "경주황남빵",
                      "y": "35.836220",
                      "x": "129.211150",
                      "address_name": "경북 경주시 황오동",
                      "road_address_name": "경북 경주시 태종로",
                      "category_name": "음식점 > 간식 > 제과,베이커리"
                    }
                  ]
                }
                """);

        var result = service.validate(
                place("경주 황남빵 본점", "쇼핑", "경주 황남동", "경주 황남빵"),
                "경주"
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("경주황남빵");
    }

    @Test
    void rejectsGeneralRestaurantAsShoppingCandidate() {
        mockKakaoSearch("""
                {
                  "documents": [
                    {
                      "id": "1",
                      "place_name": "월정제과",
                      "y": "35.836220",
                      "x": "129.211150",
                      "address_name": "경북 경주시 황오동",
                      "road_address_name": "경북 경주시 태종로",
                      "category_name": "음식점 > 한식"
                    }
                  ]
                }
                """);

        var result = service.validate(
                place("경주 기념품 쇼핑", "쇼핑", "경주 황남동", "경주 기념품"),
                "경주"
        );

        assertThat(result).isEmpty();
    }

    private void mockKakaoSearch(String body) {
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private AiPlannerResponse.PlaceItem place(
            String name,
            String category,
            String areaHint,
            String searchKeyword
    ) {
        return new AiPlannerResponse.PlaceItem(
                1, 1, name, category,
                name + " 설명",
                0, 60, "12:00",
                areaHint, searchKeyword,
                null, null, null
        );
    }
}
