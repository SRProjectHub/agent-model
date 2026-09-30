package agentmodel;

/**
 * Whether a relation runs one way or both.
 *
 * <p>The distinction is the relation's meaning, not an optimisation: depending on something is
 * directed and asymmetric, while being each other's counterpart is not, and a model that conflates
 * them loses the ability to ask which way round a relation runs.
 */
public enum Direction {

    /** Connecting A to B says nothing about B to A — dependent to dependency, overseer to overseen. */
    DIRECTED,

    /** Connecting A to B connects B to A by the same act — peers, counterparts, shared membership. */
    UNDIRECTED
}
