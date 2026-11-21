package com.copymylife.copymylife.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

// Decides which calendar day a user is in. The browser sends its IANA zone in the
// X-Time-Zone header; a missing or unknown zone falls back to the configured one,
// never to the server's own default.
@Component
public class UserTimeZone {

    public static final String HEADER = "X-Time-Zone";

    private final ZoneId fallback;

    public UserTimeZone(
            @Value("${app.tracking.default-time-zone:Asia/Almaty}") String fallback
    ) {
        this.fallback = ZoneId.of(fallback);
    }

    public ZoneId resolve(String zone) {
        if (zone == null || zone.isBlank() || zone.length() > 64) {
            return fallback;
        }

        try {
            return ZoneId.of(zone.trim());
        } catch (DateTimeException exception) {
            return fallback;
        }
    }

    // The user's calendar day at a moment.
    public LocalDate today(String zone, Instant now) {
        return LocalDate.ofInstant(now, resolve(zone));
    }
}
