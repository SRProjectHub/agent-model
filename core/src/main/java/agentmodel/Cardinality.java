package agentmodel;

/**
 * How many agents one agent may reach over a relation.
 *
 * <p>{@link #ONE} is a constraint worth stating rather than merely intending: where an agent is
 * meant to reach exactly one other, a second connection appearing silently is the kind of fault that
 * shows up much later as an ambiguous answer.
 */
public enum Cardinality {

    /** At most one connection per agent. A second is refused rather than replacing the first. */
    ONE,

    /** Any number of connections per agent. */
    MANY
}
