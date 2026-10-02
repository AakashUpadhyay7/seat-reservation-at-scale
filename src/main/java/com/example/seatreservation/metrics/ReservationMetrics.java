package com.example.seatreservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ReservationMetrics {
    private final Counter confirmed;
    private final Map<String, Counter> declined = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> available = new ConcurrentHashMap<>();
    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        confirmed = Counter.builder("reservations_confirmed_total")
                .description("Confirmed reservations")
                .register(registry);
        for (String reason : new String[]{"seat-taken", "per-user-limit", "idempotent-replay",
                "invalid-seat", "duplicate-seat", "cancelled"}) {
            declined.put(reason, Counter.builder("reservations_declined_total")
                    .tag("reason", reason)
                    .description("Reservation declines by reason")
                    .register(registry));
        }
    }

    public void confirmed() { confirmed.increment(); }

    public void declined(String reason) {
        Counter c = declined.get(reason);
        if (c != null) c.increment();
    }

    public void setAvailable(UUID showId, int value) {
        AtomicInteger holder = available.computeIfAbsent(showId, id -> {
            AtomicInteger h = new AtomicInteger();
            Gauge.builder("seats_available", h, AtomicInteger::get)
                    .tag("show_id", id.toString())
                    .description("Currently available seats")
                    .register(registry);
            return h;
        });
        holder.set(value);
    }
}
