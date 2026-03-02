package io.outbox.testing;

import io.outbox.EventEnvelope;
import io.outbox.WriterHook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link WriterHook} that records all lifecycle invocations for test assertions.
 *
 * <p>Captures events passed to each phase: {@code beforeWrite}, {@code afterWrite},
 * {@code afterCommit}, and {@code afterRollback}. Call counts and event snapshots
 * are available for verification.
 *
 * <p>This class is not thread-safe. It is intended for single-threaded unit tests
 * where writes happen on the test thread.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var hook = new RecordingWriterHook();
 * var writer = new DefaultOutboxWriter(txContext, store, hook);
 * writer.write(EventEnvelope.ofJson("Test", "{}"));
 * txContext.runAfterCommit();
 * assertEquals(1, hook.afterCommitCount());
 * }</pre>
 */
public class RecordingWriterHook implements WriterHook {
    private final List<List<EventEnvelope>> beforeWriteInvocations = new ArrayList<>();
    private final List<List<EventEnvelope>> afterWriteInvocations = new ArrayList<>();
    private final List<List<EventEnvelope>> afterCommitInvocations = new ArrayList<>();
    private final List<List<EventEnvelope>> afterRollbackInvocations = new ArrayList<>();

    @Override
    public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
        beforeWriteInvocations.add(List.copyOf(events));
        return events;
    }

    @Override
    public void afterWrite(List<EventEnvelope> events) {
        afterWriteInvocations.add(List.copyOf(events));
    }

    @Override
    public void afterCommit(List<EventEnvelope> events) {
        afterCommitInvocations.add(List.copyOf(events));
    }

    @Override
    public void afterRollback(List<EventEnvelope> events) {
        afterRollbackInvocations.add(List.copyOf(events));
    }

    /** Returns the number of {@code beforeWrite} invocations. */
    public int beforeWriteCount() {
        return beforeWriteInvocations.size();
    }

    /** Returns all event lists passed to {@code beforeWrite}. */
    public List<List<EventEnvelope>> beforeWriteInvocations() {
        return Collections.unmodifiableList(beforeWriteInvocations);
    }

    /** Returns the number of {@code afterWrite} invocations. */
    public int afterWriteCount() {
        return afterWriteInvocations.size();
    }

    /** Returns all event lists passed to {@code afterWrite}. */
    public List<List<EventEnvelope>> afterWriteInvocations() {
        return Collections.unmodifiableList(afterWriteInvocations);
    }

    /** Returns the number of {@code afterCommit} invocations. */
    public int afterCommitCount() {
        return afterCommitInvocations.size();
    }

    /** Returns all event lists passed to {@code afterCommit}. */
    public List<List<EventEnvelope>> afterCommitInvocations() {
        return Collections.unmodifiableList(afterCommitInvocations);
    }

    /** Returns the number of {@code afterRollback} invocations. */
    public int afterRollbackCount() {
        return afterRollbackInvocations.size();
    }

    /** Returns all event lists passed to {@code afterRollback}. */
    public List<List<EventEnvelope>> afterRollbackInvocations() {
        return Collections.unmodifiableList(afterRollbackInvocations);
    }
}
