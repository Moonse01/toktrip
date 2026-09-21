package com.triplan.triplan.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/kakao/login")
    public void kakaoLogin(
            @RequestParam(required = false) String redirect,
            HttpServletResponse response
    ) throws IOException {
        String target = "/oauth2/authorization/kakao";
        if (redirect != null && !redirect.isBlank()) {
            target += "?redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8);
        }
        response.sendRedirect(target);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("ACCESS_TOKEN", null);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok().build();
    }
}
