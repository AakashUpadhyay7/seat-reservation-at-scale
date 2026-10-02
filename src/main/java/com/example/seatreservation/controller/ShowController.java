package com.example.seatreservation.controller;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ReserveRequest;
import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.security.AuthService;
import com.example.seatreservation.security.AuthUser;
import com.example.seatreservation.service.ReservationService;
import com.example.seatreservation.service.ShowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.UUID;

@RestController
public class ShowController {
    private final ShowService shows;
    private final ReservationService reservations;
    private final AuthService auth;

    public ShowController(ShowService shows, ReservationService reservations, AuthService auth) {
        this.shows = shows; this.reservations = reservations; this.auth = auth;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request, HttpServletRequest http) {
        auth.requireAdmin(http);
        return ResponseEntity.status(201).body(shows.create(request));
    }

    @GetMapping("/shows/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return shows.get(id);
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID id,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @Valid @RequestBody ReserveRequest request,
            HttpServletRequest http) {
        AuthUser user = auth.requireUser(http);
        return ResponseEntity.status(201).body(reservations.reserve(id, user.userId(), request.seats(), key));
    }

    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, HttpServletRequest http) {
        AuthUser user = auth.requireUser(http);
        reservations.cancel(id, user.userId());
        return ResponseEntity.noContent().build();
    }
}
