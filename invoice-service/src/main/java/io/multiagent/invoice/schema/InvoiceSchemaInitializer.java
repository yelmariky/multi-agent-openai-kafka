package io.multiagent.invoice.schema;

import io.weaviate.client.WeaviateClient;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.Schema;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Initializes the Weaviate Invoice class at startup.
 * Extracted from ai-core WeaviateService — same properties (24 props).
 * Uses retry logic (max 6 attempts, 10s backoff) to handle Weaviate startup delays.
 */
@Slf4j
@Component
public class InvoiceSchemaInitializer implements ApplicationRunner {

    private final WeaviateClient client;
    private final String invoiceClassName;
    private final int maxAttempts;
    private final long backoffMs;

    public InvoiceSchemaInitializer(
            WeaviateClient client,
            @Value("${weaviate.invoice-class:Invoice}") String invoiceClassName,
            @Value("${weaviate.schema-init.max-attempts:6}") int maxAttempts,
            @Value("${weaviate.schema-init.backoff-ms:10000}") long backoffMs) {
        this.client = client;
        this.invoiceClassName = invoiceClassName;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(1000, backoffMs);
    }

    @Override
    public void run(ApplicationArguments args) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                synchronizeInvoiceSchema();
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.error("Erreur InvoiceSchemaInitializer après {} tentatives: {}", attempt, e.getMessage(), e);
                } else {
                    log.warn("Weaviate indisponible (tentative {}/{}): {}. Nouvelle tentative dans {}ms...",
                            attempt, maxAttempts, e.getMessage(), backoffMs);
                    sleepQuietly(backoffMs);
                }
            }
        }
    }

    private void synchronizeInvoiceSchema() {
        var schemaResult = client.schema().getter().run();
        if (schemaResult.hasErrors()) {
            throw new IllegalStateException("Weaviate schema getter error: " + schemaResult.getError());
        }

        Schema schema = schemaResult.getResult();

        boolean invoiceExists = schema != null
                && schema.getClasses() != null
                && schema.getClasses().stream().anyMatch(c -> invoiceClassName.equals(c.getClassName()));

        if (!invoiceExists) {
            log.info("Weaviate: creation de la classe '{}'", invoiceClassName);
            WeaviateClass invoice = WeaviateClass.builder()
                    .className(invoiceClassName)
                    .description("Factures simples générées")
                    .vectorizer("none")
                    .properties(List.of(
                            Property.builder().name("invoiceName").dataType(List.of("string")).description("Numéro de facture").build(),
                            Property.builder().name("invoiceDate").dataType(List.of("date")).description("Date de facture").build(),
                            Property.builder().name("billingMonth").dataType(List.of("string")).description("Mois de facturation YYYY-MM").build(),
                            Property.builder().name("sellerCompanyName").dataType(List.of("string")).description("Société émettrice").build(),
                            Property.builder().name("sellerAddress").dataType(List.of("text")).description("Adresse émetteur").build(),
                            Property.builder().name("sellerRcs").dataType(List.of("string")).description("RCS émetteur").build(),
                            Property.builder().name("clientCompanyName").dataType(List.of("string")).description("Société cliente").build(),
                            Property.builder().name("clientAddress").dataType(List.of("text")).description("Adresse client").build(),
                            Property.builder().name("clientRcs").dataType(List.of("string")).description("RCS client").build(),
                            Property.builder().name("invoiceTitle").dataType(List.of("string")).description("Intitulé de facture").build(),
                            Property.builder().name("daysCount").dataType(List.of("int")).description("Nombre de jours").build(),
                            Property.builder().name("daysExact").dataType(List.of("number")).description("Nombre de jours exact (supporte les demi-journées, ex. 12.5)").build(),
                            Property.builder().name("unitPriceHt").dataType(List.of("number")).description("Prix HT par jour").build(),
                            Property.builder().name("totalHt").dataType(List.of("number")).description("Total HT").build(),
                            Property.builder().name("vatRate").dataType(List.of("number")).description("Taux de TVA").build(),
                            Property.builder().name("totalTtc").dataType(List.of("number")).description("Total TTC").build(),
                            Property.builder().name("currency").dataType(List.of("string")).description("Devise").build(),
                            Property.builder().name("paymentDueDate").dataType(List.of("date")).description("Date d'échéance").build(),
                            Property.builder().name("latePaymentClause").dataType(List.of("text")).description("Clause pénalités de retard").build(),
                            Property.builder().name("notes").dataType(List.of("text")).description("Notes").build(),
                            Property.builder().name("sourceText").dataType(List.of("text")).description("Texte source").build(),
                            Property.builder().name("pdfPath").dataType(List.of("string")).description("Chemin PDF").build(),
                            Property.builder().name("excelPath").dataType(List.of("string")).description("Chemin Excel").build(),
                            Property.builder().name("consultantEmail").dataType(List.of("string")).description("Email du consultant").build(),
                            Property.builder().name("text").dataType(List.of("text")).description("Texte concaténé pour embedding").build()
                    ))
                    .build();
            var result = client.schema().classCreator().withClass(invoice).run();
            if (result.hasErrors()) {
                throw new IllegalStateException("Weaviate invoice class creation error: " + result.getError());
            }
            log.info("Weaviate: classe '{}' créée avec succès", invoiceClassName);
        } else {
            log.info("Weaviate: classe '{}' déjà présente", invoiceClassName);
            ensureInvoiceProperties(schema, invoiceClassName);
        }
    }

    private void ensureInvoiceProperties(Schema schema, String targetClass) {
        if (schema == null || schema.getClasses() == null) return;
        WeaviateClass invoice = schema.getClasses().stream()
                .filter(c -> targetClass.equals(c.getClassName())).findFirst().orElse(null);
        if (invoice == null || invoice.getProperties() == null) return;

        var existing = new ArrayList<String>();
        invoice.getProperties().forEach(p -> existing.add(p.getName()));

        List<Property> desired = List.of(
                Property.builder().name("billingMonth").dataType(List.of("string")).description("Mois de facturation YYYY-MM").build(),
                Property.builder().name("sellerCompanyName").dataType(List.of("string")).description("Société émettrice").build(),
                Property.builder().name("sellerAddress").dataType(List.of("text")).description("Adresse émetteur").build(),
                Property.builder().name("sellerRcs").dataType(List.of("string")).description("RCS émetteur").build(),
                Property.builder().name("clientCompanyName").dataType(List.of("string")).description("Société cliente").build(),
                Property.builder().name("clientAddress").dataType(List.of("text")).description("Adresse client").build(),
                Property.builder().name("clientRcs").dataType(List.of("string")).description("RCS client").build(),
                Property.builder().name("invoiceTitle").dataType(List.of("string")).description("Intitulé de facture").build(),
                Property.builder().name("daysCount").dataType(List.of("int")).description("Nombre de jours").build(),
                Property.builder().name("unitPriceHt").dataType(List.of("number")).description("Prix HT par jour").build(),
                Property.builder().name("totalHt").dataType(List.of("number")).description("Total HT").build(),
                Property.builder().name("vatRate").dataType(List.of("number")).description("Taux de TVA").build(),
                Property.builder().name("totalTtc").dataType(List.of("number")).description("Total TTC").build(),
                Property.builder().name("paymentDueDate").dataType(List.of("date")).description("Date d'échéance").build(),
                Property.builder().name("latePaymentClause").dataType(List.of("text")).description("Clause pénalités de retard").build(),
                Property.builder().name("sourceText").dataType(List.of("text")).description("Texte source").build(),
                Property.builder().name("pdfPath").dataType(List.of("string")).description("Chemin PDF").build(),
                Property.builder().name("excelPath").dataType(List.of("string")).description("Chemin Excel").build(),
                Property.builder().name("text").dataType(List.of("text")).description("Texte concaténé pour embedding").build(),
                Property.builder().name("consultantEmail").dataType(List.of("string")).description("Email du consultant").build(),
                Property.builder().name("daysExact").dataType(List.of("number")).description("Nombre de jours exact (supporte les demi-journées, ex. 12.5)").build()
        );

        for (Property prop : desired) {
            if (!existing.contains(prop.getName())) {
                log.info("Weaviate: ajout propriété '{}' à la classe '{}'", prop.getName(), targetClass);
                var r = client.schema().propertyCreator()
                        .withClassName(targetClass)
                        .withProperty(prop)
                        .run();
                if (r.hasErrors()) {
                    log.warn("Weaviate: erreur ajout propriété '{}': {}", prop.getName(), r.getError());
                }
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
