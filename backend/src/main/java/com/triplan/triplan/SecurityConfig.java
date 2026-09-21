package com.triplan.triplan;

import com.triplan.triplan.jwt.JwtAuthenticationFilter;
import com.triplan.triplan.jwt.JwtUtil;
import com.triplan.triplan.oauth.CustomOAuth2UserService;
import com.triplan.triplan.oauth.OAuth2FailureHandler;
import com.triplan.triplan.oauth.OAuth2RedirectFilter;
import com.triplan.triplan.oauth.OAuth2SuccessHandler;
import com.triplan.triplan.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2RedirectFilter oAuth2RedirectFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    if (request.getRequestURI().startsWith("/api/")) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                        return;
                    }
                    response.sendRedirect("/api/v1/auth/kakao/login");
                }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/health",
                                "/api/v1/auth/**",
                                "/api/v1/users/me/dashboard",
                                "/login/**",
                                "/oauth2/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/plans/*",
                                "/api/v1/plans/*/status",
                                "/api/v1/plans/*/candidates",
                                "/api/v1/plans/*/final",
                                "/api/v1/route/**",
                                "/api/v1/notifications/vapid-public-key"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/plans",
                                "/api/v1/plans/*/generate",
                                "/api/v1/plans/*/votes",
                                "/api/v1/plans/*/votes/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(kakaoAuthorizationRequestResolver()))
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler)
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtUtil, userRepository),
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        oAuth2RedirectFilter,
                        OAuth2AuthorizationRequestRedirectFilter.class
                );

        return http.build();
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    private OAuth2AuthorizationRequestResolver kakaoAuthorizationRequestResolver() {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository, "/oauth2/authorization");

        resolver.setAuthorizationRequestCustomizer(customizer ->
                customizer.additionalParameters(params -> params.put("prompt", "login"))
        );

        return resolver;
    }
}
