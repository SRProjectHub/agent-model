package agentmodel;

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
