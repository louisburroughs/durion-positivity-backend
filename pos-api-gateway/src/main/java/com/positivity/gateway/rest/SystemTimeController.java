package com.positivity.gateway.rest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.time.ScaledClock;

import reactor.core.publisher.Mono;

@RestController
public class SystemTimeController {

    private final Clock clock;

    public SystemTimeController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/system/time")
    public Mono<SystemTimeResponse> getTime() {
        Instant now = clock.instant();
        ZoneId zone = clock.getZone();
        double scale = (clock instanceof ScaledClock sc) ? sc.getScale() : 1.0;
        return Mono.just(new SystemTimeResponse(now.toString(), scale, zone.getId()));
    }

    public record SystemTimeResponse(String virtualTime, double scale, String zone) {}
}
