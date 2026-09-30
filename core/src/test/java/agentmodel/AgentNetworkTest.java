package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentNetworkTest {

    static final class Group extends AbstractAgent {
        Group(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Unit extends AbstractAgent {
        Unit(String typeName, String name) {
            super(typeName, name, () -> 0.0);
        }
    }

    /** A message type carried by a relation, to show the declaration fixes one. */
    static final class Notice {
        final double amount;

        Notice(double amount) {
            this.amount = amount;
        }
    }

    /** Root of a small hierarchy: one group with a units, so relations span a subcontext. */
    private static Group groupWithUnits(Population<Unit>[] out) {
        Group group = new Group("group");
        out[0] = group.population("unit_group", Unit.class);
        return group;
    }

    // -- declaration -----------------------------------------------------------------------

    private static Mediator mediator() {
        return new Mediator(new repast.simphony.engine.schedule.Schedule());
    }

    @Test
    void aRelationFixesItsDirectionCardinalityAndMessageTypeWhenDeclared() {
        Group group = new Group("group");
        AgentNetwork<Notice> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, Notice.class);

        assertEquals("dependsOn", dependsOn.getName());
        assertEquals(Direction.DIRECTED, dependsOn.direction());
        assertEquals(Cardinality.MANY, dependsOn.cardinality());
        assertEquals(Notice.class, dependsOn.messageType());
    }

    @Test
    void declarationRefusesMissingPieces() {
        Group group = new Group("group");
        assertThrows(IllegalArgumentException.class, () -> AgentNetwork.declare(
                mediator(),                 null, "dependsOn", Direction.DIRECTED, Cardinality.MANY, Notice.class));
        assertThrows(IllegalArgumentException.class, () -> AgentNetwork.declare(
                mediator(),                 group.subContext(), "", Direction.DIRECTED, Cardinality.MANY, Notice.class));
        assertThrows(IllegalArgumentException.class, () -> AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, null));
    }

    // -- several independent relations over the same agents ---------------------------------

    @Test
    void relationsArePluralAndIndependentOfEachOtherAndOfContainment() {
        @SuppressWarnings("unchecked")
        Population<Unit>[] unitsRef = new Population[1];
        Group group = groupWithUnits(unitsRef);
        Population<Unit> units = unitsRef[0];

        Unit first = new Unit("gamma", units.mintName());
        Unit second = new Unit("beta", units.mintName());
        units.add(first);
        units.add(second);

        AgentNetwork<Notice> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, Notice.class);
        AgentNetwork<String> regulates = AgentNetwork.declare(
                mediator(),                 group.subContext(), "regulates", Direction.DIRECTED, Cardinality.MANY, String.class);

        dependsOn.connect(first, second);

        // A second relation over the same agents is untouched by the first.
        assertTrue(dependsOn.isConnected(first, second));
        assertFalse(regulates.isConnected(first, second));

        // And neither has anything to do with who contains whom.
        assertSame(group, first.container().get());
        assertFalse(dependsOn.isConnected(group, first));
    }

    @Test
    void aRelationReachesAgentsLivingInNestedContexts() {
        // The reason a projection on the top context is enough: contexts re-fire their children's
        // membership events, so agents deep in the hierarchy become vertices without special care.
        @SuppressWarnings("unchecked")
        Population<Unit>[] unitsRef = new Population[1];
        Group group = groupWithUnits(unitsRef);
        Population<Unit> units = unitsRef[0];

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);

        // Added to the population's nested context *after* the relation was declared.
        Unit a = new Unit("alpha", units.mintName());
        Unit b = new Unit("beta", units.mintName());
        units.add(a);
        units.add(b);

        dependsOn.connect(a, b);
        assertTrue(dependsOn.isConnected(a, b));
    }

    // -- direction -------------------------------------------------------------------------

    @Test
    void aDirectedRelationDistinguishesWhoSuppliesWhom() {
        Group group = new Group("group");
        Unit upstream = new Unit("alpha", "m-1");
        Unit downstream = new Unit("beta", "m-2");
        group.contain(upstream);
        group.contain(downstream);

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        dependsOn.connect(upstream, downstream);

        assertTrue(dependsOn.isConnected(upstream, downstream));
        assertFalse(dependsOn.isConnected(downstream, upstream), "direction is meaning, not decoration");

        assertEquals(Arrays.asList((Agent) downstream), dependsOn.connectedFrom(upstream));
        assertEquals(Arrays.asList((Agent) upstream), dependsOn.connectedTo(downstream));
        assertTrue(dependsOn.connectedFrom(downstream).isEmpty());
    }

    @Test
    void anUndirectedRelationConnectsBothWaysInOneAct() {
        Group group = new Group("group");
        Unit one = new Unit("alpha", "p-1");
        Unit two = new Unit("alpha", "p-2");
        group.contain(one);
        group.contain(two);

        AgentNetwork<String> pairedWith = AgentNetwork.declare(
                mediator(),                 group.subContext(), "pairedWith", Direction.UNDIRECTED, Cardinality.MANY, String.class);
        pairedWith.connect(one, two);

        assertTrue(pairedWith.isConnected(one, two));
        assertTrue(pairedWith.isConnected(two, one));
        assertEquals(Arrays.asList((Agent) two), pairedWith.connectedFrom(one));
        assertEquals(Arrays.asList((Agent) one), pairedWith.connectedFrom(two));
    }

    // -- cardinality -----------------------------------------------------------------------

    @Test
    void aOneCardinalityRelationRefusesASecondConnectionRatherThanReplacingIt() {
        Group group = new Group("group");
        Unit unit = new Unit("alpha", "p-1");
        Group first = new Group("first");
        Group second = new Group("second");
        group.contain(unit);
        group.contain(first);
        group.contain(second);

        AgentNetwork<String> assignedTo = AgentNetwork.declare(
                mediator(),                 group.subContext(), "assignedTo", Direction.DIRECTED, Cardinality.ONE, String.class);
        assignedTo.connect(unit, first);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> assignedTo.connect(unit, second));
        assertTrue(thrown.getMessage().contains("disconnect it first"));
        assertSame(first, assignedTo.sole(unit).get(), "the first connection still stands");
    }

    @Test
    void reassigningAOneCardinalityConnectionIsDisconnectThenConnect() {
        Group group = new Group("group");
        Unit unit = new Unit("alpha", "p-1");
        Group first = new Group("first");
        Group second = new Group("second");
        group.contain(unit);
        group.contain(first);
        group.contain(second);

        AgentNetwork<String> assignedTo = AgentNetwork.declare(
                mediator(),                 group.subContext(), "assignedTo", Direction.DIRECTED, Cardinality.ONE, String.class);
        assignedTo.connect(unit, first);
        assertTrue(assignedTo.disconnect(unit, first));
        assignedTo.connect(unit, second);

        assertSame(second, assignedTo.sole(unit).get());
    }

    @Test
    void askingForTheSoleConnectionOfAManyRelationIsRefused() {
        Group group = new Group("group");
        Unit unit = new Unit("alpha", "p-1");
        group.contain(unit);
        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);

        assertThrows(IllegalStateException.class, () -> dependsOn.sole(unit));
    }

    // -- refusals and removal --------------------------------------------------------------

    @Test
    void connectingAnAgentToItselfOrTwiceIsRefused() {
        Group group = new Group("group");
        Unit one = new Unit("alpha", "p-1");
        Unit two = new Unit("alpha", "p-2");
        group.contain(one);
        group.contain(two);
        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);

        assertThrows(IllegalStateException.class, () -> dependsOn.connect(one, one));
        dependsOn.connect(one, two);
        assertThrows(IllegalStateException.class, () -> dependsOn.connect(one, two));
    }

    @Test
    void disconnectingReportsWhetherThereWasAConnection() {
        Group group = new Group("group");
        Unit one = new Unit("alpha", "p-1");
        Unit two = new Unit("alpha", "p-2");
        group.contain(one);
        group.contain(two);
        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);

        assertFalse(dependsOn.disconnect(one, two));
        dependsOn.connect(one, two);
        assertTrue(dependsOn.disconnect(one, two));
        assertFalse(dependsOn.isConnected(one, two));
    }

    @Test
    void disconnectAllClearsBothDirections() {
        Group group = new Group("group");
        Unit middle = new Unit("alpha", "p-mid");
        Unit up = new Unit("alpha", "p-up");
        Unit down = new Unit("alpha", "p-down");
        for (Unit f : Arrays.asList(middle, up, down)) {
            group.contain(f);
        }
        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        dependsOn.connect(up, middle);
        dependsOn.connect(middle, down);

        assertEquals(2, dependsOn.degree(middle));
        assertEquals(2, dependsOn.disconnectAll(middle));
        assertEquals(0, dependsOn.degree(middle));
        assertFalse(dependsOn.isConnected(up, middle));
        assertFalse(dependsOn.isConnected(middle, down));
    }

    @Test
    void disconnectWhereRemovesOnlyTheMatchingConnections() {
        Group group = new Group("group");
        Unit hub = new Unit("alpha", "hub");
        Unit keep = new Unit("keep", "k-1");
        Unit drop = new Unit("discard", "d-1");
        for (Unit f : Arrays.asList(hub, keep, drop)) {
            group.contain(f);
        }
        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        dependsOn.connect(hub, keep);
        dependsOn.connect(hub, drop);

        assertEquals(1, dependsOn.disconnectWhere(hub, other -> other.typeName().equals("discard")));
        assertEquals(Arrays.asList((Agent) keep), dependsOn.connectedFrom(hub));
    }

    // -- the reason a Repast projection is worth using -------------------------------------

    @Test
    void anAgentLeavingTheHierarchyLosesItsConnectionsWithoutAnyoneTidyingUp() {
        @SuppressWarnings("unchecked")
        Population<Unit>[] unitsRef = new Population[1];
        Group group = groupWithUnits(unitsRef);
        Population<Unit> units = unitsRef[0];

        Unit staying = new Unit("alpha", units.mintName());
        Unit leaving = new Unit("alpha", units.mintName());
        units.add(staying);
        units.add(leaving);

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        dependsOn.connect(staying, leaving);
        assertTrue(dependsOn.isConnected(staying, leaving));

        // Removing the agent from its population is the only act; nothing touches the relation.
        units.remove(leaving);

        assertFalse(dependsOn.isConnected(staying, leaving));
        assertEquals(0, dependsOn.degree(staying), "no dangling edge is left behind");
    }

    @Test
    void releasingAnAgentClearsItsConnectionsEvenWhenItStaysUnderTheSameTopContext() {
        // Worth pinning precisely, because it is the mechanism above working as designed and yet
        // surprising: release removes the agent from a context, that removal propagates up to the
        // context the projection lives on, and the projection drops the vertex with its edges. So a
        // unit reassigned from one group to another arrives with no relations at all.
        Group top = new Group("top");
        Group left = new Group("left");
        Group right = new Group("right");
        top.contain(left);
        top.contain(right);
        Unit unit = new Unit("alpha", "p-1");
        Unit counterpart = new Unit("alpha", "p-2");
        left.contain(unit);
        top.contain(counterpart);

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 top.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        dependsOn.connect(unit, counterpart);
        assertTrue(dependsOn.isConnected(unit, counterpart));

        left.release(unit);
        assertFalse(dependsOn.isConnected(unit, counterpart), "release drops the vertex and its edges");
        assertTrue(dependsOn.connectedFrom(unit).isEmpty(), "and querying a non-vertex is empty, not an error");

        right.contain(unit);
        assertFalse(
                dependsOn.isConnected(unit, counterpart),
                "re-containing does not restore what release removed");
    }
}
