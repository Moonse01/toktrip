package com.triplan.triplan.oauth;

import com.triplan.triplan.jwt.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;

    @Value("${app.frontend.url:http://localhost:5174}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        KakaoOAuth2User oAuth2User = (KakaoOAuth2User) authentication.getPrincipal();
        String token = jwtUtil.generate(oAuth2User.getUser().getId());

        Cookie cookie = new Cookie("ACCESS_TOKEN", token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24 * 7);
        response.addCookie(cookie);

        // 세션에서 리디렉트 경로 꺼내기
        HttpSession session = request.getSession(false);
        String redirectPath = "/";
        if (session != null && session.getAttribute("REDIRECT_AFTER_LOGIN") != null) {
            redirectPath = (String) session.getAttribute("REDIRECT_AFTER_LOGIN");
            session.removeAttribute("REDIRECT_AFTER_LOGIN");
        }

        getRedirectStrategy().sendRedirect(request, response, frontendUrl + sanitizeRedirectPath(redirectPath));
    }

    private String sanitizeRedirectPath(String redirectPath) {
        if (redirectPath == null || redirectPath.isBlank()) {
            return "/";
        }
        if (!redirectPath.startsWith("/") || redirectPath.startsWith("//") || redirectPath.contains("://")) {
            return "/";
        }
        return redirectPath;
    }
}
