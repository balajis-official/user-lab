package com.userlab.testctl;

import com.userlab.support.AppClock;
import com.userlab.support.BugSwitches;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exists ONLY with the "test" profile. Without it, every /test/** URL is 404. */
@RestController
@RequestMapping("/test")
@Profile("test")
public class TestControlController {

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final AppClock clock;
    private final BugSwitches bugs;

    public TestControlController(JdbcClient jdbc, DataSource dataSource, AppClock clock, BugSwitches bugs) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.clock = clock;
        this.bugs = bugs;
    }

    /** Empties data tables, ids restart at 1. The roles table (reference data) is kept. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        jdbc.sql("TRUNCATE addresses, user_roles, users RESTART IDENTITY CASCADE").update();
        clock.pin(null);
        bugs.set(false, false);
        return state();
    }

    /** reset + seed.sql: users 1..12, always the same. */
    @PostMapping("/seed")
    public Map<String, Object> seed() {
        reset();
        new ResourceDatabasePopulator(new ClassPathResource("seed/seed.sql")).execute(dataSource);
        Map<String, Object> s = state();
        s.put("users", jdbc.sql("SELECT count(*) FROM users").query(Long.class).single());
        s.put("addresses", jdbc.sql("SELECT count(*) FROM addresses").query(Long.class).single());
        return s;
    }

    public record ClockRequest(LocalDate today) {}

    @PutMapping("/clock")
    public Map<String, Object> clock(@RequestBody ClockRequest req) {
        clock.pin(req.today());
        return state();
    }

    public record BugRequest(boolean ignoreIfMatch, boolean mergePatchNullIgnored) {}

    @PutMapping("/bugs")
    public Map<String, Object> bugs(@RequestBody BugRequest req) {
        bugs.set(req.ignoreIfMatch(), req.mergePatchNullIgnored());
        return state();
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", clock.today());
        m.put("clockPinned", clock.pinnedDate() != null);
        m.put("bugs", Map.of("ignoreIfMatch", bugs.ignoreIfMatch(), "mergePatchNullIgnored", bugs.mergePatchNullIgnored()));
        return m;
    }
}
