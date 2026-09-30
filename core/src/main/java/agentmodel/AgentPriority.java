package agentmodel;

import processmodel.foundation.SchedulingPriority;

/**
 * Where agent-layer work sits among everything else happening at one instant. Higher runs earlier,
 * matching the scheduler's own convention and {@link SchedulingPriority}, which declares the bands
 * the Block libraries schedule into.
 *
 * <p>Both bands here sit <b>below every Block band</b> — below {@code SETTLE}, the lowest of them.
 * So a message delivered to an agent sees a settled world: every Block event at that instant has
 * already happened, including a continuous solve. Structural change comes last of all, after the
 * instant's messages have finished, because adding or removing agents while something is iterating
 * the population is the fault this ordering exists to prevent.
 *
 * <p>Agents still <em>act</em> within the instant rather than only after it. That works because an
 * agent watching its own held Block is not interacting with anyone: those callbacks fire
 * synchronously inside the Block event, exactly ordered, and never come through here. Only traffic
 * between distinct agents is mediated, and only that trails the instant.
 *
 * <p>Plain {@code double}s, and the gaps are deliberate. A model that genuinely needs to interleave
 * with the Block bands can pass its own value instead.
 */
public final class AgentPriority {

    /** Mediated agent-to-agent delivery. Nested sends re-enter this band and drain in order. */
    public static final double INTERACTION = 50.0;

    /** Agents joining and leaving, with departure guards evaluated and retried here. */
    public static final double STRUCTURE = 25.0;

    private AgentPriority() {
    }
}
