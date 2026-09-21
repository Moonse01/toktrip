package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParserNormalizationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ParserNormalizationService service = new ParserNormalizationService(objectMapper);

    @Test
    void normalizesRepeatedParserMissesBeforePlannerInput() throws Exception {
        String conditionsJson = """
                {
                  "destination": "경주",
                  "dates": {
                    "start": "2026-06-14",
                    "end": "2026-06-15",
                    "duration_nights": 1
                  },
                  "participants": {
                    "count": 5,
                    "names": [],
                    "count_source": "explicit_text"
                  },
                  "transport": "렌트카",
                  "budget": {
                    "per_person_limit": 80000,
                    "currency": "KRW"
                  },
                  "accommodation": {
                    "area": "황리단길",
                    "type": "호텔"
                  },
                  "constraints": [
                    "한식파와 비건파 의견이 갈림",
                    "동궁과월지 야경은 마지막 날 꼭 넣어줘"
                  ],
                  "preferences": [
                    "디카페인 커피 선호"
                  ],
                  "conflicts": [
                    {
                      "topic": "한식파와 비건파 의견이 갈림",
                      "opinions": [
                        { "who": "민수", "wants": "한식" },
                        { "who": "지연", "wants": "비건" }
                      ]
                    }
                  ],
                  "must_include": [],
                  "must_exclude": ["술"],
                  "host_requests": [
                    "황남빵은 돌아가기 전쯤 포장하고 싶어",
                    "비건 메뉴 가능한 식당을 챙겨줘"
                  ]
                }
                """;
        String mission = "술 못 마심. 디카페인 옵션도 있으면 좋겠어.";
        String chatLog = """
                [민수] [오후 2:15] 우리 총 5명 경주 여행 가자
                [지연] [오후 2:16] 나는 비건이라 식당 중요해
                [현우] [오후 2:17] 동궁과월지 야경은 마지막 날 꼭 넣어줘
                [수빈] [오후 2:18] 황리단길 숙소면 좋겠다
                [태호] [오후 2:19] 나는 이번엔 빠질게
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, mission, chatLog));

        assertThat(result.path("constraints"))
                .extracting(JsonNode::asText)
                .contains(
                        "인당 80000원 이내",
                        "숙소는 황리단길 근처",
                        "비건 식사 필요",
                        "채식 메뉴 또는 사찰음식 가능한 식당",
                        "무알코올 음료 옵션 필요",
                        "디카페인 옵션 필요"
                )
                .doesNotContain("한식파와 비건파 의견이 갈림");
        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .contains("동궁과월지 야경")
                .doesNotContain("황남빵");
        assertThat(result.path("must_exclude"))
                .extracting(JsonNode::asText)
                .contains("고기", "해산물", "유제품", "계란")
                .doesNotContain("술");
        assertThat(result.path("participants").path("names"))
                .extracting(JsonNode::asText)
                .containsExactly("민수", "지연", "현우", "수빈");
    }

    @Test
    void doesNotDuplicateBudgetWhenKoreanManWonFormat() throws Exception {
        // "인당 35만원" 이미 있으면 "인당 350000원 이내"를 추가하지 않아야 함
        String conditionsJson = """
                {
                  "budget": {
                    "per_person_limit": 350000,
                    "currency": "KRW"
                  },
                  "participants": {
                    "count": 5,
                    "names": ["준영"],
                    "count_source": "explicit_text"
                  },
                  "constraints": [
                    "현장 지출은 인당 35만원 안쪽으로 맞춰줘",
                    "렌트카랑 숙소비는 따로 계산한다고 보면 돼"
                  ],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        long budgetCount = 0;
        for (JsonNode c : result.path("constraints")) {
            if (c.asText().contains("인당") && (c.asText().contains("35만") || c.asText().contains("350000"))) {
                budgetCount++;
            }
        }
        assertThat(budgetCount)
                .as("인당 예산 constraint는 1개만 존재해야 함 (35만원 = 350000원)")
                .isEqualTo(1);
    }

    @Test
    void defaultsMissingDurationToDayTrip() throws Exception {
        String conditionsJson = """
                {
                  "destination": "강릉",
                  "dates": {
                    "start": null,
                    "end": null,
                    "duration_nights": null
                  },
                  "transport": "미정",
                  "budget": { "per_person_limit": null, "currency": "KRW" },
                  "accommodation": { "area": null, "type": "미정" },
                  "constraints": [],
                  "preferences": ["바다"],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": [],
                  "host_requests": ["강릉, 바다있다는것만 알아 여행계획을 한번 짜줘봐"]
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("dates").path("duration_nights").asInt()).isEqualTo(0);
    }

    @Test
    void keepsExplicitDurationWhenProvided() throws Exception {
        String conditionsJson = """
                {
                  "destination": "부산",
                  "dates": {
                    "start": null,
                    "end": null,
                    "duration_nights": 2
                  },
                  "constraints": [],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("dates").path("duration_nights").asInt()).isEqualTo(2);
    }

    @Test
    void calculatesDurationFromStartAndEndDates() throws Exception {
        String conditionsJson = """
                {
                  "destination": "강릉",
                  "dates": {
                    "start": "2026-06-20",
                    "end": "2026-06-22",
                    "duration_nights": null
                  },
                  "constraints": [],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("dates").path("duration_nights").asInt()).isEqualTo(2);
    }

    @Test
    void addsMustExcludeToConstraints() throws Exception {
        // Rule 2: must_exclude가 있으면 constraints에 "○○ 제외" 보강
        String conditionsJson = """
                {
                  "budget": { "per_person_limit": 300000, "currency": "KRW" },
                  "participants": { "count": 4, "names": ["석용"], "count_source": "explicit_text" },
                  "constraints": ["첫날은 오후 도착"],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": ["해산물", "회", "조개류"],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("constraints"))
                .extracting(JsonNode::asText)
                .contains("해산물, 회, 조개류 제외");
    }

    @Test
    void promotesKkokGagoSipeo() throws Exception {
        // "꼭 가고 싶어" 패턴 → must_include 승격
        String conditionsJson = """
                {
                  "budget": { "per_person_limit": 150000, "currency": "KRW" },
                  "participants": { "count": 6, "names": ["석용"], "count_source": "explicit_text" },
                  "constraints": ["남이섬은 꼭 가고 싶어"],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": [],
                  "host_requests": ["남이섬은 꼭 가고 싶어"]
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .contains("남이섬");
    }

    @Test
    void removesDescriptiveConflictConstraint() throws Exception {
        // 여수: conflict opinions 키워드를 포함하는 설명형 문장이 constraints에서 제거
        String conditionsJson = """
                {
                  "budget": { "per_person_limit": 150000, "currency": "KRW" },
                  "participants": { "count": 2, "names": ["민준", "태우"], "count_source": "speaker_count" },
                  "constraints": [
                    "현장에서 쓰는 돈은 인당 15만원 안쪽",
                    "향일암 일출까지 보고 싶다는 사람도 있고, 늦게 시작해서 케이블카랑 야경 위주로 보고 싶다는 사람도 있음"
                  ],
                  "preferences": [],
                  "conflicts": [
                    {
                      "topic": "일출 vs 케이블카 야경",
                      "opinions": [
                        { "who": "민준", "wants": "향일암 일출" },
                        { "who": "태우", "wants": "케이블카 타고 야경" }
                      ]
                    }
                  ],
                  "must_include": ["회"],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("constraints"))
                .extracting(JsonNode::asText)
                .doesNotContain("향일암 일출까지 보고 싶다는 사람도 있고, 늦게 시작해서 케이블카랑 야경 위주로 보고 싶다는 사람도 있음")
                .contains("현장에서 쓰는 돈은 인당 15만원 안쪽");
    }

    @Test
    void doesNotRemoveAlcoholWhenOriginalTextMeansVenueBan() throws Exception {
        String conditionsJson = """
                {
                  "budget": {
                    "per_person_limit": null,
                    "currency": "KRW"
                  },
                  "participants": {
                    "count": null,
                    "names": ["민수"],
                    "count_source": "speaker_count"
                  },
                  "constraints": [],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": ["술"],
                  "host_requests": ["술집 제외하고 조용한 곳으로"]
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_exclude"))
                .extracting(JsonNode::asText)
                .containsExactly("술");
        assertThat(result.path("constraints"))
                .extracting(JsonNode::asText)
                .doesNotContain("무알코올 음료 옵션 필요");
    }

    @Test
    void normalizesSentenceAndMultipleMustIncludesIntoAtomicItems() throws Exception {
        String conditionsJson = """
                {
                  "budget": { "per_person_limit": 150000, "currency": "KRW" },
                  "constraints": [],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [
                    "회는 주문진항 쪽에서 먹기",
                    "초당순두부랑 짬뽕순두부"
                  ],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .containsExactly("회", "초당순두부", "짬뽕순두부");
    }

    @Test
    void splitsConnectedFoodMustIncludeAndRemovesActionParticles() throws Exception {
        String conditionsJson = """
                {
                  "destination": "부산",
                  "budget": { "per_person_limit": 150000, "currency": "KRW" },
                  "constraints": [],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [
                    "밀면 먹고 돼지국밥도 먹으러 가고 싶어"
                  ],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .containsExactly("밀면", "돼지국밥");
    }

    @Test
    void doesNotTrimProtectedNounEndingWithEulSyllable() throws Exception {
        String conditionsJson = """
                {
                  "budget": { "per_person_limit": 150000, "currency": "KRW" },
                  "constraints": [],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": ["한옥마을", "한옥마을을"],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .containsExactly("한옥마을");
    }

    @Test
    void doesNotPromoteSoftMissionOnlyPreferenceToMustInclude() throws Exception {
        String conditionsJson = """
                {
                  "destination": "전주",
                  "budget": { "per_person_limit": null, "currency": "KRW" },
                  "constraints": [],
                  "preferences": ["한옥마을", "맛있는 거"],
                  "conflicts": [],
                  "must_include": ["한옥마을이랑 맛있는 거 먹는 여행"],
                  "must_exclude": [],
                  "host_requests": ["전주, 한옥마을이랑 맛있는 거 먹는 여행으로 짜줘"]
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .isEmpty();
        assertThat(result.path("preferences"))
                .extracting(JsonNode::asText)
                .contains("한옥마을", "맛있는 거");
    }

    @Test
    void keepsStrongIncludeForProtectedNoun() throws Exception {
        String conditionsJson = """
                {
                  "destination": "전주",
                  "budget": { "per_person_limit": null, "currency": "KRW" },
                  "constraints": [],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": [],
                  "host_requests": ["한옥마을은 꼭 넣어줘"]
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .containsExactly("한옥마을");
    }

    @Test
    void replacesMealSlotMustIncludeWithActualRequiredFood() throws Exception {
        String conditionsJson = """
                {
                  "budget": { "per_person_limit": 150000, "currency": "KRW" },
                  "constraints": ["첫날 점심은 춘천 닭갈비"],
                  "preferences": [],
                  "conflicts": [],
                  "must_include": ["남이섬", "첫날 점심"],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, "", ""));

        assertThat(result.path("must_include"))
                .extracting(JsonNode::asText)
                .containsExactly("남이섬", "춘천 닭갈비");
    }

    @Test
    void addsGroupMealPreparationConstraintsForMtBarbecueAndBeverages() throws Exception {
        String conditionsJson = """
                {
                  "destination": "가평",
                  "budget": { "per_person_limit": 150000, "currency": "KRW" },
                  "constraints": ["펜션에서 바베큐"],
                  "preferences": ["글램핑장에서 쉬기"],
                  "conflicts": [],
                  "must_include": [],
                  "must_exclude": [],
                  "host_requests": []
                }
                """;
        String mission = "술 못 마시는 사람도 있어서 음료도 같이 챙기면 좋겠어.";
        String chatLog = "[지민] [오후 9:20] 가평이면 펜션 잡고 바베큐하지";

        JsonNode result = objectMapper.readTree(service.normalize(conditionsJson, mission, chatLog));

        assertThat(result.path("constraints"))
                .extracting(JsonNode::asText)
                .contains(
                        "저녁 바베큐 준비 및 장보기 일정 필요",
                        "장보기 시 무알코올 음료를 함께 준비"
                );
    }
}
