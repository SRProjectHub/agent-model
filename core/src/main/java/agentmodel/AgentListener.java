package agentmodel;

import processmodel.foundation.ProcessStage;
import processmodel.foundation.ResourcePool;

/**
 * Receives what an agent's held Blocks emit, with the holding agent named in every callback.
 *
 * <p>This is the aggregation {@code process-model} deliberately refuses to do: its
 * {@code docs/plan.md} states that combining many Blocks into a view of the whole owning object is
 * that object's job, and this is that job discharged once rather than per model. Without it,
 * every consumer re-implements the same fan-in over {@code addProcessListener}.
 *
 * <p>Every method is a no-op default, so an implementation states only what it cares about.
 *
 * <p>The surface covers discrete Blocks and resources. Continuous nodes emit a different and much
 * larger set of events ({@code FlowListener} has six methods of its own), and re-firing those is
 * deferred until {@code flow-model} is actually in this build — designing that surface without a
 * consumer to check it against is how it ends up wrong.
 */
public interface AgentListener {

    /** An entity arrived at one of this agent's Blocks, before any buffering there. */
    default void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
    }

    /**
     * An entity actually departed one of this agent's Blocks — not when it arrived. The running
     * difference against {@link #entityEntered} is how many the agent currently holds, which is
     * the guarantee that makes this worth listening to at all.
     */
    default void entityExited(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
    }

    /**
     * One of this agent's Blocks seized resource units. The pool may be shared with other agents,
     * so {@code agent} identifies which agent seized them; no agent holds the pool itself.
     */
    default void resourceAcquired(
            Agent agent, ResourcePool pool, Object holder, int units, double tick) {
    }

    default void resourceReleased(
            Agent agent, ResourcePool pool, Object holder, int units, double tick) {
    }
}
