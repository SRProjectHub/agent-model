package agentmodel;

import java.util.Collection;
import java.util.Optional;

/**
 * A participant in a simulation: something with an identity that holds things, can be asked what
 * it is able to do, and is observable from outside.
 *
 * <p>Deliberately thin. An agent is not a facility, owns no material, and has no lifecycle — a
 * market, an owner and a government are agents on exactly the same terms as a production plant,
 * and a substrate that assumed otherwise would fit one of those and fail the rest. What an agent
 * <em>is</em> is the small set of things all of them share.
 *
 * <p>An interface rather than a base class, with {@link AbstractAgent} supplying the plumbing —
 * mirroring {@code ProcessStage}/{@code AbstractProcessStage} in {@code foundation}. That split
 * matters more here than in a language with multiple inheritance: capabilities are objects an
 * agent <em>holds</em> (see {@link #view}) rather than interfaces it implements, precisely because
 * Java allows only one superclass, and a model whose agents already extend something can
 * implement this interface and delegate.
 */
public interface Agent {

    String getName();

    /**
     * The agent containing this one, empty at the top of a hierarchy. Containment is exclusive, so
     * there is at most one.
     */
    Optional<Agent> container();

    /** The agents this one directly contains, not recursing further down. */
    Collection<Agent> children();

    /**
     * Looks up a capability this agent holds, keyed by the type it was held as. Empty when the
     * agent has no such capability, which is the normal way to ask "can you do this?" rather than
     * testing a class or catching an exception.
     */
    <C> Optional<C> view(Class<C> capability);

    /**
     * Observes everything the agent's held Blocks emit, re-attributed to the agent, so watching a
     * whole facility is one registration rather than one per Block. Registration lives on the
     * agent rather than on a shared bus, matching how {@code ProcessStage} and Repast's own
     * {@code Context} both do it.
     */
    void addAgentListener(AgentListener listener);

    void removeAgentListener(AgentListener listener);
}
