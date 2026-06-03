package io.multiagent.core.weaviate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.model.ExpenseItem;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Utilitaires pour extraire des champs simples (comme "text")
 * à partir d'une réponse GraphQL Weaviate.
 *
 * Structure visée :
 * {
 *   "data": {
 *     "Get": {
 *       "Expense": [
 *         { "text": "..." },
 *         { "text": "..." }
 *       ]
 *     }
 *   }
 * }
 */
@Slf4j
public final class WeaviateResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ExpenseItem.AbsencePeriod>> ABSENCE_TYPE =
            new TypeReference<>() {};

    private WeaviateResponseParser() {
        // util class
    }

    /**
     * Extrait la liste des champs "text" d'une classe donnée dans la réponse GraphQL.
     *
     * @param response  réponse GraphQL Weaviate
     * @param className nom de la classe (ex: "Expense", "DocumentChunk", etc.)
     * @return liste des textes trouvés
     */
    public static List<String> extractTexts(GraphQLResponse response, String className) {
        if (response == null || response.getData() == null) {
            return List.of();
        }

        Object dataObj = response.getData();
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            return List.of();
        }

        Object getObj = dataMap.get("Get");
        if (!(getObj instanceof Map<?, ?> getMap)) {
            return List.of();
        }

        Object classObj = getMap.get(className);
        if (!(classObj instanceof List<?> list)) {
            return List.of();
        }

        List<String> results = new ArrayList<>();

        for (Object o : list) {
            if (o instanceof Map<?, ?> objMap) {
                Object textVal = objMap.get("text");
                if (textVal != null) {
                    results.add(textVal.toString());
                }
            }
        }

        return results;
    }

    public static List<ExpenseItem> extractExpenseItems(GraphQLResponse response, String className) {
        if (response == null || response.getData() == null) {
            return List.of();
        }
        if (!(response.getData() instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        if (!(dataMap.get("Get") instanceof Map<?, ?> getMap)) {
            return List.of();
        }
        if (!(getMap.get(className) instanceof List<?> list)) {
            return List.of();
        }

        List<ExpenseItem> result = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> map) {
                ExpenseItem item = mapToExpenseItem(map);
                log.info("extractExpenseItems: {}", item);
                result.add(item);
            }
        }
        return result;
    }

    private static ExpenseItem mapToExpenseItem(Map<?, ?> map) {
        ExpenseItem item = new ExpenseItem();

        // Weaviate UUID from _additional { id }
        Object additional = map.get("_additional");
        if (additional instanceof Map<?, ?> addMap) {
            Object uuid = addMap.get("id");
            if (uuid != null) {
                item.setWeaviateId(uuid.toString());
            }
        }

        Object id = map.get("expenseId") != null ? map.get("expenseId") : map.get("id");
        if (id != null) {
            try {
                item.setId((int) Math.round(Double.parseDouble(id.toString())));
            } catch (Exception ignored) {
                item.setId(0);
            }
        } else {
            item.setId(0);
        }

        Object amount = map.get("amount");
        if (amount != null) {
            try { item.setAmount(Double.parseDouble(amount.toString())); } catch (Exception ignored) { /* malformed number — skip */ }
        }
        Object km = map.get("km");
        if (km != null) {
            try { item.setKm(Double.parseDouble(km.toString())); } catch (Exception ignored) { /* malformed number — skip */ }
        }
        setStringFields(item, map);

        Object absenceJson = map.get("absencePeriodsJson");
        if (absenceJson != null && !absenceJson.toString().isBlank()) {
            try {
                item.setAbsencePeriods(MAPPER.readValue(absenceJson.toString(), ABSENCE_TYPE));
            } catch (Exception e) {
                log.warn("⚠️ Could not parse absencePeriodsJson: {}", e.getMessage());
            }
        }
        return item;
    }

    private static void setStringFields(ExpenseItem item, Map<?, ?> map) {
        Object currency = map.get("currency");
        if (currency != null) { item.setCurrency(currency.toString()); }

        Object type = map.get("type");
        if (type != null) { item.setType(type.toString()); }

        Object date = map.get("date");
        if (date != null) { item.setDate(date.toString()); }
        Object dateText = map.get("dateText");
        if (item.getDate() == null && dateText != null) { item.setDate(dateText.toString()); }

        Object description = map.get("description");
        if (description != null) { item.setDescription(description.toString()); }

        Object originalText = map.get("originalText");
        if (originalText != null) { item.setOriginalText(originalText.toString()); }

        Object paymentMode = map.get("paymentMode");
        if (paymentMode != null) { item.setPaymentMode(paymentMode.toString()); }

        Object address = map.get("address");
        if (address != null) { item.setAddress(address.toString()); }

        Object company = map.get("company");
        if (company != null) { item.setCompany(company.toString()); }

        Object consultantEmail = map.get("consultantEmail");
        if (consultantEmail != null) { item.setConsultantEmail(consultantEmail.toString()); }

        Object approvalStatus = map.get("approvalStatus");
        if (approvalStatus != null) { item.setApprovalStatus(approvalStatus.toString()); }

        Object approvalNote = map.get("approvalNote");
        if (approvalNote != null) { item.setApprovalNote(approvalNote.toString()); }
    }
}
