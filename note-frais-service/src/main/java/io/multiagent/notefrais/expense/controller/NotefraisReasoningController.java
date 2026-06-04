package io.multiagent.notefrais.expense.controller;

import io.multiagent.notefrais.model.ReasoningResult;
import io.multiagent.notefrais.reasoning.NotefraisReasoningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoint called by ai-core coordinator to process expense reasoning.
 * POST /reasoning/process
 * Body: { "text": "...", "intentJson": "...", "consultantEmail": "..." }
 */
@RestController
@RequestMapping("/reasoning")
@RequiredArgsConstructor
@Slf4j
public class NotefraisReasoningController {

    private final NotefraisReasoningService reasoningService;

    @PostMapping("/process")
    public ResponseEntity<ReasoningResult> process(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        String intentJson = body.get("intentJson");
        String consultantEmail = body.get("consultantEmail");

        log.info("POST /reasoning/process text='{}' consultantEmail='{}'", text, consultantEmail);

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(ReasoningResult.error("Missing 'text' in request body"));
        }

        ReasoningResult result = reasoningService.processFromJson(text, intentJson, consultantEmail);
        return ResponseEntity.ok(result);
    }
}
