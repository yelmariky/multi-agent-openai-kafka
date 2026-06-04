package io.multiagent.notefrais.expense.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.notefrais.client.LLMAIClient;
import io.multiagent.notefrais.model.ExpenseItem;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.WhereArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import io.multiagent.notefrais.weaviate.WeaviateResponseParser;
import static io.multiagent.notefrais.weaviate.WeaviateUtils.*;

@Slf4j
@Repository
public class ExpenseWeaviateRepository {

    private final WeaviateClient client;
    private final LLMAIClient llm;
    private final ObjectMapper objectMapper;
    private final String expenseClassName;

    public ExpenseWeaviateRepository(
            WeaviateClient client,
            LLMAIClient llm,
            ObjectMapper objectMapper,
            @Value("${weaviate.expense-class:Expense}") String expenseClassName) {
        this.client = client;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.expenseClassName = expenseClassName;
    }

    public void indexExpense(String id, ExpenseItem item, String source, boolean duplicate, String hash) {
        try {
            if (item == null
                    || item.getAmount() == null
                    || isNullOrBlank(item.getCurrency())
                    || isNullOrBlank(item.getType())
                    || isNullOrBlank(item.getPaymentMode())
                    || isNullOrBlank(item.getAddress())) {
                log.warn("indexExpense ignoré: champs obligatoires manquants (amount/currency/type/paymentMode/address) pour {}", item);
                return;
            }
            String text = buildExpenseText(item, source, duplicate);
            List<Double> vector = llm.embed(llm.getEmbeddingModel(), text);
            log.debug("expenseITEM: {}", item);
            Map<String, Object> props = new HashMap<>();
            props.put("amount", item.getAmount());
            props.put("currency", item.getCurrency());
            props.put("type", item.getType());
            props.put("expenseId", item.getId());
            if (item.getKm() != null) {
                props.put("km", item.getKm());
            }
            String isoDate = null;
            if (item.getDate() != null && !item.getDate().isBlank()) {
                try {
                    LocalDate ld = LocalDate.parse(item.getDate());
                    isoDate = formatRfc3339(ld);
                } catch (Exception ignored) { }
            }
            props.put("date", isoDate);
            props.put("dateText", isoDate);
            props.put("description", item.getDescription());
            props.put("originalText", item.getOriginalText());
            props.put("source", source);
            props.put("duplicateFlag", duplicate);
            props.put("hash", hash);
            props.put("text", text);
            props.put("paymentMode", item.getPaymentMode());
            props.put("address", item.getAddress());
            if (item.getCompany() != null) {
                props.put("company", item.getCompany());
            }
            if (item.getConsultantEmail() != null && !item.getConsultantEmail().isBlank()) {
                props.put("consultantEmail", item.getConsultantEmail().toLowerCase(java.util.Locale.ROOT));
            }
            props.put("approvalStatus", item.getApprovalStatus() != null ? item.getApprovalStatus() : "PENDING");
            if (item.getApprovalNote() != null) {
                props.put("approvalNote", item.getApprovalNote());
            }
            if (item.getAbsencePeriods() != null && !item.getAbsencePeriods().isEmpty()) {
                try {
                    props.put("absencePeriodsJson", objectMapper.writeValueAsString(item.getAbsencePeriods()));
                } catch (Exception ex) {
                    log.warn("Could not serialize absencePeriods: {}", ex.getMessage());
                }
            }

            var creator = client.data().creator()
                    .withClassName(expenseClassName)
                    .withProperties(props)
                    .withVector(toFloatArray(vector));

            if (id != null && !id.isBlank()) {
                creator = creator.withID(id);
            }

            var result = creator.run();
            if (result.hasErrors()) {
                log.error("Weaviate indexExpense error: {}", result.getError());
            } else {
                log.info("Weaviate: expense indexée (id={})", id);
            }
        } catch (Exception e) {
            log.error("Exception indexExpense: {}", e.getMessage(), e);
        }
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end) {
        return findExpensesBetween(start, end, null, null);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency) {
        return findExpensesBetween(start, end, type, currency, null);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency, String consultantEmail) {
        try {
            String startIso = formatRfc3339(start);
            String endExclusiveIso = formatRfc3339(end.plusDays(1));

            WhereFilter afterStart = WhereFilter.builder()
                    .path("date")
                    .operator(Operator.GreaterThanEqual)
                    .valueDate(toDate(startIso))
                    .build();

            WhereFilter beforeEnd = WhereFilter.builder()
                    .path("date")
                    .operator(Operator.LessThan)
                    .valueDate(toDate(endExclusiveIso))
                    .build();

            List<WhereFilter> filters = new ArrayList<>();
            filters.add(afterStart);
            filters.add(beforeEnd);
            if (type != null && !type.isBlank()) {
                filters.add(WhereFilter.builder()
                        .path("type")
                        .operator(Operator.Equal)
                        .valueText(type)
                        .build());
            }
            if (currency != null && !currency.isBlank()) {
                filters.add(WhereFilter.builder()
                        .path("currency")
                        .operator(Operator.Equal)
                        .valueText(currency.toUpperCase())
                        .build());
            }
            if (consultantEmail != null && !consultantEmail.isBlank()) {
                filters.add(WhereFilter.builder()
                        .path("consultantEmail")
                        .operator(Operator.Equal)
                        .valueText(consultantEmail.toLowerCase(java.util.Locale.ROOT))
                        .build());
            }

            WhereFilter combined = filters.size() == 1
                    ? filters.get(0)
                    : WhereFilter.builder().operator(Operator.And).operands(filters.toArray(new WhereFilter[0])).build();

            Field additionalField = Field.builder()
                    .name("_additional")
                    .fields(new Field[]{Field.builder().name("id").build()})
                    .build();

            Result<GraphQLResponse> response = client.graphQL().get()
                    .withClassName(expenseClassName)
                    .withWhere(WhereArgument.builder().filter(combined).build())
                    .withFields(
                            Field.builder().name("expenseId").build(),
                            Field.builder().name("amount").build(),
                            Field.builder().name("currency").build(),
                            Field.builder().name("type").build(),
                            Field.builder().name("km").build(),
                            Field.builder().name("date").build(),
                            Field.builder().name("dateText").build(),
                            Field.builder().name("description").build(),
                            Field.builder().name("originalText").build(),
                            Field.builder().name("paymentMode").build(),
                            Field.builder().name("address").build(),
                            Field.builder().name("company").build(),
                            Field.builder().name("consultantEmail").build(),
                            Field.builder().name("approvalStatus").build(),
                            Field.builder().name("approvalNote").build(),
                            Field.builder().name("absencePeriodsJson").build(),
                            Field.builder().name("duplicateFlag").build(),
                            Field.builder().name("source").build(),
                            additionalField
                    )
                    .withLimit(500)
                    .run();

            if (response.hasErrors()) {
                log.error("Weaviate findExpensesBetween error: {}", response.getError());
            } else {
                List<ExpenseItem> parsed = WeaviateResponseParser.extractExpenseItems(response.getResult(), expenseClassName);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }

            // Fallback string-based if no results (schéma legacy avec date en string)
            log.debug("Weaviate date query returned empty; trying fallback on dateText (string)");

            WhereFilter afterStartText = WhereFilter.builder()
                    .path("dateText")
                    .operator(Operator.GreaterThanEqual)
                    .valueText(start.toString())
                    .build();
            WhereFilter beforeEndText = WhereFilter.builder()
                    .path("dateText")
                    .operator(Operator.LessThan)
                    .valueText(end.plusDays(1).toString())
                    .build();

            List<WhereFilter> filtersText = new ArrayList<>();
            filtersText.add(afterStartText);
            filtersText.add(beforeEndText);
            if (type != null && !type.isBlank()) {
                filtersText.add(WhereFilter.builder()
                        .path("type")
                        .operator(Operator.Equal)
                        .valueText(type)
                        .build());
            }
            if (currency != null && !currency.isBlank()) {
                filtersText.add(WhereFilter.builder()
                        .path("currency")
                        .operator(Operator.Equal)
                        .valueText(currency.toUpperCase())
                        .build());
            }
            if (consultantEmail != null && !consultantEmail.isBlank()) {
                filtersText.add(WhereFilter.builder()
                        .path("consultantEmail")
                        .operator(Operator.Equal)
                        .valueText(consultantEmail.toLowerCase(java.util.Locale.ROOT))
                        .build());
            }

            WhereFilter combinedText = filtersText.size() == 1
                    ? filtersText.get(0)
                    : WhereFilter.builder().operator(Operator.And).operands(filtersText.toArray(new WhereFilter[0])).build();

            Result<GraphQLResponse> responseText = client.graphQL().get()
                    .withClassName(expenseClassName)
                    .withWhere(WhereArgument.builder().filter(combinedText).build())
                    .withFields(
                            Field.builder().name("expenseId").build(),
                            Field.builder().name("amount").build(),
                            Field.builder().name("currency").build(),
                            Field.builder().name("type").build(),
                            Field.builder().name("km").build(),
                            Field.builder().name("date").build(),
                            Field.builder().name("dateText").build(),
                            Field.builder().name("description").build(),
                            Field.builder().name("originalText").build(),
                            Field.builder().name("duplicateFlag").build(),
                            Field.builder().name("paymentMode").build(),
                            Field.builder().name("address").build(),
                            Field.builder().name("company").build(),
                            Field.builder().name("consultantEmail").build(),
                            Field.builder().name("approvalStatus").build(),
                            Field.builder().name("approvalNote").build(),
                            Field.builder().name("absencePeriodsJson").build(),
                            Field.builder().name("source").build(),
                            additionalField
                    )
                    .withLimit(500)
                    .run();

            if (responseText.hasErrors()) {
                log.error("Fallback dateText error: {}", responseText.getError());
                return List.of();
            }

            return WeaviateResponseParser.extractExpenseItems(responseText.getResult(), expenseClassName);

        } catch (Exception e) {
            log.error("Exception findExpensesBetween: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public int findMaxExpenseId(YearMonth ym) {
        try {
            String startIso = formatRfc3339(ym.atDay(1));
            String endIso = formatRfc3339(ym.plusMonths(1).atDay(1));

            WhereFilter afterStart = WhereFilter.builder()
                    .path("date")
                    .operator(Operator.GreaterThanEqual)
                    .valueDate(toDate(startIso))
                    .build();

            WhereFilter beforeEnd = WhereFilter.builder()
                    .path("date")
                    .operator(Operator.LessThan)
                    .valueDate(toDate(endIso))
                    .build();

            WhereFilter combined = WhereFilter.builder()
                    .operator(Operator.And)
                    .operands(new WhereFilter[]{afterStart, beforeEnd})
                    .build();

            Result<GraphQLResponse> response = client.graphQL().get()
                    .withClassName(expenseClassName)
                    .withWhere(WhereArgument.builder().filter(combined).build())
                    .withFields(Field.builder().name("expenseId").build())
                    .withLimit(500)
                    .run();

            if (response.hasErrors()) {
                log.warn("findMaxExpenseId erreur: {}", response.getError());
                return 0;
            }
            List<ExpenseItem> items = WeaviateResponseParser.extractExpenseItems(response.getResult(), expenseClassName);
            return items.stream()
                    .filter(e -> e.getId() != null)
                    .mapToInt(ExpenseItem::getId)
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            log.warn("findMaxExpenseId exception: {}", e.getMessage());
            return 0;
        }
    }

    public int deleteExpensesByDate(LocalDate date) {
        return deleteExpensesByDate(date, null);
    }

    public int deleteExpensesByDate(LocalDate date, String company) {
        if (date == null) return 0;
        try {
            int deleted = deleteExpensesByDateSafely(date, company);
            if (deleted == 0 && company != null) {
                log.warn("deleteExpensesByDate aucun résultat avec company='{}', tentative sans filtre company (legacy)", company);
                deleted = deleteExpensesByDateSafely(date, null);
            }
            return deleted;
        } catch (Exception e) {
            log.error("Exception deleteExpensesByDate: {}", e.getMessage(), e);
            return -1;
        }
    }

    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym) {
        return deleteExpenseByIdAndMonth(expenseId, ym, null);
    }

    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym, String company) {
        try {
            int deleted = deleteExpenseByIdAndMonthSafely(expenseId, ym, company);
            if (deleted == 0 && company != null) {
                log.warn("deleteExpenseByIdAndMonth aucun résultat avec company='{}', tentative sans filtre company (legacy)", company);
                deleted = deleteExpenseByIdAndMonthSafely(expenseId, ym, null);
            }
            return deleted;
        } catch (Exception e) {
            log.error("deleteExpenseByIdAndMonth exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    public int deleteExpenseById(int expenseId) {
        return deleteExpenseById(expenseId, null);
    }

    public int deleteExpenseById(int expenseId, String company) {
        try {
            int deleted = deleteExpenseByIdSafely(expenseId, company);
            if (deleted == 0 && company != null) {
                log.warn("deleteExpenseById aucun résultat avec company='{}', tentative sans filtre company (legacy)", company);
                deleted = deleteExpenseByIdSafely(expenseId, null);
            }
            return deleted;
        } catch (Exception e) {
            log.error("deleteExpenseById exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    public void updateExpenseApproval(String weaviateId, String status, String note) {
        try {
            Map<String, Object> props = new HashMap<>();
            props.put("approvalStatus", status);
            if (note != null) {
                props.put("approvalNote", note);
            }
            var result = client.data().updater()
                    .withClassName(expenseClassName)
                    .withID(weaviateId)
                    .withProperties(props)
                    .withMerge()
                    .run();
            if (result.hasErrors()) {
                log.error("updateExpenseApproval error for id={}: {}", weaviateId, result.getError());
                throw new RuntimeException("updateExpenseApproval failed: " + result.getError());
            }
            log.info("Expense approval updated: id={}, status={}", weaviateId, status);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("updateExpenseApproval exception: {}", e.getMessage(), e);
            throw new RuntimeException("updateExpenseApproval exception: " + e.getMessage(), e);
        }
    }

    public List<ExpenseItem.AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        try {
            if (isNullOrBlank(month)) return List.of();

            var response = client.data().objectsGetter()
                    .withClassName(expenseClassName)
                    .withLimit(1000)
                    .run();

            if (response.hasErrors() || response.getResult() == null) {
                log.error("findKmExpenseAbsences fetch error: {}", response.getError());
                return List.of();
            }

            List<ExpenseItem.AbsencePeriod> result = new ArrayList<>();
            for (var obj : response.getResult()) {
                if (obj == null || obj.getProperties() == null) continue;
                Map<String, Object> props = obj.getProperties();

                String type = safeString(props.get("type")).toLowerCase(java.util.Locale.ROOT);
                if (!type.contains("km") && !type.contains("kilom")) continue;

                if (!isNullOrBlank(company)) {
                    String c = safeString(props.get("company"));
                    if (!c.trim().equalsIgnoreCase(company.trim())) continue;
                }

                String dateStr = safeString(props.get("dateText"));
                if (dateStr.isBlank()) dateStr = safeString(props.get("date"));
                if (!dateStr.startsWith(month)) continue;

                String absenceJson = safeString(props.get("absencePeriodsJson"));
                if (!absenceJson.isBlank()) {
                    try {
                        List<ExpenseItem.AbsencePeriod> periods = objectMapper.readValue(absenceJson,
                                objectMapper.getTypeFactory().constructCollectionType(
                                        List.class, ExpenseItem.AbsencePeriod.class));
                        result.addAll(periods);
                    } catch (Exception e) {
                        log.warn("findKmExpenseAbsences: could not parse absencePeriodsJson: {}", e.getMessage());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("findKmExpenseAbsences exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String buildExpenseText(ExpenseItem item, String source, boolean duplicate) {
        return "date=%s | amount=%s %s | type=%s | paymentMode=%s | address=%s | desc=%s | source=%s | duplicate=%s | original=%s"
                .formatted(
                        item.getDate(),
                        item.getAmount(),
                        item.getCurrency(),
                        item.getType(),
                        item.getPaymentMode(),
                        item.getAddress(),
                        item.getDescription(),
                        source,
                        duplicate,
                        item.getOriginalText()
                );
    }

    private int deleteExpenseByIdAndMonthSafely(int expenseId, YearMonth ym, String company) {
        try {
            int deleted = deleteMatchingExpenses(props ->
                    matchesExpenseId(props.get("expenseId"), expenseId)
                            && matchesCompany(props.get("company"), company)
                            && matchesMonth(props, ym)
            , "expenseId=%s month=%s company=%s".formatted(expenseId, ym, company));
            log.info("Weaviate safe delete by expenseId/month -> expenseId={}, month={}, company={}, deleted={}",
                    expenseId, ym, company, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("deleteExpenseByIdAndMonthSafely exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    private int deleteExpenseByIdSafely(int expenseId, String company) {
        return deleteMatchingExpenses(props ->
                matchesExpenseId(props.get("expenseId"), expenseId)
                        && matchesCompany(props.get("company"), company)
        , "expenseId=%s company=%s".formatted(expenseId, company));
    }

    private int deleteExpensesByDateSafely(LocalDate date, String company) {
        return deleteMatchingExpenses(props ->
                matchesCompany(props.get("company"), company)
                        && matchesDay(props, date)
        , "date=%s company=%s".formatted(date, company));
    }

    private int deleteMatchingExpenses(Predicate<Map<String, Object>> predicate, String debugContext) {
        try {
            var response = client.data().objectsGetter()
                    .withClassName(expenseClassName)
                    .withLimit(1000)
                    .run();

            if (response.hasErrors() || response.getResult() == null) {
                log.error("deleteMatchingExpenses fetch error: {}", response.getError());
                return -1;
            }

            int deleted = 0;
            for (var object : response.getResult()) {
                if (object == null || object.getProperties() == null) {
                    continue;
                }
                Map<String, Object> props = object.getProperties();
                log.debug("deleteMatchingExpenses candidate [{}] -> weaviateId={}, expenseId={}, company={}, date={}, dateText={}",
                        debugContext,
                        object.getId(),
                        props.get("expenseId"),
                        props.get("company"),
                        props.get("date"),
                        props.get("dateText"));
                if (!predicate.test(props)) {
                    continue;
                }

                var deleteResult = client.data().deleter()
                        .withClassName(expenseClassName)
                        .withID(object.getId())
                        .run();
                if (deleteResult.hasErrors() || !Boolean.TRUE.equals(deleteResult.getResult())) {
                    log.error("Weaviate single delete error for id={}: {}", object.getId(), deleteResult.getError());
                } else {
                    deleted++;
                }
            }
            log.info("deleteMatchingExpenses summary [{}] -> deleted={}", debugContext, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("deleteMatchingExpenses exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    private boolean matchesExpenseId(Object rawExpenseId, int expected) {
        if (rawExpenseId == null) {
            return false;
        }
        try {
            if (rawExpenseId instanceof Number n) {
                return n.intValue() == expected;
            }
            return Integer.parseInt(rawExpenseId.toString()) == expected;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesCompany(Object rawCompany, String expectedCompany) {
        if (expectedCompany == null || expectedCompany.isBlank()) {
            return true;
        }
        if (rawCompany == null) {
            return false;
        }
        return rawCompany.toString().trim().equalsIgnoreCase(expectedCompany.trim());
    }

    private boolean matchesMonth(Map<String, Object> props, YearMonth ym) {
        YearMonth parsed = extractYearMonth(props.get("date"));
        if (parsed == null) {
            parsed = extractYearMonth(props.get("dateText"));
        }
        return ym.equals(parsed);
    }

    private boolean matchesDay(Map<String, Object> props, LocalDate day) {
        LocalDate parsed = extractLocalDate(props.get("date"));
        if (parsed == null) {
            parsed = extractLocalDate(props.get("dateText"));
        }
        return day.equals(parsed);
    }
}
