package outbox.demo.starter;

import outbox.EventEnvelope;
import outbox.EventListener;
import outbox.spring.boot.OutboxListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@OutboxListener(eventType = "OrderPlaced", aggregateType = "Order")
public class OrderPlacedListener implements EventListener {

  private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

  @Override
  public void onEvent(EventEnvelope event) {
    log.info("[Listener] Order/OrderPlaced: id={}, payload={}",
        event.eventId(), event.payloadJson());
  }
}
