package agentmodel;

import java.util.concurrent.atomic.AtomicLong;
import repast.simphony.context.DefaultContext;

/**
 * The Repast {@code Context} holding one agent's children, which knows the agent that owns it.
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
        // Repast keys subcontexts by id in a map, so two same-named agents under one parent would
        // otherwise collide and the second would displace the first. The name is kept for
        // readability while the sequence keeps it unique.
        super(owner.getName() + "#" + SEQUENCE.incrementAndGet());
        this.owner = owner;
    }

    /** The agent whose children this context holds. */
    public Agent owner() {
        return owner;
    }
}
