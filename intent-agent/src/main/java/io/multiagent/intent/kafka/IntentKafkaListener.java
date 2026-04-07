package io.multiagent.intent.kafka;

import io.multiagent.intent.dto.IntentDTO;
import io.multiagent.intent.service.IntentAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IntentKafkaListener {

    private final IntentAgentService intentService;

    @KafkaListener(
            topics = "${spring.kafka.topics.intent-input}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(String text) {

        log.info("📥 [Intent-Agent] Message reçu de Kafka : {}", text);

        IntentDTO dto = intentService.classifyAndPublish(text);

        log.info("✅ [Intent-Agent] Classification effectuée : intent={}, confidence={}",
                dto.getIntent(), dto.getConfidence());
    }
}
