package com.tmd.zkp_rkp.service.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

import static com.tmd.zkp_rkp.common.ZkpValue.TOPIC_AUTH_EVENTS;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/28
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthEventPublisher {
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    public Mono<Void> publishUserRegistered(String userId, String username, LocalDateTime time) {
        return publishEvent(Map.of(
                "eventType", "USER_REGISTERED",
                "userId", userId,
                "username", username,
                "timestamp", time.toString()
        ));
    }

    public Mono<Void> publishLoginSuccess(String username) {
        return publishEvent(Map.of(
                "eventType", "LOGIN_SUCCESS",
                "username", username,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    public Mono<Void> publishLoginFailed(String username, String reason) {
        return publishEvent(Map.of(
                "eventType", "LOGIN_FAILED",
                "username", username,
                "reason", reason,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    private Mono<Void> publishEvent(Map<String, Object> event) {
        return Mono.fromFuture(kafkaTemplate.send(TOPIC_AUTH_EVENTS, event))
                .doOnNext(result -> log.debug("Published event: {}", event.get("eventType")))
                .doOnError(err -> log.error("Failed to publish event", err))
                .then();
    }
}
