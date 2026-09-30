package agentmodel;

/**
 * What an agent does when a message reaches it over a relation.
 *
 * <p>Called from the mediator's own event rather than from inside the sender's, so by the time this
 * runs the send has returned and the instant's Block activity has finished. Acting here is expected
 * — that is the whole difference between an interaction and an {@link Inspection}.
 */
public interface MessageHandler<M> {

    /**
     * @param sender the agent the message came from
     * @param message what it sent
     * @param tick the instant of delivery, which is the instant it was sent
     */
    void receive(Agent sender, M message, double tick);
}
