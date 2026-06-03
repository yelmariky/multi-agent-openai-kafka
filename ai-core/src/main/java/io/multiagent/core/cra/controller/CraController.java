package io.multiagent.core.cra.controller;

import io.multiagent.core.model.CraRequest;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.cra.service.CraService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/cra")
public class CraController {

    private final CraService craService;

    public CraController(CraService craService) {
        this.craService = craService;
    }

    @PostMapping("/save")
    public ResponseEntity<Object> save(@RequestBody CraRequest cra) {
        try {
            CraRequest saved = craService.save(cra);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<Object> submit(@RequestBody CraRequest cra) {
        try {
            CraRequest submitted = craService.submit(cra);
            return ResponseEntity.ok(submitted);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<Object> validate(
            @RequestBody CraRequest cra,
            @RequestParam String validatedBy) {
        try {
            CraRequest validated = craService.validate(cra, validatedBy);
            return ResponseEntity.ok(validated);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping("/report")
    public ResponseEntity<Object> report(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String consultant,
            @RequestParam(required = false) String company) {
        try {
            List<Map<String, Object>> result = craService.report(start, end, consultant, company);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping("/absences")
    public ResponseEntity<Object> absences(
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String consultant) {
        try {
            List<ExpenseItem.AbsencePeriod> absences = craService.getKmAbsences(company, month, consultant);
            return ResponseEntity.ok(absences);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/refuse")
    public ResponseEntity<Object> refuse(
            @RequestBody CraRequest cra,
            @RequestParam(required = false) String reason) {
        try {
            CraRequest refused = craService.refuse(cra, reason);
            return ResponseEntity.ok(refused);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/recall")
    public ResponseEntity<Object> recall(@RequestBody CraRequest cra) {
        try {
            CraRequest recalled = craService.recall(cra);
            return ResponseEntity.ok(recalled);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/reopen")
    public ResponseEntity<Object> reopen(@RequestBody CraRequest cra) {
        try {
            CraRequest reopened = craService.reopen(cra);
            return ResponseEntity.ok(reopened);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

}
