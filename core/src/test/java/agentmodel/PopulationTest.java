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

class PopulationTest {

    static final class Group extends AbstractAgent {
        Group(String name) {
            super(name, () -> 0.0);
        }
    }

    /** Several types share this one class, which is why a type name is not the same as a class. */
    static final class Unit extends AbstractAgent {
        Unit(String typeName, String name) {
            super(typeName, name, () -> 0.0);
        }
    }

    static final class OtherUnit extends AbstractAgent {
        OtherUnit(String typeName, String name) {
            super(typeName, name, () -> 0.0);
        }
    }

    // -- the three-part identity -----------------------------------------------------------

    @Test
    void anAgentIsIdentifiedByItsClassItsTypeNameAndAMintedInstanceName() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);

        Unit first = new Unit("alpha", units.mintName());
        Unit second = new Unit("alpha", units.mintName());
        units.add(first);
        units.add(second);

        assertEquals(Unit.class, first.getClass());
        assertEquals("alpha", first.typeName());
        assertEquals("unit_group-1", first.getName());
        assertEquals("unit_group-2", second.getName());
    }

    @Test
    void ordinalsAreNeverReusedSoANameIdentifiesOneAgentForTheWholeRun() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);

        Unit first = new Unit("alpha", units.mintName());
        units.add(first);
        units.remove(first);

        assertEquals("unit_group-2", units.mintName(), "the departed agent's ordinal is not handed out again");
    }

    @Test
    void aOneOfAKindAgentTakesItsClassNameAsItsTypeName() {
        assertEquals("Group", new Group("gov").typeName());
    }

    @Test
    void aBlankTypeNameIsRefusedThoughAnAbsentOneIsFine() {
        assertThrows(IllegalArgumentException.class, () -> new Unit("", "r-1"));
    }

    // -- one container, several populations -------------------------------------------------

    @Test
    void oneContainerHoldsSeveralPopulationsIncludingTwoOfTheSameClass() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Population<Unit> altUnits = group.population("alt_group", Unit.class);
        Population<OtherUnit> otherUnits = group.population("other_group", OtherUnit.class);

        units.add(new Unit("alpha", units.mintName()));
        units.add(new Unit("alpha", units.mintName()));
        altUnits.add(new Unit("beta", altUnits.mintName()));
        otherUnits.add(new OtherUnit("gamma", otherUnits.mintName()));

        // Two populations of the same class stay distinct — the thing a type-filtered view could
        // not have done.
        assertEquals(2, units.size());
        assertEquals(1, altUnits.size());
        assertEquals(1, otherUnits.size());
        assertEquals(
                Arrays.asList("unit_group-1", "unit_group-2"),
                Arrays.asList(units.members().get(0).getName(), units.members().get(1).getName()));
        assertEquals("alt_group-1", altUnits.members().get(0).getName());
    }

    @Test
    void twoPopulationsCannotShareANameSinceNamesMintIdentities() {
        Group group = new Group("group");
        group.population("unit_group", Unit.class);
        assertThrows(IllegalStateException.class, () -> group.population("unit_group", Unit.class));
    }

    @Test
    void aPopulationsMembersLiveInItsOwnContextNestedInTheContainers() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit unit = new Unit("alpha", units.mintName());
        units.add(unit);

        assertTrue(units.context().contains(unit));
        assertSame(group, units.context().agent(), "a population subdivides what one agent holds");

        // Still transitive from the container: a root-level typed query finds population members.
        assertEquals(1, group.subContext().getObjects(Unit.class).size());
    }

    @Test
    void theContainerNotThePopulationIsWhatContainsAMember() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit unit = new Unit("alpha", units.mintName());
        units.add(unit);

        assertSame(group, unit.container().get());
        assertTrue(group.contained().contains(unit));
        assertTrue(group.isAbove(unit));
    }

    // -- membership ------------------------------------------------------------------------

    @Test
    void eventsFromAMembersBlockStillForwardUpThroughTheContainer() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit unit = new Unit("alpha", units.mintName());
        FakeStage<String> core = new FakeStage<>("stage");
        unit.hold(core);
        units.add(unit);

        List<String> seen = new ArrayList<>();
        group.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                seen.add(agent.getName() + "/" + stage.getName());
            }
        });

        core.emitEntered("item", 1.0);
        assertEquals(Arrays.asList("unit_group-1/stage"), seen);
    }

    @Test
    void joiningAndLeavingAreNotified() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);

        List<String> events = new ArrayList<>();
        units.addPopulationListener(new PopulationListener<Unit>() {
            @Override
            public void joined(Population<Unit> population, Unit agent) {
                events.add("joined " + population.getName() + "/" + agent.getName());
            }

            @Override
            public void left(Population<Unit> population, Unit agent) {
                events.add("left " + population.getName() + "/" + agent.getName());
            }
        });

        Unit unit = new Unit("alpha", units.mintName());
        units.add(unit);
        units.remove(unit);

        assertEquals(Arrays.asList("joined unit_group/unit_group-1", "left unit_group/unit_group-1"), events);
    }

    @Test
    void removingLeavesTheAgentUncontainedAndOutOfTheContext() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit unit = new Unit("alpha", units.mintName());
        units.add(unit);

        assertTrue(units.remove(unit));
        assertFalse(units.contains(unit));
        assertFalse(unit.container().isPresent());
        assertTrue(units.isEmpty());
        assertFalse(units.remove(unit), "removing a non-member reports false rather than throwing");
    }

    @Test
    void anAgentCannotBeInTwoPopulationsAtOnce() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Population<Unit> altUnits = group.population("alt_group", Unit.class);
        Unit unit = new Unit("alpha", units.mintName());
        units.add(unit);

        assertThrows(IllegalStateException.class, () -> altUnits.add(unit));
    }

    @Test
    void lookupByInstanceName() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        units.add(new Unit("alpha", units.mintName()));
        units.add(new Unit("alpha", units.mintName()));

        assertEquals("unit_group-2", units.byName("unit_group-2").get().getName());
        assertFalse(units.byName("unit_group-99").isPresent());
    }

    @Test
    void aMembersOwnChildrenAreNotCountedAsMembersOfThePopulation() {
        // getObjects recurses into subcontexts, so a unit containing a unit would otherwise
        // inflate the units it belongs to.
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit parent = new Unit("alpha", units.mintName());
        units.add(parent);
        parent.contain(new Unit("nested", "sub-1"));

        assertEquals(1, units.size(), "the nested unit is not a member of this units");
        assertEquals(2, group.subContext().getObjects(Unit.class).size(), "though both are below the group");
    }

    @Test
    void membersIsASnapshotAndNotAWayToMutateThePopulation() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        units.add(new Unit("alpha", units.mintName()));
        assertThrows(UnsupportedOperationException.class, () -> units.members().clear());
    }
}
