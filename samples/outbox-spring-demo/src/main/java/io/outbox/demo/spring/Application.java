package io.outbox.demo.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot demo for outbox framework.
 * <p>
 * Run with: mvn install -DskipTests && mvn -f samples/outbox-spring-demo/pom.xml spring-boot:run
 * <p>
 * Endpoints:
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
