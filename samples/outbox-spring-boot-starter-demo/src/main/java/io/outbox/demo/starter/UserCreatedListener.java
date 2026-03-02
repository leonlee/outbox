package io.outbox.demo.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import io.outbox.EventEnvelope;
import io.outbox.EventListener;
import io.outbox.spring.boot.OutboxListener;

@Component
@OutboxListener(eventType = "UserCreated", aggregateType = "User")
public class UserCreatedListener implements EventListener {

    private static final Logger log = LoggerFactory.getLogger(UserCreatedListener.class);

    @Override
    public void onEvent(EventEnvelope event) {
        log.info("[Listener] User/UserCreated: id={}, payload={}",
                event.eventId(), event.payloadJson());
    }
}
