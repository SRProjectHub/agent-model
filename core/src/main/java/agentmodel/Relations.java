package agentmodel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import repast.simphony.context.Context;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import repast.simphony.space.projection.Projection;

/**
 * Saving and restoring an agent's relations around a move.
 *
 * <p>Needed because containment and relations are maintained by different mechanisms that meet
 * awkwardly. A relation is a Repast projection listening to a context; taking an agent out of that
 * context — which is exactly what changing its container does — drops its vertex and every edge
 * with it. That is correct for a departure and wrong for a transfer, so a transfer saves the edges
 * first and puts them back afterwards.
 *
 * <p>Nothing here keeps a registry of declared relations. Repast already knows which projections a
 * context carries, so the networks to preserve are found by asking the contexts the agent sits
 * inside, walking up through its containers.
 */
final class Relations {

    private Relations() {
    }

    /** One edge to put back: which network it belonged to, its ends, and its weight. */
    static final class SavedEdge {
        final Network<Object> network;
        final Object source;
        final Object target;
        final double weight;

        SavedEdge(Network<Object> network, Object source, Object target, double weight) {
            this.network = network;
            this.source = source;
            this.target = target;
            this.weight = weight;
        }
    }

    /** Every edge touching {@code agent}, across every relation it can currently be seen in. */
    static List<SavedEdge> save(AbstractAgent agent) {
        List<SavedEdge> saved = new ArrayList<>();
        for (Network<Object> network : networksAround(agent)) {
            Map<RepastEdge<Object>, Boolean> seen = new IdentityHashMap<>();
            for (RepastEdge<Object> edge : incidentEdges(network, agent)) {
                if (seen.put(edge, Boolean.TRUE) == null) {
                    saved.add(new SavedEdge(network, edge.getSource(), edge.getTarget(), edge.getWeight()));
                }
            }
        }
        return saved;
    }

    /**
     * Puts saved edges back, skipping any whose other end has meanwhile left the same relation —
     * a connection to something no longer there is not worth recreating.
     */
    static void restore(List<SavedEdge> saved) {
        for (SavedEdge edge : saved) {
            if (isVertex(edge.network, edge.source) && isVertex(edge.network, edge.target)) {
                edge.network.addEdge(edge.source, edge.target, edge.weight);
            }
        }
    }

    private static List<Network<Object>> networksAround(AbstractAgent agent) {
        List<Network<Object>> networks = new ArrayList<>();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        for (Context<Object> context : contextsAround(agent)) {
            for (Projection<?> projection : context.getProjections()) {
                if (projection instanceof Network && seen.put(projection, Boolean.TRUE) == null) {
                    @SuppressWarnings("unchecked")
                    Network<Object> network = (Network<Object>) projection;
                    networks.add(network);
                }
            }
        }
        return networks;
    }

    /**
     * The contexts this agent sits inside, innermost first. Repast contexts have no upward link, so
     * the chain is rebuilt from the agent hierarchy instead — for each container, both the context
     * it keeps its own children in and the one it lives in itself, since a relation may have been
     * declared at any level.
     */
    private static List<Context<Object>> contextsAround(AbstractAgent agent) {
        List<Context<Object>> chain = new ArrayList<>();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        addIfNew(chain, seen, agent.homeContext());
        for (AbstractAgent above = agent.containerAgent(); above != null; above = above.containerAgent()) {
            if (above.hasSubContext()) {
                addIfNew(chain, seen, above.subContext());
            }
            addIfNew(chain, seen, above.homeContext());
        }
        return chain;
    }

    private static void addIfNew(
            List<Context<Object>> chain, Map<Object, Boolean> seen, Context<Object> context) {
        if (context != null && seen.put(context, Boolean.TRUE) == null) {
            chain.add(context);
        }
    }

    private static Iterable<RepastEdge<Object>> incidentEdges(Network<Object> network, Object node) {
        List<RepastEdge<Object>> edges = new ArrayList<>();
        collect(edges, network.getOutEdges(node));
        collect(edges, network.getInEdges(node));
        return edges;
    }

    private static void collect(List<RepastEdge<Object>> into, Iterable<RepastEdge<Object>> maybe) {
        if (maybe != null) {
            for (RepastEdge<Object> edge : maybe) {
                into.add(edge);
            }
        }
    }

    /**
     * Whether the node is in this relation at all. Repast offers no direct test, but it returns null
     * rather than an empty iterable for a node that is not a vertex, which answers the question.
     */
    private static boolean isVertex(Network<Object> network, Object node) {
        return network.getOutEdges(node) != null;
    }
}
