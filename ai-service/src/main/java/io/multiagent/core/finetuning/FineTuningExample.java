package io.multiagent.core.finetuning;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FileObject;
import com.openai.models.files.FilePurpose;
import com.openai.models.finetuning.jobs.FineTuningJob;
import com.openai.models.finetuning.jobs.JobCreateParams;
import com.openai.models.finetuning.jobs.JobListEventsParams;
import com.openai.models.finetuning.jobs.JobRetrieveParams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Exemple complet du workflow fine-tuning OpenAI adapté à ce projet ESN.
 *
 * Activez avec le profil Spring "fine-tuning-demo" :
 *   mvn spring-boot:run -Dspring-boot.run.profiles=fine-tuning-demo
 *
 * Étapes exécutées :
 *   1. Upload du fichier JSONL d'entraînement vers l'API Files OpenAI
 *   2. Lancement d'un job de fine-tuning sur gpt-4.1-mini
 *   3. Polling du statut jusqu'à succeeded/failed
 *   4. Test de l'inférence sur le modèle fine-tuné
 *
 * En production, remplacez cette démo par un service Spring qui injecte
 * le model ID depuis la variable d'env OPENAI_FINE_TUNED_MODEL.
 */
@Slf4j
@Component
@Profile("fine-tuning-demo")
public class FineTuningExample implements CommandLineRunner {

    private static final String TRAINING_JSONL = "fine-tuning/expense-classification-training.jsonl";
    private static final String BASE_MODEL = "gpt-4.1-mini-2025-07-18";
    private static final String SYSTEM_PROMPT =
        "Tu es un assistant de classification de notes de frais pour une ESN française. " +
        "Pour chaque dépense décrite en texte libre, tu retournes un JSON avec les champs: " +
        "category (TRANSPORT|HEBERGEMENT|RESTAURATION|MATERIEL|FORMATION|AUTRE), " +
        "amount (float), currency (EUR), justification (string), billable (boolean).";

    private final OpenAIClient client;

    public FineTuningExample(@Value("${openai.api-key}") String apiKey) {
        this.client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build();
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Fine-tuning demo — ESN expense classifier ===");

        // Étape 1 : upload du fichier d'entraînement
        String fileId = uploadTrainingFile();
        log.info("Fichier uploadé : {}", fileId);

        // Étape 2 : créer le job de fine-tuning
        String jobId = createFineTuningJob(fileId);
        log.info("Job créé : {}", jobId);

        // Étape 3 : attendre la fin du job (polling toutes les 60s)
        String fineTunedModelId = waitForCompletion(jobId);
        if (fineTunedModelId == null) {
            log.error("Fine-tuning échoué — vérifiez les events du job {}", jobId);
            return;
        }
        log.info("Modèle fine-tuné disponible : {}", fineTunedModelId);

        // Étape 4 : test d'inférence sur le modèle fine-tuné
        runInferenceDemo(fineTunedModelId);
    }

    private String uploadTrainingFile() throws IOException {
        ClassPathResource resource = new ClassPathResource(TRAINING_JSONL);
        Path tmpFile = Files.createTempFile("expense-training-", ".jsonl");
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, tmpFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        FileObject uploaded = client.files().create(FileCreateParams.builder()
            .file(tmpFile)
            .purpose(FilePurpose.FINE_TUNE)
            .build());

        Files.deleteIfExists(tmpFile);
        return uploaded.id();
    }

    private String createFineTuningJob(String fileId) {
        FineTuningJob job = client.fineTuning().jobs().create(
            JobCreateParams.builder()
                .trainingFile(fileId)
                .model(BASE_MODEL)
                // Hyperparamètres optionnels — omis pour laisser OpenAI choisir les défauts (3 epochs)
                .suffix("esn-expense-v1")  // model final : ft:gpt-4.1-mini-...:esn-expense-v1:xxxx
                .build()
        );
        return job.id();
    }

    private String waitForCompletion(String jobId) throws InterruptedException {
        int maxPolls = 60; // max ~60 min à 60s/poll
        for (int i = 0; i < maxPolls; i++) {
            FineTuningJob job = client.fineTuning().jobs().retrieve(
                JobRetrieveParams.builder().fineTuningJobId(jobId).build()
            );
            String status = job.status().toString();
            log.info("Job {} — statut : {} (poll {}/{})", jobId, status, i + 1, maxPolls);

            if ("succeeded".equalsIgnoreCase(status)) {
                return job.fineTunedModel().orElse(null);
            }
            if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
                printJobEvents(jobId);
                return null;
            }

            Thread.sleep(60_000);
        }
        log.warn("Timeout atteint — le job {} est toujours en cours", jobId);
        return null;
    }

    private void printJobEvents(String jobId) {
        client.fineTuning().jobs().listEvents(
            JobListEventsParams.builder()
                .fineTuningJobId(jobId)
                .limit(10L)
                .build()
        ).data().forEach(event ->
            log.info("  [{}] {}", event.createdAt(), event.message())
        );
    }

    private void runInferenceDemo(String modelId) {
        List<String> testCases = List.of(
            "Taxi aéroport CDG → siège social client LVMH, 52€",
            "Repas équipe kick-off projet 8 personnes, brasserie Lyon, 320€",
            "Clé USB 128Go pour transfert données client, 19,99€"
        );

        log.info("=== Test d'inférence sur modèle fine-tuné {} ===", modelId);
        for (String expense : testCases) {
            ChatCompletion response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model(modelId)
                    .messages(List.of(
                        ChatCompletionMessageParam.ofSystem(
                            ChatCompletionSystemMessageParam.builder()
                                .content(SYSTEM_PROMPT)
                                .build()
                        ),
                        ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder()
                                .content(expense)
                                .build()
                        )
                    ))
                    .maxTokens(256L)
                    .build()
            );

            String result = response.choices().get(0).message().content().orElse("(vide)");
            log.info("INPUT  : {}", expense);
            log.info("OUTPUT : {}", result);
            log.info("---");
        }
    }
}
