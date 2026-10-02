package com.example.seatreservation.repository;

import com.example.seatreservation.dto.ShowResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class ShowRepository {
    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(UUID id, String name, long price, int limit, List<String> seats) {
        jdbc.update("INSERT INTO shows(id,name,price_paise,per_user_limit) VALUES (?,?,?,?)",
                id, name, price, limit);
        for (String seat : seats) {
            jdbc.update("INSERT INTO seats(id,show_id,seat_number,status) VALUES (?,?,?,'available')",
                    UUID.randomUUID(), id, seat);
        }
    }

    public Optional<ShowResponse> find(UUID id) {
        List<ShowResponse> shows = jdbc.query("""
                SELECT s.id,s.name,s.price_paise,s.per_user_limit,
                  COUNT(*) total,
                  COUNT(*) FILTER (WHERE status='available') available,
                  COUNT(*) FILTER (WHERE status='held') held,
                  COUNT(*) FILTER (WHERE status='confirmed') confirmed
                FROM shows s JOIN seats se ON se.show_id=s.id
                WHERE s.id=?
                GROUP BY s.id,s.name,s.price_paise,s.per_user_limit
                """, (rs, n) -> {
                    List<ShowResponse.SeatState> states = jdbc.query(
                            "SELECT seat_number,status FROM seats WHERE show_id=? ORDER BY seat_number",
                            (r, x) -> new ShowResponse.SeatState(r.getString(1), r.getString(2)), id);
                    return new ShowResponse(
                            rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getLong("price_paise"), rs.getInt("per_user_limit"),
                            rs.getInt("total"), rs.getInt("available"), rs.getInt("held"),
                            rs.getInt("confirmed"), states);
                }, id);
        return shows.stream().findFirst();
    }

    public boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shows WHERE id=?)", Boolean.class, id));
    }
}
