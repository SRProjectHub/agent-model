package agentmodel;

import static agentmodel.ScheduleTestSupport.advanceTo;
import static agentmodel.ScheduleTestSupport.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class MembershipTest {

    static final class Group extends AbstractAgent {
        Group(String name, ISchedule schedule) {
            super(name, schedule::getTickCount);
        }
    }

    static final class Unit extends AbstractAgent {
        Unit(String name, ISchedule schedule) {
            super("alpha", name, schedule::getTickCount);
        }
    }

    private static final class World {
        final ISchedule schedule = new Schedule();
        final Membership membership = new Membership(schedule);
        final Group group = new Group("group", schedule);
        final Population<Unit> units = group.population("unit_group", Unit.class);
        final List<String> log = new ArrayList<>();

        Unit joined(String name) {
            Unit unit = new Unit(name, schedule);
            units.add(unit);
            return unit;
        }
    }

    // -- deferral ---------------------------------------------------------------------------

    @Test
    void aDepartureDoesNotTakeEffectUntilTheStructureBand() {
        World w = new World();
        Unit unit = w.joined("u-1");

        w.membership.requestDeparture(unit);

        assertTrue(w.units.contains(unit), "still a member — the request is only queued");
        assertEquals(Arrays.asList((Agent) unit), w.membership.pendingDepartures());

        drive(w.schedule, 0.0);

        assertFalse(w.units.contains(unit));
        assertFalse(unit.container().isPresent());
        assertTrue(w.membership.pendingDepartures().isEmpty());
    }

    @Test
    void anAgentCannotVanishFromUnderAWalkOverThePopulation() {
        // The fault the whole band exists to prevent. A Block event at an instant asks every member
        // to leave while iterating them; nothing is removed until that walk is long over.
        World w = new World();
        for (int i = 1; i <= 4; i++) {
            w.joined("u-" + i);
        }

        w.schedule.schedule(
                ScheduleParameters.createOneTime(3.0, SchedulingPriority.COMPLETION),
                () -> {
                    for (Unit member : w.units) {
                        w.log.add("walked " + member.getName());
                        w.membership.requestDeparture(member);
                    }
                    w.log.add("walk finished with " + w.units.size() + " members");
                });
        drive(w.schedule, 3.0);

        assertEquals(
                Arrays.asList("walked u-1", "walked u-2", "walked u-3", "walked u-4",
                        "walk finished with 4 members"),
                w.log);
        assertEquals(0, w.units.size(), "and all four are gone once the instant's structure band runs");
    }

    @Test
    void joinsAndDeparturesApplyAfterTheInstantsMessagesHaveBeenDelivered() {
        ISchedule schedule = new Schedule();
        Mediator mediator = new Mediator(schedule);
        Membership membership = new Membership(schedule);
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit from = new Unit("u-1", schedule);
        Unit to = new Unit("u-2", schedule);
        units.add(from);
        units.add(to);

        AgentNetwork<String> relation = mediator.declare(
                group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        relation.connect(from, to);

        List<String> log = new ArrayList<>();
        relation.onReceive(to, (sender, message, tick) -> log.add("delivered@" + tick));

        relation.send(from, to, "m");
        membership.requestDeparture(to);
        drive(schedule, 0.0);

        assertEquals(Arrays.asList("delivered@0.0"), log, "the message arrived before its receiver left");
        assertFalse(units.contains(to));
    }

    @Test
    void aJoinRequestedMidInstantAppearsOnlyAfterward() {
        World w = new World();
        w.joined("u-1");
        Unit late = new Unit("u-2", w.schedule);

        w.schedule.schedule(
                ScheduleParameters.createOneTime(2.0, SchedulingPriority.COMPLETION),
                () -> {
                    w.membership.requestJoin(w.units, late);
                    w.log.add("during the event: " + w.units.size());
                });
        drive(w.schedule, 2.0);

        assertEquals(Arrays.asList("during the event: 1"), w.log);
        assertEquals(2, w.units.size());
        assertTrue(w.units.contains(late));
    }

    // -- guards -----------------------------------------------------------------------------

    @Test
    void aRefusingGuardKeepsTheAgentAndIsAskedAgainLater() {
        World w = new World();
        Unit unit = w.joined("u-1");
        final boolean[] mayGo = {false};
        final int[] asked = {0};

        w.membership.guard(unit, agent -> {
            asked[0]++;
            return mayGo[0];
        });
        w.membership.requestDeparture(unit);

        drive(w.schedule, 0.0);
        assertTrue(w.units.contains(unit), "refused, so it stays");
        assertEquals(1, asked[0]);
        assertEquals(Arrays.asList((Agent) unit), w.membership.pendingDepartures());

        // The condition changes, and the model says so.
        mayGo[0] = true;
        advanceTo(w.schedule, 1.0);
        w.membership.applyPending();
        drive(w.schedule, 1.0);

        assertEquals(2, asked[0]);
        assertFalse(w.units.contains(unit), "asked again, agreed, gone");
        assertTrue(w.membership.pendingDepartures().isEmpty());
    }

    @Test
    void aGuardRefusingForeverLeavesTheAgentInPlaceRatherThanFailing() {
        World w = new World();
        Unit unit = w.joined("u-1");
        w.membership.guard(unit, agent -> false);
        w.membership.requestDeparture(unit);

        for (double tick = 0.0; tick <= 3.0; tick += 1.0) {
            advanceTo(w.schedule, tick);
            w.membership.applyPending();
            drive(w.schedule, tick);
        }

        assertTrue(w.units.contains(unit));
        assertEquals(1, w.membership.pendingDepartures().size(), "still waiting, not lost and not forced");
    }

    @Test
    void removingAGuardLetsThePendingDepartureThrough() {
        World w = new World();
        Unit unit = w.joined("u-1");
        w.membership.guard(unit, agent -> false);
        w.membership.requestDeparture(unit);
        drive(w.schedule, 0.0);
        assertTrue(w.units.contains(unit));

        w.membership.guard(unit, null);
        advanceTo(w.schedule, 1.0);
        w.membership.applyPending();
        drive(w.schedule, 1.0);

        assertFalse(w.units.contains(unit));
    }

    @Test
    void oneAgentsGuardDoesNotHoldUpAnother() {
        World w = new World();
        Unit stuck = w.joined("u-1");
        Unit free = w.joined("u-2");
        w.membership.guard(stuck, agent -> false);

        w.membership.requestDeparture(stuck);
        w.membership.requestDeparture(free);
        drive(w.schedule, 0.0);

        assertTrue(w.units.contains(stuck));
        assertFalse(w.units.contains(free));
        assertEquals(1, w.membership.pendingDepartures().size());
    }

    // -- what departing does ----------------------------------------------------------------

    @Test
    void departingUnregistersEverythingTheAgentHadInstalledOnItsBlocks() {
        World w = new World();
        Unit unit = w.joined("u-1");
        FakeStage<String> stage = new FakeStage<>("stage");
        unit.hold(stage);
        assertEquals(1, stage.listenerCount());

        w.membership.requestDeparture(unit);
        drive(w.schedule, 0.0);

        assertEquals(0, stage.listenerCount(), "no stale registration reporting for an agent that left");
        assertTrue(unit.heldBlocks().isEmpty());
    }

    @Test
    void departingNotifiesThePopulationItLeft() {
        World w = new World();
        Unit unit = w.joined("u-1");
        w.units.addPopulationListener(new PopulationListener<Unit>() {
            @Override
            public void left(Population<Unit> population, Unit agent) {
                w.log.add("left " + population.getName() + "/" + agent.getName());
            }
        });

        w.membership.requestDeparture(unit);
        drive(w.schedule, 0.0);

        assertEquals(Arrays.asList("left unit_group/u-1"), w.log);
    }

    @Test
    void departingTakesTheAgentsRelationsWithIt() {
        ISchedule schedule = new Schedule();
        Mediator mediator = new Mediator(schedule);
        Membership membership = new Membership(schedule);
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit staying = new Unit("u-1", schedule);
        Unit leaving = new Unit("u-2", schedule);
        units.add(staying);
        units.add(leaving);

        AgentNetwork<String> relation = mediator.declare(
                group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        relation.connect(staying, leaving);

        membership.requestDeparture(leaving);
        drive(schedule, 0.0);

        assertFalse(relation.isConnected(staying, leaving));
        assertEquals(0, relation.degree(staying));
    }

    // -- requests -----------------------------------------------------------------------------

    @Test
    void repeatingADepartureRequestDoesNothing() {
        World w = new World();
        Unit unit = w.joined("u-1");
        w.membership.requestDeparture(unit);
        w.membership.requestDeparture(unit);
        assertEquals(1, w.membership.pendingDepartures().size());
    }

    @Test
    void askingAnUncontainedAgentToDepartIsRefused() {
        World w = new World();
        assertThrows(
                IllegalStateException.class,
                () -> w.membership.requestDeparture(new Unit("stranger", w.schedule)));
    }

    @Test
    void anAgentJoiningAndLeavingInTheSameInstantExistsBeforeItGoes() {
        // Joins are applied before departures in a drain, so the departure finds something to do
        // rather than silently failing against an agent that is not there yet.
        World w = new World();
        Unit unit = new Unit("u-1", w.schedule);
        w.units.addPopulationListener(new PopulationListener<Unit>() {
            @Override
            public void joined(Population<Unit> population, Unit agent) {
                w.log.add("joined");
            }

            @Override
            public void left(Population<Unit> population, Unit agent) {
                w.log.add("left");
            }
        });

        w.membership.requestJoin(w.units, unit);
        // Requested before the join has been applied, which is the awkward ordering.
        w.schedule.schedule(
                ScheduleParameters.createOneTime(0.0, SchedulingPriority.SETTLE),
                () -> w.membership.requestDeparture(unit));
        drive(w.schedule, 0.0);

        assertEquals(Arrays.asList("joined", "left"), w.log);
        assertEquals(0, w.units.size());
    }

    @Test
    void membershipNeedsASchedule() {
        assertThrows(IllegalArgumentException.class, () -> new Membership(null));
    }

    @Test
    void theStructureBandIsBelowTheInteractionBand() {
        assertTrue(AgentPriority.STRUCTURE < AgentPriority.INTERACTION);
        assertEquals(25.0, AgentPriority.STRUCTURE, 1e-9);
    }
}
