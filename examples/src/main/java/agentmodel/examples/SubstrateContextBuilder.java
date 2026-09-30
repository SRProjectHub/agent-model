package agentmodel.examples;

import agentmodel.AbstractAgent;
import agentmodel.Agent;
import agentmodel.AgentListener;
import agentmodel.AgentNetwork;
import agentmodel.Cardinality;
import agentmodel.Direction;
import agentmodel.DepartureGuard;
import agentmodel.Inspection;
import agentmodel.Mediator;
import agentmodel.Membership;
import agentmodel.Population;
import agentmodel.Registry;
import java.util.List;
import processmodel.core.Buffer;
import processmodel.foundation.ProcessStage;
import processmodel.stages.Queue;
import repast.simphony.context.Context;
import repast.simphony.dataLoader.ContextBuilder;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.ISchedule;

/**
 * How a real Repast Simphony model wires this library up, as distinct from the test-only pattern of
 * building a bare {@code new Schedule()} by hand. Inside a real run the runtime has already
 * initialised {@code RunEnvironment} before {@link #build} is called, so a {@link ContextBuilder}
 * retrieves the running schedule rather than making one.
 *
 * <p>Deliberately abstract. The agent types here are a container, a working unit and an overseer,
 * because this library has no opinion about what a model's agents represent; substitute your own and
 * the wiring is unchanged.
 *
 * <p>Nothing is added to the {@link Context} directly. Agents live in their container's subcontext,
 * and attaching the top of the hierarchy is the one line that puts them all in — after which Repast's
 * own population tooling sees every agent beneath it, because membership is transitive upward.
 */
public class SubstrateContextBuilder implements ContextBuilder<Object> {

    /** A container: holds other agents and observes what they do, but does no work of its own. */
    public static final class Group extends AbstractAgent {
        public Group(String name, ISchedule schedule) {
            super(name, schedule::getTickCount);
        }
    }

    /** A working agent: holds a Block, and can answer how much it has waiting. */
    public static final class WorkingUnit extends AbstractAgent implements Backlog {
        private final Queue<Object> intake;

        public WorkingUnit(String typeName, String name, ISchedule schedule) {
            super(typeName, name, schedule::getTickCount);
            this.intake = new Queue<>(name + "-intake", schedule, Buffer.fifo(), item -> null);
            hold(intake);
            // Published under the capability, not the class, so a poller needs no knowledge of this
            // type at all.
            hold(Backlog.class, this);
        }

        public ProcessStage<Object> intake() {
            return intake;
        }

        @Override
        public int waiting() {
            return intake.size();
        }
    }

    /** What an overseer asks a unit. Read-only, as every inspected capability must be. */
    public interface Backlog {
        int waiting();
    }

    /** What travels between agents over the relation declared below. */
    public static final class Notice {
        public final String text;

        public Notice(String text) {
            this.text = text;
        }
    }

    @Override
    public Context build(Context<Object> context) {
        ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();

        // One mediator and one membership serve the whole run: all agent traffic shares an ordering,
        // and every mid-run change to who belongs goes through one place.
        Mediator mediator = new Mediator(schedule);
        Membership membership = new Membership(schedule);
        Registry registry = new Registry(schedule, membership);

        // The hierarchy. Containment is exclusive and defines where an agent lives.
        Group top = new Group("top", schedule);
        Group operator = new Group("operator", schedule);
        top.contain(operator);

        // A named, typed population of its own, which mints its members' instance names.
        Population<WorkingUnit> units = operator.population("unit_group", WorkingUnit.class);

        // Types are registered once, by name, with a factory taking the name the population mints.
        registry.register("alpha", name -> new WorkingUnit("alpha", name, schedule));
        registry.register("beta", name -> new WorkingUnit("beta", name, schedule));

        // Two now, and more later: a deployment names its own type, so one population can grow a
        // mixed composition over a run.
        registry.deploy("alpha", 2, units);
        registry.deployAt(100.0, "beta", 1, units);

        // A relation, which is also the channel its messages travel along. Declared through the
        // mediator because a channel with no way to deliver is half a thing.
        AgentNetwork<Notice> dependsOn = mediator.declare(
                top.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, Notice.class);

        List<WorkingUnit> deployed = units.members();
        dependsOn.connect(deployed.get(0), deployed.get(1));
        dependsOn.onReceive(deployed.get(1), (sender, notice, tick) -> {
            // Runs in its own event at the instant it was sent, after every Block event there.
            // Acting here is expected — that is what separates an interaction from an inspection.
        });

        // An overseer, related to the units but containing none of them: oversight is not containment.
        Group overseer = new Group("overseer", schedule);
        top.contain(overseer);
        AgentNetwork<Notice> oversees = mediator.declare(
                top.subContext(), "oversees", Direction.DIRECTED, Cardinality.MANY, Notice.class);
        for (WorkingUnit unit : deployed) {
            oversees.connect(overseer, unit);
        }

        // One registration at the top sees everything any agent beneath it holds, attributed to the
        // agent that held it rather than to the container that heard about it.
        top.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                // A model would record this, or drive a decision from it.
            }
        });

        // Polling many agents for one capability, which is the shape a ranked decision takes: ask
        // everyone reachable, keep those that can answer, decide locally.
        List<Inspection<Backlog>> answers =
                Inspection.over(oversees.connectedFrom(overseer), Backlog.class);
        for (Inspection<Backlog> answer : answers) {
            answer.agent().getName();
            answer.capability().waiting();
        }

        // A unit may not leave while it still has work waiting. Refusing is a "not yet": the request
        // stays pending and is asked again, and the model says when to ask by calling applyPending.
        DepartureGuard idle = agent -> ((WorkingUnit) agent).waiting() == 0;
        for (WorkingUnit unit : deployed) {
            membership.guard(unit, idle);
        }

        // Attaching the top of the hierarchy is what puts every agent beneath it into the model's own
        // context, since a context's membership includes its subcontexts'.
        context.addSubContext(top.subContext());
        return context;
    }

}
