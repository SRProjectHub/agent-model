package agentmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import repast.simphony.context.Context;
import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.graph.DirectedJungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import repast.simphony.space.graph.UndirectedJungNetwork;

/**
 * One named relation among agents — {@code supplies}, {@code participatesIn}, {@code regulates} —
 * with its direction, how many connections an agent may have over it, and the type of message it
 * carries all fixed when it is declared.
 *
 * <p>Relations are plural and independent. Containment (§ {@link AbstractAgent#contain}) is a
 * separate thing entirely: an owner contains its facilities but does not supply them, a government
 * regulates a market it does not own, and a facility supplies another it has no authority over.
 * Collapsing those into one graph is what makes a substrate fit a material-flow model and fail
 * everything else, so each is its own network over the same agents.
 *
 * <p>A relation is also the <b>channel</b>. Declaring it fixes the message type that travels along
 * it, so who-may-talk-to-whom and what-they-may-say are one decision rather than two. Delivery
 * itself is mediated and arrives with the interaction band; this class establishes the structure and
 * the contract that delivery will use.
 *
 * <p>Backed by a Repast {@code Network} projection, which earns its place: because the projection
 * listens to its context and contexts re-fire their children's events, an agent leaving anywhere in
 * the hierarchy has its connections removed automatically. Nothing here has to find and clean up
 * dangling edges.
 */
public final class AgentNetwork<M> {

    private final String name;
    private final Direction direction;
    private final Cardinality cardinality;
    private final Class<M> messageType;
    private final Network<Object> network;

    private AgentNetwork(
            String name,
            Direction direction,
            Cardinality cardinality,
            Class<M> messageType,
            Network<Object> network) {
        this.name = name;
        this.direction = direction;
        this.cardinality = cardinality;
        this.messageType = messageType;
        this.network = network;
    }

    /**
     * Declares a relation over the agents in {@code over} and everything nested beneath it.
     *
     * <p>Pass the context at or above the highest agent that will take part — typically the model's
     * root, or the top agent's own context. Agents in subcontexts are included, since a context
     * re-fires its children's membership events to its own listeners and the projection is one of
     * them.
     */
    public static <M> AgentNetwork<M> declare(
            Context<Object> over,
            String name,
            Direction direction,
            Cardinality cardinality,
            Class<M> messageType) {
        if (over == null) {
            throw new IllegalArgumentException("a relation needs a context to span");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a relation needs a name");
        }
        if (direction == null || cardinality == null || messageType == null) {
            throw new IllegalArgumentException("direction, cardinality and message type are required");
        }
        Network<Object> underlying = new ContextJungNetwork<>(
                direction == Direction.DIRECTED
                        ? new DirectedJungNetwork<Object>(name)
                        : new UndirectedJungNetwork<Object>(name),
                over);
        over.addProjection(underlying);
        return new AgentNetwork<>(name, direction, cardinality, messageType, underlying);
    }

    public String getName() {
        return name;
    }

    public Direction direction() {
        return direction;
    }

    public Cardinality cardinality() {
        return cardinality;
    }

    /** The type of message this relation carries, once delivery exists. */
    public Class<M> messageType() {
        return messageType;
    }

    // -- connecting ------------------------------------------------------------------------

    /**
     * Connects {@code from} to {@code to}. On an undirected relation the same act connects them
     * both ways.
     *
     * @throws IllegalStateException if either end already has its one allowed connection, or if the
     *     two are already connected
     */
    public void connect(Agent from, Agent to) {
        require(from, "from");
        require(to, "to");
        if (from == to) {
            throw new IllegalStateException(name + ": an agent cannot connect to itself");
        }
        if (isConnected(from, to)) {
            throw new IllegalStateException(
                    name + ": " + from.getName() + " is already connected to " + to.getName());
        }
        if (cardinality == Cardinality.ONE) {
            refuseIfTaken(from, outgoing(from));
            // On a directed relation the target's own outgoing connections are its business; only
            // what arrives at it is constrained. Undirected has no such distinction.
            refuseIfTaken(to, direction == Direction.DIRECTED ? incoming(to) : outgoing(to));
        }
        network.addEdge(from, to);
    }

    private void refuseIfTaken(Agent agent, List<Agent> existing) {
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    name + " allows one connection per agent, and " + agent.getName()
                            + " already has one to " + existing.get(0).getName()
                            + " — disconnect it first to reassign");
        }
    }

    /** Removes the connection between the two, reporting whether there was one. */
    public boolean disconnect(Agent from, Agent to) {
        RepastEdge<Object> edge = network.getEdge(from, to);
        if (edge == null && direction == Direction.UNDIRECTED) {
            edge = network.getEdge(to, from);
        }
        if (edge == null) {
            return false;
        }
        network.removeEdge(edge);
        return true;
    }

    /** Removes every connection this agent has over this relation, in either direction. */
    public int disconnectAll(Agent agent) {
        int removed = 0;
        for (RepastEdge<Object> edge : new ArrayList<>(edgesOf(agent))) {
            network.removeEdge(edge);
            removed++;
        }
        return removed;
    }

    /** Removes this agent's connections to those matching {@code condition}, reporting how many. */
    public int disconnectWhere(Agent agent, Predicate<Agent> condition) {
        int removed = 0;
        for (Agent other : connected(agent)) {
            if (condition.test(other) && disconnect(agent, other)) {
                removed++;
            }
        }
        return removed;
    }

    // -- asking ----------------------------------------------------------------------------

    public boolean isConnected(Agent from, Agent to) {
        return direction == Direction.DIRECTED
                ? network.getEdge(from, to) != null
                : network.getEdge(from, to) != null || network.getEdge(to, from) != null;
    }

    /** Everything this agent reaches over this relation — outgoing only, when directed. */
    public List<Agent> connectedFrom(Agent agent) {
        return outgoing(agent);
    }

    /** Everything that reaches this agent over this relation — incoming only, when directed. */
    public List<Agent> connectedTo(Agent agent) {
        return direction == Direction.DIRECTED ? incoming(agent) : outgoing(agent);
    }

    /** Everything connected to this agent in either direction. */
    public List<Agent> connected(Agent agent) {
        List<Agent> found = new ArrayList<>(outgoing(agent));
        if (direction == Direction.DIRECTED) {
            for (Agent other : incoming(agent)) {
                if (!found.contains(other)) {
                    found.add(other);
                }
            }
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * The single agent this one reaches, for a {@link Cardinality#ONE} relation. Empty when
     * unconnected.
     *
     * @throws IllegalStateException on a {@link Cardinality#MANY} relation, where the question has
     *     no single answer
     */
    public Optional<Agent> sole(Agent agent) {
        if (cardinality != Cardinality.ONE) {
            throw new IllegalStateException(
                    name + " allows many connections per agent, so there is no single one to return");
        }
        List<Agent> found = outgoing(agent);
        return found.isEmpty() ? Optional.<Agent>empty() : Optional.of(found.get(0));
    }

    public int degree(Agent agent) {
        return connected(agent).size();
    }

    // -- internals -------------------------------------------------------------------------

    // Repast returns null rather than an empty iterable for an agent that is not a vertex — which
    // an agent that has left the hierarchy no longer is. Having no connections is the right answer
    // to every question below, so each traversal treats null as empty rather than propagating it.
    private static <E> Iterable<E> orEmpty(Iterable<E> maybe) {
        return maybe == null ? Collections.<E>emptyList() : maybe;
    }

    private List<Agent> outgoing(Agent agent) {
        List<Agent> found = new ArrayList<>();
        if (direction == Direction.DIRECTED) {
            for (RepastEdge<Object> edge : orEmpty(network.getOutEdges(agent))) {
                found.add(asAgent(edge.getTarget()));
            }
        } else {
            for (Object other : orEmpty(network.getAdjacent(agent))) {
                found.add(asAgent(other));
            }
        }
        return Collections.unmodifiableList(found);
    }

    private List<Agent> incoming(Agent agent) {
        List<Agent> found = new ArrayList<>();
        for (RepastEdge<Object> edge : orEmpty(network.getInEdges(agent))) {
            found.add(asAgent(edge.getSource()));
        }
        return Collections.unmodifiableList(found);
    }

    private List<RepastEdge<Object>> edgesOf(Agent agent) {
        List<RepastEdge<Object>> edges = new ArrayList<>();
        for (RepastEdge<Object> edge : orEmpty(network.getOutEdges(agent))) {
            edges.add(edge);
        }
        if (direction == Direction.DIRECTED) {
            for (RepastEdge<Object> edge : orEmpty(network.getInEdges(agent))) {
                edges.add(edge);
            }
        }
        return edges;
    }

    private static Agent asAgent(Object o) {
        return (Agent) o;
    }

    private static void require(Agent agent, String role) {
        if (agent == null) {
            throw new IllegalArgumentException(role + " agent is required");
        }
    }

    @Override
    public String toString() {
        return "AgentNetwork(" + name + ", " + direction + ", " + cardinality + ")";
    }
}
