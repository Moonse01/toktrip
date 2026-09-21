package com.triplan.triplan.service;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ChatPreprocessor {

    private static final int MAX_LENGTH = 3000;
    private static final List<Pattern> SKIP_PATTERNS = List.of(
            Pattern.compile("^\\s*$"),
            Pattern.compile("^-{3,}.*"),
            Pattern.compile(".*(사진|동영상|이모티콘).*"),
            Pattern.compile(".*https?://\\S+.*"),
            Pattern.compile("^[ㅋㅎㅠㅜ]+$"),
            Pattern.compile(".*\\]\\s*[ㅋㅎㅠㅜ]+$"),
            Pattern.compile("^\\[[^]]+\\]\\s*$")
    );

    public String preprocess(String chatLog) {
        if (chatLog == null || chatLog.isBlank()) {
            return "";
        }

        String cleaned = Arrays.stream(chatLog.split("\\R"))
                .map(String::trim)
                .filter(line -> SKIP_PATTERNS.stream().noneMatch(pattern -> pattern.matcher(line).matches()))
                .collect(Collectors.joining("\n"));

        if (cleaned.length() <= MAX_LENGTH) {
            return cleaned;
        }

        return cleaned.substring(cleaned.length() - MAX_LENGTH);
    }
}
