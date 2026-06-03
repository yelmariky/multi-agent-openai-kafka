package io.multiagent.core.service;

import io.multiagent.core.client.LLMAIClient;
import io.multiagent.core.model.ConsultantProfile;
import io.multiagent.core.model.CraRequest;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.model.SellerProfile;
import io.multiagent.core.cra.repository.CraWeaviateRepository;
import io.multiagent.core.expense.repository.ExpenseWeaviateRepository;
import io.multiagent.core.settings.repository.SettingsWeaviateRepository;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.Schema;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static io.multiagent.core.weaviate.WeaviateUtils.toFloatArray;

/**
 * WeaviateService — façade délégant aux repositories par domaine.
 *
 * Responsabilité résiduelle :
 * - Initialisation du schéma Weaviate au démarrage (cross-cutting)
 * - Recherche vectorielle sur DocumentChunk
 * - Indexation de chunks documentaires
 */
@Slf4j
@Service
public class WeaviateService {

    private final WeaviateClient client;
    private final LLMAIClient llm;
    private final String className;
    private final String expenseClassName;
    private final String sellerProfileClassName;
    private final String consultantProfileClassName;
    private final String craClassName;
    private final int schemaInitMaxAttempts;
    private final Duration schemaInitBackoff;

    private final ExpenseWeaviateRepository expenseRepo;
    private final CraWeaviateRepository craRepo;
    private final SettingsWeaviateRepository settingsRepo;

    public WeaviateService(
            WeaviateClient client,
            LLMAIClient llm,
            @Value("${weaviate.class-name:DocumentChunk}") String className,
            @Value("${weaviate.expense-class:Expense}") String expenseClassName,
            @Value("${weaviate.seller-profile-class:SellerProfile}") String sellerProfileClassName,
            @Value("${weaviate.consultant-profile-class:ConsultantProfile}") String consultantProfileClassName,
            @Value("${weaviate.schema-init.max-attempts:6}") int schemaInitMaxAttempts,
            @Value("${weaviate.schema-init.backoff:10s}") Duration schemaInitBackoff,
            @Value("${weaviate.class.cra:CRA}") String craClassName,
            ExpenseWeaviateRepository expenseRepo,
            CraWeaviateRepository craRepo,
            SettingsWeaviateRepository settingsRepo) {
        this.client = client;
        this.llm = llm;
        this.className = className;
        this.expenseClassName = expenseClassName;
        this.sellerProfileClassName = sellerProfileClassName;
        this.consultantProfileClassName = consultantProfileClassName;
        this.craClassName = craClassName;
        this.schemaInitMaxAttempts = Math.max(1, schemaInitMaxAttempts);
        this.schemaInitBackoff = schemaInitBackoff == null ? Duration.ofSeconds(10) : schemaInitBackoff;
        this.expenseRepo = expenseRepo;
        this.craRepo = craRepo;
        this.settingsRepo = settingsRepo;
    }

    // -----------------------------------------------------------------------
    // Schema initialisation (cross-cutting — stays here)
    // -----------------------------------------------------------------------

    @PostConstruct
    public void initSchema() {
        for (int attempt = 1; attempt <= schemaInitMaxAttempts; attempt++) {
            try {
                synchronizeSchema();
                return;
            } catch (Exception e) {
                if (attempt == schemaInitMaxAttempts) {
                    log.error("❌ Erreur initSchema Weaviate après {} tentatives: {}", attempt, e.getMessage(), e);
                } else {
                    log.warn("⏳ Weaviate indisponible (tentative {}/{}): {}. Nouvelle tentative dans {}...",
                            attempt, schemaInitMaxAttempts, e.getMessage(), schemaInitBackoff);
                    waitBeforeRetry();
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // DocumentChunk — indexation + recherche vectorielle (stays here)
    // -----------------------------------------------------------------------

    public void indexChunk(String text, String source) {
        indexChunk(null, text, source);
    }

    public void indexChunk(String id, String text, String source) {
        float[] embedding = llm.embed(text);
        Float[] vector = toFloatArray(embedding);
        persistChunk(id, text, source, vector);
    }

    public void indexChunk(String id, String text, List<Double> vector) {
        persistChunk(id, text, null, toFloatArray(vector));
    }

    public List<String> searchByVector(List<Double> vector, int k) {
        try {
            Float[] weaviateVector = toFloatArray(vector);
            NearVectorArgument nearVector = NearVectorArgument.builder()
                    .vector(weaviateVector)
                    .build();

            Result<GraphQLResponse> response = client.graphQL().get()
                    .withClassName(className)
                    .withFields(Field.builder().name("text").build())
                    .withNearVector(nearVector)
                    .withLimit(k)
                    .run();

            if (response.hasErrors()) {
                log.error("❌ Weaviate search error: {}", response.getError());
                return List.of();
            }

            GraphQLResponse<?> gql = response.getResult();
            if (gql == null || gql.getData() == null) {
                return List.of();
            }

            Object get = gql.getData();
            if (!(get instanceof Map<?, ?> getMap)) {
                return List.of();
            }

            Object raw = getMap.get(className);
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }

            List<String> results = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> obj) {
                    Object text2 = obj.get("text");
                    if (text2 != null) {
                        results.add(text2.toString());
                    }
                }
            }
            return results;
        } catch (Exception e) {
            log.error("❌ Weaviate searchByVector error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Expense — delegation
    // -----------------------------------------------------------------------

    public void indexExpense(String id, ExpenseItem item, String source, boolean duplicate, String hash) {
        expenseRepo.indexExpense(id, item, source, duplicate, hash);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end) {
        return expenseRepo.findExpensesBetween(start, end);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency) {
        return expenseRepo.findExpensesBetween(start, end, type, currency);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency, String consultantEmail) {
        return expenseRepo.findExpensesBetween(start, end, type, currency, consultantEmail);
    }

    public int findMaxExpenseId(YearMonth ym) {
        return expenseRepo.findMaxExpenseId(ym);
    }

    public int deleteExpensesByDate(LocalDate date) {
        return expenseRepo.deleteExpensesByDate(date);
    }

    public int deleteExpensesByDate(LocalDate date, String company) {
        return expenseRepo.deleteExpensesByDate(date, company);
    }

    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym) {
        return expenseRepo.deleteExpenseByIdAndMonth(expenseId, ym);
    }

    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym, String company) {
        return expenseRepo.deleteExpenseByIdAndMonth(expenseId, ym, company);
    }

    public int deleteExpenseById(int expenseId) {
        return expenseRepo.deleteExpenseById(expenseId);
    }

    public int deleteExpenseById(int expenseId, String company) {
        return expenseRepo.deleteExpenseById(expenseId, company);
    }

    public void updateExpenseApproval(String weaviateId, String status, String note) {
        expenseRepo.updateExpenseApproval(weaviateId, status, note);
    }

    public List<ExpenseItem.AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        return expenseRepo.findKmExpenseAbsences(company, month);
    }

    // -----------------------------------------------------------------------
    // CRA — delegation
    // -----------------------------------------------------------------------

    public String indexCra(CraRequest cra) {
        return craRepo.indexCra(cra);
    }

    public List<Map<String, Object>> findCrasByPeriod(String start, String end, String consultant, String company) {
        return craRepo.findCrasByPeriod(start, end, consultant, company);
    }

    public List<ExpenseItem.AbsencePeriod> findCraAbsentDays(String consultant, String company, String month) {
        return craRepo.findCraAbsentDays(consultant, company, month);
    }

    public void deleteCra(String id) {
        craRepo.deleteCra(id);
    }

    // -----------------------------------------------------------------------
    // Settings (SellerProfile / ConsultantProfile) — delegation
    // -----------------------------------------------------------------------

    public SellerProfile findSellerProfile(String companyName) {
        return settingsRepo.findSellerProfile(companyName);
    }

    public void upsertSellerProfile(SellerProfile profile) {
        settingsRepo.upsertSellerProfile(profile);
    }

    public void upsertConsultantProfile(ConsultantProfile profile) {
        settingsRepo.upsertConsultantProfile(profile);
    }

    public List<ConsultantProfile> findAllConsultantProfiles(String company) {
        return settingsRepo.findAllConsultantProfiles(company);
    }

    public void deleteConsultantProfile(String email) {
        settingsRepo.deleteConsultantProfile(email);
    }

    // -----------------------------------------------------------------------
    // Schema synchronization — stays here (cross-cutting, touches all classes)
    // -----------------------------------------------------------------------

    private void synchronizeSchema() {
        Result<Schema> schemaResult = client.schema().getter().run();
        if (schemaResult.hasErrors()) {
            throw new IllegalStateException("Weaviate schema getter error: " + schemaResult.getError());
        }

        Schema schema = schemaResult.getResult();
        boolean docExists = schema != null
                && schema.getClasses() != null
                && schema.getClasses().stream().anyMatch(c -> className.equals(c.getClassName()));

        if (!docExists) {
            log.info("🧱 Weaviate: création de la classe '{}'", className);
            WeaviateClass clazz = WeaviateClass.builder()
                    .className(className)
                    .description("Chunk de texte indexé pour RAG")
                    .vectorizer("none")
                    .properties(List.of(
                            Property.builder().name("text").dataType(List.of("text")).description("Contenu du chunk").build(),
                            Property.builder().name("source").dataType(List.of("string")).description("Origine du chunk").build(),
                            Property.builder().name("timestamp").dataType(List.of("string")).description("Horodatage d'indexation").build()
                    ))
                    .build();
            Result<Boolean> creationResult = client.schema().classCreator().withClass(clazz).run();
            if (creationResult.hasErrors()) {
                throw new IllegalStateException("Weaviate class creation error: " + creationResult.getError());
            }
        } else {
            log.info("✅ Weaviate: classe '{}' déjà présente", className);
        }

        boolean expenseExists = schema != null
                && schema.getClasses() != null
                && schema.getClasses().stream().anyMatch(c -> expenseClassName.equals(c.getClassName()));

        if (!expenseExists) {
            log.info("🧱 Weaviate: création de la classe '{}'", expenseClassName);
            WeaviateClass expense = WeaviateClass.builder()
                    .className(expenseClassName)
                    .description("Dépenses structurées (notes de frais)")
                    .vectorizer("none")
                    .properties(List.of(
                            Property.builder().name("amount").dataType(List.of("number")).description("Montant").build(),
                            Property.builder().name("currency").dataType(List.of("string")).description("Devise").build(),
                            Property.builder().name("type").dataType(List.of("string")).description("Type dépense").build(),
                            Property.builder().name("km").dataType(List.of("number")).description("Kilométrage (frais km)").build(),
                            Property.builder().name("expenseId").dataType(List.of("int")).description("Id incrémental mensuel").build(),
                            Property.builder().name("date").dataType(List.of("date")).description("Date ISO").build(),
                            Property.builder().name("dateText").dataType(List.of("string")).description("Date ISO (texte)").build(),
                            Property.builder().name("description").dataType(List.of("text")).description("Description").build(),
                            Property.builder().name("originalText").dataType(List.of("text")).description("Texte source").build(),
                            Property.builder().name("source").dataType(List.of("string")).description("receipt|text").build(),
                            Property.builder().name("paymentMode").dataType(List.of("string")).description("Personnel|Business").build(),
                            Property.builder().name("address").dataType(List.of("text")).description("Adresse/lieu").build(),
                            Property.builder().name("company").dataType(List.of("string")).description("Société/organisation").build(),
                            Property.builder().name("duplicateFlag").dataType(List.of("boolean")).description("Doublon").build(),
                            Property.builder().name("hash").dataType(List.of("string")).description("Hash binaire/texte").build(),
                            Property.builder().name("text").dataType(List.of("text")).description("Texte concaténé pour embedding").build()
                    ))
                    .build();
            Result<Boolean> creationExpense = client.schema().classCreator().withClass(expense).run();
            if (creationExpense.hasErrors()) {
                throw new IllegalStateException("Weaviate expense class creation error: " + creationExpense.getError());
            }
        } else {
            log.info("✅ Weaviate: classe '{}' déjà présente", expenseClassName);
            ensureExpenseProperties(schemaResult.getResult(), expenseClassName);
        }

        // Invoice schema is now managed by invoice-service (InvoiceSchemaInitializer)

        boolean sellerProfileExists = schema != null
                && schema.getClasses() != null
                && schema.getClasses().stream().anyMatch(c -> sellerProfileClassName.equals(c.getClassName()));

        if (!sellerProfileExists) {
            log.info("🧱 Weaviate: création de la classe '{}'", sellerProfileClassName);
            WeaviateClass sellerProfile = WeaviateClass.builder()
                    .className(sellerProfileClassName)
                    .description("Profils des sociétés émettrices")
                    .vectorizer("none")
                    .properties(List.of(
                            Property.builder().name("companyName").dataType(List.of("string")).description("Nom de la société").build(),
                            Property.builder().name("address").dataType(List.of("text")).description("Adresse").build(),
                            Property.builder().name("rcs").dataType(List.of("string")).description("RCS").build(),
                            Property.builder().name("iban").dataType(List.of("string")).description("IBAN").build(),
                            Property.builder().name("bic").dataType(List.of("string")).description("BIC").build(),
                            Property.builder().name("email").dataType(List.of("string")).description("Email").build(),
                            Property.builder().name("capital").dataType(List.of("string")).description("Capital").build(),
                            Property.builder().name("latePaymentClause").dataType(List.of("text")).description("Clause pénalités de retard").build()
                    ))
                    .build();
            Result<Boolean> creationSellerProfile = client.schema().classCreator().withClass(sellerProfile).run();
            if (creationSellerProfile.hasErrors()) {
                throw new IllegalStateException("Weaviate seller profile class creation error: " + creationSellerProfile.getError());
            }
        } else {
            ensureSellerProfileProperties(schemaResult.getResult(), sellerProfileClassName);
        }

        boolean consultantProfileExists = schema != null
                && schema.getClasses() != null
                && schema.getClasses().stream().anyMatch(c -> consultantProfileClassName.equals(c.getClassName()));

        if (!consultantProfileExists) {
            log.info("🧱 Weaviate: création de la classe '{}'", consultantProfileClassName);
            WeaviateClass consultantProfile = WeaviateClass.builder()
                    .className(consultantProfileClassName)
                    .description("Profils des consultants")
                    .vectorizer("none")
                    .properties(List.of(
                            Property.builder().name("email").dataType(List.of("string")).description("Email du consultant").build(),
                            Property.builder().name("name").dataType(List.of("string")).description("Nom complet").build(),
                            Property.builder().name("role").dataType(List.of("string")).description("Rôle (Salarié/Freelance)").build(),
                            Property.builder().name("company").dataType(List.of("string")).description("Société").build(),
                            Property.builder().name("clientName").dataType(List.of("string")).description("Nom du client").build(),
                            Property.builder().name("clientAddress").dataType(List.of("text")).description("Adresse du client").build(),
                            Property.builder().name("clientRcs").dataType(List.of("string")).description("RCS du client").build(),
                            Property.builder().name("tjm").dataType(List.of("number")).description("Taux journalier moyen").build(),
                            Property.builder().name("active").dataType(List.of("boolean")).description("Consultant actif").build()
                    ))
                    .build();
            Result<Boolean> creationConsultantProfile = client.schema().classCreator().withClass(consultantProfile).run();
            if (creationConsultantProfile.hasErrors()) {
                throw new IllegalStateException("Weaviate consultant profile class creation error: " + creationConsultantProfile.getError());
            }
        } else {
            log.info("✅ Weaviate: classe '{}' déjà présente", consultantProfileClassName);
            ensureConsultantProfileProperties(schemaResult.getResult(), consultantProfileClassName);
        }

        boolean craExists = schema != null
                && schema.getClasses() != null
                && schema.getClasses().stream().anyMatch(c -> craClassName.equals(c.getClassName()));

        if (!craExists) {
            log.info("🧱 Weaviate: création de la classe '{}'", craClassName);
            WeaviateClass cra = WeaviateClass.builder()
                    .className(craClassName)
                    .description("Comptes Rendus d'Activité consultants")
                    .vectorizer("none")
                    .properties(List.of(
                            Property.builder().name("consultant").dataType(List.of("string")).description("Nom du consultant").build(),
                            Property.builder().name("company").dataType(List.of("string")).description("Société").build(),
                            Property.builder().name("clientCompany").dataType(List.of("string")).description("Société cliente").build(),
                            Property.builder().name("billingMonth").dataType(List.of("string")).description("Mois YYYY-MM").build(),
                            Property.builder().name("entriesJson").dataType(List.of("text")).description("Entrées JSON sérialisées").build(),
                            Property.builder().name("totalDays").dataType(List.of("number")).description("Nombre de jours travaillés").build(),
                            Property.builder().name("status").dataType(List.of("string")).description("BROUILLON|SOUMIS|VALIDE|REFUSE").build(),
                            Property.builder().name("submittedAt").dataType(List.of("string")).description("Date de soumission ISO").build(),
                            Property.builder().name("validatedAt").dataType(List.of("string")).description("Date de validation ISO").build(),
                            Property.builder().name("validatedBy").dataType(List.of("string")).description("Nom du validateur").build(),
                            Property.builder().name("refusedReason").dataType(List.of("text")).description("Motif de refus").build()
                    ))
                    .build();
            Result<Boolean> creationCra = client.schema().classCreator().withClass(cra).run();
            if (creationCra.hasErrors()) {
                throw new IllegalStateException("Weaviate CRA class creation error: " + creationCra.getError());
            }
        } else {
            log.info("✅ Weaviate: classe '{}' déjà présente", craClassName);
        }
    }

    private void ensureExpenseProperties(Schema schema, String targetClass) {
        if (schema == null || schema.getClasses() == null) return;
        WeaviateClass expense = schema.getClasses().stream()
                .filter(c -> targetClass.equals(c.getClassName())).findFirst().orElse(null);
        if (expense == null || expense.getProperties() == null) return;
        var existing = new ArrayList<String>();
        expense.getProperties().forEach(p -> existing.add(p.getName()));
        List<Property> desired = List.of(
                Property.builder().name("dateText").dataType(List.of("string")).description("Date ISO (texte)").build(),
                Property.builder().name("text").dataType(List.of("text")).description("Texte concaténé pour embedding").build(),
                Property.builder().name("hash").dataType(List.of("string")).description("Hash binaire/texte").build(),
                Property.builder().name("paymentMode").dataType(List.of("string")).description("Personnel|Business").build(),
                Property.builder().name("address").dataType(List.of("text")).description("Adresse/lieu").build(),
                Property.builder().name("expenseId").dataType(List.of("int")).description("Id incrémental mensuel").build(),
                Property.builder().name("km").dataType(List.of("number")).description("Kilométrage (frais km)").build(),
                Property.builder().name("company").dataType(List.of("string")).description("Société/organisation").build(),
                Property.builder().name("consultantEmail").dataType(List.of("string")).description("Email du consultant").build(),
                Property.builder().name("approvalStatus").dataType(List.of("string")).description("PENDING|APPROVED|REFUSED").build(),
                Property.builder().name("approvalNote").dataType(List.of("text")).description("Note d'approbation").build(),
                Property.builder().name("absencePeriodsJson").dataType(List.of("text")).description("Périodes d'absence JSON").build()
        );
        addMissingProperties(targetClass, existing, desired);
    }

    // ensureInvoiceProperties removed — Invoice schema is now managed by invoice-service

    private void ensureSellerProfileProperties(Schema schema, String targetClass) {
        if (schema == null || schema.getClasses() == null) return;
        WeaviateClass sellerProfile = schema.getClasses().stream()
                .filter(c -> targetClass.equals(c.getClassName())).findFirst().orElse(null);
        if (sellerProfile == null || sellerProfile.getProperties() == null) return;
        var existing = new ArrayList<String>();
        sellerProfile.getProperties().forEach(p -> existing.add(p.getName()));
        List<Property> desired = List.of(
                Property.builder().name("iban").dataType(List.of("string")).description("IBAN").build(),
                Property.builder().name("bic").dataType(List.of("string")).description("BIC").build(),
                Property.builder().name("latePaymentClause").dataType(List.of("text")).description("Clause pénalités de retard").build()
        );
        addMissingProperties(targetClass, existing, desired);
    }

    private void ensureConsultantProfileProperties(Schema schema, String targetClass) {
        if (schema == null || schema.getClasses() == null) return;
        WeaviateClass consultantProfile = schema.getClasses().stream()
                .filter(c -> targetClass.equals(c.getClassName())).findFirst().orElse(null);
        if (consultantProfile == null || consultantProfile.getProperties() == null) return;
        var existing = new ArrayList<String>();
        consultantProfile.getProperties().forEach(p -> existing.add(p.getName()));
        List<Property> desired = List.of(
                Property.builder().name("email").dataType(List.of("string")).description("Email du consultant").build(),
                Property.builder().name("name").dataType(List.of("string")).description("Nom complet").build(),
                Property.builder().name("role").dataType(List.of("string")).description("Rôle (Salarié/Freelance)").build(),
                Property.builder().name("company").dataType(List.of("string")).description("Société").build(),
                Property.builder().name("clientName").dataType(List.of("string")).description("Nom du client").build(),
                Property.builder().name("clientAddress").dataType(List.of("text")).description("Adresse du client").build(),
                Property.builder().name("clientRcs").dataType(List.of("string")).description("RCS du client").build(),
                Property.builder().name("tjm").dataType(List.of("number")).description("Taux journalier moyen").build(),
                Property.builder().name("active").dataType(List.of("boolean")).description("Consultant actif").build()
        );
        addMissingProperties(targetClass, existing, desired);
    }

    private void addMissingProperties(String targetClass, List<String> existing, List<Property> desired) {
        for (Property prop : desired) {
            if (!existing.contains(prop.getName())) {
                Result<Boolean> creation = client.schema().propertyCreator()
                        .withClassName(targetClass)
                        .withProperty(prop)
                        .run();
                if (creation.hasErrors()) {
                    log.warn("⚠️ Impossible d'ajouter la propriété '{}' dans '{}': {}", prop.getName(), targetClass, creation.getError());
                } else {
                    log.info("➕ Propriété '{}' ajoutée dans la classe '{}'", prop.getName(), targetClass);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void persistChunk(String id, String text, String source, Float[] vector) {
        try {
            Map<String, Object> props = new HashMap<>();
            props.put("text", text);
            if (source != null) {
                props.put("source", source);
            }
            props.put("timestamp", new Date().toString());

            var creator = client.data().creator()
                    .withClassName(className)
                    .withProperties(props)
                    .withVector(vector);

            if (id != null && !id.isBlank()) {
                creator = creator.withID(id);
            }

            var result = creator.run();
            if (result.hasErrors()) {
                log.error("❌ Weaviate indexChunk error: {}", result.getError());
            } else {
                log.info("📥 Weaviate: chunk indexé (id={})", id);
            }
        } catch (Exception e) {
            log.error("❌ Exception indexChunk: {}", e.getMessage(), e);
        }
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(schemaInitBackoff.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
