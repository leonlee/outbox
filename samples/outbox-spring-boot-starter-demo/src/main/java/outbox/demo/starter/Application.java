package outbox.demo.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Starter demo — zero-config auto-configuration.
 *
 * <p>Compare with {@code outbox-spring-demo} which requires a manual
 * {@code OutboxConfiguration} class with 7+ bean definitions. This demo
 * needs only {@code @SpringBootApplication} — the starter auto-configures
 * everything.
 *
 * <p>Run with: mvn install -DskipTests && mvn -f samples/outbox-spring-boot-starter-demo/pom.xml spring-boot:run
 *
 * <p>Endpoints:
 * POST /events/user-created?name=Alice     - publish UserCreated event
 * POST /events/order-placed?orderId=123    - publish OrderPlaced event
 * GET  /events                             - list all events in outbox
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
