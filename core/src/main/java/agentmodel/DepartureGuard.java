package agentmodel;

/**
 * Decides whether an agent may leave yet.
 *
 * <p>The general form of a condition both source models impose in their own vocabulary: something
 * must not disappear while it still holds work that would vanish with it. Refusing is not an error —
 * the request stays pending and is asked again, so a guard is a "not yet", not a "no".
 *
 * <p>Must not act. A guard is asked possibly many times before it agrees, so anything it changed
 * would be changed repeatedly; it answers from state it already has, like an {@link Inspection}.
 */
public interface DepartureGuard {

    /** True when the agent may leave now. False leaves the request pending for a later attempt. */
    boolean mayDepart(Agent agent);
}
