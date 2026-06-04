package io.multiagent.notefrais.expense.service;

import io.multiagent.notefrais.expense.repository.ExpenseWeaviateRepository;
import io.multiagent.notefrais.model.ExpenseItem;
import io.multiagent.notefrais.model.ExpenseReportResponse;
import io.multiagent.notefrais.util.DateProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpenseReportService {

    private final ExpenseWeaviateRepository expenseRepo;
    private final DateProvider dateProvider;
    private final ExpenseExcelService excelService;

    @Value("${ai-core.company-name:}")
    private String defaultCompanyName;

    public ExpenseReportResponse report(String startStr, String endStr, String type, String currency, String company) {
        return report(startStr, endStr, type, currency, company, null);
    }

    public ExpenseReportResponse report(String startStr, String endStr, String type, String currency, String company, String consultantEmail) {
        LocalDate[] range = resolveRange(startStr, endStr);
        String resolvedCompany = resolveCompany(company);
        List<ExpenseItem> expenses = expenseRepo.findExpensesBetween(range[0], range[1], type, currency,
                consultantEmail != null ? consultantEmail.toLowerCase(java.util.Locale.ROOT) : null);

        log.info("Weaviate returned {} expenses between {} and {} (type={}, currency={}, company={})",
                expenses == null ? 0 : expenses.size(), range[0], range[1], type, currency, resolvedCompany);

        if (expenses != null && !expenses.isEmpty()) {
            log.debug("Expenses raw: {}", expenses);
        }
        normalize(expenses);
        if (!isBlank(company)) {
            String companyLower = company.toLowerCase(Locale.ROOT);
            expenses = expenses.stream()
                    .filter(e -> matchesCompany(e, companyLower))
                    .toList();
            log.info("Filtre société='{}' -> {} dépenses restantes", company, expenses.size());
        }
        expenses = expenses.stream()
                .filter(e -> !isBlank(e.getPaymentMode()) && !isBlank(e.getAddress()))
                .toList();
        log.info("Expenses kept after filtering (paymentMode+address) : {}", expenses.size());

        Map<String, Double> totalsByCurrency = expenses.stream()
                .filter(e -> e.getAmount() != null && e.getCurrency() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getCurrency().toUpperCase(Locale.ROOT),
                        Collectors.summingDouble(ExpenseItem::getAmount)
                ));

        Map<String, Double> totalsByType = expenses.stream()
                .filter(e -> e.getAmount() != null && e.getType() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getType().toLowerCase(Locale.ROOT),
                        Collectors.summingDouble(ExpenseItem::getAmount)
                ));

        return ExpenseReportResponse.builder()
                .start(range[0])
                .end(range[1])
                .company(resolvedCompany)
                .consultantEmail(consultantEmail)
                .expenses(expenses)
                .totalsByCurrency(totalsByCurrency)
                .totalsByType(totalsByType)
                .count(expenses.size())
                .build();
    }

    public byte[] buildExcel(String start, String end, String type, String currency, String company) {
        return buildExcel(start, end, type, currency, company, null);
    }

    public byte[] buildExcel(String start, String end, String type, String currency, String company, String consultantEmail) {
        return excelService.buildExcel(report(start, end, type, currency, company, consultantEmail));
    }

    private LocalDate[] resolveRange(String startStr, String endStr) {
        LocalDate today = dateProvider.todayUtc();
        LocalDate start;
        LocalDate end;

        if (isBlank(startStr) && isBlank(endStr)) {
            YearMonth currentMonth = YearMonth.from(today);
            start = currentMonth.atDay(1);
            end = currentMonth.atEndOfMonth();
        } else {
            start = isBlank(startStr) ? today.minusDays(30) : LocalDate.parse(startStr);
            end = isBlank(endStr) ? today : LocalDate.parse(endStr);
        }

        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        return new LocalDate[]{start, end};
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean matchesCompany(ExpenseItem e, String companyLower) {
        if (isBlank(companyLower)) return true;
        if (e == null) return false;
        if (e.getCompany() != null && e.getCompany().toLowerCase(Locale.ROOT).contains(companyLower)) {
            return true;
        }
        return (e.getDescription() != null && e.getDescription().toLowerCase(Locale.ROOT).contains(companyLower))
                || (e.getOriginalText() != null && e.getOriginalText().toLowerCase(Locale.ROOT).contains(companyLower));
    }

    private String resolveCompany(String companyParam) {
        String c = isBlank(companyParam) ? defaultCompanyName : companyParam;
        if (isBlank(c)) {
            throw new IllegalStateException("Company/organisation is required (provide query param 'company' or property ai-core.company-name)");
        }
        return c;
    }

    private void normalize(List<ExpenseItem> expenses) {
        if (expenses == null) return;
        expenses.stream().filter(e -> e != null).forEach(this::normalizeOne);
    }

    private void normalizeOne(ExpenseItem e) {
        if (e.getId() == null) e.setId(0);
        if (!isBlank(e.getCurrency())) e.setCurrency(e.getCurrency().toUpperCase(Locale.ROOT));
        if (!isBlank(e.getType())) e.setType(e.getType().toLowerCase(Locale.ROOT));
        e.setPaymentMode(resolvePaymentMode(e.getPaymentMode()));
    }

    private String resolvePaymentMode(String raw) {
        if (isBlank(raw)) return "Personnel";
        String lower = raw.toLowerCase(Locale.ROOT);
        return (lower.contains("business") || lower.contains("entreprise") || lower.contains("company"))
                ? "Business" : "Personnel";
    }
}
