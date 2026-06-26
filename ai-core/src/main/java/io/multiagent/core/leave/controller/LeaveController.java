package io.multiagent.core.leave.controller;

import io.multiagent.core.leave.service.LeaveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/leaves")
@RequiredArgsConstructor
@Slf4j
public class LeaveController {

    private final LeaveService leaveService;

    /** Consultant — soumet une demande de congé. */
    @PostMapping("/request")
    public ResponseEntity<Object> request(@RequestBody Map<String, String> body) {
        try {
            String email  = body.get("consultantEmail");
            String type   = body.get("type");
            String start  = body.get("startDate");
            String end    = body.get("endDate");
            String reason = body.get("reason");
            if (email == null || type == null || start == null || end == null)
                return ResponseEntity.badRequest().body("Champs requis : consultantEmail, type, startDate, endDate");
            return ResponseEntity.ok(leaveService.request(email, type, start, end, reason));
        } catch (Exception e) {
            log.error("leave request error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Consultant — mes demandes. */
    @GetMapping("/mine")
    public ResponseEntity<Object> mine(
            @RequestParam(name = "consultantEmail") String email,
            @RequestParam(name = "status", required = false) String status) {
        try {
            return ResponseEntity.ok(leaveService.listMine(email, status));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Consultant — solde CP/RTT. */
    @GetMapping("/balance")
    public ResponseEntity<Object> balance(
            @RequestParam(name = "consultantEmail") String email,
            @RequestParam(name = "year", required = false) Integer year) {
        try {
            int y = year != null ? year : Year.now().getValue();
            return ResponseEntity.ok(leaveService.balance(email, y));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Admin — toutes les demandes du tenant. */
    @GetMapping
    public ResponseEntity<Object> listAll(
            @RequestParam(name = "status", required = false) String status) {
        try {
            return ResponseEntity.ok(leaveService.listAll(status));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Admin — approuver une demande. */
    @PutMapping("/{id}/approve")
    public ResponseEntity<Object> approve(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        try {
            String approvedBy = body.getOrDefault("approvedBy", "admin");
            return ResponseEntity.ok(leaveService.approve(id, approvedBy));
        } catch (Exception e) {
            log.error("leave approve error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Admin — refuser une demande. */
    @PutMapping("/{id}/refuse")
    public ResponseEntity<Object> refuse(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(leaveService.refuse(id, body.get("reason")));
        } catch (Exception e) {
            log.error("leave refuse error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Admin — initialiser/modifier le solde CP/RTT d'un consultant. */
    @PutMapping("/balance")
    public ResponseEntity<Object> setBalance(@RequestBody Map<String, Object> body) {
        try {
            String email      = (String) body.get("consultantEmail");
            int    year       = body.get("year") != null ? ((Number) body.get("year")).intValue() : Year.now().getValue();
            double cpInitial  = body.get("cpInitial")  != null ? ((Number) body.get("cpInitial")).doubleValue()  : 25.0;
            double rttInitial = body.get("rttInitial") != null ? ((Number) body.get("rttInitial")).doubleValue() : 12.0;
            return ResponseEntity.ok(leaveService.setBalance(email, year, cpInitial, rttInitial));
        } catch (Exception e) {
            log.error("leave setBalance error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
