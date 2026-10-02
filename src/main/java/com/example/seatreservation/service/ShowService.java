package com.example.seatreservation.service;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.exception.DomainException;
import com.example.seatreservation.repository.ShowRepository;
import com.example.seatreservation.metrics.ReservationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ShowService {
    private final ShowRepository repository;
    private final ReservationMetrics metrics;
    private final MeterRegistry registry;

    public ShowService(ShowRepository repository, ReservationMetrics metrics, MeterRegistry registry) {
        this.repository = repository; this.metrics = metrics; this.registry = registry;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        List<String> seats = request.seats().stream().map(String::trim).toList();
        if (seats.stream().anyMatch(String::isBlank))
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_SEAT", "Seat names cannot be blank");
        if (seats.size() != new HashSet<>(seats).size())
            throw new DomainException(HttpStatus.CONFLICT, "DUPLICATE_SEAT", "Duplicate seat in show");
        int limit = request.per_user_limit() == null ? 4 : request.per_user_limit();
        if (limit <= 0) throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "per_user_limit must be positive");

        UUID id = UUID.randomUUID();
        repository.create(id, request.name(), request.price_paise(), limit, seats);
        ShowResponse response = repository.find(id).orElseThrow();
        metrics.setAvailable(id, response.available());
        return response;
    }

    public ShowResponse get(UUID id) {
        ShowResponse response = repository.find(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "SHOW_NOT_FOUND", "Show not found"));
        if (response.available() + response.held() + response.confirmed() != response.total_seats())
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR, "RECONCILIATION_BROKEN", "Seat reconciliation invariant failed");
        metrics.setAvailable(id, response.available());
        return response;
    }
}
