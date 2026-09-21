package com.triplan.triplan.service;

import com.triplan.triplan.entity.User;
import com.triplan.triplan.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VoterResolverService {

    private final UserRepository userRepository;

    public User resolve(User authenticatedUser, String guestUuid) {
        if (authenticatedUser != null) {
            return authenticatedUser;
        }

        String normalizedGuestUuid = guestUuid != null && !guestUuid.isBlank()
                ? guestUuid
                : "anonymous";
        String kakaoId = "guest:" + normalizedGuestUuid;

        return userRepository.findByKakaoId(kakaoId)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .kakaoId(kakaoId)
                                .nickname("게스트")
                                .build()
                ));
    }
}
