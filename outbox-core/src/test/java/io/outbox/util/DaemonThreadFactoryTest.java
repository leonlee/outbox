package io.outbox.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonThreadFactoryTest {

    @Test
    void createsNamedDaemonThreads() {
        DaemonThreadFactory factory = new DaemonThreadFactory("worker-");

        Thread thread = factory.newThread(() -> {
        });

        assertTrue(thread.isDaemon());
        assertTrue(thread.getName().startsWith("worker-"));
    }

    @Test
    void sequentialNaming() {
        DaemonThreadFactory factory = new DaemonThreadFactory("test-");

        Thread t1 = factory.newThread(() -> {
        });
        Thread t2 = factory.newThread(() -> {
        });
        Thread t3 = factory.newThread(() -> {
        });

        assertEquals("test-1", t1.getName());
        assertEquals("test-2", t2.getName());
        assertEquals("test-3", t3.getName());
    }

    @Test
    void nullPrefixThrows() {
        assertThrows(NullPointerException.class, () ->
                new DaemonThreadFactory(null));
    }
}
