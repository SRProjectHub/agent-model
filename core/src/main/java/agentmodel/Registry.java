package agentmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;

/**
 * Makes agents of a named type, now or at a stated time.
 *
 * <p>Deliberately not part of {@link Population}, which would have conflated two jobs: a registry
 * knows <em>how to make</em> an agent of a given type, a population knows <em>where</em> agents live.
 * Keeping them apart also keeps deployment off populations whose members arrive some other way, by
 * transfer or wired in directly by the model.
 *
 * <p>A type is registered with a factory taking the instance name it should use, which the population
 * mints. That is simpler and safer than registering a configured agent and copying it: copying needs
 * copy construction forbidden outright to stay safe, and it makes every instance's identity a
 * derivative of one template's.
 *
 * <p>The <b>type is named per deployment</b>, not per registry entry or per population, so one
 * population can grow a mixed composition over a run — which is how capacity usually gets built.
 *
 * <p>Deploying at a stated time goes through {@link Membership}, so every mid-run change to who
 * belongs passes through one place and lands in the {@link AgentPriority#STRUCTURE} band. Deploying
 * immediately does not, and is meant for building a scenario before the clock moves.
 */
public final class Registry {

    private final ISchedule schedule;
    private final Membership membership;
    private final Map<String, Function<String, ? extends Agent>> factories = new LinkedHashMap<>();

    public Registry(ISchedule schedule, Membership membership) {
        if (schedule == null) {
            throw new IllegalArgumentException("a registry needs a schedule to deploy on");
        }
        if (membership == null) {
            throw new IllegalArgumentException(
                    "a registry needs a membership, so deployment goes where every other change goes");
        }
        this.schedule = schedule;
        this.membership = membership;
    }

    /**
     * Registers how to make agents of {@code typeName}. The factory is given the instance name to
     * use and must set the same type name on what it returns, since that is what a deployment is
     * later identified by.
     *
     * @throws IllegalStateException if that type name is already registered
     */
    public void register(String typeName, Function<String, ? extends Agent> factory) {
        if (typeName == null || typeName.isEmpty()) {
            throw new IllegalArgumentException("a type needs a name");
        }
        if (factory == null) {
            throw new IllegalArgumentException("a type needs a factory");
        }
        if (factories.containsKey(typeName)) {
            throw new IllegalStateException(typeName + " is already registered");
        }
        factories.put(typeName, factory);
    }

    /** The type names registered, in registration order. */
    public Set<String> registered() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    /**
     * Makes {@code count} agents of this type and adds them to the population straight away, for
     * building a scenario before the clock moves. Mid-run, prefer {@link #deployAt}.
     *
     * @return the agents made, in the order their names were minted
     */
    public <A extends Agent> List<A> deploy(String typeName, int count, Population<A> into) {
        List<A> made = make(typeName, count, into);
        for (A agent : made) {
            into.add(agent);
        }
        return made;
    }

    /**
     * Makes {@code count} agents of this type at {@code tick} and has them join then. Nothing exists
     * until that instant — an agent deployed in a future year is not lurking in the model before it.
     *
     * @throws IllegalArgumentException if the tick has already passed, which would otherwise be a
     *     deployment that silently never happens
     */
    public <A extends Agent> void deployAt(double tick, String typeName, int count, Population<A> into) {
        requireRegistered(typeName);
        requireCount(count);
        if (into == null) {
            throw new IllegalArgumentException("a population to deploy into is required");
        }
        double now = Math.max(0.0, schedule.getTickCount());
        if (tick < now) {
            throw new IllegalArgumentException(
                    "cannot deploy " + typeName + " at " + tick + ", which is already past (now " + now + ")");
        }
        schedule.schedule(
                ScheduleParameters.createOneTime(tick, AgentPriority.STRUCTURE),
                () -> {
                    for (A agent : make(typeName, count, into)) {
                        membership.requestJoin(into, agent);
                    }
                });
    }

    private <A extends Agent> List<A> make(String typeName, int count, Population<A> into) {
        requireRegistered(typeName);
        requireCount(count);
        if (into == null) {
            throw new IllegalArgumentException("a population to deploy into is required");
        }
        Function<String, ? extends Agent> factory = factories.get(typeName);
        List<A> made = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String instanceName = into.mintName();
            Agent produced = factory.apply(instanceName);
            if (produced == null) {
                throw new IllegalStateException("the factory for " + typeName + " returned nothing");
            }
            if (!into.type().isInstance(produced)) {
                throw new IllegalStateException(
                        "the factory for " + typeName + " made a " + produced.getClass().getSimpleName()
                                + ", which " + into.getName() + " cannot hold");
            }
            if (!typeName.equals(produced.typeName())) {
                throw new IllegalStateException(
                        "the factory for " + typeName + " made an agent whose type name is "
                                + produced.typeName() + " — a deployment is identified by its type,"
                                + " so the two have to agree");
            }
            if (!instanceName.equals(produced.getName())) {
                throw new IllegalStateException(
                        "the factory for " + typeName + " ignored the minted name " + instanceName
                                + " and used " + produced.getName() + " instead");
            }
            made.add(into.type().cast(produced));
        }
        return made;
    }

    private void requireRegistered(String typeName) {
        if (!factories.containsKey(typeName)) {
            throw new IllegalStateException(
                    typeName + " is not registered; known types are " + factories.keySet());
        }
    }

    private static void requireCount(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("a deployment makes at least one agent");
        }
    }
}
