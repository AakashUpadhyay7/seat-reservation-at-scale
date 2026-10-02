package com.example.seatreservation.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/live")
    public Map<String,String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    public Map<String,String> ready() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP", "dependency", "postgres");
    }
}
