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
 * Transferring an agent between containers — a unit reassigned. The point of a distinct
 * operation is that plain release-and-contain silently destroys the agent's relations, which is
 * right for a departure and wrong for a move.
 */
class TransferTest {

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

    private static Mediator mediator() {
        return new Mediator(new repast.simphony.engine.schedule.Schedule());
    }

    @Test
    void aTransferredAgentKeepsItsRelationsWhereReleaseAndContainWouldHaveLostThem() {
        Group top = new Group("top");
        Group origin = new Group("origin");
        Group destination = new Group("destination");
        top.contain(origin);
        top.contain(destination);

        Unit unit = new Unit("alpha", "p-1");
        Unit counterpart = new Unit("alpha", "c-1");
        origin.contain(unit);
        top.contain(counterpart);

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 top.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        dependsOn.connect(unit, counterpart);

        destination.transfer(unit);

        assertSame(destination, unit.container().get(), "the unit changed hands");
        assertTrue(dependsOn.isConnected(unit, counterpart), "and kept supplying its counterpart");
        assertEquals(Arrays.asList((Agent) counterpart), dependsOn.connectedFrom(unit));
    }

    @Test
    void relationsAreKeptInBothDirectionsAndAcrossSeveralAtOnce() {
        Group top = new Group("top");
        Group origin = new Group("origin");
        Group destination = new Group("destination");
        top.contain(origin);
        top.contain(destination);

        Unit unit = new Unit("alpha", "p-1");
        Unit upstream = new Unit("alpha", "m-1");
        Unit downstream = new Unit("gamma", "f-1");
        Unit peer = new Unit("alpha", "p-2");
        origin.contain(unit);
        for (Unit other : Arrays.asList(upstream, downstream, peer)) {
            top.contain(other);
        }

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 top.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        AgentNetwork<String> pairedWith = AgentNetwork.declare(
                mediator(),                 top.subContext(), "pairedWith", Direction.UNDIRECTED, Cardinality.MANY, String.class);

        dependsOn.connect(upstream, unit);   // incoming
        dependsOn.connect(unit, downstream); // outgoing
        pairedWith.connect(unit, peer);          // undirected, a second relation

        destination.transfer(unit);

        assertTrue(dependsOn.isConnected(upstream, unit), "incoming survived");
        assertTrue(dependsOn.isConnected(unit, downstream), "outgoing survived");
        assertTrue(pairedWith.isConnected(unit, peer), "the other relation survived too");
        assertEquals(2, dependsOn.degree(unit));
        assertEquals(1, pairedWith.degree(unit));
    }

    @Test
    void aTransferredAgentKeepsWhatItHoldsAndKeepsForwardingToItsNewContainer() {
        Group top = new Group("top");
        Group origin = new Group("origin");
        Group destination = new Group("destination");
        top.contain(origin);
        top.contain(destination);

        Unit unit = new Unit("alpha", "p-1");
        FakeStage<String> line = new FakeStage<>("stage");
        unit.hold(line);
        origin.contain(unit);

        List<String> atOrigin = new ArrayList<>();
        List<String> atDestination = new ArrayList<>();
        origin.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                atOrigin.add(agent.getName());
            }
        });
        destination.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                atDestination.add(agent.getName());
            }
        });

        destination.transfer(unit);
        line.emitEntered("item", 1.0);

        assertEquals(1, unit.heldBlocks().size(), "a transfer does not strip what the agent holds");
        assertEquals(Arrays.asList("p-1"), atDestination, "events now reach the new container");
        assertTrue(atOrigin.isEmpty(), "and no longer reach the old one");
    }

    @Test
    void transferringBetweenPopulationsNotifiesBothSides() {
        Group top = new Group("top");
        Group origin = new Group("origin");
        Group destination = new Group("destination");
        top.contain(origin);
        top.contain(destination);
        Population<Unit> sourceUnits = origin.population("source_group", Unit.class);
        Population<Unit> targetUnits = destination.population("target_group", Unit.class);

        List<String> events = new ArrayList<>();
        sourceUnits.addPopulationListener(new PopulationListener<Unit>() {
            @Override
            public void left(Population<Unit> population, Unit agent) {
                events.add("left " + population.getName());
            }
        });
        targetUnits.addPopulationListener(new PopulationListener<Unit>() {
            @Override
            public void joined(Population<Unit> population, Unit agent) {
                events.add("joined " + population.getName());
            }
        });

        Unit unit = new Unit("alpha", sourceUnits.mintName());
        sourceUnits.add(unit);
        targetUnits.transfer(unit);

        assertEquals(Arrays.asList("left source_group", "joined target_group"), events);
        assertTrue(targetUnits.contains(unit));
        assertFalse(sourceUnits.contains(unit));
        assertSame(destination, unit.container().get());
    }

    @Test
    void anAgentKeepsItsMintedNameWhenItChangesPopulation() {
        // The name records where an agent came from, not where it currently sits. Renaming on
        // transfer would break every record already written against the old name.
        Group top = new Group("top");
        Group origin = new Group("origin");
        Group destination = new Group("destination");
        top.contain(origin);
        top.contain(destination);
        Population<Unit> sourceUnits = origin.population("source_group", Unit.class);
        Population<Unit> targetUnits = destination.population("target_group", Unit.class);

        Unit unit = new Unit("alpha", sourceUnits.mintName());
        sourceUnits.add(unit);
        targetUnits.transfer(unit);

        assertEquals("source_group-1", unit.getName());
        assertEquals("target_group-1", targetUnits.mintName(), "the new population's own sequence is untouched");
    }

    @Test
    void transferringSomethingUncontainedIsRefused() {
        Group destination = new Group("destination");
        assertThrows(IllegalStateException.class, () -> destination.transfer(new Unit("alpha", "p-1")));
    }

    @Test
    void transferringIntoTheSamePlaceIsANoOp() {
        Group group = new Group("group");
        Unit unit = new Unit("alpha", "p-1");
        group.contain(unit);

        group.transfer(unit);

        assertSame(group, unit.container().get());
        assertEquals(1, group.contained().size(), "not contained twice over");
    }

    @Test
    void transferringIntoOneOfItsOwnDescendantsIsRefused() {
        Group top = new Group("top");
        Group middle = new Group("middle");
        Group inner = new Group("inner");
        top.contain(middle);
        middle.contain(inner);

        assertThrows(IllegalStateException.class, () -> inner.transfer(middle));
        assertSame(top, middle.container().get(), "the refused transfer left the tree alone");
    }

    @Test
    void aConnectionToSomethingThatHasItselfDepartedIsNotRecreated() {
        Group top = new Group("top");
        Group origin = new Group("origin");
        Group destination = new Group("destination");
        top.contain(origin);
        top.contain(destination);

        Unit unit = new Unit("alpha", "p-1");
        Unit gone = new Unit("alpha", "g-1");
        origin.contain(unit);
        top.contain(gone);

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                mediator(),                 top.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        dependsOn.connect(unit, gone);

        top.release(gone); // the counterparty leaves entirely
        destination.transfer(unit);

        assertEquals(0, dependsOn.degree(unit), "a relation to something no longer there is not restored");
        assertSame(destination, unit.container().get());
    }
}
