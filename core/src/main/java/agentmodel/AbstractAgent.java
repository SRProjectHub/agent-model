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
 * says what it can do. Capabilities are held rather than implemented because Java permits one
 * superclass: Cyclus can write {@code Facility : TimeListener, Agent, Trader} and we cannot, and
 * its own toolkit shows the better answer anyway — its buy and sell policies are separate objects
 * an agent owns, several at a time, not interfaces it implements.
 *
 * <p>Registration lifetime is this class's responsibility, not the model's. Every listener it
 * installs on a held Block is remembered alongside a way to remove it, and {@link #detachHeld}
 * undoes all of them at once. Cyclus leaves this to the caller and documents the consequence —
 * <i>"When a policy's managing agent is deallocated, you MUST either call the policy's Stop
 * function or delete the policy. Otherwise SEGFAULT."</i> A stale registration here would not
 * crash, it would quietly keep reporting events for an agent that has left, which is worse.
 */
public abstract class AbstractAgent implements Agent {

    private final String name;
    private final DoubleSupplier tickSource;

    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();

    // Identity-keyed: two Blocks are never "equal", and a model may well hold several that would
    // compare equal by value. The value is how to unregister the listener installed for that Block.
    private final Map<ProcessStage<?>, Runnable> heldStages = new IdentityHashMap<>();

    // Insertion-ordered so iteration is reproducible; keyed by the type the capability was held
    // as, which is what makes view() a typed question rather than a string lookup.
    private final Map<Class<?>, Object> capabilities = new LinkedHashMap<>();

    // Containment. The subcontext is authoritative for children and is created lazily, so the
    // numerous leaf agents never allocate one. `container` is a cached inverse maintained only by
    // contain/release, in the manner of a doubly-linked list rather than a second structure.
    private AgentContext subContext;
    private volatile AbstractAgent container;
    private final Map<AbstractAgent, AgentListener> childForwarders = new IdentityHashMap<>();

    protected AbstractAgent(String name, DoubleSupplier tickSource) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("an agent needs a name");
        }
        if (tickSource == null) {
            throw new IllegalArgumentException("an agent needs a tick source");
        }
        this.name = name;
        this.tickSource = tickSource;
    }

    @Override
    public final String getName() {
        return name;
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
     * The Repast context holding this agent's children, created on first use. A leaf agent never
     * allocates one, which matters because leaves are the numerous kind.
     *
     * <p>Exposed so a model can attach the top of its hierarchy into its own root context, and so
     * a display or dataset can be pointed at one container's population.
     */
    public final AgentContext subContext() {
        if (subContext == null) {
            subContext = new AgentContext(this);
            // If this agent is already contained, its context belongs under its container's, so
            // membership stays transitive however late the context comes into existence.
            if (container != null) {
                container.subContext().addSubContext(subContext);
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
     * <p>Forwarded events keep their <em>original</em> attribution: a listener on an owner is told
     * which facility's Block emitted, not merely that something under the owner did.
     *
     * @throws IllegalStateException if the child is already contained, or if containing it would
     *     make a cycle
     */
    public final void contain(AbstractAgent child) {
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
        if (this.descendantOf(child)) {
            throw new IllegalStateException(
                    "containing " + child.getName() + " in " + name + " would make a cycle");
        }

        subContext().add(child);
        if (child.subContext != null) {
            subContext().addSubContext(child.subContext);
        }
        child.container = this;

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
        childForwarders.put(child, forwarder);
    }

    /**
     * Undoes {@link #contain}: the child leaves this agent's subcontext, loses its back-reference,
     * and stops forwarding. The child itself is otherwise untouched — it keeps what it holds.
     *
     * @throws IllegalStateException if this agent does not contain that child
     */
    public final void release(AbstractAgent child) {
        AgentListener forwarder = childForwarders.remove(child);
        if (forwarder == null) {
            throw new IllegalStateException(name + " does not contain " + child.getName());
        }
        child.removeAgentListener(forwarder);
        if (child.subContext != null) {
            subContext().removeSubContext(child.subContext);
        }
        subContext().remove(child);
        child.container = null;
    }

    @Override
    public final Optional<Agent> container() {
        return Optional.<Agent>ofNullable(container);
    }

    @Override
    public final Collection<Agent> children() {
        return Collections.unmodifiableCollection(new ArrayList<Agent>(childForwarders.keySet()));
    }

    // -- traversal -------------------------------------------------------------------------

    /**
     * The {@code layer}-th ancestor of the given type, counting from the closest, or the most
     * distant when {@code layer} is -1. Typed by Java class, where Cyclus has to match a
     * {@code kind_} string because C++ gave it no better option.
     */
    public final <A extends Agent> Optional<A> ancestorOfType(Class<A> type, int layer) {
        List<A> found = ancestorsOfType(type);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (layer == -1) {
            return Optional.of(found.get(found.size() - 1));
        }
        if (layer < 1 || layer > found.size()) {
            return Optional.empty();
        }
        return Optional.of(found.get(layer - 1));
    }

    /** Every ancestor of the given type, ordered closest first. */
    public final <A extends Agent> List<A> ancestorsOfType(Class<A> type) {
        List<A> found = new ArrayList<>();
        for (AbstractAgent above = container; above != null; above = above.container) {
            if (type.isInstance(above)) {
                found.add(type.cast(above));
            }
        }
        return Collections.unmodifiableList(found);
    }

    /** Whether this agent is somewhere above {@code other} in the containment tree. */
    public final boolean ancestorOf(Agent other) {
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
    public final boolean descendantOf(Agent other) {
        for (AbstractAgent above = container; above != null; above = above.container) {
            if (above == other) {
                return true;
            }
        }
        return false;
    }

    /** Whether the two agents share a containment tree at all, in either direction. */
    public final boolean inFamilyTree(Agent other) {
        return this == other || ancestorOf(other) || descendantOf(other);
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
