package agentmodel;

import java.util.concurrent.atomic.AtomicLong;
import repast.simphony.context.DefaultContext;

/**
 * The Repast {@code Context} holding the agents one agent contains, which knows whose it is.
 *
 * <p>Containment is mapped onto Repast subcontexts rather than kept as a private tree, so the
 * hierarchy gets Repast's own behaviour for free: {@code size()}, {@code getAgentTypes()} and
 * removal all recurse into subcontexts, and because {@code AbstractContext} is itself a
 * {@code ContextListener} that re-fires to its own listeners, a {@code Network} projection on the
 * root context sees agents added anywhere down the chain.
 *
 * <p>{@link #owner} is the one thing Repast cannot supply. Its {@code findParent(o)} returns the
 * containing <em>context</em>, and nothing maps that back to the agent whose context it is — so
 * without this, going from a facility up to its owner would need a second structure kept in sync
 * with the first.
 */
public final class AgentContext extends DefaultContext<Object> {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final Agent owner;

    AgentContext(Agent owner) {
        this(owner, owner.getName());
    }

    /**
     * @param label what this context holds — an agent's name for its own children, or a
     *     population's name for that population's members
     */
    AgentContext(Agent owner, String label) {
        // Repast keys subcontexts by id in a map, so two identically labelled contexts under one
        // parent would collide and the second would displace the first. The label is kept for
        // readability while the sequence keeps it unique.
        super(label + "#" + SEQUENCE.incrementAndGet());
        this.owner = owner;
    }

    /**
     * The agent this context belongs to. For a population's context that is the agent containing
     * the population, since a population is a subdivision of what one agent holds rather than an
     * owner in its own right.
     */
    public Agent owner() {
        return owner;
    }
}
