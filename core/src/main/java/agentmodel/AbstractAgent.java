package agentmodel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import processmodel.foundation.ProcessListener;
import processmodel.foundation.ProcessStage;
import processmodel.foundation.ResourcePool;

/**
 * The plumbing every agent needs: what it holds, who is watching, and a clock that is safe to read.
 *
 * <p>Holding is the central act. An agent holds <b>Blocks</b>, whose events it re-fires to its own
 * listeners with itself named as the source, and <b>capability objects</b>, which are how an agent
 * says what it can do. Capabilities are held rather than implemented because Java permits a single
 * superclass, so an agent cannot inherit one interface per role — and holding is the better answer
 * regardless: an agent can hold several capabilities of different kinds, swap one at runtime, and
 * keep the implementing class private behind the type it is published under.
 *
 * <p>Registration lifetime is this class's responsibility, not the model's. Every listener it
 * installs on a held Block is remembered alongside a way to remove it, and {@link #detachHeld}
 * undoes all of them at once. Leaving that to the caller invites the failure it is meant to
 * prevent: a stale registration would not crash, it would quietly keep reporting events for an
 * agent that has already left, which is harder to notice and harder to trace.
 */
public abstract class AbstractAgent implements Agent {

    private final String typeName;
    private final String name;
    private final DoubleSupplier tickSource;

    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();

    // Identity-keyed: two Blocks are never "equal", and a model may well hold several that would
    // compare equal by value. The value is how to unregister the listener installed for that Block.
    private final Map<ProcessStage<?>, Runnable> heldStages = new IdentityHashMap<>();

    // Insertion-ordered so iteration is reproducible; keyed by the type the capability was held
    // as, which is what makes view() a typed question rather than a string lookup.
    private final Map<Class<?>, Object> capabilities = new LinkedHashMap<>();

    // Containment. The subcontext is authoritative for contained agents and is created lazily, so
    // numerous leaf agents never allocate one. `container` is a cached inverse maintained only by
    // contain/release, in the manner of a doubly-linked list rather than a second structure.
    private AgentContext subContext;
    private volatile AbstractAgent container;

    // The context this agent actually lives in once contained — its container's own context, or a
    // population's context nested inside it. Kept so a subcontext created later attaches in the
    // right place rather than always at the container's top level.
    private AgentContext homeContext;

    // Identity-keyed, because two agents are never "equal" and a model is free to give two of them
    // equal fields — the same reason ResourcePool keys its holders by identity. IdentityHashMap has
    // no defined iteration order though, so the order agents were contained in is kept alongside it;
    // otherwise contained() would hand back a different order on every JVM run.
    private final Map<AbstractAgent, Containment> containments = new IdentityHashMap<>();
    private final List<AbstractAgent> containedInOrder = new ArrayList<>();

    // Keyed by name because names are what mint instance names, so two populations sharing one
    // would hand out colliding identities.
    private final Map<String, Population<?>> populations = new LinkedHashMap<>();

    /** What one containment installed, so releasing it can undo exactly that. */
    private static final class Containment {
        final AgentListener forwarder;
        final AgentContext where;

        Containment(AgentListener forwarder, AgentContext where) {
            this.forwarder = forwarder;
            this.where = where;
        }
    }

    /** Takes the concrete class's simple name as the type name, for a one-of-a-kind agent. */
    protected AbstractAgent(String name, DoubleSupplier tickSource) {
        this(null, name, tickSource);
    }

    protected AbstractAgent(String typeName, String name, DoubleSupplier tickSource) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("an agent needs a name");
        }
        if (tickSource == null) {
            throw new IllegalArgumentException("an agent needs a tick source");
        }
        if (typeName != null && typeName.isEmpty()) {
            throw new IllegalArgumentException("a type name may be omitted but not blank");
        }
        this.typeName = typeName == null ? getClass().getSimpleName() : typeName;
        this.name = name;
        this.tickSource = tickSource;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String typeName() {
        return typeName;
    }

    /**
     * The current simulation time, floored at 0. Repast's {@code Schedule} reports tick {@code -1}
     * until its first {@code execute()} — a "not yet run" sentinel, not a time — and one clamp site
     * is what keeps it out of every timestamp the agent reports.
     */
    protected final double currentTick() {
        return Math.max(0.0, tickSource.getAsDouble());
    }

    // -- listeners -------------------------------------------------------------------------

    @Override
    public final void addAgentListener(AgentListener listener) {
        listeners.add(listener);
    }

    @Override
    public final void removeAgentListener(AgentListener listener) {
        listeners.remove(listener);
    }

    // -- containment -----------------------------------------------------------------------

    /**
     * The Repast context holding the agents this one contains, created on first use. A leaf never
     * allocates one, which matters because leaves are the numerous kind.
     *
     * <p>Exposed so a model can attach the top of its hierarchy into its own root context, and so
     * a display or dataset can be pointed at one container's population.
     */
    public final AgentContext subContext() {
        if (subContext == null) {
            subContext = new AgentContext(this);
            // If this agent is already contained, its own context belongs under whichever context
            // it lives in, so membership stays transitive however late the context comes into
            // existence — and a population member nests under that population, not beside it.
            if (homeContext != null) {
                homeContext.addSubContext(subContext);
            }
        }
        return subContext;
    }

    /** Whether this agent has allocated a subcontext yet — true once it has ever had a child. */
    public final boolean hasSubContext() {
        return subContext != null;
    }

    /**
     * Contains {@code child}, exclusively: the child joins this agent's subcontext, gains a
     * back-reference to this agent, and its events begin forwarding up to this agent's listeners.
     *
     * <p>Deliberately a different verb from {@link #hold}. Holding a Block or a capability is a
     * reference with observation attached; containing an agent is structural — exclusive, mutual,
     * and the thing that defines where an agent lives.
     *
     * <p>Forwarded events keep their <em>original</em> attribution: a listener high in the tree is
     * told which agent's Block emitted, not merely that something beneath it did.
     *
     * @throws IllegalStateException if the child is already contained, or if containing it would
     *     make a cycle
     */
    public final void contain(AbstractAgent child) {
        containIn(child, subContext());
    }

    /** The containing agent, or null at the top — the concrete type, for package-internal walks. */
    final AbstractAgent containerAgent() {
        return container;
    }

    /** The context this agent lives in, or null while it is uncontained. */
    final AgentContext homeContext() {
        return homeContext;
    }

    /** Containment placing the child in a specific context — how a population holds its members. */
    final void containIn(AbstractAgent child, AgentContext where) {
        if (child == null) {
            throw new IllegalArgumentException("cannot contain null");
        }
        if (child == this) {
            throw new IllegalStateException(name + " cannot contain itself");
        }
        if (child.container != null) {
            throw new IllegalStateException(
                    child.getName() + " is already contained by " + child.container.getName());
        }
        if (this.isBelow(child)) {
            throw new IllegalStateException(
                    "containing " + child.getName() + " in " + name + " would make a cycle");
        }

        where.add(child);
        if (child.subContext != null) {
            where.addSubContext(child.subContext);
        }
        child.container = this;
        child.homeContext = where;

        AgentListener forwarder = new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                for (AgentListener listener : listeners) {
                    listener.entityEntered(agent, stage, entity, tick);
                }
            }

            @Override
            public void entityExited(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                for (AgentListener listener : listeners) {
                    listener.entityExited(agent, stage, entity, tick);
                }
            }

            @Override
            public void resourceAcquired(
                    Agent agent, ResourcePool pool, Object holder, int units, double tick) {
                for (AgentListener listener : listeners) {
                    listener.resourceAcquired(agent, pool, holder, units, tick);
                }
            }

            @Override
            public void resourceReleased(
                    Agent agent, ResourcePool pool, Object holder, int units, double tick) {
                for (AgentListener listener : listeners) {
                    listener.resourceReleased(agent, pool, holder, units, tick);
                }
            }
        };
        child.addAgentListener(forwarder);
        containments.put(child, new Containment(forwarder, where));
        containedInOrder.add(child);
    }

    /**
     * Undoes {@link #contain}: the child leaves this agent's subcontext, loses its back-reference,
     * and stops forwarding. The child itself is otherwise untouched — it keeps what it holds.
     *
     * @throws IllegalStateException if this agent does not contain that child
     */
    public final void release(AbstractAgent child) {
        Containment containment = containments.remove(child);
        if (containment == null) {
            throw new IllegalStateException(name + " does not contain " + child.getName());
        }
        child.removeAgentListener(containment.forwarder);
        if (child.subContext != null) {
            containment.where.removeSubContext(child.subContext);
        }
        containment.where.remove(child);
        removeIdentical(containedInOrder, child);
        child.container = null;
        child.homeContext = null;
    }

    /** List.remove would use equals; containment is identity throughout. */
    private static void removeIdentical(List<AbstractAgent> from, AbstractAgent agent) {
        for (int i = 0; i < from.size(); i++) {
            if (from.get(i) == agent) {
                from.remove(i);
                return;
            }
        }
    }

    /**
     * Moves an already-contained agent into this one, keeping its relations.
     *
     * <p>Distinct from {@link #release} followed by {@link #contain}, and that distinction is the
     * whole point: changing an agent's container takes it out of a Repast context, which drops it
     * from every relation declared on that context or above it. For a departure that is right. For a
     * reassignment, arriving with none of its relations would be wrong, so they are saved across the
     * move and put back.
     *
     * @throws IllegalStateException if the agent is not contained anywhere to begin with
     */
    public final void transfer(AbstractAgent agent) {
        transferInto(agent, subContext());
    }

    /** Transfer placing the agent in a specific context — how a population takes a member. */
    final void transferInto(AbstractAgent agent, AgentContext destination) {
        if (agent == null) {
            throw new IllegalArgumentException("cannot transfer null");
        }
        if (agent.container == null) {
            throw new IllegalStateException(
                    agent.getName() + " is not contained anywhere, so there is nothing to transfer");
        }
        if (agent.homeContext == destination) {
            return;
        }
        // Everything containIn would refuse is checked here, before anything is touched. Releasing
        // first and discovering the problem afterwards left the agent contained nowhere, with its
        // relations already dropped and no longer saved — a refused transfer has to be a no-op.
        if (agent == this) {
            throw new IllegalStateException(name + " cannot transfer itself into itself");
        }
        if (this.isBelow(agent)) {
            throw new IllegalStateException(
                    "transferring " + agent.getName() + " into " + name + " would make a cycle");
        }
        List<Relations.SavedEdge> relations = Relations.save(agent);
        agent.container.release(agent);
        containIn(agent, destination);
        Relations.restore(relations);
    }

    @Override
    public final Optional<Agent> container() {
        return Optional.<Agent>ofNullable(container);
    }

    @Override
    public final Collection<Agent> contained() {
        return Collections.unmodifiableCollection(new ArrayList<Agent>(containedInOrder));
    }

    /**
     * Opens a named, typed population held by this agent, with its own nested context.
     *
     * @throws IllegalStateException if this agent already has a population of that name
     */
    public final <A extends Agent> Population<A> population(String populationName, Class<A> type) {
        if (populationName == null || populationName.isEmpty()) {
            throw new IllegalArgumentException("a population needs a name");
        }
        if (type == null) {
            throw new IllegalArgumentException("a population needs a member type");
        }
        if (populations.containsKey(populationName)) {
            throw new IllegalStateException(
                    name + " already has a population named " + populationName);
        }
        Population<A> population = new Population<>(this, populationName, type);
        populations.put(populationName, population);
        return population;
    }

    /** The populations this agent has opened, keyed by name. */
    public final Map<String, Population<?>> populations() {
        return Collections.unmodifiableMap(populations);
    }

    // -- traversal -------------------------------------------------------------------------

    /**
     * The {@code depth}-th container of the given type, counting from the closest, or the most
     * distant when {@code depth} is -1. Typed by Java class rather than by a type-name string, so
     * a mistyped lookup is a compile error instead of an empty result at runtime.
     */
    public final <A extends Agent> Optional<A> findContainerOfType(Class<A> type, int depth) {
        List<A> found = findContainersOfType(type);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (depth == -1) {
            return Optional.of(found.get(found.size() - 1));
        }
        if (depth < 1 || depth > found.size()) {
            return Optional.empty();
        }
        return Optional.of(found.get(depth - 1));
    }

    /** Every container of the given type above this agent, ordered closest first. */
    public final <A extends Agent> List<A> findContainersOfType(Class<A> type) {
        List<A> found = new ArrayList<>();
        for (AbstractAgent above = container; above != null; above = above.container) {
            if (type.isInstance(above)) {
                found.add(type.cast(above));
            }
        }
        return Collections.unmodifiableList(found);
    }

    /** Whether this agent is somewhere above {@code other} in the containment tree. */
    public final boolean isAbove(Agent other) {
        if (!(other instanceof AbstractAgent)) {
            return false;
        }
        for (AbstractAgent above = ((AbstractAgent) other).container; above != null; above = above.container) {
            if (above == this) {
                return true;
            }
        }
        return false;
    }

    /** Whether this agent is somewhere below {@code other} in the containment tree. */
    public final boolean isBelow(Agent other) {
        for (AbstractAgent above = container; above != null; above = above.container) {
            if (above == other) {
                return true;
            }
        }
        return false;
    }

    // -- capabilities ----------------------------------------------------------------------

    /**
     * Holds {@code capability}, reachable through {@link #view} under exactly {@code as}. The type
     * is given explicitly rather than taken from the object's own class so a model can publish an
     * interface and keep the implementation private, which is the usual case.
     *
     * @throws IllegalStateException if this agent already holds a capability under that type
     */
    public final <C> void hold(Class<C> as, C capability) {
        if (as == null || capability == null) {
            throw new IllegalArgumentException("capability type and instance are both required");
        }
        if (!as.isInstance(capability)) {
            throw new IllegalArgumentException(capability.getClass() + " is not a " + as);
        }
        if (capabilities.containsKey(as)) {
            throw new IllegalStateException(name + " already holds a capability as " + as.getName());
        }
        capabilities.put(as, capability);
    }

    @Override
    public final <C> Optional<C> view(Class<C> capability) {
        return Optional.ofNullable(capability).map(type -> type.cast(capabilities.get(type)));
    }

    // -- held Blocks -----------------------------------------------------------------------

    /**
     * Holds a Block, so whatever it emits reaches this agent's listeners attributed to this agent.
     *
     * <p>Generic in the Block's entity type because {@code ProcessStage.addProcessListener} is, so
     * an agent can hold Blocks over entirely different entity types and the adapter installed for
     * each stays type-correct.
     *
     * @throws IllegalStateException if this agent already holds that Block — holding twice would
     *     double-report every event from it
     */
    public final <T> void hold(final ProcessStage<T> stage) {
        if (stage == null) {
            throw new IllegalArgumentException("cannot hold a null Block");
        }
        if (heldStages.containsKey(stage)) {
            throw new IllegalStateException(name + " already holds Block " + stage.getName());
        }
        final ProcessListener<T> adapter = new ProcessListener<T>() {
            @Override
            public void entityEnteredStage(T entity, ProcessStage<T> source, double tick) {
                for (AgentListener listener : listeners) {
                    listener.entityEntered(AbstractAgent.this, source, entity, tick);
                }
            }

            @Override
            public void entityExitedStage(T entity, ProcessStage<T> source, double tick) {
                for (AgentListener listener : listeners) {
                    listener.entityExited(AbstractAgent.this, source, entity, tick);
                }
            }

            @Override
            public void resourceAcquired(ResourcePool pool, Object holder, int units, double tick) {
                for (AgentListener listener : listeners) {
                    listener.resourceAcquired(AbstractAgent.this, pool, holder, units, tick);
                }
            }

            @Override
            public void resourceReleased(ResourcePool pool, Object holder, int units, double tick) {
                for (AgentListener listener : listeners) {
                    listener.resourceReleased(AbstractAgent.this, pool, holder, units, tick);
                }
            }
        };
        stage.addProcessListener(adapter);
        heldStages.put(stage, () -> stage.removeProcessListener(adapter));
    }

    /** The Blocks this agent holds, in no particular order. */
    public final Collection<ProcessStage<?>> heldBlocks() {
        return Collections.unmodifiableCollection(heldStages.keySet());
    }

    /**
     * Removes every listener this agent installed on the Blocks it holds, and forgets them. Called
     * by the departure path; a model has no reason to call it directly.
     *
     * <p>Capabilities are left in place — they register nothing yet. Once they do, unregistering
     * them belongs here too.
     */
    public void detachHeld() {
        for (Runnable detach : heldStages.values()) {
            detach.run();
        }
        heldStages.clear();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + name + ")";
    }
}
