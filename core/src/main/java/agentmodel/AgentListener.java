package agentmodel;

import processmodel.foundation.ProcessStage;
import processmodel.foundation.ResourcePool;
import processmodel.foundation.flow.FlowNode;
import processmodel.foundation.flow.FlowPort;

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
 * <p>Covers both paradigms, which is the point: an agent may hold discrete Blocks and continuous
 * nodes at once, and one registration here sees everything either kind emits. The continuous half
 * mirrors {@code FlowListener} faithfully rather than curating it — attribution is this layer's job,
 * deciding which events matter is the model's.
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

    /** The amount held in one of this agent's continuous nodes changed. */
    default void levelChanged(Agent agent, FlowNode<?> node, double level, double tick) {
    }

    /** The rate through one of this agent's ports changed. */
    default void rateChanged(Agent agent, FlowPort<?> port, double rate, double tick) {
    }

    default void nodeFull(Agent agent, FlowNode<?> node, double tick) {
    }

    default void nodeEmpty(Agent agent, FlowNode<?> node, double tick) {
    }

    /** A port of this agent's had nothing arriving where something was wanted. */
    default void portStarved(Agent agent, FlowPort<?> port, double tick) {
    }

    /** The solver corrected a conservation error at one of this agent's nodes. */
    default void conservationCorrected(
            Agent agent, FlowNode<?> node, double relativeError, double tick) {
    }
}
