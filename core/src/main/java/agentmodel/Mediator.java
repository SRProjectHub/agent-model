package agentmodel;

import java.util.ArrayDeque;
import java.util.Deque;
import repast.simphony.context.Context;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;

/**
 * Carries messages between agents, and hands out the relations they travel along.
 *
 * <p>Sending never calls the receiver. A message goes onto a queue, and the queue is drained from
 * the mediator's own scheduled event at the same instant in the {@link AgentPriority#INTERACTION}
 * band. So the sender's own event finishes first, the receiver acts in an event of its own, and
 * neither is nested inside the other — which is what stops a chain of sends from becoming a chain of
 * stack frames, and what makes the order of deliveries something this class decides rather than
 * something the call graph happens to produce.
 *
 * <p>Delivery is <b>first in, first out</b>, including for sends made from inside a handler: those
 * join the back of the queue being drained and are delivered in the same instant, after everything
 * already waiting. A cycle that never settles would otherwise hang the run, so there is a cap on how
 * many deliveries one instant may make; exceeding it fails loudly and names the last pair involved.
 *
 * <p>One mediator serves a whole run. Relations are declared through it rather than built
 * independently, because a relation is a channel and a channel with no way to deliver is half a
 * thing — so there is one queue and one ordering for all agent traffic, not one per relation.
 */
public final class Mediator {

    /** Deliveries allowed in a single instant before a send cycle is assumed. */
    public static final int DEFAULT_MAX_DELIVERIES_PER_INSTANT = 10_000;

    private final ISchedule schedule;
    private final int maxDeliveriesPerInstant;
    private final Deque<Delivery<?>> queue = new ArrayDeque<>();

    private boolean armed;
    private boolean draining;

    public Mediator(ISchedule schedule) {
        this(schedule, DEFAULT_MAX_DELIVERIES_PER_INSTANT);
    }

    public Mediator(ISchedule schedule, int maxDeliveriesPerInstant) {
        if (schedule == null) {
            throw new IllegalArgumentException("a mediator needs a schedule to deliver on");
        }
        if (maxDeliveriesPerInstant < 1) {
            throw new IllegalArgumentException("a mediator must allow at least one delivery");
        }
        this.schedule = schedule;
        this.maxDeliveriesPerInstant = maxDeliveriesPerInstant;
    }

    /**
     * Declares a relation carried by this mediator. See
     * {@link AgentNetwork#declare(Mediator, Context, String, Direction, Cardinality, Class)}.
     */
    public <M> AgentNetwork<M> declare(
            Context<Object> over,
            String name,
            Direction direction,
            Cardinality cardinality,
            Class<M> messageType) {
        return AgentNetwork.declare(this, over, name, direction, cardinality, messageType);
    }

    /** The current instant, floored at 0 — the schedule reports -1 until it has first run. */
    public double currentTick() {
        return Math.max(0.0, schedule.getTickCount());
    }

    /** How many messages are waiting, which outside a drain is how many were sent this instant. */
    public int pending() {
        return queue.size();
    }

    <M> void enqueue(AgentNetwork<M> over, Agent sender, Agent receiver, M message) {
        queue.addLast(new Delivery<>(over, sender, receiver, message));
        // Already inside drain(): the loop will reach this without another event being scheduled,
        // which is what makes a send from a handler arrive in the same instant.
        if (!draining && !armed) {
            armed = true;
            schedule.schedule(
                    ScheduleParameters.createOneTime(currentTick(), AgentPriority.INTERACTION),
                    this::drain);
        }
    }

    private void drain() {
        armed = false;
        draining = true;
        int delivered = 0;
        try {
            while (!queue.isEmpty()) {
                if (++delivered > maxDeliveriesPerInstant) {
                    Delivery<?> next = queue.peekFirst();
                    throw new IllegalStateException(
                            "more than " + maxDeliveriesPerInstant + " deliveries at tick "
                                    + currentTick() + " — a send cycle, last seen sending from "
                                    + next.sender.getName() + " to " + next.receiver.getName()
                                    + " over " + next.over.getName());
                }
                queue.pollFirst().deliver(currentTick());
            }
        } finally {
            draining = false;
            // A handler that threw leaves the rest undelivered; dropping them silently would hide
            // the failure's reach, so they stay queued and the next send re-arms the drain.
            if (!queue.isEmpty()) {
                armed = false;
            }
        }
    }

    /** One queued message, holding its own type so delivery stays type-correct. */
    private static final class Delivery<M> {
        private final AgentNetwork<M> over;
        private final Agent sender;
        private final Agent receiver;
        private final M message;

        Delivery(AgentNetwork<M> over, Agent sender, Agent receiver, M message) {
            this.over = over;
            this.sender = sender;
            this.receiver = receiver;
            this.message = message;
        }

        void deliver(double tick) {
            MessageHandler<M> handler = over.handlerFor(receiver);
            if (handler != null) {
                handler.receive(sender, message, tick);
            }
        }
    }
}
