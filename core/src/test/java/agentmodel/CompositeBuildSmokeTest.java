package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import processmodel.foundation.AdmissionHandle;
import processmodel.foundation.BlockObserver;
import processmodel.foundation.ProcessListener;
import processmodel.foundation.ResourcePool;
import processmodel.foundation.SchedulingPriority;
import processmodel.foundation.flow.FlowListener;

/**
 * Proves the composite build wiring works before anything is built on top of it: these types come
 * from the sibling process-model repo's {@code foundation} module, resolved through
 * {@code includeBuild} rather than from a published jar.
 *
 * <p>If this fails, nothing else in this repo can work, and the cause is the build wiring rather
 * than any agent-model code — which is exactly why it is worth having as its own test.
 */
class CompositeBuildSmokeTest {

    @Test
    void theContractsAnAgentHoldsBlocksThroughResolveThroughTheCompositeBuild() {
        AdmissionHandle admitted = AdmissionHandle.admitted();
        assertTrue(admitted.isAdmitted());

        boolean[] ran = {false};
        admitted.onAdmitted(() -> ran[0] = true);
        assertTrue(ran[0], "an already-admitted handle runs its callback synchronously");
    }

    @Test
    void oneObserverSupertypeCoversBothParadigms() {
        // The claim this library's held-Block observability rests on: an agent registers once and
        // sees discrete and continuous Blocks alike, because both listener types share a supertype.
        assertTrue(BlockObserver.class.isAssignableFrom(ProcessListener.class));
        assertTrue(BlockObserver.class.isAssignableFrom(FlowListener.class));
    }

    @Test
    void resourcePoolIsSharedAcrossAllThreeLibrariesRatherThanReimplemented() {
        ResourcePool crane = new ResourcePool("crane", 2, () -> 0.0);
        Object holder = new Object();

        assertTrue(crane.tryAcquire(holder, 2));
        assertEquals(0, crane.available());
        assertSame(2, crane.currentHolders().get(holder));

        crane.release(holder, 2);
        assertTrue(crane.isIdle());
    }

    @Test
    void theBlockSchedulingBandsAreWhereThisLibrarysOwnBandsExpectThem() {
        // agent-model's INTERACTION and STRUCTURE bands sit below every block band, so a mediated
        // message always sees settled block state. That only holds while SETTLE is the lowest of
        // them; pin it here so a new, lower block band upstream fails loudly rather than silently
        // reordering agent traffic.
        assertTrue(SchedulingPriority.COMPLETION > SchedulingPriority.EXPIRY);
        assertTrue(SchedulingPriority.EXPIRY > SchedulingPriority.ARRIVAL);
        assertTrue(SchedulingPriority.ARRIVAL > SchedulingPriority.SETTLE);
        assertEquals(100.0, SchedulingPriority.SETTLE, 1e-9);
    }
}
