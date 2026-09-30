package agentmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import repast.simphony.util.collections.IndexedIterable;

/**
 * A named, typed group of agents held by one container — a fleet of reactors, the plants one owner
 * operates, the participants in a market.
 *
 * <p>Each population owns its own Repast context, nested inside its container's. That is what lets
 * one container hold several populations, including two of the same class, and lets a display or
 * dataset be pointed at one population rather than at everything the container holds. Membership
 * lives in that context and nowhere else, so there is no second structure to fall out of step.
 *
 * <p>The container is still the agent that <em>contains</em> the members: a population subdivides
 * what one agent holds rather than owning anything itself, so a member's container is the owner and
 * events still forward up through it.
 *
 * <p>Populations mint their members' names. An instance name is the population's name plus an
 * ordinal — {@code sfr_fleet-1}, {@code sfr_fleet-2} — which is the third part of an agent's
 * identity alongside its Java class and its {@link Agent#typeName() type name}.
 */
public final class Population<A extends Agent> implements Iterable<A> {

    private final String name;
    private final Class<A> type;
    private final AbstractAgent container;
    private final AgentContext context;
    private final AtomicInteger nextOrdinal = new AtomicInteger();
    private final List<PopulationListener<A>> listeners = new CopyOnWriteArrayList<>();

    Population(AbstractAgent container, String name, Class<A> type) {
        this.container = container;
        this.name = name;
        this.type = type;
        this.context = new AgentContext(container, name);
        container.subContext().addSubContext(context);
    }

    public String getName() {
        return name;
    }

    /** The class every member is an instance of. */
    public Class<A> type() {
        return type;
    }

    /** The agent whose population this is. */
    public Agent container() {
        return container;
    }

    /**
     * The Repast context holding this population's members. Exposed so a display, dataset or
     * projection can be scoped to one population.
     */
    public AgentContext context() {
        return context;
    }

    /**
     * The next instance name in this population's sequence — {@code sfr_fleet-1} then
     * {@code sfr_fleet-2}. Ordinals are never reused, so a name identifies one agent for the whole
     * run even after that agent has left.
     */
    public String mintName() {
        return name + "-" + nextOrdinal.incrementAndGet();
    }

    /**
     * Adds an agent, which the container then contains. Nothing checks that the name came from
     * {@link #mintName} — a model restoring a saved run needs to supply its own.
     *
     * @throws IllegalStateException if the agent is already contained somewhere
     */
    public void add(A agent) {
        if (!(agent instanceof AbstractAgent)) {
            throw new IllegalArgumentException(
                    "a population holds AbstractAgent instances; " + agent.getClass() + " is not one");
        }
        container.containIn((AbstractAgent) agent, context);
        for (PopulationListener<A> listener : listeners) {
            listener.joined(this, agent);
        }
    }

    /** Removes an agent from this population, releasing the container's hold on it. */
    public boolean remove(A agent) {
        if (!contains(agent)) {
            return false;
        }
        container.release((AbstractAgent) agent);
        for (PopulationListener<A> listener : listeners) {
            listener.left(this, agent);
        }
        return true;
    }

    /**
     * Whether this agent is a direct member. Deliberately not {@code context.contains(agent)}:
     * Repast's {@code contains} comes from {@code AbstractCollection} over an iterator that chains
     * subcontexts, so it answers true for anything nested below a member too.
     */
    public boolean contains(A agent) {
        return agent instanceof AbstractAgent && ((AbstractAgent) agent).homeContext() == context;
    }

    /** How many members this population currently has, not counting anything they contain. */
    public int size() {
        return members().size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** The current members, as a snapshot — safe to iterate while the population changes. */
    public List<A> members() {
        List<A> found = new ArrayList<>();
        IndexedIterable<Object> matching = context.getObjects(type);
        for (int i = 0; i < matching.size(); i++) {
            Object candidate = matching.get(i);
            // getObjects recurses too, so a member's own child of the same class would otherwise be
            // counted here as well. Membership is decided by the context the agent actually lives
            // in, which containIn records and nothing else infers.
            if (candidate instanceof AbstractAgent && ((AbstractAgent) candidate).homeContext() == context) {
                found.add(type.cast(candidate));
            }
        }
        return Collections.unmodifiableList(found);
    }

    @Override
    public Iterator<A> iterator() {
        return members().iterator();
    }

    public Optional<A> byName(String instanceName) {
        for (A member : members()) {
            if (member.getName().equals(instanceName)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public void addPopulationListener(PopulationListener<A> listener) {
        listeners.add(listener);
    }

    public void removePopulationListener(PopulationListener<A> listener) {
        listeners.remove(listener);
    }

    @Override
    public String toString() {
        return "Population(" + name + " of " + type.getSimpleName() + ", " + size() + " members)";
    }
}
