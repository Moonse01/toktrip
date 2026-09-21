package com.triplan.triplan.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Value("${app.frontend.url:http://localhost:5174}")
    private String frontendUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String redirectPath = popRedirectPath(request.getSession(false));
        String separator = redirectPath.contains("?") ? "&" : "?";
        getRedirectStrategy().sendRedirect(
                request,
                response,
                frontendUrl + redirectPath + separator + "loginError=oauth"
        );
    }

    private String popRedirectPath(HttpSession session) {
        if (session == null || session.getAttribute("REDIRECT_AFTER_LOGIN") == null) {
            return "/";
        }

        String redirectPath = String.valueOf(session.getAttribute("REDIRECT_AFTER_LOGIN"));
        session.removeAttribute("REDIRECT_AFTER_LOGIN");
        return sanitizeRedirectPath(redirectPath);
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
