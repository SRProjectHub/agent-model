package agentmodel;

/**
 * Notified as a population gains and loses members.
 *
 * <p>Repast's own {@code Context} fires add and remove events, but only over {@code Object} and
 * only for the context as a whole, so a model watching one fleet would have to filter by type and
 * by which context the event came from. This states it directly instead.
 */
public interface PopulationListener<A extends Agent> {

    default void joined(Population<A> population, A agent) {
    }

    default void left(Population<A> population, A agent) {
    }
}
