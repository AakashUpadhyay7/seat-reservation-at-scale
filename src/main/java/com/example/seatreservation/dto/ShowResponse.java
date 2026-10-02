package com.example.seatreservation.dto;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long price_paise,
        int per_user_limit,
        int total_seats,
        int available,
        int held,
        int confirmed,
        List<SeatState> seats
) {
    public record SeatState(String seat, String status) {}
}
