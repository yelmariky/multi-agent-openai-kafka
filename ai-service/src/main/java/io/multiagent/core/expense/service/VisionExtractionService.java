package io.multiagent.core.expense.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Fallback extraction via OpenAI Vision API (gpt-4o-mini).
 * Utilisé quand l'OCR Tesseract produit un texte de trop mauvaise qualité
 * (image froissée, inclinée, ticket thermique dégradé…).
 */
@Service
@Slf4j
public class VisionExtractionService {

    private static final String VISION_MODEL  = "gpt-4o-mini";
    private static final String OPENAI_URL    = "https://api.openai.com/v1/chat/completions";
    private static final int    TIMEOUT_SEC   = 30;

    private final String apiKey;
    private final ObjectMapper mapper;

    public VisionExtractionService(
            @Value("${openai.api-key}") String apiKey,
            ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.mapper = mapper;
    }

    /**
     * Analyse l'image directement et retourne le JSON de dépense.
     *
     * @param imagePath chemin vers l'image (jpg, png…)
     * @param systemPrompt même prompt que l'extracteur OCR texte
     * @return JSON brut retourné par le LLM
     */
    public String extractFromImage(Path imagePath, String systemPrompt) throws Exception {
        byte[] bytes    = Files.readAllBytes(imagePath);
        String base64   = Base64.getEncoder().encodeToString(bytes);
        String mimeType = detectMime(imagePath.getFileName().toString());

        String dataUrl = "data:" + mimeType + ";base64," + base64;

        Map<String, Object> body = Map.of(
            "model",           VISION_MODEL,
            "response_format", Map.of("type", "json_object"),
            "max_tokens",      1024,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", List.of(
                    Map.of(
                        "type",      "image_url",
                        "image_url", Map.of("url", dataUrl, "detail", "high")
                    ),
                    Map.of(
                        "type", "text",
                        "text", "Extrais les informations de dépense de ce justificatif et retourne uniquement le JSON demandé."
                    )
                ))
            )
        );

        String json = mapper.writeValueAsString(body);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(TIMEOUT_SEC))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type",  "application/json")
                .timeout(java.time.Duration.ofSeconds(TIMEOUT_SEC))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("OpenAI Vision HTTP " + resp.statusCode() + ": " + resp.body());
        }

        // Extraire choices[0].message.content
        var root   = mapper.readTree(resp.body());
        var choice = root.path("choices").get(0);
        if (choice == null) {
            throw new IllegalStateException("OpenAI Vision: aucun choix dans la réponse");
        }
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("OpenAI Vision: contenu vide");
        }
        log.info("🔭 Vision extraction réussie via {} (image={})", VISION_MODEL, imagePath.getFileName());
        return content;
    }

    private String detectMime(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif"))  return "image/gif";
        return "image/jpeg"; // jpg, jpeg, default
    }
}
