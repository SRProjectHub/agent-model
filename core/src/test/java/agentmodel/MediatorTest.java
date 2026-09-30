package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static agentmodel.ScheduleTestSupport.drive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import processmodel.foundation.ProcessStage;
import processmodel.foundation.SchedulingPriority;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.Schedule;
import repast.simphony.engine.schedule.ScheduleParameters;
import repast.simphony.random.RandomHelper;

class MediatorTest {

    static final class Group extends AbstractAgent {
        Group(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Unit extends AbstractAgent {
        Unit(String name, ISchedule schedule) {
            super("alpha", name, schedule::getTickCount);
        }
    }

    /** A fixture: a group of connected units over one relation, with a shared event log. */
    private static final class World {
        final ISchedule schedule = new Schedule();
        final Mediator mediator = new Mediator(schedule);
        final Group group = new Group("group");
        final AgentNetwork<String> dependsOn;
        final List<String> log = new ArrayList<>();

        World() {
            dependsOn = mediator.declare(
                    group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        }

        Unit unit(String name) {
            Unit unit = new Unit(name, schedule);
            group.contain(unit);
            return unit;
        }

        /** Records every message the agent receives, so order is observable. */
        void logging(Unit unit) {
            dependsOn.onReceive(unit, (sender, message, tick) ->
                    log.add(unit.getName() + "<-" + sender.getName() + ":" + message + "@" + tick));
        }
    }

    // -- delivery is its own event, at the same instant -------------------------------------

    @Test
    void sendingDoesNotCallTheReceiver() {
        World w = new World();
        Unit from = w.unit("u-1");
        Unit to = w.unit("u-2");
        w.dependsOn.connect(from, to);
        w.logging(to);

        w.dependsOn.send(from, to, "hello");

        assertTrue(w.log.isEmpty(), "the receiver has not run yet — sending only queues");
        assertEquals(1, w.mediator.pending());

        drive(w.schedule, 0.0);
        assertEquals(Arrays.asList("u-2<-u-1:hello@0.0"), w.log);
        assertEquals(0, w.mediator.pending());
    }

    @Test
    void aMessageSentFromInsideABlockEventIsHandledAfterThatEventAtTheSameInstant() {
        // The contract the whole band scheme exists for: an agent reacting to its own Block may send,
        // and the receiver runs after that Block event finishes rather than nested inside it.
        World w = new World();
        Unit from = w.unit("u-1");
        Unit to = w.unit("u-2");
        w.dependsOn.connect(from, to);
        w.logging(to);

        FakeStage<String> stage = new FakeStage<>("stage");
        from.hold(stage);
        from.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> s, Object entity, double tick) {
                w.log.add("block-event@" + tick);
                w.dependsOn.send(from, to, "triggered");
            }
        });

        // A Block event at tick 5, scheduled in the COMPLETION band as a real Block would be.
        w.schedule.schedule(
                ScheduleParameters.createOneTime(5.0, SchedulingPriority.COMPLETION),
                () -> stage.emitEntered("item", 5.0));
        drive(w.schedule, 5.0);

        assertEquals(
                Arrays.asList("block-event@5.0", "u-2<-u-1:triggered@5.0"),
                w.log,
                "the Block event completes first, then delivery, both at tick 5");
    }

    @Test
    void deliveryRunsAfterEveryBlockBandIncludingTheLowest() {
        World w = new World();
        Unit from = w.unit("u-1");
        Unit to = w.unit("u-2");
        w.dependsOn.connect(from, to);
        w.logging(to);

        w.dependsOn.send(from, to, "m");
        // One action in each Block band at the same instant. INTERACTION sits below all of them.
        for (double band : new double[] {
                SchedulingPriority.COMPLETION, SchedulingPriority.EXPIRY,
                SchedulingPriority.ARRIVAL, SchedulingPriority.SETTLE}) {
            final double b = band;
            w.schedule.schedule(
                    ScheduleParameters.createOneTime(0.0, b), () -> w.log.add("block@" + b));
        }
        drive(w.schedule, 0.0);

        assertEquals(
                Arrays.asList("block@400.0", "block@300.0", "block@200.0", "block@100.0",
                        "u-2<-u-1:m@0.0"),
                w.log);
    }

    // -- ordering ---------------------------------------------------------------------------

    @Test
    void messagesAreDeliveredFirstInFirstOut() {
        World w = new World();
        Unit hub = w.unit("hub");
        List<Unit> targets = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Unit t = w.unit("u-" + i);
            w.dependsOn.connect(hub, t);
            w.logging(t);
            targets.add(t);
        }

        // Sent out of name order, so FIFO and name order differ.
        for (int index : new int[] {2, 0, 3, 1}) {
            w.dependsOn.send(hub, targets.get(index), "m");
        }
        drive(w.schedule, 0.0);

        assertEquals(
                Arrays.asList("u-3<-hub:m@0.0", "u-1<-hub:m@0.0", "u-4<-hub:m@0.0", "u-2<-hub:m@0.0"),
                w.log);
    }

    @Test
    void deliveryOrderDoesNotDependOnTheRandomSeed() {
        List<String> reference = null;
        for (int seed : new int[] {1, 2, 3, 7, 42, 1009}) {
            RandomHelper.setSeed(seed);
            World w = new World();
            Unit hub = w.unit("hub");
            for (int i = 1; i <= 4; i++) {
                Unit t = w.unit("u-" + i);
                w.dependsOn.connect(hub, t);
                w.logging(t);
            }
            w.dependsOn.sendToConnected(hub, "m");
            drive(w.schedule, 0.0);

            if (reference == null) {
                reference = w.log;
            } else {
                assertEquals(reference, w.log, "delivery order must not depend on the seed, seed=" + seed);
            }
        }
    }

    @Test
    void aSendFromInsideAHandlerArrivesInTheSameInstantBehindWhatWasAlreadyWaiting() {
        World w = new World();
        Unit first = w.unit("u-1");
        Unit second = w.unit("u-2");
        Unit third = w.unit("u-3");
        w.dependsOn.connect(first, second);
        w.dependsOn.connect(first, third);
        w.dependsOn.connect(second, third);

        // u-2 forwards on receipt; u-3 just records.
        w.dependsOn.onReceive(second, (sender, message, tick) -> {
            w.log.add("u-2<-" + sender.getName() + ":" + message + "@" + tick);
            w.dependsOn.send(second, third, "forwarded");
        });
        w.logging(third);

        w.dependsOn.send(first, second, "start");
        w.dependsOn.send(first, third, "direct");
        drive(w.schedule, 0.0);

        assertEquals(
                Arrays.asList(
                        "u-2<-u-1:start@0.0",
                        "u-3<-u-1:direct@0.0",
                        "u-3<-u-2:forwarded@0.0"),
                w.log,
                "the forwarded message goes behind the message already queued, not ahead of it");
    }

    @Test
    void sendingToEverythingConnectedFollowsConnectionOrderAndReportsTheCount() {
        World w = new World();
        Unit hub = w.unit("hub");
        List<Unit> targets = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Unit t = w.unit("u-" + i);
            w.logging(t);
            targets.add(t);
        }
        for (int index : new int[] {1, 2, 0}) {
            w.dependsOn.connect(hub, targets.get(index));
        }

        assertEquals(3, w.dependsOn.sendToConnected(hub, "m"));
        drive(w.schedule, 0.0);

        assertEquals(
                Arrays.asList("u-2<-hub:m@0.0", "u-3<-hub:m@0.0", "u-1<-hub:m@0.0"),
                w.log);
    }

    @Test
    void anAgentWithNoHandlerIsAValidTargetAndItsMessagesAreDropped() {
        World w = new World();
        Unit from = w.unit("u-1");
        Unit silent = w.unit("u-2");
        w.dependsOn.connect(from, silent);

        w.dependsOn.send(from, silent, "unheard");
        drive(w.schedule, 0.0);

        assertTrue(w.log.isEmpty());
        assertEquals(0, w.mediator.pending(), "the message was consumed, not left queued");
    }

    @Test
    void aLaterHandlerReplacesAnEarlierOneAndNullRemovesIt() {
        World w = new World();
        Unit from = w.unit("u-1");
        Unit to = w.unit("u-2");
        w.dependsOn.connect(from, to);

        w.dependsOn.onReceive(to, (sender, message, tick) -> w.log.add("first"));
        w.dependsOn.onReceive(to, (sender, message, tick) -> w.log.add("second"));
        w.dependsOn.send(from, to, "m");
        drive(w.schedule, 0.0);
        assertEquals(Arrays.asList("second"), w.log);

        w.dependsOn.onReceive(to, null);
        w.dependsOn.send(from, to, "m");
        drive(w.schedule, 1.0);
        assertEquals(Arrays.asList("second"), w.log, "removing the handler drops later messages");
    }

    // -- refusals ---------------------------------------------------------------------------

    @Test
    void sendingWhereNoRelationExistsIsRefused() {
        World w = new World();
        Unit from = w.unit("u-1");
        Unit unrelated = w.unit("u-2");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> w.dependsOn.send(from, unrelated, "m"));
        assertTrue(thrown.getMessage().contains("no channel to send along"));
    }

    @Test
    void aSendCycleFailsLoudlyRatherThanHanging() {
        ISchedule schedule = new Schedule();
        Mediator mediator = new Mediator(schedule, 50);
        Group group = new Group("group");
        AgentNetwork<String> relation = mediator.declare(
                group.subContext(), "dependsOn", Direction.UNDIRECTED, Cardinality.MANY, String.class);
        Unit one = new Unit("u-1", schedule);
        Unit two = new Unit("u-2", schedule);
        group.contain(one);
        group.contain(two);
        relation.connect(one, two);

        // Each bounces straight back, so the queue never empties.
        relation.onReceive(one, (sender, message, tick) -> relation.send(one, two, "pong"));
        relation.onReceive(two, (sender, message, tick) -> relation.send(two, one, "ping"));

        relation.send(one, two, "start");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> drive(schedule, 0.0));
        assertTrue(thrown.getMessage().contains("send cycle"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("dependsOn"), thrown.getMessage());
    }

    @Test
    void aMediatorNeedsAScheduleAndAtLeastOneAllowedDelivery() {
        assertThrows(IllegalArgumentException.class, () -> new Mediator(null));
        assertThrows(IllegalArgumentException.class, () -> new Mediator(new Schedule(), 0));
    }

    @Test
    void theBandsSitBelowEveryBlockBand() {
        assertTrue(AgentPriority.INTERACTION < SchedulingPriority.SETTLE);
        assertTrue(AgentPriority.STRUCTURE < AgentPriority.INTERACTION);
    }
}
