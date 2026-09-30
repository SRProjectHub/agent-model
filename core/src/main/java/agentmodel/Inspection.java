package agentmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One agent's answer to "can you do this, and if so, here is the thing that does it" — an agent
 * paired with a capability it holds.
 *
 * <p>Exists because asking many agents at once is the shape real work takes. A coordinator deciding
 * between candidates polls everything it is connected to, keeps the ones that can answer, and ranks
 * what comes back; doing that by hand means a loop, an {@link Optional} unwrap and a list build every
 * time. {@link #over} is that loop.
 *
 * <p><b>Inspection is read-only.</b> A capability reached this way must answer from state it already
 * has and change nothing — not its own agent, not the model. That is the same discipline
 * {@code SolveContext} states on the continuous side, and for the same reason: a query that quietly
 * acts makes the answer depend on who asked first. It cannot be enforced for arbitrary interfaces,
 * so it is stated here and expected of implementations. Anything that <em>does</em> act on the
 * receiving agent is an interaction, not an inspection, and goes through the mediator instead.
 *
 * <p>Views are not meant to be retained. Hold the result of a poll for the decision being made and
 * ask again next time, rather than caching it — what an agent can offer changes as the run proceeds.
 */
public final class Inspection<C> {

    private final Agent agent;
    private final C capability;

    private Inspection(Agent agent, C capability) {
        this.agent = agent;
        this.capability = capability;
    }

    /** The agent that answered. */
    public Agent agent() {
        return agent;
    }

    /** What it answered with — the capability object it holds, under the type asked for. */
    public C capability() {
        return capability;
    }

    /**
     * Polls every agent given, keeping those that hold the capability and dropping the rest, so the
     * result is exactly the candidates that can answer.
     *
     * <p>Order follows the order of {@code agents}. Over a relation that is the order connections
     * were made, which is stable from run to run — so a caller that ranks these and breaks ties by
     * position gets the same outcome every time.
     */
    public static <C> List<Inspection<C>> over(Iterable<? extends Agent> agents, Class<C> capability) {
        if (agents == null) {
            throw new IllegalArgumentException("nothing to inspect");
        }
        if (capability == null) {
            throw new IllegalArgumentException("a capability type is required");
        }
        List<Inspection<C>> answered = new ArrayList<>();
        for (Agent agent : agents) {
            Optional<C> held = agent.view(capability);
            if (held.isPresent()) {
                answered.add(new Inspection<>(agent, held.get()));
            }
        }
        return Collections.unmodifiableList(answered);
    }

    @Override
    public String toString() {
        return "Inspection(" + agent.getName() + " as " + capability.getClass().getSimpleName() + ")";
    }
}
