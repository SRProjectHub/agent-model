package agentmodel;

import static agentmodel.ScheduleTestSupport.advanceTo;
import static agentmodel.ScheduleTestSupport.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import processmodel.foundation.SchedulingPriority;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.Schedule;
import repast.simphony.engine.schedule.ScheduleParameters;

class RegistryTest {

    static final class Group extends AbstractAgent {
        Group(String name, ISchedule schedule) {
            super(name, schedule::getTickCount);
        }
    }

    static class Unit extends AbstractAgent {
        Unit(String typeName, String name, ISchedule schedule) {
            super(typeName, name, schedule::getTickCount);
        }
    }

    static final class OtherUnit extends AbstractAgent {
        OtherUnit(String typeName, String name, ISchedule schedule) {
            super(typeName, name, schedule::getTickCount);
        }
    }

    private static final class World {
        final ISchedule schedule = new Schedule();
        final Membership membership = new Membership(schedule);
        final Registry registry = new Registry(schedule, membership);
        final Group group = new Group("group", schedule);
        final Population<Unit> units = group.population("unit_group", Unit.class);

        World() {
            registry.register("alpha", name -> new Unit("alpha", name, schedule));
            registry.register("beta", name -> new Unit("beta", name, schedule));
        }

        List<String> names() {
            List<String> names = new ArrayList<>();
            for (Unit member : units) {
                names.add(member.getName());
            }
            return names;
        }
    }

    // -- deploying now ----------------------------------------------------------------------

    @Test
    void deployingMakesAgentsWithMintedNamesAndAddsThem() {
        World w = new World();
        List<Unit> made = w.registry.deploy("alpha", 3, w.units);

        assertEquals(3, made.size());
        assertEquals(Arrays.asList("unit_group-1", "unit_group-2", "unit_group-3"), w.names());
        for (Unit unit : made) {
            assertEquals("alpha", unit.typeName());
            assertTrue(w.units.contains(unit));
        }
    }

    @Test
    void onePopulationTakesSeveralTypesSinceTheTypeIsNamedPerDeployment() {
        World w = new World();
        w.registry.deploy("alpha", 2, w.units);
        w.registry.deploy("beta", 1, w.units);

        assertEquals(Arrays.asList("unit_group-1", "unit_group-2", "unit_group-3"), w.names());
        List<String> types = new ArrayList<>();
        for (Unit unit : w.units) {
            types.add(unit.typeName());
        }
        assertEquals(Arrays.asList("alpha", "alpha", "beta"), types);
    }

    @Test
    void theNameSequenceBelongsToThePopulationNotTheRegistry() {
        World w = new World();
        Population<Unit> other = w.group.population("alt_group", Unit.class);

        w.registry.deploy("alpha", 2, w.units);
        w.registry.deploy("alpha", 2, other);

        assertEquals(Arrays.asList("unit_group-1", "unit_group-2"), w.names());
        List<String> otherNames = new ArrayList<>();
        for (Unit unit : other) {
            otherNames.add(unit.getName());
        }
        assertEquals(Arrays.asList("alt_group-1", "alt_group-2"), otherNames);
    }

    // -- deploying later --------------------------------------------------------------------

    @Test
    void anAgentDeployedAtATimeDoesNotExistBeforeThatTime() {
        World w = new World();
        w.registry.deployAt(5.0, "alpha", 2, w.units);

        advanceTo(w.schedule, 4.0);
        assertEquals(0, w.units.size(), "not lurking in the model before its time");

        drive(w.schedule, 5.0);
        assertEquals(2, w.units.size());
        assertEquals(Arrays.asList("unit_group-1", "unit_group-2"), w.names());
    }

    @Test
    void aScheduledDeploymentJoinsAfterTheInstantsOtherWork() {
        // The guarantee comes from Membership, not from the band this schedules its construction in:
        // deployment hands the new agents to requestJoin, and that always drains in STRUCTURE. Moving
        // this class's own scheduling into the highest Block band changes nothing observable, which is
        // why this test is named for the ordering rather than for where the construction sits.
        World w = new World();
        List<String> log = new ArrayList<>();
        w.registry.deployAt(5.0, "alpha", 1, w.units);

        // A Block event at the same instant, in the highest Block band.
        w.schedule.schedule(
                ScheduleParameters.createOneTime(5.0, SchedulingPriority.COMPLETION),
                () -> log.add("block, population size " + w.units.size()));
        w.units.addPopulationListener(new PopulationListener<Unit>() {
            @Override
            public void joined(Population<Unit> population, Unit agent) {
                log.add("joined " + agent.getName());
            }
        });
        drive(w.schedule, 5.0);

        assertEquals(Arrays.asList("block, population size 0", "joined unit_group-1"), log);
    }

    @Test
    void severalDeploymentsAtDifferentTimesArriveInTimeOrder() {
        World w = new World();
        w.registry.deployAt(3.0, "alpha", 1, w.units);
        w.registry.deployAt(1.0, "beta", 1, w.units);
        w.registry.deployAt(2.0, "alpha", 1, w.units);

        drive(w.schedule, 3.0);

        List<String> typesInOrder = new ArrayList<>();
        for (Unit unit : w.units) {
            typesInOrder.add(unit.typeName() + "/" + unit.getName());
        }
        // Names are minted when the deployment fires, so ordinals follow time, not request order.
        assertEquals(
                Arrays.asList("beta/unit_group-1", "alpha/unit_group-2", "alpha/unit_group-3"),
                typesInOrder);
    }

    @Test
    void deployingAtATimeAlreadyPastIsRefusedRatherThanNeverHappening() {
        World w = new World();
        advanceTo(w.schedule, 6.0);
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> w.registry.deployAt(2.0, "alpha", 1, w.units));
        assertTrue(thrown.getMessage().contains("already past"));
    }

    @Test
    void aDeployedAgentCanBeAskedToDepartLikeAnyOther() {
        World w = new World();
        w.registry.deployAt(2.0, "alpha", 1, w.units);
        drive(w.schedule, 2.0);
        Unit deployed = w.units.members().get(0);

        advanceTo(w.schedule, 3.0);
        w.membership.requestDeparture(deployed);
        w.membership.applyPending();

        assertFalse(w.units.contains(deployed));
    }

    // -- refusals ---------------------------------------------------------------------------

    @Test
    void registeringATypeTwiceIsRefused() {
        World w = new World();
        assertThrows(
                IllegalStateException.class,
                () -> w.registry.register("alpha", name -> new Unit("alpha", name, w.schedule)));
    }

    @Test
    void deployingAnUnregisteredTypeSaysWhatIsRegistered() {
        World w = new World();
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> w.registry.deploy("gamma", 1, w.units));
        assertTrue(thrown.getMessage().contains("gamma"));
        assertTrue(thrown.getMessage().contains("alpha"), thrown.getMessage());
    }

    @Test
    void aFactoryThatIgnoresTheTypeNameIsRefused() {
        World w = new World();
        w.registry.register("mismatched", name -> new Unit("something-else", name, w.schedule));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> w.registry.deploy("mismatched", 1, w.units));
        assertTrue(thrown.getMessage().contains("type name"), thrown.getMessage());
    }

    @Test
    void aFactoryThatIgnoresTheMintedNameIsRefused() {
        World w = new World();
        w.registry.register("stubborn", name -> new Unit("stubborn", "my-own-name", w.schedule));

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> w.registry.deploy("stubborn", 1, w.units));
        assertTrue(thrown.getMessage().contains("ignored the minted name"), thrown.getMessage());
    }

    @Test
    void aFactoryMakingSomethingThePopulationCannotHoldIsRefused() {
        World w = new World();
        w.registry.register("wrong-class", name -> new OtherUnit("wrong-class", name, w.schedule));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> w.registry.deploy("wrong-class", 1, w.units));
        assertTrue(thrown.getMessage().contains("cannot hold"), thrown.getMessage());
    }

    @Test
    void aFactoryReturningNothingIsRefused() {
        World w = new World();
        w.registry.register("empty", name -> null);
        assertThrows(IllegalStateException.class, () -> w.registry.deploy("empty", 1, w.units));
    }

    @Test
    void aDeploymentMakesAtLeastOneAgent() {
        World w = new World();
        assertThrows(IllegalArgumentException.class, () -> w.registry.deploy("alpha", 0, w.units));
        assertThrows(IllegalArgumentException.class, () -> w.registry.deployAt(1.0, "alpha", -1, w.units));
    }

    @Test
    void aRegistryNeedsAScheduleAndAMembership() {
        Schedule schedule = new Schedule();
        assertThrows(IllegalArgumentException.class, () -> new Registry(null, new Membership(schedule)));
        assertThrows(IllegalArgumentException.class, () -> new Registry(schedule, null));
    }

    @Test
    void registeredTypesComeBackInRegistrationOrder() {
        World w = new World();
        assertEquals(Arrays.asList("alpha", "beta"), new ArrayList<>(w.registry.registered()));
    }
}
