package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import processmodel.foundation.ProcessStage;

/**
 * Containment over Repast subcontexts. The tests that matter most are the two ordering cases and
 * the transitive-membership one, because those are what the subcontext mapping was chosen for — a
 * private tree would have been simpler otherwise.
 */
class ContainmentTest {

    // Distinct types, so container lookup by Java class is actually being exercised.
    static final class Government extends AbstractAgent {
        Government(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Owner extends AbstractAgent {
        Owner(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Facility extends AbstractAgent {
        Facility(String name) {
            super(name, () -> 0.0);
        }
    }

    private static final class Recorder implements AgentListener {
        final List<String> seen = new ArrayList<>();

        @Override
        public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
            seen.add("entered " + agent.getName() + "/" + stage.getName() + "/" + entity);
        }
    }

    // -- the structure ---------------------------------------------------------------------

    @Test
    void containingAnAgentLinksItBothWays() {
        Owner owner = new Owner("owner");
        Facility facility = new Facility("facility");
        owner.contain(facility);

        assertSame(owner, facility.container().get());
        assertEquals(Arrays.asList((Agent) facility), new ArrayList<>(owner.contained()));
        assertTrue(owner.subContext().contains(facility));
        assertSame(owner, owner.subContext().owner(), "the context knows whose it is");
    }

    @Test
    void aLeafAgentNeverAllocatesASubcontext() {
        Owner owner = new Owner("owner");
        Facility facility = new Facility("facility");
        owner.contain(facility);

        assertTrue(owner.hasSubContext());
        assertFalse(facility.hasSubContext(), "leaves are the numerous kind; they should cost nothing");
    }

    @Test
    void membershipIsTransitiveUpwardWhenTheChildsContextExistsFirst() {
        Government gov = new Government("gov");
        Owner owner = new Owner("owner");
        Facility facility = new Facility("facility");

        owner.contain(facility); // owner's subcontext exists before it is itself contained
        gov.contain(owner);

        assertEquals(1, gov.subContext().getObjects(Facility.class).size());
        assertSame(facility, gov.subContext().getObjects(Facility.class).get(0));
        assertEquals(2, gov.subContext().size(), "owner plus the facility beneath it");
    }

    @Test
    void membershipIsTransitiveUpwardWhenTheChildsContextIsCreatedLater() {
        // The ordering wrinkle: the owner is contained while still a leaf, so its own subcontext
        // does not exist yet and has to attach itself to the government's when it is created.
        Government gov = new Government("gov");
        Owner owner = new Owner("owner");
        gov.contain(owner);
        assertFalse(owner.hasSubContext());

        owner.contain(new Facility("facility"));

        assertEquals(1, gov.subContext().getObjects(Facility.class).size());
        assertEquals(2, gov.subContext().size());
    }

    // -- aggregation -----------------------------------------------------------------------

    @Test
    void aLeafBlocksEventReachesTheTopOfTheTreeStillAttributedToTheAgentThatHeldIt() {
        Government gov = new Government("gov");
        Owner owner = new Owner("owner");
        Facility facility = new Facility("facility");
        gov.contain(owner);
        owner.contain(facility);

        FakeStage<String> queue = new FakeStage<>("queue");
        facility.hold(queue);

        Recorder atTheTop = new Recorder();
        gov.addAgentListener(atTheTop);

        queue.emitEntered("cask-1", 1.0);

        // Attributed to the facility, not the government: a listener two levels up still learns
        // whose Block emitted, which is the only attribution worth forwarding.
        assertEquals(Arrays.asList("entered facility/queue/cask-1"), atTheTop.seen);
    }

    @Test
    void anAgentContainedAfterItsBlocksStillForwardsThem() {
        Owner owner = new Owner("owner");
        Facility facility = new Facility("facility");
        FakeStage<String> queue = new FakeStage<>("queue");
        facility.hold(queue);
        owner.contain(facility); // contained after holding

        Recorder recorder = new Recorder();
        owner.addAgentListener(recorder);
        queue.emitEntered("cask-1", 1.0);

        assertEquals(1, recorder.seen.size());
    }

    // -- refusals --------------------------------------------------------------------------

    @Test
    void anAgentCannotBeContainedTwiceOverSinceContainmentIsExclusive() {
        Owner first = new Owner("first");
        Owner second = new Owner("second");
        Facility facility = new Facility("facility");
        first.contain(facility);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> second.contain(facility));
        assertTrue(thrown.getMessage().contains("already contained"));
        assertSame(first, facility.container().get(), "the first containment still stands");
    }

    @Test
    void containingOneOfItsOwnContainersOrItselfIsRefusedRatherThanMakingACycle() {
        Government gov = new Government("gov");
        Owner owner = new Owner("owner");
        gov.contain(owner);

        assertThrows(IllegalStateException.class, () -> owner.contain(gov));
        assertThrows(IllegalStateException.class, () -> owner.contain(owner));
    }

    @Test
    void releasingUndoesTheContextMembershipTheBackReferenceAndTheForwarding() {
        Owner owner = new Owner("owner");
        Facility facility = new Facility("facility");
        FakeStage<String> queue = new FakeStage<>("queue");
        facility.hold(queue);
        owner.contain(facility);

        Recorder recorder = new Recorder();
        owner.addAgentListener(recorder);

        owner.release(facility);

        assertFalse(facility.container().isPresent());
        assertTrue(owner.contained().isEmpty());
        assertFalse(owner.subContext().contains(facility));

        queue.emitEntered("after-release", 2.0);
        assertTrue(recorder.seen.isEmpty(), "a released child must stop forwarding");

        // The child keeps what it holds — release is about structure, not about stripping the agent.
        assertEquals(1, facility.heldBlocks().size());
    }

    @Test
    void releasingSomethingNotContainedIsRefused() {
        assertThrows(
                IllegalStateException.class,
                () -> new Owner("owner").release(new Facility("stranger")));
    }

    // -- traversal -------------------------------------------------------------------------

    @Test
    void containerLookupIsByJavaTypeAndCountsFromTheClosest() {
        Government gov = new Government("gov");
        Owner outer = new Owner("outer");
        Owner inner = new Owner("inner");
        Facility facility = new Facility("facility");
        gov.contain(outer);
        outer.contain(inner);
        inner.contain(facility);

        assertSame(inner, facility.findContainerOfType(Owner.class, 1).get());
        assertSame(outer, facility.findContainerOfType(Owner.class, 2).get());
        assertSame(outer, facility.findContainerOfType(Owner.class, -1).get(), "-1 is the most distant");
        assertSame(gov, facility.findContainerOfType(Government.class, 1).get());

        assertFalse(facility.findContainerOfType(Owner.class, 3).isPresent());
        assertFalse(gov.findContainerOfType(Owner.class, 1).isPresent());

        assertEquals(Arrays.asList(inner, outer), facility.findContainersOfType(Owner.class));
    }

    @Test
    void containmentQuestionsAnswerInBothDirections() {
        Government gov = new Government("gov");
        Owner owner = new Owner("owner");
        Facility facility = new Facility("facility");
        Facility unrelated = new Facility("unrelated");
        gov.contain(owner);
        owner.contain(facility);

        assertTrue(gov.isAbove(facility));
        assertTrue(facility.isBelow(gov));
        assertFalse(facility.isAbove(gov));
        assertFalse(gov.isBelow(facility));

        assertFalse(facility.isBelow(unrelated), "an unrelated agent is neither above nor below");
        assertFalse(facility.isAbove(unrelated));
    }

    @Test
    void containedIsASnapshotAndNotAWayToRestructureTheTree() {
        Owner owner = new Owner("owner");
        owner.contain(new Facility("facility"));
        assertThrows(UnsupportedOperationException.class, () -> owner.contained().clear());
    }
}
