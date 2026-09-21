package com.triplan.triplan.controller;

import com.triplan.triplan.dto.ApiResponse;
import com.triplan.triplan.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/route")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @GetMapping
    public ApiResponse<List<double[]>> getRoute(
            @RequestParam String originLat,
            @RequestParam String originLng,
            @RequestParam String destLat,
            @RequestParam String destLng
    ) {
        Double parsedOriginLat = parseCoordinate(originLat);
        Double parsedOriginLng = parseCoordinate(originLng);
        Double parsedDestLat = parseCoordinate(destLat);
        Double parsedDestLng = parseCoordinate(destLng);

        if (!isValidCoordinate(parsedOriginLat, parsedOriginLng)
                || !isValidCoordinate(parsedDestLat, parsedDestLng)) {
            return ApiResponse.ok(List.of());
        }
        List<double[]> coords = routeService.getRoute(
                parsedOriginLat,
                parsedOriginLng,
                parsedDestLat,
                parsedDestLng
        );
        return ApiResponse.ok(coords);
    }

    private Double parseCoordinate(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isValidCoordinate(Double lat, Double lng) {
        return lat != null
                && lng != null
                && Double.isFinite(lat)
                && Double.isFinite(lng);
    }
}
