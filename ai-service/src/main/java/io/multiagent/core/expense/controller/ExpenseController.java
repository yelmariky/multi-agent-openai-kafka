package io.multiagent.core.expense.controller;

import io.multiagent.core.model.ExpenseReportResponse;
import io.multiagent.core.expense.service.ExpenseReportService;
import io.multiagent.core.expense.service.ExpensePdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;

@RestController
@RequestMapping("/expenses")
@RequiredArgsConstructor
@Slf4j
public class ExpenseController {

    private final ExpenseReportService reportService;
    private final ExpensePdfService expensePdfService;

    @GetMapping("/report")
    public ExpenseReportResponse report(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String consultantEmail
    ) {
        log.info("📊 /expenses/report start={} end={} type={} currency={} company={} consultant={}", start, end, type, currency, company, consultantEmail);
        try {
            return reportService.report(start, end, type, currency, company, consultantEmail);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/report/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> reportExcel(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String consultantEmail
    ) {
        log.info("📊 /expenses/report/excel start={} end={} type={} currency={} company={} consultant={}", start, end, type, currency, company, consultantEmail);
        byte[] bytes;
        try {
            bytes = reportService.buildExcel(start, end, type, currency, company, consultantEmail);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rapport-notes-frais.xlsx\"");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    @GetMapping(value = "/report/pdf/month", produces = "application/pdf")
    public ResponseEntity<byte[]> reportPdfForMonth(
            @RequestParam String month,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String consultantEmail
    ) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paramètre month attendu au format YYYY-MM", e);
        }
        String start = ym.atDay(1).toString();
        String end = ym.atEndOfMonth().toString();
        log.info("📊 /expenses/report/pdf/month month={} start={} end={} type={} currency={} company={} consultant={}", month, start, end, type, currency, company, consultantEmail);
        byte[] bytes;
        try {
            bytes = expensePdfService.buildPdf(start, end, type, currency, company, consultantEmail);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rapport-notes-frais.pdf\"");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}
