package agentmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import processmodel.foundation.AdmissionHandle;
import processmodel.foundation.ProcessListener;
import processmodel.foundation.ProcessStage;

/**
 * An agent passing through a chain of Blocks and coming back out, still itself.
 *
 * <p>Blocks are built for entities that a {@code Source} creates and a {@code Sink} destroys. A
 * long-lived agent is neither: it exists before it enters a chain and carries on afterwards, so
 * sending one through means treating the visit as a temporary state of a permanent thing. Both source
 * models are built this way — each participant runs work through a chain of its own and is still
 * there when that work is done.
 *
 * <p>The agent <b>never leaves its population</b>. Being inside a chain is not a kind of absence: it
 * stays a member throughout, keeps its relations, and keeps forwarding what it holds. Only
 * {@link #inProcess} changes.
 *
 * <p>{@code process-model} already has everything needed underneath — {@code ProcessStage.enter}
 * accepts any type, and a {@code Router} returning null means "leave the process" — but nothing there
 * manages the round trip or remembers where a traveller currently is. That is what this adds.
 *
 * <p>Two things have to be wired, because a chain is defined by routing lambdas that no library can
 * discover: each Block the visit should track is passed to {@link #watch}, and {@link #exit} gives the
 * stage the chain's last Block routes to, which is what marks a visit finished. A chain whose last
 * Router returns null instead needs {@link #returned} called for the traveller, or it stays marked as
 * travelling for the rest of the run.
 *
 * <p>Note what {@link AbstractAgent#currentBlock} means, given how Blocks report arrivals: a Block
 * fires its entered event on arrival, <em>before</em> any buffering, so a traveller waiting for room in
 * a full Block still reports that Block as the one holding it. That is the Block's own account of
 * where the traveller is, and a caller wanting to know whether it has actually been taken in should
 * ask the Block — process-model exposes a pending-admission count for exactly this.
 */
public final class ProcessVisit<A extends Agent> {

    private final Map<A, Boolean> travelling = new IdentityHashMap<>();
    private final List<A> travellingInOrder = new ArrayList<>();
    private final List<ProcessStage<A>> watched = new ArrayList<>();

    /**
     * Tracks entries into and exits from this Block, so a traveller's {@link
     * AbstractAgent#currentBlock} stays current as it moves.
     *
     * <p>Watching is per Block rather than per chain, and every Block a visit may reach should be
     * watched — an unwatched one leaves the traveller reported as still in whichever Block it was in
     * last.
     */
    public void watch(final ProcessStage<A> stage) {
        if (stage == null) {
            throw new IllegalArgumentException("a Block to watch is required");
        }
        for (ProcessStage<A> already : watched) {
            if (already == stage) {
                return;
            }
        }
        watched.add(stage);
        stage.addProcessListener(new ProcessListener<A>() {
            @Override
            public void entityEnteredStage(A entity, ProcessStage<A> source, double tick) {
                if (entity instanceof AbstractAgent) {
                    ((AbstractAgent) entity).enteredBlock(source, tick);
                }
            }

            @Override
            public void entityExitedStage(A entity, ProcessStage<A> source, double tick) {
                // Departing one Block and arriving at the next are separate events, in that order, so
                // clearing here and setting there leaves the right answer. Between them — while an
                // admission is pending against a full Block — the traveller is in no Block at all,
                // which is true and is why inProcess is tracked separately.
                if (entity instanceof AbstractAgent) {
                    ((AbstractAgent) entity).leftBlock(source);
                }
            }
        });
    }

    /**
     * A terminal Block for the chain's last Router to send travellers to, ending their visit and
     * handing each one to {@code onReturn}.
     *
     * <p>Returns rather than consumes: nothing is destroyed here, and the agent is a member of its
     * population before and after exactly as it was during.
     */
    public ProcessStage<A> exit(final String name, final Consumer<A> onReturn) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("the exit needs a name");
        }
        return new ProcessStage<A>() {
            private final List<ProcessListener<A>> listeners = new ArrayList<>();

            @Override
            public String getName() {
                return name;
            }

            @Override
            public AdmissionHandle enter(A entity) {
                for (ProcessListener<A> listener : new ArrayList<>(listeners)) {
                    listener.entityEnteredStage(entity, this, 0.0);
                }
                finished(entity);
                if (entity instanceof AbstractAgent) {
                    ((AbstractAgent) entity).leftBlock(null);
                }
                if (onReturn != null) {
                    onReturn.accept(entity);
                }
                for (ProcessListener<A> listener : new ArrayList<>(listeners)) {
                    listener.entityExitedStage(entity, this, 0.0);
                }
                // A terminal always admits: there is nothing downstream to be full.
                return AdmissionHandle.admitted();
            }

            @Override
            public void addProcessListener(ProcessListener<A> listener) {
                listeners.add(listener);
            }

            @Override
            public void removeProcessListener(ProcessListener<A> listener) {
                listeners.remove(listener);
            }
        };
    }

    /** An exit that reports nothing beyond ending the visit. */
    public ProcessStage<A> exit(String name) {
        return exit(name, null);
    }

    /**
     * Sends an agent into a chain at {@code entry}, marking it as travelling.
     *
     * @return the entry Block's own admission handle, so a caller can see whether it was taken
     *     straight away or is waiting for room
     * @throws IllegalStateException if the agent is already travelling, since a single agent cannot
     *     be in two places in a chain at once
     */
    public AdmissionHandle inject(A agent, ProcessStage<A> entry) {
        if (agent == null || entry == null) {
            throw new IllegalArgumentException("an agent and an entry Block are both required");
        }
        if (isInProcess(agent)) {
            throw new IllegalStateException(agent.getName() + " is already travelling through a chain");
        }
        travelling.put(agent, Boolean.TRUE);
        travellingInOrder.add(agent);
        return entry.enter(agent);
    }

    /**
     * Ends a visit without the agent passing through {@link #exit}, for a chain whose last Router
     * returns null instead. Without this such a traveller would stay marked as travelling for the rest
     * of the run, since nothing downstream reports that it left.
     *
     * @return whether the agent was travelling
     */
    public boolean returned(A agent) {
        boolean was = isInProcess(agent);
        finished(agent);
        if (agent instanceof AbstractAgent) {
            ((AbstractAgent) agent).leftBlock(null);
        }
        return was;
    }

    /** Whether this agent is currently somewhere inside a chain, between injection and exit. */
    public boolean isInProcess(A agent) {
        return travelling.containsKey(agent);
    }

    /** Everyone currently inside a chain, in the order they were injected. */
    public List<A> inProcess() {
        return Collections.unmodifiableList(new ArrayList<>(travellingInOrder));
    }

    private void finished(A agent) {
        if (travelling.remove(agent) != null) {
            for (int i = 0; i < travellingInOrder.size(); i++) {
                if (travellingInOrder.get(i) == agent) {
                    travellingInOrder.remove(i);
                    break;
                }
            }
        }
    }
}
