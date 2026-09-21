package com.triplan.triplan.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.exception.AiGenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions API 호출 래퍼.
 *
 * 사용법:
 *   String json = openAiClient.chat(systemPrompt, userPrompt);
 *
 * - 모델: gpt-4o
 * - 타임아웃: 60초
 * - 응답에서 content(문자열)만 추출하여 반환
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient {

    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    /**
     * OpenAI Chat Completions API를 호출하고 응답 content를 반환한다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt   사용자 프롬프트
     * @return AI 응답 content (JSON 문자열)
     * @throws AiGenerationException API 호출 실패 또는 응답 파싱 실패 시
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, 0.5);
    }

    /**
     * OpenAI Chat Completions API를 호출하고 응답 content를 반환한다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt   사용자 프롬프트
     * @param temperature  생성 온도
     * @return AI 응답 content (JSON 문자열)
     * @throws AiGenerationException API 호출 실패 또는 응답 파싱 실패 시
     */
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        return chat(systemPrompt, userPrompt, temperature, null);
    }

    /**
     * OpenAI Chat Completions API를 호출하고 응답 content를 반환한다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt   사용자 프롬프트
     * @param temperature  생성 온도
     * @param maxTokens    최대 출력 토큰 수. null이면 API 기본값 사용
     * @return AI 응답 content (JSON 문자열)
     * @throws AiGenerationException API 호출 실패 또는 응답 파싱 실패 시
     */
    public String chat(String systemPrompt, String userPrompt, double temperature, Integer maxTokens) {
        return chat(model, systemPrompt, userPrompt, temperature, maxTokens);
    }

    /**
     * 지정한 모델로 OpenAI Chat Completions API를 호출하고 응답 content를 반환한다.
     *
     * <p>파서와 플래너처럼 단계별 모델을 명시적으로 분리해야 할 때 사용한다.
     *
     * @param requestedModel 호출할 OpenAI 모델명
     * @param systemPrompt   시스템 프롬프트
     * @param userPrompt     사용자 프롬프트
     * @param temperature    생성 온도
     * @param maxTokens      최대 출력 토큰 수. null이면 API 기본값 사용
     * @return AI 응답 content (JSON 문자열)
     * @throws AiGenerationException API 호출 실패 또는 응답 파싱 실패 시
     */
    public String chat(
            String requestedModel,
            String systemPrompt,
            String userPrompt,
            double temperature,
            Integer maxTokens
    ) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", requestedModel);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", temperature);
        if (maxTokens != null) {
            requestBody.put("max_tokens", maxTokens);
        }
        requestBody.put("response_format", Map.of("type", "json_object"));

        try {
            String responseJson = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();

            return extractContent(responseJson, requestedModel);
        } catch (WebClientResponseException e) {
            log.error("OpenAI API 호출 실패 - status: {}, body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new AiGenerationException("OpenAI API 호출 실패: " + e.getStatusCode(), e);
        } catch (Exception e) {
            if (e instanceof AiGenerationException) throw (AiGenerationException) e;
            log.error("OpenAI API 호출 중 예외 발생", e);
            throw new AiGenerationException("OpenAI API 호출 중 오류: " + e.getMessage(), e);
        }
    }

    /**
     * OpenAI 응답 JSON에서 choices[0].message.content를 추출한다.
     */
    private String extractContent(String responseJson, String requestedModel) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new AiGenerationException("OpenAI 응답에서 content를 찾을 수 없습니다.");
            }
            logUsage(root, requestedModel);
            return content.asText();
        } catch (AiGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI 응답 파싱 실패: {}", responseJson, e);
            throw new AiGenerationException("OpenAI 응답 파싱 실패", e);
        }
    }

    private void logUsage(JsonNode root, String requestedModel) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            log.warn("[OpenAI Usage] usage 정보 없음 - model: {}", requestedModel);
            return;
        }

        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);
        log.info("[OpenAI Usage] model: {}, inputTokens: {}, cachedInputTokens: {}, outputTokens: {}, totalTokens: {}",
                requestedModel, promptTokens, cachedTokens, completionTokens, totalTokens);
    }
}
