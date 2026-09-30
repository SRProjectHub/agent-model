package agentmodel;

/**
 * Whether a relation runs one way or both.
 *
 * <p>The distinction is the relation's meaning, not an optimisation: supplying is directed and
 * asymmetric, while sharing a trade association is not, and a model that conflates them loses the
 * ability to ask who supplies whom.
 */
public enum Direction {

    /** Connecting A to B says nothing about B to A — supplier to consumer, regulator to regulated. */
    DIRECTED,

    /** Connecting A to B connects B to A by the same act — peers, counterparties, shared membership. */
    UNDIRECTED
}
