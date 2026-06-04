package io.multiagent.notefrais.reasoning;

import io.multiagent.notefrais.client.LLMAIClient;
import io.multiagent.notefrais.expense.repository.ExpenseWeaviateRepository;
import io.multiagent.notefrais.model.ExpenseItem;
import io.multiagent.notefrais.util.DateRange;
import io.multiagent.notefrais.util.DateProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    private final LLMAIClient llm;
    private final JdbcTemplate jdbc;
    private final ExpenseWeaviateRepository expenseRepo;
    private final DateProvider dateProvider;

    @Value("${ai-core.openai.embedding-model:text-embedding-3-small}")
    private String embeddingModel;

    public List<String> searchTopK(String query, int k) {
        log.info("SemanticSearchService.searchTopK(query='{}', k={})", query, k);
        List<Double> vector = llm.embed(embeddingModel, query);
        return searchByVector(vector, k);
    }

    public List<String> searchByVector(List<Double> vector, int k) {
        try {
            String vec = "[" + vector.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
            List<String> results = jdbc.queryForList(
                "SELECT content FROM document_chunks ORDER BY embedding <=> ?::vector LIMIT ?",
                String.class, vec, k);
            return results;
        } catch (Exception e) {
            log.error("pgvector searchByVector error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<String> searchExpensesForPeriod(String rewrittenQuery) {
        log.info("Recherche de dépenses pour la période (rewritten) : {}", rewrittenQuery);
        DateRange range = extractDateRange(rewrittenQuery);
        if (range.getDays() > 31) {
            log.warn("Période > 30 jours, limitation automatique appliquée.");
            range = range.limitTo30Days();
        }
        log.info("Recherche entre {} et {}", range.start(), range.end());
        List<ExpenseItem> expenses = expenseRepo.findExpensesBetween(range.start(), range.end());
        return expenses.stream().map(this::formatExpenseDocument).toList();
    }

    public DateRange extractDateRange(String text) {
        Pattern p = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}).*(\\d{4}-\\d{2}-\\d{2})");
        Matcher m = p.matcher(text);
        if (m.find()) {
            LocalDate start = LocalDate.parse(m.group(1));
            LocalDate end = LocalDate.parse(m.group(2));
            return new DateRange(start, end);
        }
        log.warn("Impossible d'extraire une plage de dates → fallback = 7 jours");
        LocalDate end = dateProvider.todayUtc();
        LocalDate start = end.minusDays(7);
        return new DateRange(start, end);
    }

    private String formatExpenseDocument(ExpenseItem item) {
        return """
            amount=%s %s
            type=%s
            date=%s
            description=%s
            text=%s
            """.formatted(item.getAmount(), item.getCurrency(), item.getType(),
                item.getDate(), item.getDescription(), item.getOriginalText());
    }
}
