package io.outbox.dispatch;

import io.outbox.dispatch.QueuedEvent.ClaimLease;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimHandoffTest {

    private static final Instant T0 = Instant.parse("2026-10-13T00:00:00Z");
    private static final ClaimLease OWN = new ClaimLease("pod-a", T0);
    private static final ClaimLease NEWER = new ClaimLease("pod-a", T0.plusSeconds(600));

    @Test
    void writesUnderAnAdoptedLease() {
        ClaimHandoff handoff = new ClaimHandoff(OWN);
        assertTrue(handoff.adopt(NEWER));
        assertEquals(NEWER, writtenUnder(handoff));
    }

    @Test
    void aHotCopyAdoptsItsFirstClaim() {
        ClaimHandoff handoff = new ClaimHandoff(null);
        assertTrue(handoff.adopt(OWN));
        assertEquals(OWN, writtenUnder(handoff));
    }

    @Test
    void refusesAnAdoptionOnceWritten() {
        ClaimHandoff handoff = new ClaimHandoff(OWN);
        writtenUnder(handoff);
        assertFalse(handoff.adopt(NEWER), "too late to use it: the caller must release it");
        assertNull(handoff.abandon());
    }

    @Test
    void refusesAnOlderLease() {
        ClaimHandoff handoff = new ClaimHandoff(NEWER);
        assertFalse(handoff.adopt(OWN));
    }

    /** A duplicate of the claim already held is held, not orphaned: the caller must not release it. */
    @Test
    void acceptsADuplicateOfTheHeldLeaseWithoutTakingIt() {
        ClaimHandoff handoff = new ClaimHandoff(OWN);
        assertTrue(handoff.adopt(new ClaimLease("pod-a", T0)));
        assertNull(handoff.abandon(), "its own lease is not an adoption to release");

        ClaimHandoff adopting = new ClaimHandoff(OWN);
        assertTrue(adopting.adopt(NEWER));
        assertTrue(adopting.adopt(new ClaimLease("pod-a", NEWER.claimedAt())));
        assertEquals(NEWER, writtenUnder(adopting));
    }

    /**
     * A captured handoff that a newer reservation has retired must refuse, or the superseded
     * attempt would write its stale outcome under the newest claim.
     */
    @Test
    void refusesAnAdoptionOnceRetired() {
        ClaimHandoff handoff = new ClaimHandoff(OWN);
        assertNull(handoff.retire());
        assertFalse(handoff.adopt(NEWER));
        assertEquals(OWN, writtenUnder(handoff));
    }

    /** An adoption that landed before retirement moves to the newer reservation, not the old attempt. */
    @Test
    void retirementCarriesTheAdoptedLeaseAway() {
        ClaimHandoff handoff = new ClaimHandoff(OWN);
        assertTrue(handoff.adopt(NEWER));

        assertEquals(NEWER, handoff.retire());
        assertEquals(OWN, writtenUnder(handoff), "the superseded attempt keeps only its own lease");
        assertNull(handoff.abandon(), "and its cleanup has nothing of the newer one to release");
    }

    @Test
    void abandoningReturnsTheAdoptedLeaseToRelease() {
        ClaimHandoff handoff = new ClaimHandoff(OWN);
        assertTrue(handoff.adopt(NEWER));

        assertEquals(NEWER, handoff.abandon());
        assertFalse(handoff.adopt(new ClaimLease("pod-a", T0.plusSeconds(1200))));
    }

    @Test
    void aWriteThatThrowsStillEndsTheAuthority() {
        ClaimHandoff handoff = new ClaimHandoff(OWN);
        assertThrows(IllegalStateException.class, () -> handoff.write(lease -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(handoff.adopt(NEWER));
    }

    private static ClaimLease writtenUnder(ClaimHandoff handoff) {
        AtomicReference<ClaimLease> used = new AtomicReference<>();
        handoff.write(lease -> {
            used.set(lease);
            return 1;
        });
        return used.get();
    }
}
