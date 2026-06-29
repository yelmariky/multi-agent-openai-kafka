package io.multiagent.activity.cra.controller;

import io.multiagent.activity.model.CraRequest;
import io.multiagent.activity.model.ExpenseItem;
import io.multiagent.activity.cra.service.CraPdfService;
import io.multiagent.activity.cra.service.CraService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/cra")
public class CraController {

    private final CraService craService;
    private final CraPdfService craPdfService;

    public CraController(CraService craService, CraPdfService craPdfService) {
        this.craService = craService;
        this.craPdfService = craPdfService;
    }

    @GetMapping(value = "/pdf/{id}", produces = "application/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable UUID id,
            @RequestParam(name = "projectId", required = false) UUID projectId) {
        try {
            byte[] pdf = craPdfService.generatePdf(id, projectId);
            String filename = projectId != null
                    ? "cra-" + id + "-" + projectId + ".pdf"
                    : "cra-" + id + ".pdf";
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            return ResponseEntity.ok().headers(headers).body(pdf);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
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
            @RequestParam(name = "validatedBy") String validatedBy) {
        try {
            CraRequest validated = craService.validate(cra, validatedBy);
            return ResponseEntity.ok(validated);
        } catch (Exception e) {
            log.error("CRA validate failed for id={} validatedBy={}: {}", cra.id(), validatedBy, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping("/report")
    public ResponseEntity<Object> report(
            @RequestParam(name = "start",      required = false) String start,
            @RequestParam(name = "end",        required = false) String end,
            @RequestParam(name = "consultant", required = false) String consultant,
            @RequestParam(name = "company",    required = false) String company) {
        try {
            List<Map<String, Object>> result = craService.report(start, end, consultant, company);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping("/absences")
    public ResponseEntity<Object> absences(
            @RequestParam(name = "company",    required = false) String company,
            @RequestParam(name = "month",      required = false) String month,
            @RequestParam(name = "consultant", required = false) String consultant) {
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
            @RequestParam(name = "reason", required = false) String reason) {
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
