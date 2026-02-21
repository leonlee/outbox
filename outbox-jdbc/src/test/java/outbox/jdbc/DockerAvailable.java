package outbox.jdbc;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JUnit 5 condition annotation that skips tests when Docker is not available.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DockerAvailable.DockerAvailableCondition.class)
@interface DockerAvailable {

  class DockerAvailableCondition implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      try {
        DockerClientFactory.instance().client();
        return ConditionEvaluationResult.enabled("Docker is available");
      } catch (Throwable t) {
        return ConditionEvaluationResult.disabled("Docker is not available: " + t.getMessage());
      }
    }
  }
}
