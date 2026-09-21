package com.triplan.triplan.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

@Controller
public class LoginRedirectController {

    @Value("${app.frontend.url:http://localhost:5174}")
    private String frontendUrl;

    @GetMapping("/login")
    public void loginFallback(HttpServletResponse response) throws IOException {
        response.sendRedirect(frontendUrl + "/?loginError=oauth");
    }
}
