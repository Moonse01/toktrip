package com.triplan.triplan.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplatesTest {

    @Test
    void missionOnlyParserPromptUsesExplicitEmptyChatMessage() {
        String prompt = PromptTemplates.parserUserPrompt(
                "부산 1박 2일로 바다 산책과 맛집 중심 일정을 추천해줘.",
                null
        );

        assertThat(prompt)
                .contains("[호스트 추가 요청사항]")
                .contains("부산 1박 2일")
                .contains("없음 (호스트 추가 요청사항만으로 여행 조건을 추출하세요.)")
                .doesNotContain("\nnull");
    }

    @Test
    void conflictFreeBPlanStillReceivesContrastRule() {
        assertThat(PromptTemplates.PLANNER_B_SYSTEM)
                .contains("conflicts가 없으면 preferences와 host_requests에서 A안이 중심으로 삼지 않은 두 번째 취향을 우선 선택하세요.")
                .contains("conflicts가 없고 취향이 하나뿐이면");
    }
}
