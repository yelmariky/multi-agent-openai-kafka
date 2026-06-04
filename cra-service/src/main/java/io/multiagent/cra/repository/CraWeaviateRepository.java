package io.multiagent.cra.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.cra.model.AbsencePeriod;
import io.multiagent.cra.model.CraDayEntry;
import io.multiagent.cra.model.CraRequest;
import io.weaviate.client.WeaviateClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.multiagent.cra.weaviate.WeaviateUtils.isNullOrBlank;
import static io.multiagent.cra.weaviate.WeaviateUtils.safeString;

@Slf4j
@Repository
public class CraWeaviateRepository {

    private final WeaviateClient client;
    private final ObjectMapper objectMapper;
    private final String craClassName;

    public CraWeaviateRepository(
            WeaviateClient client,
            ObjectMapper objectMapper,
            @Value("${weaviate.class.cra:CRA}") String craClassName) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.craClassName = craClassName;
    }

    /**
     * Upsert a CRA in Weaviate. If cra.id() is non-null, deletes the existing object first.
     * Returns the Weaviate UUID of the saved object.
     */
    public String indexCra(CraRequest cra) {
        try {
            String entriesJson = "[]";
            if (cra.entries() != null && !cra.entries().isEmpty()) {
                try {
                    entriesJson = objectMapper.writeValueAsString(cra.entries());
                } catch (Exception e) {
                    log.warn("indexCra: could not serialize entries: {}", e.getMessage());
                }
            }

            String uuid = cra.id();
            if (uuid != null && !uuid.isBlank()) {
                client.data().deleter().withClassName(craClassName).withID(uuid).run();
            } else {
                uuid = java.util.UUID.randomUUID().toString();
            }

            Map<String, Object> props = new HashMap<>();
            props.put("consultant",    safeString(cra.consultant()));
            props.put("company",       safeString(cra.company()));
            props.put("clientCompany", safeString(cra.clientCompany()));
            props.put("billingMonth",  safeString(cra.billingMonth()));
            props.put("entriesJson",   entriesJson);
            props.put("totalDays",     cra.totalDays());
            props.put("status",        safeString(cra.status()));
            props.put("submittedAt",   cra.submittedAt()   != null ? cra.submittedAt()   : "");
            props.put("validatedAt",   cra.validatedAt()   != null ? cra.validatedAt()   : "");
            props.put("validatedBy",   cra.validatedBy()   != null ? cra.validatedBy()   : "");
            props.put("refusedReason", cra.refusedReason() != null ? cra.refusedReason() : "");

            var result = client.data().creator()
                    .withClassName(craClassName)
                    .withProperties(props)
                    .withID(uuid)
                    .run();

            if (result.hasErrors()) {
                log.error("indexCra error: {}", result.getError());
                throw new RuntimeException("indexCra failed: " + result.getError());
            }
            log.info("Weaviate: CRA upserted (uuid={}, consultant={}, month={})", uuid, cra.consultant(), cra.billingMonth());
            return uuid;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("indexCra exception: {}", e.getMessage(), e);
            throw new RuntimeException("indexCra exception: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> findCrasByPeriod(String start, String end, String consultant, String company) {
        try {
            var response = client.data().objectsGetter()
                    .withClassName(craClassName)
                    .withLimit(1000)
                    .run();

            if (response.hasErrors() || response.getResult() == null) {
                log.error("findCrasByPeriod fetch error: {}", response.getError());
                return List.of();
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (var obj : response.getResult()) {
                if (obj == null || obj.getProperties() == null) continue;
                Map<String, Object> props = obj.getProperties();

                String bm = safeString(props.get("billingMonth"));
                if (!isNullOrBlank(start) && bm.compareTo(start) < 0) continue;
                if (!isNullOrBlank(end)   && bm.compareTo(end)   > 0) continue;

                if (!isNullOrBlank(company)) {
                    String c = safeString(props.get("company"));
                    if (!c.trim().equalsIgnoreCase(company.trim())) continue;
                }
                if (!isNullOrBlank(consultant)) {
                    String name = safeString(props.get("consultant")).toLowerCase(Locale.ROOT);
                    if (!name.contains(consultant.toLowerCase(Locale.ROOT))) continue;
                }

                List<CraDayEntry> entries = List.of();
                String entriesJson = safeString(props.get("entriesJson"));
                if (!entriesJson.isBlank()) {
                    try {
                        entries = objectMapper.readValue(entriesJson,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, CraDayEntry.class));
                    } catch (Exception e) {
                        log.warn("findCrasByPeriod: could not parse entriesJson: {}", e.getMessage());
                    }
                }

                double storedTotal = props.get("totalDays") instanceof Number n ? n.doubleValue() : 0.0;
                if (storedTotal <= 0.0 && !entries.isEmpty()) {
                    storedTotal = entries.stream()
                            .filter(e -> e != null && e.value() > 0)
                            .mapToDouble(CraDayEntry::value)
                            .sum();
                }

                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id",            obj.getId());
                row.put("consultant",    safeString(props.get("consultant")));
                row.put("company",       safeString(props.get("company")));
                row.put("clientCompany", safeString(props.get("clientCompany")));
                row.put("billingMonth",  bm);
                row.put("entries",       entries);
                row.put("entriesJson",   entriesJson);
                row.put("totalDays",     storedTotal);
                row.put("status",        safeString(props.get("status")));
                row.put("submittedAt",   safeString(props.get("submittedAt")));
                row.put("validatedAt",   safeString(props.get("validatedAt")));
                row.put("validatedBy",   safeString(props.get("validatedBy")));
                row.put("refusedReason", safeString(props.get("refusedReason")));
                result.add(row);
            }
            result.sort(Comparator.comparing(m -> safeString(m.get("billingMonth")), String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (Exception e) {
            log.error("findCrasByPeriod exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Returns absence periods derived from CRA entries of type ABSENT for a given consultant/company/month.
     */
    public List<AbsencePeriod> findCraAbsentDays(String consultant, String company, String month) {
        try {
            if (isNullOrBlank(consultant) || isNullOrBlank(month)) return List.of();

            List<Map<String, Object>> cras = findCrasByPeriod(month, month, consultant, company);
            List<AbsencePeriod> result = new ArrayList<>();
            for (Map<String, Object> cra : cras) {
                Object entriesObj = cra.get("entries");
                if (!(entriesObj instanceof List<?> entriesList)) continue;
                for (Object e : entriesList) {
                    if (!(e instanceof CraDayEntry entry)) continue;
                    if ("ABSENT".equalsIgnoreCase(entry.type())) {
                        String d = entry.date();
                        if (d != null && !d.isBlank()) {
                            result.add(new AbsencePeriod(d, d));
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("findCraAbsentDays exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Returns absence periods from mileage (km) expenses for a given company/month.
     * Reads absencePeriodsJson stored in Expense objects in Weaviate.
     */
    public List<AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        try {
            if (isNullOrBlank(company) || isNullOrBlank(month)) return List.of();
            var response = client.data().objectsGetter()
                    .withClassName("Expense")
                    .withLimit(500)
                    .run();
            if (response.hasErrors() || response.getResult() == null) return List.of();
            List<AbsencePeriod> result = new ArrayList<>();
            for (var obj : response.getResult()) {
                if (obj == null || obj.getProperties() == null) continue;
                Map<String, Object> props = obj.getProperties();
                String expCompany = safeString(props.get("company"));
                if (!expCompany.trim().equalsIgnoreCase(company.trim())) continue;
                String type = safeString(props.get("type"));
                if (!"frais_km".equalsIgnoreCase(type)) continue;
                String absJson = safeString(props.get("absencePeriodsJson"));
                if (absJson.isBlank() || "[]".equals(absJson)) continue;
                try {
                    ObjectMapper om = new ObjectMapper();
                    JsonNode arr = om.readTree(absJson);
                    if (arr.isArray()) {
                        for (JsonNode n : arr) {
                            String from = n.path("from").asText("");
                            String to = n.path("to").asText("");
                            if (!from.isBlank() && !to.isBlank()) {
                                result.add(new AbsencePeriod(from, to));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("findKmExpenseAbsences: parse error: {}", e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            log.error("findKmExpenseAbsences exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public void deleteCra(String id) {
        try {
            if (isNullOrBlank(id)) {
                log.warn("deleteCra: id is blank, skipping");
                return;
            }
            var result = client.data().deleter()
                    .withClassName(craClassName)
                    .withID(id)
                    .run();
            if (result.hasErrors()) {
                log.error("deleteCra error for id={}: {}", id, result.getError());
            } else {
                log.info("Weaviate: CRA deleted (id={})", id);
            }
        } catch (Exception e) {
            log.error("deleteCra exception: {}", e.getMessage(), e);
        }
    }
}
