package com.triplan.triplan.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPreprocessorTest {

    private final ChatPreprocessor chatPreprocessor = new ChatPreprocessor();

    @Test
    void removesLowSignalLinesAndKeepsUsefulConversation() {
        String input = """
                --------------- 2026년 5월 18일 월요일 ---------------
                [민수] [오전 9:00] 사진
                [지연] [오전 9:01] ㅋㅋㅋㅋ
                [수빈] [오전 9:02] https://example.com
                [현우] [오전 9:03] 바다 보이는 카페는 꼭 가고 싶어
                [민수] [오전 9:04] 마지막 날은 일찍 끝나면 좋겠어
                """;

        String result = chatPreprocessor.preprocess(input);

        assertThat(result)
                .contains("바다 보이는 카페는 꼭 가고 싶어")
                .contains("마지막 날은 일찍 끝나면 좋겠어")
                .doesNotContain("사진")
                .doesNotContain("ㅋㅋㅋㅋ")
                .doesNotContain("https://example.com");
    }

    @Test
    void keepsOnlyMostRecentCharactersWhenInputIsTooLong() {
        String prefix = "a".repeat(2500);
        String suffix = "b".repeat(1000);

        String result = chatPreprocessor.preprocess(prefix + suffix);

        assertThat(result)
                .hasSize(3000)
                .startsWith("a".repeat(2000))
                .endsWith(suffix);
    }
}
