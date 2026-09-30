package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;
import processmodel.foundation.ProcessStage;
import processmodel.foundation.ResourcePool;

class AbstractAgentTest {

    /** Concrete because AbstractAgent is abstract, and it exposes the protected clock for one test. */
    static final class TestAgent extends AbstractAgent {
        TestAgent(String name, DoubleSupplier tickSource) {
            super(name, tickSource);
        }

        double tick() {
            return currentTick();
        }
    }

    /** Records what reached the agent's listener, including which agent it was attributed to. */
    static final class Recorder implements AgentListener {
        final List<String> seen = new ArrayList<>();

        @Override
        public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
            seen.add("entered " + agent.getName() + "/" + stage.getName() + "/" + entity + "@" + tick);
        }

        @Override
        public void entityExited(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
            seen.add("exited " + agent.getName() + "/" + stage.getName() + "/" + entity + "@" + tick);
        }

        @Override
        public void resourceAcquired(
                Agent agent, ResourcePool pool, Object holder, int units, double tick) {
            seen.add("acquired " + agent.getName() + "/" + pool.getName() + "/" + units);
        }

        @Override
        public void resourceReleased(
                Agent agent, ResourcePool pool, Object holder, int units, double tick) {
            seen.add("released " + agent.getName() + "/" + pool.getName() + "/" + units);
        }
    }

    private static TestAgent agent(String name) {
        return new TestAgent(name, () -> 0.0);
    }

    // -- held Blocks -----------------------------------------------------------------------

    @Test
    void aHeldBlocksEventsReachTheAgentsListenerAttributedToTheAgent() {
        TestAgent facility = agent("facility");
        FakeStage<String> queue = new FakeStage<>("queue");
        facility.hold(queue);

        Recorder recorder = new Recorder();
        facility.addAgentListener(recorder);

        queue.emitEntered("cask-1", 3.0);
        queue.emitExited("cask-1", 7.0);

        assertEquals(
                Arrays.asList("entered facility/queue/cask-1@3.0", "exited facility/queue/cask-1@7.0"),
                recorder.seen);
    }

    @Test
    void resourceEventsComeThroughTheSameRegistration() {
        // ProcessListener extends BlockObserver, so holding a Block picks up its resource events
        // too — no second registration, and the pool is not held by anyone.
        TestAgent facility = agent("facility");
        FakeStage<String> activity = new FakeStage<>("activity");
        facility.hold(activity);

        Recorder recorder = new Recorder();
        facility.addAgentListener(recorder);

        ResourcePool crane = new ResourcePool("crane", 2, () -> 0.0);
        activity.emitResourceAcquired(crane, new Object(), 2, 1.0);
        activity.emitResourceReleased(crane, new Object(), 2, 4.0);

        assertEquals(Arrays.asList("acquired facility/crane/2", "released facility/crane/2"), recorder.seen);
    }

    @Test
    void oneAgentHoldsBlocksOverDifferentEntityTypes() {
        TestAgent facility = agent("facility");
        FakeStage<String> casks = new FakeStage<>("casks");
        FakeStage<Integer> batches = new FakeStage<>("batches");
        facility.hold(casks);
        facility.hold(batches);

        Recorder recorder = new Recorder();
        facility.addAgentListener(recorder);

        casks.emitEntered("cask-1", 1.0);
        batches.emitEntered(42, 2.0);

        assertEquals(
                Arrays.asList("entered facility/casks/cask-1@1.0", "entered facility/batches/42@2.0"),
                recorder.seen);
        assertEquals(2, facility.heldBlocks().size());
    }

    @Test
    void holdingTheSameBlockTwiceIsRefusedRatherThanDoubleReporting() {
        TestAgent facility = agent("facility");
        FakeStage<String> queue = new FakeStage<>("queue");
        facility.hold(queue);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> facility.hold(queue));
        assertTrue(thrown.getMessage().contains("queue"));
        assertEquals(1, queue.listenerCount(), "the refused hold must not have installed a second listener");
    }

    @Test
    void twoAgentsMayHoldDistinctBlocksAndEachSeesOnlyItsOwn() {
        TestAgent one = agent("one");
        TestAgent two = agent("two");
        FakeStage<String> queueOne = new FakeStage<>("q1");
        FakeStage<String> queueTwo = new FakeStage<>("q2");
        one.hold(queueOne);
        two.hold(queueTwo);

        Recorder recorder = new Recorder();
        one.addAgentListener(recorder);

        queueTwo.emitEntered("x", 1.0);
        assertTrue(recorder.seen.isEmpty(), "an agent must not report another agent's Blocks");

        queueOne.emitEntered("y", 2.0);
        assertEquals(Arrays.asList("entered one/q1/y@2.0"), recorder.seen);
    }

    // -- detaching -------------------------------------------------------------------------

    @Test
    void detachingRemovesTheRegistrationItselfNotJustTheReporting() {
        TestAgent facility = agent("facility");
        FakeStage<String> queue = new FakeStage<>("queue");
        facility.hold(queue);
        assertEquals(1, queue.listenerCount());

        Recorder recorder = new Recorder();
        facility.addAgentListener(recorder);

        facility.detachHeld();

        // The direct assertion: the listener is gone from the Block, so a departed agent cannot
        // keep reporting. Checking only that no events arrive would pass even if emit broke.
        assertEquals(0, queue.listenerCount());
        assertTrue(facility.heldBlocks().isEmpty());

        queue.emitEntered("after-departure", 9.0);
        assertTrue(recorder.seen.isEmpty());
    }

    @Test
    void detachingIsIdempotent() {
        TestAgent facility = agent("facility");
        facility.hold(new FakeStage<String>("queue"));
        facility.detachHeld();
        facility.detachHeld();
        assertTrue(facility.heldBlocks().isEmpty());
    }

    // -- capabilities ----------------------------------------------------------------------

    interface Bidder {
        double quote();
    }

    interface Regulator {
    }

    @Test
    void aHeldCapabilityIsReachableUnderTheTypeItWasHeldAs() {
        TestAgent facility = agent("facility");
        Bidder bidder = () -> 42.0;
        facility.hold(Bidder.class, bidder);

        Optional<Bidder> found = facility.view(Bidder.class);
        assertTrue(found.isPresent());
        assertSame(bidder, found.get());
        assertEquals(42.0, found.get().quote(), 1e-9);
    }

    @Test
    void askingForACapabilityAnAgentDoesNotHoldIsEmptyRatherThanAnError() {
        // The normal way to ask "can you do this?" — a market polling for bidders should not have
        // to catch anything, which is the failure mode of a stringly-typed parameter map.
        assertFalse(agent("government").view(Bidder.class).isPresent());
    }

    @Test
    void holdingTwoCapabilitiesUnderOneTypeIsRefused() {
        TestAgent facility = agent("facility");
        facility.hold(Bidder.class, () -> 1.0);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> facility.hold(Bidder.class, () -> 2.0));
        assertTrue(thrown.getMessage().contains("Bidder"));
        assertEquals(1.0, facility.view(Bidder.class).get().quote(), 1e-9, "the first hold still stands");
    }

    @Test
    void oneAgentHoldsSeveralCapabilitiesIndependently() {
        TestAgent owner = agent("owner");
        Bidder bidder = () -> 7.0;
        Regulator regulator = new Regulator() {
        };
        owner.hold(Bidder.class, bidder);
        owner.hold(Regulator.class, regulator);

        assertSame(bidder, owner.view(Bidder.class).get());
        assertSame(regulator, owner.view(Regulator.class).get());
    }

    @Test
    void aCapabilityMustActuallyBeOfTheTypeItIsHeldAs() {
        TestAgent facility = agent("facility");
        // Reachable only by defeating generics, which a model mixing raw types can do by accident.
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class raw = Bidder.class;
        assertThrows(IllegalArgumentException.class, () -> facility.hold(raw, "not a bidder"));
    }

    // -- construction and clock ------------------------------------------------------------

    @Test
    void theTickSourceIsFlooredAtZeroSoTheNotYetRunSentinelNeverEscapes() {
        // Repast's Schedule reports -1 until its first execute(); that is a sentinel, not a time.
        assertEquals(0.0, new TestAgent("a", () -> -1.0).tick(), 1e-9);
        assertEquals(5.0, new TestAgent("a", () -> 5.0).tick(), 1e-9);
    }

    @Test
    void anAgentNeedsANameAndAClock() {
        assertThrows(IllegalArgumentException.class, () -> new TestAgent(null, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TestAgent("", () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TestAgent("a", null));
    }

    @Test
    void heldBlocksIsNotAWayToMutateWhatTheAgentHolds() {
        TestAgent facility = agent("facility");
        facility.hold(new FakeStage<String>("queue"));
        assertThrows(UnsupportedOperationException.class, () -> facility.heldBlocks().clear());
    }
}
