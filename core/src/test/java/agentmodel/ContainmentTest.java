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
    static final class Overseer extends AbstractAgent {
        Overseer(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Group extends AbstractAgent {
        Group(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Unit extends AbstractAgent {
        Unit(String name) {
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
        Group group = new Group("group");
        Unit unit = new Unit("unit");
        group.contain(unit);

        assertSame(group, unit.container().get());
        assertEquals(Arrays.asList((Agent) unit), new ArrayList<>(group.contained()));
        assertTrue(group.subContext().contains(unit));
        assertSame(group, group.subContext().agent(), "the context knows whose it is");
    }

    @Test
    void aLeafAgentNeverAllocatesASubcontext() {
        Group group = new Group("group");
        Unit unit = new Unit("unit");
        group.contain(unit);

        assertTrue(group.hasSubContext());
        assertFalse(unit.hasSubContext(), "leaves are the numerous kind; they should cost nothing");
    }

    @Test
    void membershipIsTransitiveUpwardWhenTheChildsContextExistsFirst() {
        Overseer gov = new Overseer("gov");
        Group group = new Group("group");
        Unit unit = new Unit("unit");

        group.contain(unit); // group's subcontext exists before it is itself contained
        gov.contain(group);

        assertEquals(1, gov.subContext().getObjects(Unit.class).size());
        assertSame(unit, gov.subContext().getObjects(Unit.class).get(0));
        assertEquals(2, gov.subContext().size(), "group plus the unit beneath it");
    }

    @Test
    void membershipIsTransitiveUpwardWhenTheChildsContextIsCreatedLater() {
        // The ordering wrinkle: the group is contained while still a leaf, so its own subcontext
        // does not exist yet and has to attach itself to the overseer's when it is created.
        Overseer gov = new Overseer("gov");
        Group group = new Group("group");
        gov.contain(group);
        assertFalse(group.hasSubContext());

        group.contain(new Unit("unit"));

        assertEquals(1, gov.subContext().getObjects(Unit.class).size());
        assertEquals(2, gov.subContext().size());
    }

    // -- aggregation -----------------------------------------------------------------------

    @Test
    void aLeafBlocksEventReachesTheTopOfTheTreeStillAttributedToTheAgentThatHeldIt() {
        Overseer gov = new Overseer("gov");
        Group group = new Group("group");
        Unit unit = new Unit("unit");
        gov.contain(group);
        group.contain(unit);

        FakeStage<String> queue = new FakeStage<>("queue");
        unit.hold(queue);

        Recorder atTheTop = new Recorder();
        gov.addAgentListener(atTheTop);

        queue.emitEntered("item-1", 1.0);

        // Attributed to the unit, not the overseer: a listener two levels up still learns
        // whose Block emitted, which is the only attribution worth forwarding.
        assertEquals(Arrays.asList("entered unit/queue/item-1"), atTheTop.seen);
    }

    @Test
    void anAgentContainedAfterItsBlocksStillForwardsThem() {
        Group group = new Group("group");
        Unit unit = new Unit("unit");
        FakeStage<String> queue = new FakeStage<>("queue");
        unit.hold(queue);
        group.contain(unit); // contained after holding

        Recorder recorder = new Recorder();
        group.addAgentListener(recorder);
        queue.emitEntered("item-1", 1.0);

        assertEquals(1, recorder.seen.size());
    }

    // -- refusals --------------------------------------------------------------------------

    @Test
    void anAgentCannotBeContainedTwiceOverSinceContainmentIsExclusive() {
        Group first = new Group("first");
        Group second = new Group("second");
        Unit unit = new Unit("unit");
        first.contain(unit);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> second.contain(unit));
        assertTrue(thrown.getMessage().contains("already contained"));
        assertSame(first, unit.container().get(), "the first containment still stands");
    }

    @Test
    void containingOneOfItsOwnContainersOrItselfIsRefusedRatherThanMakingACycle() {
        Overseer gov = new Overseer("gov");
        Group group = new Group("group");
        gov.contain(group);

        assertThrows(IllegalStateException.class, () -> group.contain(gov));
        assertThrows(IllegalStateException.class, () -> group.contain(group));
    }

    @Test
    void releasingUndoesTheContextMembershipTheBackReferenceAndTheForwarding() {
        Group group = new Group("group");
        Unit unit = new Unit("unit");
        FakeStage<String> queue = new FakeStage<>("queue");
        unit.hold(queue);
        group.contain(unit);

        Recorder recorder = new Recorder();
        group.addAgentListener(recorder);

        group.release(unit);

        assertFalse(unit.container().isPresent());
        assertTrue(group.contained().isEmpty());
        assertFalse(group.subContext().contains(unit));

        queue.emitEntered("after-release", 2.0);
        assertTrue(recorder.seen.isEmpty(), "a released child must stop forwarding");

        // The child keeps what it holds — release is about structure, not about stripping the agent.
        assertEquals(1, unit.heldBlocks().size());
    }

    @Test
    void releasingSomethingNotContainedIsRefused() {
        assertThrows(
                IllegalStateException.class,
                () -> new Group("group").release(new Unit("stranger")));
    }

    // -- traversal -------------------------------------------------------------------------

    @Test
    void containerLookupIsByJavaTypeAndCountsFromTheClosest() {
        Overseer gov = new Overseer("gov");
        Group outer = new Group("outer");
        Group inner = new Group("inner");
        Unit unit = new Unit("unit");
        gov.contain(outer);
        outer.contain(inner);
        inner.contain(unit);

        assertSame(inner, unit.findContainerOfType(Group.class, 1).get());
        assertSame(outer, unit.findContainerOfType(Group.class, 2).get());
        assertSame(outer, unit.findContainerOfType(Group.class, -1).get(), "-1 is the most distant");
        assertSame(gov, unit.findContainerOfType(Overseer.class, 1).get());

        assertFalse(unit.findContainerOfType(Group.class, 3).isPresent());
        assertFalse(gov.findContainerOfType(Group.class, 1).isPresent());

        assertEquals(Arrays.asList(inner, outer), unit.findContainersOfType(Group.class));
    }

    @Test
    void containmentQuestionsAnswerInBothDirections() {
        Overseer gov = new Overseer("gov");
        Group group = new Group("group");
        Unit unit = new Unit("unit");
        Unit unrelated = new Unit("unrelated");
        gov.contain(group);
        group.contain(unit);

        assertTrue(gov.isAbove(unit));
        assertTrue(unit.isBelow(gov));
        assertFalse(unit.isAbove(gov));
        assertFalse(gov.isBelow(unit));

        assertFalse(unit.isBelow(unrelated), "an unrelated agent is neither above nor below");
        assertFalse(unit.isAbove(unrelated));
    }

    @Test
    void containedComesBackInTheOrderAgentsWereContained() {
        // Not incidental. The backing map is identity-keyed and so has no iteration order of its own,
        // which meant this handed back a different order on every JVM run until the order was kept
        // explicitly — the same class of nondeterminism the Block libraries had at one instant.
        Group group = new Group("group");
        List<Agent> expected = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            Unit unit = new Unit("u-" + i);
            group.contain(unit);
            expected.add(unit);
        }

        assertEquals(expected, new ArrayList<>(group.contained()));

        // And a removal from the middle leaves the rest in order.
        group.release((AbstractAgent) expected.get(2));
        expected.remove(2);
        assertEquals(expected, new ArrayList<>(group.contained()));
    }

    @Test
    void containedIsASnapshotAndNotAWayToRestructureTheTree() {
        Group group = new Group("group");
        group.contain(new Unit("unit"));
        assertThrows(UnsupportedOperationException.class, () -> group.contained().clear());
    }
}
