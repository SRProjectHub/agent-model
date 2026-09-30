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
        TestAgent unit = agent("unit");
        FakeStage<String> queue = new FakeStage<>("queue");
        unit.hold(queue);

        Recorder recorder = new Recorder();
        unit.addAgentListener(recorder);

        queue.emitEntered("item-1", 3.0);
        queue.emitExited("item-1", 7.0);

        assertEquals(
                Arrays.asList("entered unit/queue/item-1@3.0", "exited unit/queue/item-1@7.0"),
                recorder.seen);
    }

    @Test
    void resourceEventsComeThroughTheSameRegistration() {
        // ProcessListener extends BlockObserver, so holding a Block picks up its resource events
        // too — no second registration, and the pool is not held by anyone.
        TestAgent unit = agent("unit");
        FakeStage<String> activity = new FakeStage<>("activity");
        unit.hold(activity);

        Recorder recorder = new Recorder();
        unit.addAgentListener(recorder);

        ResourcePool pool = new ResourcePool("pool", 2, () -> 0.0);
        activity.emitResourceAcquired(pool, new Object(), 2, 1.0);
        activity.emitResourceReleased(pool, new Object(), 2, 4.0);

        assertEquals(Arrays.asList("acquired unit/pool/2", "released unit/pool/2"), recorder.seen);
    }

    @Test
    void oneAgentHoldsBlocksOverDifferentEntityTypes() {
        TestAgent unit = agent("unit");
        FakeStage<String> stageA = new FakeStage<>("stage-a");
        FakeStage<Integer> stageB = new FakeStage<>("stage-b");
        unit.hold(stageA);
        unit.hold(stageB);

        Recorder recorder = new Recorder();
        unit.addAgentListener(recorder);

        stageA.emitEntered("item-1", 1.0);
        stageB.emitEntered(42, 2.0);

        assertEquals(
                Arrays.asList("entered unit/stage-a/item-1@1.0", "entered unit/stage-b/42@2.0"),
                recorder.seen);
        assertEquals(2, unit.heldBlocks().size());
    }

    @Test
    void holdingTheSameBlockTwiceIsRefusedRatherThanDoubleReporting() {
        TestAgent unit = agent("unit");
        FakeStage<String> queue = new FakeStage<>("queue");
        unit.hold(queue);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> unit.hold(queue));
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
        TestAgent unit = agent("unit");
        FakeStage<String> queue = new FakeStage<>("queue");
        unit.hold(queue);
        assertEquals(1, queue.listenerCount());

        Recorder recorder = new Recorder();
        unit.addAgentListener(recorder);

        unit.detachHeld();

        // The direct assertion: the listener is gone from the Block, so a departed agent cannot
        // keep reporting. Checking only that no events arrive would pass even if emit broke.
        assertEquals(0, queue.listenerCount());
        assertTrue(unit.heldBlocks().isEmpty());

        queue.emitEntered("after-departure", 9.0);
        assertTrue(recorder.seen.isEmpty());
    }

    @Test
    void detachingIsIdempotent() {
        TestAgent unit = agent("unit");
        unit.hold(new FakeStage<String>("queue"));
        unit.detachHeld();
        unit.detachHeld();
        assertTrue(unit.heldBlocks().isEmpty());
    }

    // -- capabilities ----------------------------------------------------------------------

    interface Reporter {
        double report();
    }

    interface Tagged {
    }

    @Test
    void aHeldCapabilityIsReachableUnderTheTypeItWasHeldAs() {
        TestAgent unit = agent("unit");
        Reporter reporter = () -> 42.0;
        unit.hold(Reporter.class, reporter);

        Optional<Reporter> found = unit.view(Reporter.class);
        assertTrue(found.isPresent());
        assertSame(reporter, found.get());
        assertEquals(42.0, found.get().report(), 1e-9);
    }

    @Test
    void askingForACapabilityAnAgentDoesNotHoldIsEmptyRatherThanAnError() {
        // The normal way to ask "can you do this?" — an agent polling others for a capability should not have
        // to catch anything, which is the failure mode of a stringly-typed parameter map.
        assertFalse(agent("plain").view(Reporter.class).isPresent());
    }

    @Test
    void holdingTwoCapabilitiesUnderOneTypeIsRefused() {
        TestAgent unit = agent("unit");
        unit.hold(Reporter.class, () -> 1.0);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> unit.hold(Reporter.class, () -> 2.0));
        assertTrue(thrown.getMessage().contains("Reporter"));
        assertEquals(1.0, unit.view(Reporter.class).get().report(), 1e-9, "the first hold still stands");
    }

    @Test
    void oneAgentHoldsSeveralCapabilitiesIndependently() {
        TestAgent group = agent("group");
        Reporter reporter = () -> 7.0;
        Tagged tagged = new Tagged() {
        };
        group.hold(Reporter.class, reporter);
        group.hold(Tagged.class, tagged);

        assertSame(reporter, group.view(Reporter.class).get());
        assertSame(tagged, group.view(Tagged.class).get());
    }

    @Test
    void aCapabilityMustActuallyBeOfTheTypeItIsHeldAs() {
        TestAgent unit = agent("unit");
        // Reachable only by defeating generics, which a model mixing raw types can do by accident.
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class raw = Reporter.class;
        assertThrows(IllegalArgumentException.class, () -> unit.hold(raw, "not a reporter"));
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
        TestAgent unit = agent("unit");
        unit.hold(new FakeStage<String>("queue"));
        assertThrows(UnsupportedOperationException.class, () -> unit.heldBlocks().clear());
    }
}
