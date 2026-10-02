package com.example.seatreservation.service;

import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.exception.DomainException;
import com.example.seatreservation.metrics.ReservationMetrics;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.ShowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReservationService {
    private final JdbcTemplate jdbc;
    private final ReservationRepository reservations;
    private final ShowRepository shows;
    private final ReservationMetrics metrics;
    private final MeterRegistry registry;

    public ReservationService(JdbcTemplate jdbc, ReservationRepository reservations, ShowRepository shows,
                              ReservationMetrics metrics, MeterRegistry registry) {
        this.jdbc = jdbc; this.reservations = reservations; this.shows = shows;
        this.metrics = metrics; this.registry = registry;
    }

    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new DomainException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        if (requestedSeats == null || requestedSeats.isEmpty())
            throw new DomainException(HttpStatus.BAD_REQUEST, "SEATS_REQUIRED", "At least one seat is required");

        List<String> seats = requestedSeats.stream().map(String::trim).sorted().toList();
        if (seats.stream().anyMatch(String::isBlank))
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_SEAT", "Seat names cannot be blank");
        if (seats.size() != new HashSet<>(seats).size()) {
            metrics.declined("duplicate-seat");
            throw new DomainException(HttpStatus.CONFLICT, "DUPLICATE_SEAT", "A seat may appear only once");
        }

        ShowResponse show = shows.find(showId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "SHOW_NOT_FOUND", "Show not found"));

        String hash = sha256(String.join("\u001F", seats) + "|" + show.price_paise());

        // First writer wins. If another request with the same key is in flight,
        // PostgreSQL waits for its unique-key decision and then ON CONFLICT returns no row.
        UUID idemId = UUID.randomUUID();
        Optional<ReservationRepository.Idempotency> inserted =
                reservations.insertIdempotency(idemId, showId, userId, idempotencyKey, hash);

        ReservationRepository.Idempotency idem;
        if (inserted.isPresent()) {
            idem = inserted.get();
        } else {
            idem = reservations.lockIdempotency(showId, userId, idempotencyKey);
            if (!idem.requestHash().equals(hash)) {
                throw new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "The idempotency key was already used with a different request");
            }
            if (idem.reservationId() != null) {
                metrics.declined("idempotent-replay");
                return toResponse(idem.reservationId());
            }
            // Defensive path: a row without a reservation should not be observable
            // after a committed transaction, but FOR UPDATE keeps this safe.
        }

        // Serialize reservations for the same user/show before checking the aggregate
        // per-user limit. Without this, ten requests for ten different seats could all
        // read the same pre-request count and collectively exceed the limit.
        reservations.lockUserForShow(showId, userId);

        // Deterministic row lock order serializes competing reservations for the same
        // seat(s), and sorting prevents deadlocks when requests contain multiple seats.
        List<Map<String,Object>> locked = reservations.lockSeats(showId, seats);
        if (locked.size() != seats.size()) {
            metrics.declined("invalid-seat");
            throw new DomainException(HttpStatus.CONFLICT, "INVALID_SEAT",
                    "One or more requested seats do not exist");
        }

        boolean anyTaken = locked.stream().anyMatch(m -> !"available".equals(m.get("status")));
        if (anyTaken) {
            metrics.declined("seat-taken");
            throw new DomainException(HttpStatus.CONFLICT, "SEAT_TAKEN",
                    "One or more requested seats are already taken");
        }

        int active = reservations.countActiveUserSeats(showId, userId);
        if (active + seats.size() > show.per_user_limit()) {
            metrics.declined("per-user-limit");
            throw new DomainException(HttpStatus.CONFLICT, "PER_USER_LIMIT",
                    "Reservation would exceed the per-user seat limit");
        }

        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.price_paise(), seats.size());
        reservations.createReservation(reservationId, showId, userId, amount);
        for (Map<String,Object> row : locked) {
            reservations.attachSeat(reservationId, (UUID) row.get("id"));
        }
        jdbc.update("""
            UPDATE seats se SET status='confirmed',updated_at=CURRENT_TIMESTAMP
            FROM reservation_seats rs
            WHERE rs.reservation_id=? AND rs.seat_id=se.id
            """, reservationId);
        reservations.attachReservation(idem.id(), reservationId);
        metrics.confirmed();
        metrics.setAvailable(showId, currentAvailable(showId));

        return new ReservationResponse(reservationId, showId, userId, seats, amount, "confirmed");
    }

    @Transactional
    public void cancel(UUID reservationId, String userId) {
        Map<String,Object> row;
        try {
            row = reservations.lockReservation(reservationId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new DomainException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation not found");
        }
        String owner = String.valueOf(row.get("user_id"));
        if (!owner.equals(userId))
            throw new DomainException(HttpStatus.FORBIDDEN, "NOT_OWNER", "Only the reservation owner may cancel");
        if (!"confirmed".equals(row.get("status")))
            throw new DomainException(HttpStatus.CONFLICT, "ALREADY_CANCELLED", "Reservation is already cancelled");

        // Lock the physical seat rows before releasing them. A concurrent reservation
        // can therefore never observe a partially cancelled reservation.
        reservations.lockReservationSeats(reservationId);
        reservations.cancel(reservationId);
        UUID showId = (UUID) row.get("show_id");
        metrics.setAvailable(showId, currentAvailable(showId));
    }

    public ReservationResponse toResponse(UUID id) {
        Map<String,Object> row = reservations.reservation(id);
        List<String> seats = new ArrayList<>();
        Object rawSeats = row.get("seats");
        if (rawSeats instanceof java.sql.Array sqlArray) {
            try {
                Object value = sqlArray.getArray();
                if (value instanceof Object[] values) {
                    seats = Arrays.stream(values).map(String::valueOf).collect(Collectors.toList());
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Unable to read reservation seats", e);
            }
        } else if (rawSeats instanceof Object[] values) {
            seats = Arrays.stream(values).map(String::valueOf).collect(Collectors.toList());
        }
        return new ReservationResponse(
                (UUID) row.get("id"), (UUID) row.get("show_id"), String.valueOf(row.get("user_id")),
                seats, ((Number) row.get("amount_paise")).longValue(), String.valueOf(row.get("status")));
    }

    private int currentAvailable(UUID showId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id=? AND status='available'", Integer.class, showId);
        return n == null ? 0 : n;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
