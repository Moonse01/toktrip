package com.triplan.triplan.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class OAuth2RedirectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().contains("/oauth2/authorization/")) {
            String redirect = request.getParameter("redirect");
            if (redirect != null && !redirect.isBlank()) {
                request.getSession().setAttribute("REDIRECT_AFTER_LOGIN", redirect);
            }
        }
        filterChain.doFilter(request, response);
    }
}
