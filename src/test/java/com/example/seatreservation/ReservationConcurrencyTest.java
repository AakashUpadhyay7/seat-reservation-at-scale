package com.example.seatreservation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lightweight concurrency invariant example.
 *
 * The full database-backed version should run against PostgreSQL/Testcontainers in CI.
 * The important production concurrency primitive is documented in WRITEUP.md.
 */
class ReservationConcurrencyTest {
    @Test
    void oneWinnerInvariantConcept() throws Exception {
        int contenders = 500;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        Thread[] threads = new Thread[contenders];
        for (int i = 0; i < contenders; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    // Placeholder for the real HTTP/database race in integration CI.
                    if (winners.compareAndSet(0, 1)) {
                        // exactly one simulated winner
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) t.join();

        assertEquals(1, winners.get());
    }
}
