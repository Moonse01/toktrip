package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiFinalPlanResponse;
import com.triplan.triplan.exception.AiGenerationException;
import com.triplan.triplan.infra.OpenAiClient;
import com.triplan.triplan.infra.PromptTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinalPlanAiService {

    private static final int FINAL_PLAN_MAX_TOKENS = 2400;
    private static final int MAX_ATTEMPTS = 2;

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    @Value("${openai.final-planner.model:gpt-4o-mini}")
    private String finalPlannerModel;

    public Optional<AiFinalPlanResponse> generate(FinalPlanRequest request) {
        if (request == null || request.candidates() == null || request.candidates().isEmpty()) {
            return Optional.empty();
        }

        try {
            String inputJson = objectMapper.writeValueAsString(request);
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    log.info("[AI-FINAL] 추천 일정 재구성 호출 시작 - planId: {}, attempt: {}, model: {}",
                            request.planId(), attempt, finalPlannerModel);
                    String rawJson = openAiClient.chat(
                            finalPlannerModel,
                            PromptTemplates.FINAL_PLAN_SYSTEM,
                            PromptTemplates.finalPlanUserPrompt(inputJson),
                            0.15,
                            FINAL_PLAN_MAX_TOKENS
                    );
                    AiFinalPlanResponse response = objectMapper.readValue(rawJson, AiFinalPlanResponse.class);
                    if (response.places() == null || response.places().isEmpty()) {
                        throw new AiGenerationException("추천 일정 AI 응답에 places가 없습니다.");
                    }
                    log.info("[AI-FINAL] 추천 일정 재구성 완료 - planId: {}, places: {}",
                            request.planId(), response.places().size());
                    return Optional.of(response);
                } catch (Exception e) {
                    log.warn("[AI-FINAL] 추천 일정 재구성 실패 - planId: {}, attempt: {}, message: {}",
                            request.planId(), attempt, e.getMessage());
                    if (attempt == MAX_ATTEMPTS) {
                        return Optional.empty();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AI-FINAL] 추천 일정 입력 직렬화 실패 - planId: {}, message: {}",
                    request.planId(), e.getMessage());
        }
        return Optional.empty();
    }

    public record FinalPlanRequest(
            Long planId,
            String destination,
            String mission,
            Integer dayCount,
            Integer participantCount,
            List<CandidateInput> candidates,
            List<CommentInput> comments
    ) {}

    public record CandidateInput(
            Long candidateId,
            String label,
            String name,
            String concept,
            List<PlaceInput> places
    ) {}

    public record PlaceInput(
            Long placeId,
            Integer dayNumber,
            Integer orderIndex,
            String name,
            String category,
            String description,
            Integer estimatedCost,
            Integer durationMinutes,
            String visitTime,
            BigDecimal lat,
            BigDecimal lng,
            VoteSummary vote
    ) {}

    public record VoteSummary(
            int dislikeCount,
            int likeCount,
            int superLikeCount,
            int pinCount,
            int actualVoteCount,
            int weightedScore
    ) {}

    public record CommentInput(
            Long placeId,
            String placeName,
            String candidateLabel,
            Byte voteScore,
            String comment
    ) {}
}
