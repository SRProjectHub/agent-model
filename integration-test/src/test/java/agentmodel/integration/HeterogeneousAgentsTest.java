package agentmodel.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import agentmodel.AbstractAgent;
import agentmodel.Agent;
import agentmodel.AgentListener;
import agentmodel.AgentNetwork;
import agentmodel.Cardinality;
import agentmodel.Direction;
import agentmodel.Inspection;
import agentmodel.Mediator;
import agentmodel.Membership;
import agentmodel.Population;
import flowmodel.network.FlowNetwork;
import flowmodel.nodes.FlowSource;
import flowmodel.nodes.Tank;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import processmodel.core.Buffer;
import processmodel.foundation.ProcessStage;
import processmodel.foundation.ResourcePool;
import processmodel.foundation.flow.AttributeBlender;
import processmodel.foundation.flow.FlowNode;
import processmodel.foundation.flow.RateProfile;
import processmodel.stages.Activity;
import repast.simphony.engine.schedule.ISchedulableAction;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.Schedule;

/**
 * The scenario neither sibling library proves on its own: a hierarchy of agents where one holds
 * discrete Blocks, another holds continuous nodes, a third is reachable only over a second relation,
 * and one observer at the top sees all of it.
 *
 * <p>This is the claim that justifies a third library at all. If holding, containment, relations and
 * observation only worked for one paradigm, models would still be choosing between them.
 */
class HeterogeneousAgentsTest {

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

    static final class Overseer extends AbstractAgent {
        Overseer(String name, ISchedule schedule) {
            super(name, schedule::getTickCount);
        }
    }

    /** A capability an overseer polls for, to show inspection crossing the hierarchy. */
    interface Reporter {
        double report();
    }

    private static final AttributeBlender<Double> AVERAGE = (parts, quantities) -> {
        double total = 0.0;
        double weighted = 0.0;
        for (int i = 0; i < parts.size(); i++) {
            weighted += parts.get(i) * quantities[i];
            total += quantities[i];
        }
        return total == 0.0 ? 0.0 : weighted / total;
    };

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
    void oneObserverAtTheTopSeesBothParadigmsFromTwoLevelsDown() {
        ISchedule schedule = new Schedule();
        Group top = new Group("top", schedule);
        Group middle = new Group("middle", schedule);
        top.contain(middle);
        Population<Unit> units = middle.population("unit_group", Unit.class);

        // One agent holding a discrete Block.
        Unit discrete = new Unit("alpha", units.mintName(), schedule);
        Activity<String> activity = new Activity<>(
                "activity", schedule, Buffer.fifo(),
                Collections.<Activity.ResourceRequirement<String>>emptyList(), item -> 2.0, item -> null);
        discrete.hold(activity);
        units.add(discrete);

        // Another holding a continuous node, filling at 2/tick into a tank that holds 6.
        Unit continuous = new Unit("beta", units.mintName(), schedule);
        FlowNetwork<Double> network = new FlowNetwork<>(schedule, AVERAGE);
        FlowSource<Double> feed = new FlowSource<>(network, "feed", RateProfile.constant(2.0), 0.0);
        Tank<Double> tank = new Tank<>(network, "tank", 6.0, 0.0, null);
        network.connect(feed.outlet(), tank.inlet());
        continuous.hold(tank);
        units.add(continuous);

        // One registration, at the top of the hierarchy, for everything either kind emits.
        List<String> seen = new ArrayList<>();
        top.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                seen.add("discrete " + agent.getName() + "/" + stage.getName());
            }

            @Override
            public void nodeFull(Agent agent, FlowNode<?> node, double tick) {
                seen.add("continuous " + agent.getName() + "/" + node.getName() + "@" + tick);
            }
        });

        activity.enter("item");
        network.start();
        drive(schedule, 3.0);

        assertEquals(
                Arrays.asList("discrete unit_group-1/activity", "continuous unit_group-2/tank@3.0"),
                seen,
                "both paradigms reach the same listener, each attributed to the agent that held it");
    }

    @Test
    void oneResourcePoolIsSharedAcrossADiscreteAndAContinuousBlockUnderDifferentContainers() {
        // The paradigm-neutral piece of process-model, exercised for real: a pool seized by work on
        // either side, with the two Blocks held by agents in different parts of the hierarchy.
        ISchedule schedule = new Schedule();
        Group top = new Group("top", schedule);
        Group left = new Group("left", schedule);
        Group right = new Group("right", schedule);
        top.contain(left);
        top.contain(right);

        ResourcePool shared = new ResourcePool("shared", 1, schedule::getTickCount);

        Unit discrete = new Unit("alpha", "d-1", schedule);
        Activity<String> activity = new Activity<>(
                "activity", schedule, Buffer.fifo(),
                Collections.singletonList(new Activity.ResourceRequirement<String>(shared, item -> 1)),
                item -> 2.0, item -> null);
        discrete.hold(activity);
        left.contain(discrete);

        Unit continuous = new Unit("beta", "c-1", schedule);
        FlowNetwork<Double> network = new FlowNetwork<>(schedule, AVERAGE);
        Tank<Double> tank = new Tank<>(network, "tank", 10.0, 0.0, null);
        continuous.hold(tank);
        right.contain(continuous);

        List<String> resourceEvents = new ArrayList<>();
        top.addAgentListener(new AgentListener() {
            @Override
            public void resourceAcquired(
                    Agent agent, ResourcePool pool, Object holder, int units, double tick) {
                resourceEvents.add("acquired by " + agent.getName() + " from " + pool.getName());
            }

            @Override
            public void resourceReleased(
                    Agent agent, ResourcePool pool, Object holder, int units, double tick) {
                resourceEvents.add("released by " + agent.getName());
            }
        });

        activity.enter("item");
        drive(schedule, 2.0);

        assertEquals(
                Arrays.asList("acquired by d-1 from shared", "released by d-1"),
                resourceEvents,
                "the pool is one object, and the agent that seized it is named");
        assertTrue(shared.isIdle(), "and it was given back");
        assertSame(left, discrete.container().get());
        assertSame(right, continuous.container().get());
    }

    @Test
    void anOverseerReachesAcrossASecondRelationAndPollsWhatItFinds() {
        ISchedule schedule = new Schedule();
        Mediator mediator = new Mediator(schedule);
        Group top = new Group("top", schedule);
        Group middle = new Group("middle", schedule);
        top.contain(middle);
        Population<Unit> units = middle.population("unit_group", Unit.class);

        Unit answering = new Unit("alpha", units.mintName(), schedule);
        Unit silent = new Unit("alpha", units.mintName(), schedule);
        answering.hold(Reporter.class, () -> 12.0);
        units.add(answering);
        units.add(silent);

        // The overseer is contained at the top, not in the population, and is reachable only over a
        // relation of its own — nothing about containment connects it to what it oversees.
        Overseer overseer = new Overseer("overseer", schedule);
        top.contain(overseer);
        AgentNetwork<String> oversees = mediator.declare(
                top.subContext(), "oversees", Direction.DIRECTED, Cardinality.MANY, String.class);
        oversees.connect(overseer, answering);
        oversees.connect(overseer, silent);

        assertFalse(overseer.isAbove(answering), "oversight is not containment");
        assertSame(middle, answering.container().get());

        List<Inspection<Reporter>> answers =
                Inspection.over(oversees.connectedFrom(overseer), Reporter.class);

        assertEquals(1, answers.size(), "only the one that can answer");
        assertEquals("unit_group-1", answers.get(0).agent().getName());
        assertEquals(12.0, answers.get(0).capability().report(), 1e-9);
    }

    @Test
    void anAgentHoldingBothKindsLosesBothWhenItDeparts() {
        ISchedule schedule = new Schedule();
        Membership membership = new Membership(schedule);
        Group top = new Group("top", schedule);
        Population<Unit> units = top.population("unit_group", Unit.class);

        Unit both = new Unit("alpha", units.mintName(), schedule);
        Activity<String> activity = new Activity<>(
                "activity", schedule, Buffer.fifo(),
                Collections.<Activity.ResourceRequirement<String>>emptyList(), item -> 1.0, item -> null);
        FlowNetwork<Double> network = new FlowNetwork<>(schedule, AVERAGE);
        Tank<Double> tank = new Tank<>(network, "tank", 5.0, 0.0, null);
        both.hold(activity);
        both.hold(tank);
        units.add(both);

        assertEquals(1, both.heldBlocks().size());
        assertEquals(1, both.heldNodes().size());

        List<String> afterDeparture = new ArrayList<>();
        top.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                afterDeparture.add("discrete");
            }

            @Override
            public void levelChanged(Agent agent, FlowNode<?> node, double level, double tick) {
                afterDeparture.add("continuous");
            }
        });

        membership.requestDeparture(both);
        drive(schedule, 0.0);

        assertFalse(units.contains(both));
        assertTrue(both.heldBlocks().isEmpty());
        assertTrue(both.heldNodes().isEmpty(), "the continuous node is released too, not only the Block");

        activity.enter("item");
        network.start();
        drive(schedule, 2.0);
        assertTrue(afterDeparture.isEmpty(), "no stale registration of either kind keeps reporting");
    }
}
