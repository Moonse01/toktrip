package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceValidationService {

    private static final String KAKAO_KEYWORD_SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";
    private static final List<String> UNAVAILABLE_NAME_TOKENS = List.of(
            "휴관중", "임시휴관", "휴업", "임시휴업", "폐업", "영업종료", "운영중단"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, Optional<ValidatedPlace>> searchCache = new ConcurrentHashMap<>();

    @Value("${kakao.rest.api.key}")
    private String kakaoRestKey;

    public Optional<ValidatedPlace> validate(AiPlannerResponse.PlaceItem place, String destination) {
        return validate(place, destination, Set.of());
    }

    public Optional<ValidatedPlace> validate(
            AiPlannerResponse.PlaceItem place,
            String destination,
            Set<String> excludedKakaoPlaceIds
    ) {
        if (place == null || place.name() == null || place.name().isBlank()) {
            return Optional.empty();
        }
        if (shouldSkipValidation(place)) {
            return Optional.empty();
        }

        for (String query : buildQueries(place, destination)) {
            Optional<ValidatedPlace> result = searchBest(query, place, destination, excludedKakaoPlaceIds);
            if (result.isPresent()) {
                return result;
            }
        }

        log.warn("[PlaceValidation] 카카오 장소 검색 실패 - name: {}, keyword: {}, area: {}",
                place.name(), place.searchKeyword(), place.areaHint());
        return Optional.empty();
    }

    private boolean shouldSkipValidation(AiPlannerResponse.PlaceItem place) {
        String category = safeLower(place.category());
        String name = safeLower(place.name());

        if (category.contains("숙소")) {
            return name.contains("에어비앤비")
                    || name.contains("체크인")
                    || name.contains("체크아웃")
                    || name.equals("숙소");
        }
        if (category.contains("이동")) {
            return name.contains("렌트카 반납") || name.contains("이동");
        }
        return false;
    }

    private List<String> buildQueries(AiPlannerResponse.PlaceItem place, String destination) {
        List<String> queries = new ArrayList<>();
        addQuery(queries, place.searchKeyword());
        addQuery(queries, join(place.areaHint(), place.name()));
        addQuery(queries, join(destination, place.name()));
        addQuery(queries, place.name());

        if (queries.isEmpty()) {
            return List.of(place.name());
        }
        return queries;
    }

    private Optional<ValidatedPlace> searchBest(
            String query,
            AiPlannerResponse.PlaceItem place,
            String destination,
            Set<String> excludedKakaoPlaceIds
    ) {
        String normalizedQuery = query.trim();
        String cacheKey = normalizedQuery + "|" + safeLower(destination) + "|" + safeLower(place.category());
        boolean cacheable = excludedKakaoPlaceIds == null || excludedKakaoPlaceIds.isEmpty();
        if (cacheable) {
            Optional<ValidatedPlace> cached = searchCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(KAKAO_KEYWORD_SEARCH_URL)
                    .queryParam("query", normalizedQuery)
                    .queryParam("size", 5);

            URI uri = builder.build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoRestKey);

            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            JsonNode documents = objectMapper.readTree(response.getBody()).path("documents");
            if (!documents.isArray() || documents.isEmpty()) {
                if (cacheable) {
                    searchCache.put(cacheKey, Optional.empty());
                }
                return Optional.empty();
            }

            List<ValidatedPlace> candidates = toCandidates(documents).stream()
                    .filter(candidate -> isNotExcluded(candidate, excludedKakaoPlaceIds))
                    .filter(this::isOperationalCandidate)
                    .filter(candidate -> isInsideDestinationBounds(candidate, destination, place.areaHint(), normalizedQuery))
                    .toList();
            String localityHint = join(place.areaHint(), normalizedQuery);
            List<String> destinationTokens = destinationTokens(destination, localityHint);
            Optional<ValidatedPlace> result = candidates.stream()
                    .filter(candidate -> isSameDestination(candidate, destination, localityHint))
                    .filter(candidate -> isCompatibleCategory(candidate, place.category()))
                    .max(Comparator.comparingInt(candidate -> score(candidate, place, destination)))
                    .or(() -> requiresCategoryMatch(place.category())
                            ? Optional.empty()
                            : candidates.stream()
                            .filter(candidate -> isSameDestination(candidate, destination, localityHint))
                            .max(Comparator.comparingInt(candidate -> score(candidate, place, destination))));

            if (result.isEmpty() && destinationTokens.isEmpty()) {
                result = candidates.stream()
                            .filter(candidate -> isCompatibleCategory(candidate, place.category()))
                            .max(Comparator.comparingInt(candidate -> score(candidate, place, destination)));
            }

            if (cacheable) {
                searchCache.put(cacheKey, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("[PlaceValidation] 카카오 키워드 검색 실패 - query: {}, message: {}", normalizedQuery, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isNotExcluded(ValidatedPlace candidate, Set<String> excludedKakaoPlaceIds) {
        return excludedKakaoPlaceIds == null
                || excludedKakaoPlaceIds.isEmpty()
                || candidate.kakaoPlaceId() == null
                || !excludedKakaoPlaceIds.contains(candidate.kakaoPlaceId());
    }

    private boolean isOperationalCandidate(ValidatedPlace candidate) {
        String name = normalizeText(candidate.name());
        return UNAVAILABLE_NAME_TOKENS.stream().noneMatch(token -> name.contains(normalizeText(token)));
    }

    private boolean isInsideDestinationBounds(
            ValidatedPlace candidate,
            String destination,
            String areaHint,
            String query
    ) {
        return DestinationGeoFence.allows(candidate.lat(), candidate.lng(), destination, join(areaHint, query));
    }

    private List<ValidatedPlace> toCandidates(JsonNode documents) {
        List<ValidatedPlace> candidates = new ArrayList<>();
        documents.forEach(doc -> candidates.add(new ValidatedPlace(
                doc.path("id").asText(null),
                doc.path("place_name").asText(null),
                parseDecimal(doc.path("y").asText(null)),
                parseDecimal(doc.path("x").asText(null)),
                doc.path("address_name").asText(""),
                doc.path("road_address_name").asText(""),
                doc.path("category_name").asText("")
        )));
        return candidates;
    }

    private boolean isSameDestination(ValidatedPlace candidate, String destination, String areaHint) {
        List<String> tokens = destinationTokens(destination, areaHint);
        if (tokens.isEmpty()) {
            return true;
        }
        String address = normalizeLocation(candidate.addressName() + " " + candidate.roadAddressName());
        return tokens.stream().anyMatch(address::contains);
    }

    private boolean isCompatibleCategory(ValidatedPlace candidate, String category) {
        String expected = normalizeText(category);
        if (expected.isBlank()) {
            return true;
        }
        String actual = normalizeText(candidate.categoryName());
        if (expected.contains("맛집") || expected.contains("식당") || expected.contains("식도락")) {
            return actual.contains("음식점");
        }
        if (expected.contains("카페")) {
            return actual.contains("카페");
        }
        if (expected.contains("숙소")) {
            return actual.contains("숙박");
        }
        if (expected.contains("쇼핑")) {
            return actual.contains("쇼핑")
                    || actual.contains("마트")
                    || actual.contains("슈퍼")
                    || actual.contains("편의점")
                    || actual.contains("가정,생활")
                    || actual.contains("제과")
                    || actual.contains("베이커리")
                    || actual.contains("간식")
                    || actual.contains("떡");
        }
        return true;
    }

    private boolean requiresCategoryMatch(String category) {
        String expected = normalizeText(category);
        return expected.contains("맛집")
                || expected.contains("식당")
                || expected.contains("식도락")
                || expected.contains("카페")
                || expected.contains("숙소")
                || expected.contains("쇼핑");
    }

    private int score(ValidatedPlace candidate, AiPlannerResponse.PlaceItem place, String destination) {
        int score = 0;
        String candidateName = normalizeText(candidate.name());
        String placeName = normalizeText(place.name());
        if (!placeName.isBlank() && candidateName.equals(placeName)) {
            score += 50;
        } else if (!placeName.isBlank() && candidateName.contains(placeName)) {
            score += 30;
        }
        if (isSameDestination(candidate, destination, place.areaHint())) {
            score += 30;
        }
        score += localityScore(candidate, place.areaHint());
        if (isCompatibleCategory(candidate, place.category())) {
            score += 20;
        }
        return score;
    }

    private int localityScore(ValidatedPlace candidate, String areaHint) {
        List<String> tokens = localityTokens(areaHint);
        if (tokens.isEmpty()) {
            return 0;
        }

        String address = normalizeLocation(candidate.addressName() + " " + candidate.roadAddressName());
        int score = 0;
        for (String token : tokens) {
            if (address.contains(token)) {
                score += 35;
            }
        }
        return score;
    }

    private List<String> localityTokens(String areaHint) {
        if (areaHint == null || areaHint.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : areaHint.split("[\\s,./·]+")) {
            addToken(tokens, raw);
        }
        return tokens;
    }

    private List<String> destinationTokens(String destination, String areaHint) {
        List<String> tokens = new ArrayList<>();
        addToken(tokens, destination);
        addToken(tokens, areaHint);
        String destinationAndArea = normalizeText(destination) + " " + normalizeText(areaHint);
        if (destinationAndArea.contains("제주")) {
            addToken(tokens, "제주");
            addToken(tokens, "제주시");
            addToken(tokens, "서귀포");
        }
        if (destinationAndArea.contains("부산")) {
            addToken(tokens, "부산");
        }
        if (destinationAndArea.contains("서울")) {
            addToken(tokens, "서울");
        }
        if (destinationAndArea.contains("강릉")) {
            addToken(tokens, "강릉");
        }
        if (destinationAndArea.contains("경주")) {
            addToken(tokens, "경주");
        }
        if (destinationAndArea.contains("전주")) {
            addToken(tokens, "전주");
        }
        if (destinationAndArea.contains("여수")) {
            addToken(tokens, "여수");
        }
        if (destinationAndArea.contains("가평")) {
            addToken(tokens, "가평");
        }
        if (destinationAndArea.contains("춘천")) {
            addToken(tokens, "춘천");
        }
        return tokens;
    }

    private void addToken(List<String> tokens, String value) {
        String normalized = normalizeLocation(value);
        if (!normalized.isBlank() && !tokens.contains(normalized)) {
            tokens.add(normalized);
        }
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private String normalizeLocation(String value) {
        return normalizeText(value)
                .replace("특별자치도", "")
                .replace("특별시", "")
                .replace("광역시", "")
                .replaceAll("(시|군|구|도)$", "");
    }

    private void addQuery(List<String> queries, String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        String normalized = query.trim();
        if (!queries.contains(normalized)) {
            queries.add(normalized);
        }
    }

    private String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first.trim() + " " + second.trim();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }

    public record ValidatedPlace(
            String kakaoPlaceId,
            String name,
            BigDecimal lat,
            BigDecimal lng,
            String addressName,
            String roadAddressName,
            String categoryName,
            boolean kakaoVerified
    ) {
        /** 하위 호환: 기존 7인자 호출을 유지 */
        public ValidatedPlace(
                String kakaoPlaceId, String name,
                BigDecimal lat, BigDecimal lng,
                String addressName, String roadAddressName,
                String categoryName
        ) {
            this(kakaoPlaceId, name, lat, lng, addressName, roadAddressName, categoryName, true);
        }
    }

}
