package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.triplan.triplan.exception.AiGenerationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1차 파싱봇 결과를 2차 플래너봇 입력 전에 결정론적으로 보정한다.
 */
@Service
@RequiredArgsConstructor
public class ParserNormalizationService {

    private static final Pattern KOREAN_MAN_PATTERN = Pattern.compile("(\\d+)만");
    private static final Pattern MEAL_REQUIREMENT_PATTERN = Pattern.compile(
            "^(?:첫날|마지막날|\\d+일차)\\s*(?:아침|점심|저녁)(?:\\s*식사)?\\s*(?:은|는|으로|로|에)?\\s*(.+)$"
    );
    private static final Pattern MEAL_SLOT_PATTERN = Pattern.compile(
            "^(?:첫날|마지막날|\\d+일차)(?:아침|점심|저녁)(?:식사)?$"
    );
    private static final Pattern TOPIC_MARKER_PATTERN = Pattern.compile("^(.+?)(?:은|는)\\s+.+$");

    private static final Pattern KAKAO_MESSAGE_PATTERN = Pattern.compile(
            "^\\[(?<speaker>[^\\]]+)]\\s+\\[[^\\]]+]\\s+(?<message>.*)$"
    );
    private static final List<String> TRAVEL_KEYWORDS = List.of(
            "여행", "가자", "가고", "가볼", "가보고", "일정", "코스", "숙소", "호텔", "펜션",
            "렌트", "버스", "기차", "공항", "도착", "출발", "체크인", "체크아웃", "예산",
            "인당", "맛집", "먹", "카페", "바다", "산", "오름", "야경", "투어", "액티비티",
            "알레르기", "비건", "술", "디카페인", "바베큐", "바비큐", "마트", "장보기", "글램핑"
    );
    private static final List<String> ABSENCE_KEYWORDS = List.of("안 감", "못 감", "불참", "빠질게");
    private static final List<String> PARTICLE_PROTECTED_SUFFIXES = List.of("마을", "서울");

    private final ObjectMapper objectMapper;

    public String normalize(String conditionsJson, String mission, String chatLog) {
        try {
            JsonNode parsed = objectMapper.readTree(conditionsJson);
            if (!parsed.isObject()) {
                throw new AiGenerationException("1차 파싱봇 응답이 JSON 객체가 아닙니다.");
            }

            ObjectNode root = (ObjectNode) parsed;
            ArrayNode constraints = array(root, "constraints");
            ArrayNode preferences = array(root, "preferences");
            ArrayNode mustInclude = array(root, "must_include");
            ArrayNode mustExclude = array(root, "must_exclude");
            ArrayNode hostRequests = array(root, "host_requests");

            normalizeMustExcludeItems(mustExclude);
            normalizeDurationNights(root);
            normalizeBudgetConstraint(root, constraints);
            normalizeAccommodationConstraint(root, constraints);
            normalizeMustExcludeConstraint(mustExclude, constraints);
            promoteStrongIncludes(constraints, mustInclude);
            promoteStrongIncludes(hostRequests, mustInclude);
            promoteMealRequirements(constraints, mustInclude);
            promoteMealRequirements(hostRequests, mustInclude);
            normalizeMustIncludeItems(root, mustInclude);
            normalizeVegan(root, constraints, mustExclude);
            normalizeAlcohol(root, constraints, mustExclude, mission, chatLog);
            normalizeGroupMealPreparation(root, constraints, mission, chatLog);
            normalizeDecaf(constraints, preferences, hostRequests);
            removeDuplicatedConflictConstraints(root, constraints);
            normalizeParticipantNames(root, chatLog);

            deduplicate(root, "constraints");
            deduplicate(root, "preferences");
            deduplicate(root, "must_include");
            deduplicate(root, "must_exclude");
            deduplicate(root, "host_requests");

            return objectMapper.writeValueAsString(root);
        } catch (AiGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new AiGenerationException("1차 파싱봇 결과 normalization 실패", e);
        }
    }

    private void normalizeDurationNights(ObjectNode root) {
        ObjectNode dates = object(root, "dates");

        JsonNode directDuration = root.path("duration_nights");
        if (directDuration.isNumber()) {
            dates.put("duration_nights", directDuration.asInt());
            return;
        }

        JsonNode nestedDuration = dates.path("duration_nights");
        if (nestedDuration.isNumber()) {
            return;
        }

        Integer durationFromDates = durationFromDates(dates);
        dates.put("duration_nights", durationFromDates != null ? durationFromDates : 0);
    }

    private Integer durationFromDates(ObjectNode dates) {
        String start = text(dates.path("start"));
        String end = text(dates.path("end"));
        if (start == null || end == null) {
            return null;
        }
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end));
            return days >= 0 && days <= 30 ? (int) days : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void normalizeBudgetConstraint(ObjectNode root, ArrayNode constraints) {
        JsonNode limit = root.path("budget").path("per_person_limit");
        if (!limit.isNumber()) {
            return;
        }

        long limitValue = limit.asLong();
        String value = formatKrw(limitValue);

        // 이미 같은 금액의 인당 예산 constraint가 있으면 추가하지 않음
        // "인당 350000원" 또는 "인당 35만원" 둘 다 인식
        if (containsAll(constraints, "인당", value) || containsAll(constraints, "1인", value)) {
            return;
        }
        if (hasBudgetAmountInKorean(constraints, limitValue)) {
            return;
        }
        addUnique(constraints, "인당 " + value + "원 이내");
    }

    /**
     * constraints 중 "인당" 또는 "1인"을 포함하면서 한국어 금액(예: "35만원")이
     * targetAmount와 일치하는 항목이 있는지 검사한다.
     */
    private boolean hasBudgetAmountInKorean(ArrayNode constraints, long targetAmount) {
        for (JsonNode item : constraints) {
            String text = text(item);
            if (text == null) continue;
            if (!text.contains("인당") && !text.contains("1인")) continue;

            Matcher m = KOREAN_MAN_PATTERN.matcher(text);
            while (m.find()) {
                long parsed = Long.parseLong(m.group(1)) * 10_000;
                if (parsed == targetAmount) {
                    return true;
                }
            }
        }
        return false;
    }

    private void normalizeAccommodationConstraint(ObjectNode root, ArrayNode constraints) {
        String area = text(root.path("accommodation").path("area"));
        if (area == null) {
            return;
        }
        if (containsAll(constraints, "숙소", area)) {
            return;
        }
        addUnique(constraints, "숙소는 " + area + " 근처");
    }

    /**
     * must_exclude 항목이 있으면 constraints에 "○○ 제외" 문구를 보강한다.
     * 2차 플래너가 must_exclude를 놓치더라도 constraints에서 한 번 더 잡도록 안전장치.
     */
    private void normalizeMustExcludeConstraint(ArrayNode mustExclude, ArrayNode constraints) {
        if (mustExclude.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode item : mustExclude) {
            String value = text(item);
            if (value == null) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(value);
        }

        if (sb.isEmpty()) {
            return;
        }

        String phrase = sb.toString();
        // 이미 동일한 제외 항목들이 constraints에 있으면 추가하지 않음
        if (containsAny(constraints, phrase) || containsAll(constraints, "제외", mustExclude.get(0).asText())) {
            return;
        }
        addUnique(constraints, phrase + " 제외");
    }

    private void promoteStrongIncludes(ArrayNode source, ArrayNode mustInclude) {
        for (JsonNode item : source) {
            String value = text(item);
            if (value == null || !isStrongInclude(value)) {
                continue;
            }

            String subject = extractSubjectBeforeTopicMarker(value);
            if (subject != null && !subject.isBlank()) {
                addUnique(mustInclude, subject);
            }
        }
    }

    private boolean isStrongInclude(String text) {
        return text.contains("꼭 넣어줘")
                || text.contains("꼭 포함")
                || text.contains("꼭 가자")
                || text.contains("꼭 가고 싶")
                || text.contains("꼭 먹고 싶")
                || text.contains("꼭 보고 싶")
                || text.contains("반드시 넣")
                || text.contains("무조건 넣")
                || text.contains("필수");
    }

    private String extractSubjectBeforeTopicMarker(String text) {
        Matcher matcher = TOPIC_MARKER_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }

        String subject = matcher.group(1).trim();
        if (isPredicateStem(subject)) {
            return null;
        }
        return trimTrailingParticles(subject);
    }

    private String trimTrailingParticles(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (endsWithProtectedNoun(trimmed)) {
            return trimmed;
        }
        String result = trimmed.replaceAll("(을|를|이|가)$", "").trim();
        // '도/까지/만'은 섬 이름(제주도/우도/거제도 등 3자 이하)을 깨뜨릴 수 있어,
        // 4자 이상일 때만 조사로 보고 제거한다. (예: "돼지국밥도" -> "돼지국밥", "제주도"는 유지)
        if (result.length() >= 4 && result.matches(".*(도|까지|만)$")) {
            result = result.replaceAll("(도|까지|만)$", "").trim();
        }
        return result;
    }

    private boolean endsWithProtectedNoun(String value) {
        return PARTICLE_PROTECTED_SUFFIXES.stream().anyMatch(value::endsWith);
    }

    private boolean isPredicateStem(String value) {
        return value.endsWith("있")
                || value.endsWith("없")
                || Set.of("먹", "보", "가", "하", "놀", "쉬").contains(value);
    }

    /**
     * 파서가 must_include에 문장이나 복수 항목을 넣어도 플래너가 검증 가능한
     * 원자 단위로 정리한다.
     * 예: "회는 주문진항 쪽에서 먹기" -> "회"
     *     "초당순두부랑 짬뽕순두부" -> "초당순두부", "짬뽕순두부"
     */
    private void normalizeMustIncludeItems(ObjectNode root, ArrayNode mustInclude) {
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode item : mustInclude) {
            String value = text(item);
            if (value == null) {
                continue;
            }
            // 연결어(먹고/먹으러/가고…)도 분리자로 본다. "밀면 먹고 돼지국밥도 먹으러 가고 싶어"처럼
            // 두 음식을 한 문장에 이어 쓴 경우 각각의 원자로 쪼개야 allMatch 검증이 통과한다.
            for (String part : value.split("\\s*(?:,|/|및|그리고|이랑|랑|먹고|먹으러|가고|가서|보고|보러|먹자|가자)\\s*")) {
                String subject = extractSubjectBeforeTopicMarker(part);
                String simplified = subject != null ? subject : part.trim();
                simplified = trimTrailingParticles(simplified);
                // 약한 선호 판별은 원본 문구로 해야 host_requests 부분문자열 매칭이 유지된다.
                // 동작어 제거는 실제로 must_include에 넣는 순간에만 적용한다.
                if (!simplified.isBlank()
                        && !isMealSlot(simplified)
                        && !isWeakHostPreferenceMustInclude(root, simplified)) {
                    String cleaned = stripActionTokens(simplified);
                    if (!cleaned.isBlank()) {
                        addUnique(normalized, cleaned);
                    }
                }
            }
        }
        root.set("must_include", normalized);
    }

    /**
     * must_exclude 항목도 명사로 정제한다 (동사/부정어 제거).
     * 예: "해산물 빼줘" -> "해산물". 안 하면 validator의 contains 매칭이 빗나가 제외가 '조용히' 실패한다.
     */
    private void normalizeMustExcludeItems(ArrayNode mustExclude) {
        if (mustExclude == null || mustExclude.isEmpty()) {
            return;
        }
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        for (JsonNode item : mustExclude) {
            String value = text(item);
            if (value == null) {
                continue;
            }
            for (String part : value.split("\\s*(?:,|/|및|그리고|이랑|랑)\\s*")) {
                String simplified = stripActionTokens(trimTrailingParticles(part.trim()));
                if (simplified != null && !simplified.isBlank()) {
                    cleaned.add(simplified);
                }
            }
        }
        mustExclude.removeAll();
        cleaned.forEach(mustExclude::add);
    }

    /**
     * 명사 뒤에 붙은 동작어/부정어 토큰을 제거해 장소와 매칭 가능한 명사로 만든다.
     * 정확히 일치하는 토큰만 제거하므로 명사를 훼손하지 않는다. (예: "춘천 닭갈비 먹고" -> "춘천 닭갈비")
     */
    private static final java.util.Set<String> ACTION_TOKENS = java.util.Set.of(
            "먹고", "먹기", "먹자", "먹어", "먹으러", "먹어보고", "먹어보기", "먹는", "먹을", "먹지", "먹고싶어", "먹고싶다",
            "가고", "가기", "가자", "가려고", "가서", "가보고", "가보기", "가고싶어", "가고싶다", "방문", "방문하고",
            "보고", "보기", "보러", "보자", "보고싶어", "보고싶다",
            "하고", "하기", "하자", "해보고", "즐기고", "즐기기", "들르고", "들러",
            "싶어", "싶다", "싶음", "싶고", "싶었어", "싶네", "먹고파", "가고파", "보고파",
            "구경", "구경하고", "타고", "타기", "사고", "사기", "사러", "마시고", "쉬고", "놀고", "체험", "체험하고",
            "빼고", "빼줘", "빼기", "빼서", "제외", "없이", "없는", "못먹어", "못먹는", "안먹어", "피하고", "피해서", "말고", "싫어"
    );

    private String stripActionTokens(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return phrase;
        }
        StringBuilder sb = new StringBuilder();
        for (String token : phrase.trim().split("\\s+")) {
            if (!ACTION_TOKENS.contains(token)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(token);
            }
        }
        // 토큰이 전부 동작어였다면 빈 문자열을 돌려준다 (호출부에서 걸러져 must_include에 안 들어감).
        String filtered = sb.toString();
        String suffixStripped = filtered
                .replaceAll("(먹고|먹기|먹자|먹으러|가고|가기|가자|보고|보기|보러|하고|즐기고|들르고|체험하고)$", "")
                .trim();
        return suffixStripped.isBlank() ? filtered : suffixStripped;
    }

    private boolean isWeakHostPreferenceMustInclude(ObjectNode root, String include) {
        JsonNode hostRequests = root.path("host_requests");
        if (!hostRequests.isArray()) {
            return false;
        }

        String normalizedInclude = normalizeLooseText(include);
        for (JsonNode item : hostRequests) {
            String request = text(item);
            if (request == null || isStrongInclude(request)) {
                continue;
            }
            if (normalizeLooseText(request).contains(normalizedInclude) && isSoftPreferenceRequest(request)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSoftPreferenceRequest(String request) {
        boolean hasLooseConnector = request.contains("이랑")
                || request.contains("랑")
                || request.contains("하고")
                || request.contains("와")
                || request.contains("과");
        boolean hasPreferenceTone = request.contains("먹는 여행")
                || request.contains("먹으러")
                || request.contains("좋아")
                || request.contains("가보고 싶")
                || request.contains("들리고 싶")
                || request.contains("위주")
                || request.contains("짜줘")
                || request.contains("계획");
        return hasLooseConnector && hasPreferenceTone;
    }

    private void promoteMealRequirements(ArrayNode source, ArrayNode mustInclude) {
        for (JsonNode item : source) {
            String value = text(item);
            if (value == null) {
                continue;
            }
            Matcher matcher = MEAL_REQUIREMENT_PATTERN.matcher(value.trim());
            if (!matcher.matches()) {
                continue;
            }
            String requiredFood = matcher.group(1)
                    .replaceAll("(먹기|먹어보기|먹어보고 싶어|먹고 싶어|먹자|포함)$", "")
                    .trim();
            if (!requiredFood.isBlank()) {
                addUnique(mustInclude, requiredFood);
            }
        }
    }

    private boolean isMealSlot(String value) {
        return MEAL_SLOT_PATTERN.matcher(normalizeText(value)).matches();
    }

    private void normalizeVegan(ObjectNode root, ArrayNode constraints, ArrayNode mustExclude) {
        if (!containsText(root, "비건")) {
            return;
        }

        addUnique(mustExclude, "고기");
        addUnique(mustExclude, "해산물");
        addUnique(mustExclude, "유제품");
        addUnique(mustExclude, "계란");
        addUnique(constraints, "비건 식사 필요");
        addUnique(constraints, "채식 메뉴 또는 사찰음식 가능한 식당");
    }

    private void normalizeAlcohol(
            ObjectNode root,
            ArrayNode constraints,
            ArrayNode mustExclude,
            String mission,
            String chatLog
    ) {
        String source = ((mission == null ? "" : mission) + "\n" + (chatLog == null ? "" : chatLog)).toLowerCase(Locale.ROOT);
        if (!(source.contains("술 못 마심") || source.contains("술 못마심") || source.contains("술 약함"))) {
            return;
        }

        ArrayNode filtered = objectMapper.createArrayNode();
        for (JsonNode item : mustExclude) {
            String value = text(item);
            if (value != null && "술".equals(value.trim())) {
                continue;
            }
            filtered.add(item);
        }
        root.set("must_exclude", filtered);
        addUnique(constraints, "무알코올 음료 옵션 필요");
    }

    private void normalizeGroupMealPreparation(
            ObjectNode root,
            ArrayNode constraints,
            String mission,
            String chatLog
    ) {
        String source = normalizeText(objectMapper.valueToTree(root).toString()
                + " " + (mission == null ? "" : mission)
                + " " + (chatLog == null ? "" : chatLog));

        if ((source.contains("바베큐") || source.contains("바비큐") || source.contains("bbq"))
                && (source.contains("펜션") || source.contains("글램핑") || source.contains("숙소") || source.contains("캠핑"))) {
            addUnique(constraints, "저녁 바베큐 준비 및 장보기 일정 필요");
        }
        if ((source.contains("술못마심") || source.contains("술못마시") || source.contains("술약함") || source.contains("무알코올"))
                && source.contains("음료")) {
            addUnique(constraints, "장보기 시 무알코올 음료를 함께 준비");
        }
    }

    private void normalizeDecaf(ArrayNode constraints, ArrayNode preferences, ArrayNode hostRequests) {
        if (containsAny(preferences, "디카페인", "카페인 못")
                || containsAny(hostRequests, "디카페인", "카페인 못")
                || containsAny(constraints, "디카페인", "카페인 못")) {
            addUnique(constraints, "디카페인 옵션 필요");
        }
    }

    private void removeDuplicatedConflictConstraints(ObjectNode root, ArrayNode constraints) {
        JsonNode conflicts = root.path("conflicts");
        if (!conflicts.isArray() || conflicts.isEmpty()) {
            return;
        }

        Set<String> conflictTexts = new LinkedHashSet<>();
        // 각 conflict의 opinions.wants 값 목록 (설명형 문장 매칭용)
        List<List<String>> conflictWantsGroups = new java.util.ArrayList<>();

        for (JsonNode conflict : conflicts) {
            addNormalized(conflictTexts, text(conflict.path("topic")));
            JsonNode opinions = conflict.path("opinions");
            List<String> wantsGroup = new java.util.ArrayList<>();
            if (opinions.isArray()) {
                for (JsonNode opinion : opinions) {
                    String who = text(opinion.path("who"));
                    String wants = text(opinion.path("wants"));
                    addNormalized(conflictTexts, wants);
                    if (who != null && wants != null) {
                        addNormalized(conflictTexts, who + " " + wants);
                    }
                    if (wants != null) {
                        wantsGroup.add(wants);
                    }
                }
            }
            if (wantsGroup.size() >= 2) {
                conflictWantsGroups.add(wantsGroup);
            }
        }

        ArrayNode filtered = objectMapper.createArrayNode();
        for (JsonNode item : constraints) {
            String value = text(item);
            String normalized = normalizeText(value);
            boolean duplicated = conflictTexts.contains(normalized)
                    || (value != null && value.contains("의견") && value.contains("갈"))
                    || isDescriptiveConflictConstraint(value, conflictWantsGroups);
            if (!duplicated) {
                filtered.add(item);
            }
        }
        root.set("constraints", filtered);
    }

    /**
     * constraint 문장이 특정 conflict의 opinions.wants 키워드를 2개 이상 포함하면
     * conflict를 풀어쓴 설명형 문장으로 판단한다.
     * 예: "향일암 일출까지 보고 싶다는 사람도 있고, 케이블카랑 야경 위주로..."
     *   → opinions: ["향일암 일출", "케이블카 타고 야경"] 의 핵심어 2개 이상 포함
     */
    private boolean isDescriptiveConflictConstraint(String value, List<List<String>> conflictWantsGroups) {
        if (value == null || conflictWantsGroups.isEmpty()) {
            return false;
        }
        for (List<String> wantsGroup : conflictWantsGroups) {
            int matchCount = 0;
            for (String wants : wantsGroup) {
                // wants에서 핵심 명사를 추출해 constraint에 포함되는지 확인
                // "향일암 일출" → "향일암", "일출" 각각 체크
                String[] keywords = wants.split("\\s+");
                for (String keyword : keywords) {
                    if (keyword.length() >= 2 && value.contains(keyword)) {
                        matchCount++;
                        break; // 이 opinion은 매칭됨, 다음 opinion으로
                    }
                }
            }
            // 2개 이상의 서로 다른 opinions 키워드가 포함되면 설명형 conflict
            if (matchCount >= 2) {
                return true;
            }
        }
        return false;
    }

    private void normalizeParticipantNames(ObjectNode root, String chatLog) {
        ObjectNode participants = object(root, "participants");
        ArrayNode names = array(participants, "names");
        if (!names.isEmpty() || chatLog == null || chatLog.isBlank()) {
            return;
        }

        for (String speaker : extractTravelSpeakers(chatLog)) {
            addUnique(names, speaker);
        }
    }

    private Set<String> extractTravelSpeakers(String chatLog) {
        Set<String> speakers = new LinkedHashSet<>();
        String[] lines = chatLog.split("\\R");
        for (String line : lines) {
            Matcher matcher = KAKAO_MESSAGE_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }

            String speaker = matcher.group("speaker").trim();
            String message = matcher.group("message").trim();
            if (speaker.isBlank() || isAbsent(message) || !isTravelRelated(message)) {
                continue;
            }
            speakers.add(speaker);
        }
        return speakers;
    }

    private boolean isAbsent(String message) {
        return ABSENCE_KEYWORDS.stream().anyMatch(message::contains);
    }

    private boolean isTravelRelated(String message) {
        return TRAVEL_KEYWORDS.stream().anyMatch(message::contains);
    }

    private boolean containsText(JsonNode node, String needle) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return node.asText().contains(needle);
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsText(item, needle)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                if (containsText(child, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAll(ArrayNode array, String first, String second) {
        for (JsonNode item : array) {
            String value = text(item);
            if (value != null && value.contains(first) && value.contains(second)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(ArrayNode array, String... needles) {
        for (JsonNode item : array) {
            String value = text(item);
            if (value == null) {
                continue;
            }
            for (String needle : needles) {
                if (value.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addUnique(ArrayNode array, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalizedValue = normalizeText(value);
        for (JsonNode item : array) {
            if (normalizeText(text(item)).equals(normalizedValue)) {
                return;
            }
        }
        array.add(value);
    }

    private void deduplicate(ObjectNode root, String fieldName) {
        ArrayNode original = array(root, fieldName);
        ArrayNode deduplicated = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : original) {
            String value = text(item);
            String normalized = normalizeText(value);
            if (value != null && !value.isBlank() && seen.add(normalized)) {
                deduplicated.add(value.trim());
            }
        }
        root.set(fieldName, deduplicated);
    }

    private ObjectNode object(ObjectNode root, String fieldName) {
        JsonNode existing = root.path(fieldName);
        if (existing.isObject()) {
            return (ObjectNode) existing;
        }
        ObjectNode created = objectMapper.createObjectNode();
        root.set(fieldName, created);
        return created;
    }

    private ArrayNode array(ObjectNode root, String fieldName) {
        JsonNode existing = root.path(fieldName);
        if (existing.isArray()) {
            return (ArrayNode) existing;
        }
        ArrayNode created = objectMapper.createArrayNode();
        root.set(fieldName, created);
        return created;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private String normalizeLooseText(String value) {
        return normalizeText(value).replaceAll("[,，.。]", "");
    }

    private void addNormalized(Set<String> target, String value) {
        String normalized = normalizeText(value);
        if (!normalized.isBlank()) {
            target.add(normalized);
        }
    }

    private String formatKrw(long value) {
        return String.format("%,d", value).replace(",", "");
    }
}
