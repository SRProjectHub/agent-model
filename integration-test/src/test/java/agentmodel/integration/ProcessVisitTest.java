package agentmodel.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import agentmodel.AbstractAgent;
import agentmodel.Agent;
import agentmodel.AgentListener;
import agentmodel.Population;
import agentmodel.ProcessVisit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import processmodel.core.Buffer;
import processmodel.foundation.ProcessStage;
import processmodel.stages.Activity;
import processmodel.stages.Queue;
import repast.simphony.engine.schedule.ISchedulableAction;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.Schedule;

/**
 * An agent going through a chain of real Blocks and coming back out. The Blocks here are
 * {@code process-model}'s own — an {@code Activity} that genuinely holds work for a duration against
 * a real {@code Schedule} — which is why this lives in the integration module rather than beside the
 * unit tests: {@code core} depends on {@code foundation} alone and has no concrete Block to use.
 */
class ProcessVisitTest {

    static final class Group extends AbstractAgent {
        Group(String name, ISchedule schedule) {
            super(name, schedule::getTickCount);
        }
    }

    static final class Unit extends AbstractAgent {
        Unit(String typeName, String name, ISchedule schedule) {
            super(typeName, name, schedule::getTickCount);
        }
    }

    /** Runs every action scheduled at or before endTick, then stops — the siblings' drive loop. */
    private static void drive(ISchedule schedule, double endTick) {
        Schedule concrete = (Schedule) schedule;
        while (concrete.getActionCount() > 0) {
            ISchedulableAction next = concrete.peekNextAction();
            if (next == null || next.getNextTime() > endTick) {
                return;
            }
            concrete.execute();
        }
    }

    @Test
    void anAgentPassesThroughAChainAndComesBackStillAMemberOfItsPopulation() {
        ISchedule schedule = new Schedule();
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit traveller = new Unit("alpha", units.mintName(), schedule);
        units.add(traveller);

        ProcessVisit<Unit> visit = new ProcessVisit<>();
        List<String> returned = new ArrayList<>();
        ProcessStage<Unit> exit = visit.exit("exit", unit -> returned.add("returned " + unit.getName()));

        // A real Activity holding the traveller for 4 ticks, then routing it to the exit.
        Activity<Unit> work = new Activity<>(
                "work", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 4.0, unit -> exit);
        visit.watch(work);

        assertFalse(visit.isInProcess(traveller));
        visit.inject(traveller, work);

        assertTrue(visit.isInProcess(traveller), "in the chain from the moment it is injected");
        assertSame(work, traveller.currentBlock().get());
        assertEquals(0.0, traveller.enteredCurrentBlockAt().getAsDouble(), 1e-9);
        assertTrue(units.contains(traveller), "and still a member of its population throughout");

        drive(schedule, 4.0);

        assertFalse(visit.isInProcess(traveller), "the visit is over");
        assertFalse(traveller.currentBlock().isPresent());
        assertEquals(Arrays.asList("returned unit_group-1"), returned);
        assertTrue(units.contains(traveller), "it was never removed — it only went and came back");
        assertSame(group, traveller.container().get());
    }

    @Test
    void theTravellerReportsWhichBlockHoldsItAndSinceWhenAsItMoves() {
        ISchedule schedule = new Schedule();
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit traveller = new Unit("alpha", units.mintName(), schedule);
        units.add(traveller);

        ProcessVisit<Unit> visit = new ProcessVisit<>();
        ProcessStage<Unit> exit = visit.exit("exit");

        // second stage: held 3 ticks, then out
        Activity<Unit> second = new Activity<>(
                "second", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 3.0, unit -> exit);
        // first stage: held 2 ticks, then on to the second
        Activity<Unit> first = new Activity<>(
                "first", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 2.0, unit -> second);
        visit.watch(first);
        visit.watch(second);

        visit.inject(traveller, first);
        assertEquals("first", traveller.currentBlock().get().getName());
        assertEquals(0.0, traveller.enteredCurrentBlockAt().getAsDouble(), 1e-9);

        drive(schedule, 2.0);
        assertEquals("second", traveller.currentBlock().get().getName(), "moved on at tick 2");
        assertEquals(2.0, traveller.enteredCurrentBlockAt().getAsDouble(), 1e-9);

        drive(schedule, 5.0);
        assertFalse(traveller.currentBlock().isPresent());
        assertFalse(visit.isInProcess(traveller));
    }

    @Test
    void aTravellerWaitingForRoomStillReportsTheBlockItIsWaitingAt() {
        // Pinning process-model's own semantics rather than a choice of ours: a Block fires its entered
        // event on arrival, before any buffering, so a traveller held against a full Block reports that
        // Block as holding it. Whether it has actually been taken in is a different question, and the
        // Block answers it — pendingAdmissionCount is what distinguishes the two.
        ISchedule schedule = new Schedule();
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);

        ProcessVisit<Unit> visit = new ProcessVisit<>();
        ProcessStage<Unit> exit = visit.exit("exit");

        // Room for exactly one, and nothing ever releases it.
        Queue<Unit> blocked = new Queue<>("blocked", schedule, Buffer.<Unit>fifo().maxSize(1), unit -> exit);
        Activity<Unit> work = new Activity<>(
                "work", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 1.0, unit -> blocked);
        visit.watch(work);
        visit.watch(blocked);

        Unit occupant = new Unit("alpha", units.mintName(), schedule);
        Unit waiting = new Unit("alpha", units.mintName(), schedule);
        units.add(occupant);
        units.add(waiting);

        visit.inject(occupant, work);
        drive(schedule, 1.0);
        assertEquals("blocked", occupant.currentBlock().get().getName(), "took the only place");

        visit.inject(waiting, work);
        drive(schedule, 2.0);

        assertEquals("blocked", waiting.currentBlock().get().getName(),
                "reported at the Block it arrived at, even though it has not been admitted");
        assertEquals(1, blocked.size(), "only one is actually in");
        assertEquals(1, blocked.pendingAdmissionCount(), "the other is waiting for room");
        assertTrue(visit.isInProcess(waiting), "and both are still travelling");
        assertEquals(Arrays.asList(occupant, waiting), visit.inProcess());
    }

    @Test
    void aChainRoutingToNullEndsItsVisitOnlyWhenToldTo() {
        ISchedule schedule = new Schedule();
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit traveller = new Unit("alpha", units.mintName(), schedule);
        units.add(traveller);

        ProcessVisit<Unit> visit = new ProcessVisit<>();
        // No exit stage: the last Router simply returns null, which is process-model's "leave".
        Activity<Unit> work = new Activity<>(
                "work", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 2.0, unit -> null);
        visit.watch(work);
        visit.inject(traveller, work);
        drive(schedule, 2.0);

        assertFalse(traveller.currentBlock().isPresent(), "it has left the Block");
        assertTrue(visit.isInProcess(traveller), "but nothing reported the return, so it still counts as away");

        assertTrue(visit.returned(traveller));
        assertFalse(visit.isInProcess(traveller));
        assertFalse(visit.returned(traveller), "saying so twice reports that there was nothing to end");
    }

    @Test
    void whatTheTravellerHoldsKeepsReportingWhileItIsAway() {
        ISchedule schedule = new Schedule();
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit traveller = new Unit("alpha", units.mintName(), schedule);
        units.add(traveller);

        // The traveller holds a Block of its own, quite apart from the chain it is passing through.
        Queue<String> own = new Queue<>("own", schedule, Buffer.fifo(), item -> null);
        traveller.hold(own);

        List<String> seenAtGroup = new ArrayList<>();
        group.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                seenAtGroup.add(agent.getName() + "/" + stage.getName());
            }
        });

        ProcessVisit<Unit> visit = new ProcessVisit<>();
        ProcessStage<Unit> exit = visit.exit("exit");
        Activity<Unit> work = new Activity<>(
                "work", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 3.0, unit -> exit);
        visit.watch(work);
        visit.inject(traveller, work);

        own.enter("item");

        assertEquals(
                Arrays.asList("unit_group-1/own"),
                seenAtGroup,
                "being away does not interrupt what it holds, nor its forwarding to its container");
    }

    @Test
    void anAgentCannotBeInjectedTwiceAtOnce() {
        ISchedule schedule = new Schedule();
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit traveller = new Unit("alpha", units.mintName(), schedule);
        units.add(traveller);

        ProcessVisit<Unit> visit = new ProcessVisit<>();
        ProcessStage<Unit> exit = visit.exit("exit");
        Activity<Unit> work = new Activity<>(
                "work", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 5.0, unit -> exit);
        visit.watch(work);

        visit.inject(traveller, work);
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> visit.inject(traveller, work));
        assertTrue(thrown.getMessage().contains("already travelling"));
    }

    @Test
    void theSameAgentMayVisitAgainOnceItHasReturned() {
        ISchedule schedule = new Schedule();
        Group group = new Group("group", schedule);
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit traveller = new Unit("alpha", units.mintName(), schedule);
        units.add(traveller);

        ProcessVisit<Unit> visit = new ProcessVisit<>();
        List<Double> returns = new ArrayList<>();
        ProcessStage<Unit> exit =
                visit.exit("exit", unit -> returns.add(Math.max(0.0, schedule.getTickCount())));
        Activity<Unit> work = new Activity<>(
                "work", schedule, Buffer.fifo(), Collections.<Activity.ResourceRequirement<Unit>>emptyList(),
                unit -> 2.0, unit -> exit);
        visit.watch(work);

        visit.inject(traveller, work);
        drive(schedule, 2.0);
        assertFalse(visit.isInProcess(traveller));

        visit.inject(traveller, work);
        drive(schedule, 4.0);

        assertEquals(Arrays.asList(2.0, 4.0), returns, "a participant runs work through a chain repeatedly");
        assertTrue(units.contains(traveller));
    }
}
