package com.example.seatreservation.dto;

import java.time.Instant;

public record ErrorResponse(
        String code,
        String message,
        String request_id,
        Instant timestamp
) {}
