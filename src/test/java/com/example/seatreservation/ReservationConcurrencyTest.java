package com.example.seatreservation;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.exception.DomainException;
import com.example.seatreservation.service.ReservationService;
import com.example.seatreservation.service.ShowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class ReservationConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seatreservation")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "50");
    }

    @Autowired ShowService shows;
    @Autowired ReservationService reservations;

    @Test
    void hotSeatHasExactlyOneWinnerAndNoFiveHundreds() throws Exception {
        ShowResponse show = shows.create(new CreateShowRequest(
                "hot-seat-" + UUID.randomUUID(), List.of("A1"), 10000L, 4));

        int contenders = 100;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    ReservationResponse ignored = reservations.reserve(
                            show.id(), "user-" + n, List.of("A1"), "hot-" + n);
                    return true;
                } catch (DomainException e) {
                    assertEquals(409, e.getStatus().value());
                    return false;
                }
            }));
        }
        start.countDown();

        int winners = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) winners++;
        }
        pool.shutdownNow();

        assertEquals(1, winners);
        ShowResponse finalState = shows.get(show.id());
        assertEquals(show.total_seats(), finalState.available() + finalState.held() + finalState.confirmed());
        assertEquals(1, finalState.confirmed());
    }

    @Test
    void perUserLimitHoldsUnderConcurrentDifferentSeats() throws Exception {
        List<String> seats = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "P" + i).toList();
        ShowResponse show = shows.create(new CreateShowRequest(
                "limit-" + UUID.randomUUID(), seats, 10000L, 4));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            final String seat = seats.get(i);
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    reservations.reserve(show.id(), "same-user", List.of(seat), "limit-" + n);
                    return true;
                } catch (DomainException e) {
                    assertEquals(409, e.getStatus().value());
                    return false;
                }
            }));
        }
        start.countDown();

        int winners = 0;
        for (Future<Boolean> future : futures) if (future.get(30, TimeUnit.SECONDS)) winners++;
        pool.shutdownNow();

        assertEquals(4, winners);
        assertEquals(4, shows.get(show.id()).confirmed());
    }

    @Test
    void cancellationMakesSeatBookableAgainAndReplayIsIdempotent() {
        ShowResponse show = shows.create(new CreateShowRequest(
                "cancel-" + UUID.randomUUID(), List.of("C1"), 5000L, 4));

        ReservationResponse first = reservations.reserve(show.id(), "alice", List.of("C1"), "cancel-1");
        ReservationResponse replay = reservations.reserve(show.id(), "alice", List.of("C1"), "cancel-1");
        assertEquals(first.id(), replay.id());

        reservations.cancel(first.id(), "alice");
        ReservationResponse second = reservations.reserve(show.id(), "bob", List.of("C1"), "cancel-2");
        assertNotEquals(first.id(), second.id());
        assertEquals(1, shows.get(show.id()).confirmed());
    }
}
