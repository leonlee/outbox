package outbox.demo.spring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.util.JsonCodec;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/events")
public class EventController {

    private final OutboxWriter outboxWriter;
    private final JdbcTemplate jdbcTemplate;

    public EventController(OutboxWriter outboxWriter, JdbcTemplate jdbcTemplate) {
        this.outboxWriter = outboxWriter;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/user-created")
    @Transactional
    public Map<String, String> publishUserCreated(@RequestParam String name) {
        String userId = UUID.randomUUID().toString().substring(0, 8);

        // Simulate saving user to database
        // userRepository.save(new User(userId, name));

        // Publish event within the same transaction
        String eventId = outboxWriter.write(EventEnvelope.builder("UserCreated")
                .aggregateType("User")
                .aggregateId(userId)
                .headers(Map.of("source", "api", "version", "1"))
                .payloadJson(JsonCodec.getDefault().toJson(Map.of("userId", userId, "name", name)))
                .build());

        return Map.of(
                "status", "ok",
                "eventId", eventId,
                "userId", userId
        );
    }

    @PostMapping("/order-placed")
    @Transactional
    public Map<String, String> publishOrderPlaced(
            @RequestParam String orderId,
            @RequestParam(defaultValue = "99.99") double amount
    ) {
        // Simulate saving order to database
        // orderRepository.save(new Order(orderId, amount));

        // Publish event within the same transaction
        String eventId = outboxWriter.write(EventEnvelope.builder("OrderPlaced")
                .aggregateType("Order")
                .aggregateId(orderId)
                .tenantId("default")
                .headers(Map.of("source", "api"))
                .payloadJson(JsonCodec.getDefault().toJson(Map.of("orderId", orderId, "amount", String.valueOf(amount))))
                .build());

        return Map.of(
                "status", "ok",
                "eventId", eventId,
                "orderId", orderId
        );
    }

    @GetMapping
    public List<Map<String, Object>> listEvents() {
        return jdbcTemplate.queryForList(
                "SELECT event_id, event_type, aggregate_type, aggregate_id, status, attempts, created_at, done_at " +
                        "FROM outbox_event ORDER BY created_at DESC LIMIT 20"
        );
    }
}
