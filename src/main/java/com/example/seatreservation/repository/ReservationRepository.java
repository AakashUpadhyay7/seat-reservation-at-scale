package com.example.seatreservation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Idempotency(UUID id, UUID reservationId, String requestHash, String userId, UUID showId) {}

    public Optional<Idempotency> insertIdempotency(UUID id, UUID showId, String userId, String key, String hash) {
        List<Idempotency> rows = jdbc.query("""
            INSERT INTO idempotency_keys(id,show_id,user_id,idempotency_key,request_hash)
            VALUES (?,?,?,?,?)
            ON CONFLICT(show_id,user_id,idempotency_key) DO NOTHING
            RETURNING id,show_id,user_id,request_hash,reservation_id
            """, (rs,n) -> new Idempotency(
                rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class),
                rs.getString("request_hash"), rs.getString("user_id"), rs.getObject("show_id", UUID.class)
            ), id, showId, userId, key, hash);
        return rows.stream().findFirst();
    }

    public Idempotency lockIdempotency(UUID showId, String userId, String key) {
        return jdbc.queryForObject("""
            SELECT id,show_id,user_id,request_hash,reservation_id
            FROM idempotency_keys
            WHERE show_id=? AND user_id=? AND idempotency_key=?
            FOR UPDATE
            """, (rs,n) -> new Idempotency(
                rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class),
                rs.getString("request_hash"), rs.getString("user_id"), rs.getObject("show_id", UUID.class)
            ), showId, userId, key);
    }

    public void attachReservation(UUID id, UUID reservationId) {
        jdbc.update("UPDATE idempotency_keys SET reservation_id=? WHERE id=?", reservationId, id);
    }

    public void createReservation(UUID reservationId, UUID showId, String userId, long amount) {
        jdbc.update("""
            INSERT INTO reservations(id,show_id,user_id,amount_paise,status)
            VALUES (?,?,?,?,'confirmed')
            """, reservationId, showId, userId, amount);
    }

    public void attachSeat(UUID reservationId, UUID seatId) {
        jdbc.update("INSERT INTO reservation_seats(reservation_id,seat_id) VALUES (?,?)", reservationId, seatId);
    }

    /** Serializes concurrent reservations for one user within one show. */
    public void lockUserForShow(UUID showId, String userId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Long.class, showId + ":" + userId);
    }

    public int countActiveUserSeats(UUID showId, String userId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM reservation_seats rs
            JOIN reservations r ON r.id=rs.reservation_id
            WHERE r.show_id=? AND r.user_id=? AND r.status='confirmed'
            """, Integer.class, showId, userId);
        return count == null ? 0 : count;
    }

    public Optional<UUID> findReservation(UUID id) {
        List<UUID> rows = jdbc.query("SELECT id FROM reservations WHERE id=?", (rs,n) -> rs.getObject(1, UUID.class), id);
        return rows.stream().findFirst();
    }

    public Map<String,Object> reservation(UUID id) {
        return jdbc.queryForMap("""
            SELECT r.id,r.show_id,r.user_id,r.amount_paise,r.status,
                   COALESCE(array_agg(se.seat_number ORDER BY se.seat_number),'{}') AS seats
            FROM reservations r
            JOIN reservation_seats rs ON rs.reservation_id=r.id
            JOIN seats se ON se.id=rs.seat_id
            WHERE r.id=?
            GROUP BY r.id,r.show_id,r.user_id,r.amount_paise,r.status
            """, id);
    }

    public void cancel(UUID id) {
        jdbc.update("UPDATE reservations SET status='cancelled',cancelled_at=CURRENT_TIMESTAMP WHERE id=?", id);
        jdbc.update("""
            UPDATE seats se SET status='available',updated_at=CURRENT_TIMESTAMP
            FROM reservation_seats rs
            WHERE rs.reservation_id=? AND rs.seat_id=se.id
            """, id);
    }

    public List<Map<String,Object>> lockSeats(UUID showId, List<String> names) {
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(showId);
        args.addAll(names);
        return jdbc.query("""
            SELECT id,seat_number,status
            FROM seats
            WHERE show_id=? AND seat_number IN (%s)
            ORDER BY seat_number
            FOR UPDATE
            """.formatted(placeholders), (rs,n) -> {
                Map<String,Object> m = new HashMap<>();
                m.put("id", rs.getObject("id", UUID.class));
                m.put("seat_number", rs.getString("seat_number"));
                m.put("status", rs.getString("status"));
                return m;
            }, args.toArray());
    }
}
