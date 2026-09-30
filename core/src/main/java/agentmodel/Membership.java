package agentmodel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;

/**
 * Agents joining and leaving during a run, deferred so it never happens at an awkward moment.
 *
 * <p>Changing who belongs while something is walking the population is the classic fault in this kind
 * of model: an agent vanishes from under an iteration, or appears half way through one and is counted
 * by some observers and not others. So requests are queued and applied from this class's own event, in
 * the {@link AgentPriority#STRUCTURE} band — last at an instant, after every Block event and after the
 * instant's messages have finished being delivered.
 *
 * <p>Setting a model up is different from changing it mid-run, and this is only for the second.
 * Building the initial hierarchy with {@link AbstractAgent#contain} and {@link Population#add}
 * directly is correct and simpler; nothing is iterating yet.
 *
 * <p><b>One Repast behaviour to know about.</b> An action scheduled for the tick the clock has
 * already reached is silently discarded unless another action is currently executing. So a request
 * made from inside any scheduled action is applied as described; a request made from outside one, with
 * the clock already advanced, needs {@link #applyPending} to take effect at all.
 *
 * <p>A departure may be <b>guarded</b>. A refused guard is a "not yet": the request stays pending and
 * is re-examined at every later drain. It is deliberately not retried on a timer — in continuous time
 * there is no natural interval to pick, and only the model knows when the condition it is waiting on
 * has changed. When it does know, {@link #applyPending} asks again.
 */
public final class Membership {

    private final ISchedule schedule;
    private final Map<Agent, DepartureGuard> guards = new IdentityHashMap<>();
    private final List<Runnable> joins = new ArrayList<>();
    private final List<Agent> joining = new ArrayList<>();
    private final List<AbstractAgent> departures = new ArrayList<>();

    private boolean armed;

    public Membership(ISchedule schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("membership changes need a schedule to happen on");
        }
        this.schedule = schedule;
    }

    /** The current instant, floored at 0 — the schedule reports -1 until it has first run. */
    public double currentTick() {
        return Math.max(0.0, schedule.getTickCount());
    }

    /**
     * Sets the condition this agent must satisfy before it may leave, replacing any previous one.
     * Passing null removes the guard, after which the agent may leave at the next drain.
     */
    public void guard(Agent agent, DepartureGuard guard) {
        if (agent == null) {
            throw new IllegalArgumentException("an agent is required");
        }
        if (guard == null) {
            guards.remove(agent);
        } else {
            guards.put(agent, guard);
        }
    }

    /**
     * Asks for an agent to join a population. Applied at the next drain, so a walk over that
     * population in progress now will not see it appear.
     */
    public <A extends Agent> void requestJoin(final Population<A> into, final A agent) {
        if (into == null || agent == null) {
            throw new IllegalArgumentException("a population and an agent are both required");
        }
        joins.add(() -> into.add(agent));
        joining.add(agent);
        arm();
    }

    /**
     * Asks for an agent to leave: released by its container, and everything it registered on the
     * Blocks it holds unregistered. Its relations go with it, which Repast's own projections handle.
     *
     * <p>Repeating the request for an agent already waiting does nothing rather than queueing it
     * twice.
     *
     * @throws IllegalStateException if the agent is not contained anywhere, so has nothing to leave
     */
    public void requestDeparture(Agent agent) {
        if (!(agent instanceof AbstractAgent)) {
            throw new IllegalArgumentException("only an AbstractAgent can be asked to depart");
        }
        AbstractAgent leaving = (AbstractAgent) agent;
        if (leaving.containerAgent() == null && !isJoining(leaving)) {
            throw new IllegalStateException(
                    agent.getName() + " is not contained anywhere and is not waiting to join,"
                            + " so it has nothing to leave");
        }
        for (AbstractAgent already : departures) {
            if (already == leaving) {
                return;
            }
        }
        departures.add(leaving);
        arm();
    }

    /**
     * Applies every waiting join, and asks every waiting departure's guard again — now, on the
     * caller's own stack rather than from a scheduled event.
     *
     * <p>This is how a model says "the thing that guard was waiting for has happened". It is also the
     * only way to have a request take effect when made from outside any executing action, since an
     * armed drain would be discarded there.
     */
    public void applyPending() {
        drain();
    }

    /** Agents waiting to leave, in request order — those whose guard has not yet agreed. */
    public List<Agent> pendingDepartures() {
        return new ArrayList<Agent>(departures);
    }

    public int pendingJoins() {
        return joins.size();
    }

    private boolean isJoining(Agent agent) {
        for (Agent pending : joining) {
            if (pending == agent) {
                return true;
            }
        }
        return false;
    }

    private void arm() {
        if (!armed) {
            armed = true;
            schedule.schedule(
                    ScheduleParameters.createOneTime(currentTick(), AgentPriority.STRUCTURE),
                    this::drain);
        }
    }

    private void drain() {
        armed = false;

        // Joins first: an agent asked to join and then to leave in the same instant should exist
        // before it is asked to go, rather than the departure silently finding nothing.
        List<Runnable> toJoin = new ArrayList<>(joins);
        joins.clear();
        joining.clear();
        for (Runnable join : toJoin) {
            join.run();
        }

        for (AbstractAgent leaving : new ArrayList<>(departures)) {
            DepartureGuard guard = guards.get(leaving);
            if (guard != null && !guard.mayDepart(leaving)) {
                continue; // not yet — it stays queued for a later drain
            }
            depart(leaving);
            removeIdentical(departures, leaving);
            guards.remove(leaving);
        }
    }

    private static void depart(AbstractAgent leaving) {
        AbstractAgent container = leaving.containerAgent();
        if (container == null) {
            return; // already gone, by transfer or a direct release
        }
        AgentContext home = leaving.homeContext();
        Population<?> population = home == null ? null : home.population();
        container.release(leaving);
        leaving.detachHeld();
        if (population != null) {
            population.notifyLeft(leaving);
        }
    }

    private static void removeIdentical(List<AbstractAgent> from, AbstractAgent agent) {
        for (int i = 0; i < from.size(); i++) {
            if (from.get(i) == agent) {
                from.remove(i);
                return;
            }
        }
    }
}
