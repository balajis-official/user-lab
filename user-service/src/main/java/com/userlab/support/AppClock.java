package com.userlab.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/** "Today" for date rules (age check). Tests pin it with PUT /test/clock. */
@Component
public class AppClock {
    private final ZoneId zone;
    private volatile LocalDate pinned;

    public AppClock(@Value("${app.zone:Asia/Kolkata}") String zone) { this.zone = ZoneId.of(zone); }

    public LocalDate today() {
        LocalDate p = pinned;
        return p != null ? p : LocalDate.now(zone);
    }

    public void pin(LocalDate date) { this.pinned = date; }
    public LocalDate pinnedDate() { return pinned; }
}
