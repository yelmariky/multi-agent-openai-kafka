package io.multiagent.core.dashboard.controller;

import io.multiagent.core.dashboard.model.DashboardSummary;
import io.multiagent.core.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<Object> summary(
            @RequestParam(name = "month", required = false) String month) {
        try {
            String resolved = resolveMonth(month);
            DashboardSummary summary = dashboardService.summary(resolved);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Dashboard summary error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    private String resolveMonth(String month) {
        if (month != null && !month.isBlank()) {
            try {
                YearMonth.parse(month);
                return month;
            } catch (DateTimeParseException ignored) {}
        }
        return YearMonth.now().toString();
    }
}
