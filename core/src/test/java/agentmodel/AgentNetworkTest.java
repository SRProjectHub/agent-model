package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentNetworkTest {

    static final class Owner extends AbstractAgent {
        Owner(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Facility extends AbstractAgent {
        Facility(String typeName, String name) {
            super(typeName, name, () -> 0.0);
        }
    }

    /** A message type carried by a relation, to show the declaration fixes one. */
    static final class MaterialRequest {
        final double amount;

        MaterialRequest(double amount) {
            this.amount = amount;
        }
    }

    /** Root of a small hierarchy: one owner with a fleet, so relations span a subcontext. */
    private static Owner ownerWithFleet(Population<Facility>[] out) {
        Owner owner = new Owner("owner");
        out[0] = owner.population("plant_fleet", Facility.class);
        return owner;
    }

    // -- declaration -----------------------------------------------------------------------

    @Test
    void aRelationFixesItsDirectionCardinalityAndMessageTypeWhenDeclared() {
        Owner owner = new Owner("owner");
        AgentNetwork<MaterialRequest> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, MaterialRequest.class);

        assertEquals("supplies", supplies.getName());
        assertEquals(Direction.DIRECTED, supplies.direction());
        assertEquals(Cardinality.MANY, supplies.cardinality());
        assertEquals(MaterialRequest.class, supplies.messageType());
    }

    @Test
    void declarationRefusesMissingPieces() {
        Owner owner = new Owner("owner");
        assertThrows(IllegalArgumentException.class, () -> AgentNetwork.declare(
                null, "supplies", Direction.DIRECTED, Cardinality.MANY, MaterialRequest.class));
        assertThrows(IllegalArgumentException.class, () -> AgentNetwork.declare(
                owner.subContext(), "", Direction.DIRECTED, Cardinality.MANY, MaterialRequest.class));
        assertThrows(IllegalArgumentException.class, () -> AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, null));
    }

    // -- several independent relations over the same agents ---------------------------------

    @Test
    void relationsArePluralAndIndependentOfEachOtherAndOfContainment() {
        @SuppressWarnings("unchecked")
        Population<Facility>[] fleetRef = new Population[1];
        Owner owner = ownerWithFleet(fleetRef);
        Population<Facility> fleet = fleetRef[0];

        Facility enrichment = new Facility("centrifuge", fleet.mintName());
        Facility fabrication = new Facility("fuelfab", fleet.mintName());
        fleet.add(enrichment);
        fleet.add(fabrication);

        AgentNetwork<MaterialRequest> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, MaterialRequest.class);
        AgentNetwork<String> regulates = AgentNetwork.declare(
                owner.subContext(), "regulates", Direction.DIRECTED, Cardinality.MANY, String.class);

        supplies.connect(enrichment, fabrication);

        // A second relation over the same agents is untouched by the first.
        assertTrue(supplies.isConnected(enrichment, fabrication));
        assertFalse(regulates.isConnected(enrichment, fabrication));

        // And neither has anything to do with who contains whom.
        assertSame(owner, enrichment.container().get());
        assertFalse(supplies.isConnected(owner, enrichment));
    }

    @Test
    void aRelationReachesAgentsLivingInNestedContexts() {
        // The reason a projection on the top context is enough: contexts re-fire their children's
        // membership events, so agents deep in the hierarchy become vertices without special care.
        @SuppressWarnings("unchecked")
        Population<Facility>[] fleetRef = new Population[1];
        Owner owner = ownerWithFleet(fleetRef);
        Population<Facility> fleet = fleetRef[0];

        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);

        // Added to the population's nested context *after* the relation was declared.
        Facility a = new Facility("mine", fleet.mintName());
        Facility b = new Facility("mill", fleet.mintName());
        fleet.add(a);
        fleet.add(b);

        supplies.connect(a, b);
        assertTrue(supplies.isConnected(a, b));
    }

    // -- direction -------------------------------------------------------------------------

    @Test
    void aDirectedRelationDistinguishesWhoSuppliesWhom() {
        Owner owner = new Owner("owner");
        Facility upstream = new Facility("mine", "m-1");
        Facility downstream = new Facility("mill", "m-2");
        owner.contain(upstream);
        owner.contain(downstream);

        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        supplies.connect(upstream, downstream);

        assertTrue(supplies.isConnected(upstream, downstream));
        assertFalse(supplies.isConnected(downstream, upstream), "direction is meaning, not decoration");

        assertEquals(Arrays.asList((Agent) downstream), supplies.connectedFrom(upstream));
        assertEquals(Arrays.asList((Agent) upstream), supplies.connectedTo(downstream));
        assertTrue(supplies.connectedFrom(downstream).isEmpty());
    }

    @Test
    void anUndirectedRelationConnectsBothWaysInOneAct() {
        Owner owner = new Owner("owner");
        Facility one = new Facility("plant", "p-1");
        Facility two = new Facility("plant", "p-2");
        owner.contain(one);
        owner.contain(two);

        AgentNetwork<String> peers = AgentNetwork.declare(
                owner.subContext(), "peers", Direction.UNDIRECTED, Cardinality.MANY, String.class);
        peers.connect(one, two);

        assertTrue(peers.isConnected(one, two));
        assertTrue(peers.isConnected(two, one));
        assertEquals(Arrays.asList((Agent) two), peers.connectedFrom(one));
        assertEquals(Arrays.asList((Agent) one), peers.connectedFrom(two));
    }

    // -- cardinality -----------------------------------------------------------------------

    @Test
    void aOneCardinalityRelationRefusesASecondConnectionRatherThanReplacingIt() {
        Owner owner = new Owner("owner");
        Facility facility = new Facility("plant", "p-1");
        Owner first = new Owner("first");
        Owner second = new Owner("second");
        owner.contain(facility);
        owner.contain(first);
        owner.contain(second);

        AgentNetwork<String> operatedBy = AgentNetwork.declare(
                owner.subContext(), "operatedBy", Direction.DIRECTED, Cardinality.ONE, String.class);
        operatedBy.connect(facility, first);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> operatedBy.connect(facility, second));
        assertTrue(thrown.getMessage().contains("disconnect it first"));
        assertSame(first, operatedBy.sole(facility).get(), "the first connection still stands");
    }

    @Test
    void reassigningAOneCardinalityConnectionIsDisconnectThenConnect() {
        Owner owner = new Owner("owner");
        Facility facility = new Facility("plant", "p-1");
        Owner first = new Owner("first");
        Owner second = new Owner("second");
        owner.contain(facility);
        owner.contain(first);
        owner.contain(second);

        AgentNetwork<String> operatedBy = AgentNetwork.declare(
                owner.subContext(), "operatedBy", Direction.DIRECTED, Cardinality.ONE, String.class);
        operatedBy.connect(facility, first);
        assertTrue(operatedBy.disconnect(facility, first));
        operatedBy.connect(facility, second);

        assertSame(second, operatedBy.sole(facility).get());
    }

    @Test
    void askingForTheSoleConnectionOfAManyRelationIsRefused() {
        Owner owner = new Owner("owner");
        Facility facility = new Facility("plant", "p-1");
        owner.contain(facility);
        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);

        assertThrows(IllegalStateException.class, () -> supplies.sole(facility));
    }

    // -- refusals and removal --------------------------------------------------------------

    @Test
    void connectingAnAgentToItselfOrTwiceIsRefused() {
        Owner owner = new Owner("owner");
        Facility one = new Facility("plant", "p-1");
        Facility two = new Facility("plant", "p-2");
        owner.contain(one);
        owner.contain(two);
        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);

        assertThrows(IllegalStateException.class, () -> supplies.connect(one, one));
        supplies.connect(one, two);
        assertThrows(IllegalStateException.class, () -> supplies.connect(one, two));
    }

    @Test
    void disconnectingReportsWhetherThereWasAConnection() {
        Owner owner = new Owner("owner");
        Facility one = new Facility("plant", "p-1");
        Facility two = new Facility("plant", "p-2");
        owner.contain(one);
        owner.contain(two);
        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);

        assertFalse(supplies.disconnect(one, two));
        supplies.connect(one, two);
        assertTrue(supplies.disconnect(one, two));
        assertFalse(supplies.isConnected(one, two));
    }

    @Test
    void disconnectAllClearsBothDirections() {
        Owner owner = new Owner("owner");
        Facility middle = new Facility("plant", "p-mid");
        Facility up = new Facility("plant", "p-up");
        Facility down = new Facility("plant", "p-down");
        for (Facility f : Arrays.asList(middle, up, down)) {
            owner.contain(f);
        }
        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        supplies.connect(up, middle);
        supplies.connect(middle, down);

        assertEquals(2, supplies.degree(middle));
        assertEquals(2, supplies.disconnectAll(middle));
        assertEquals(0, supplies.degree(middle));
        assertFalse(supplies.isConnected(up, middle));
        assertFalse(supplies.isConnected(middle, down));
    }

    @Test
    void disconnectWhereRemovesOnlyTheMatchingConnections() {
        Owner owner = new Owner("owner");
        Facility hub = new Facility("plant", "hub");
        Facility keep = new Facility("keep", "k-1");
        Facility drop = new Facility("drop", "d-1");
        for (Facility f : Arrays.asList(hub, keep, drop)) {
            owner.contain(f);
        }
        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        supplies.connect(hub, keep);
        supplies.connect(hub, drop);

        assertEquals(1, supplies.disconnectWhere(hub, other -> other.typeName().equals("drop")));
        assertEquals(Arrays.asList((Agent) keep), supplies.connectedFrom(hub));
    }

    // -- the reason a Repast projection is worth using -------------------------------------

    @Test
    void anAgentLeavingTheHierarchyLosesItsConnectionsWithoutAnyoneTidyingUp() {
        @SuppressWarnings("unchecked")
        Population<Facility>[] fleetRef = new Population[1];
        Owner owner = ownerWithFleet(fleetRef);
        Population<Facility> fleet = fleetRef[0];

        Facility staying = new Facility("plant", fleet.mintName());
        Facility leaving = new Facility("plant", fleet.mintName());
        fleet.add(staying);
        fleet.add(leaving);

        AgentNetwork<String> supplies = AgentNetwork.declare(
                owner.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        supplies.connect(staying, leaving);
        assertTrue(supplies.isConnected(staying, leaving));

        // Removing the agent from its population is the only act; nothing touches the relation.
        fleet.remove(leaving);

        assertFalse(supplies.isConnected(staying, leaving));
        assertEquals(0, supplies.degree(staying), "no dangling edge is left behind");
    }

    @Test
    void releasingAnAgentClearsItsConnectionsEvenWhenItStaysUnderTheSameTopContext() {
        // Worth pinning precisely, because it is the mechanism above working as designed and yet
        // surprising: release removes the agent from a context, that removal propagates up to the
        // context the projection lives on, and the projection drops the vertex with its edges. So a
        // facility reassigned from one owner to another arrives with no relations at all.
        Owner top = new Owner("top");
        Owner left = new Owner("left");
        Owner right = new Owner("right");
        top.contain(left);
        top.contain(right);
        Facility facility = new Facility("plant", "p-1");
        Facility customer = new Facility("plant", "p-2");
        left.contain(facility);
        top.contain(customer);

        AgentNetwork<String> supplies = AgentNetwork.declare(
                top.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        supplies.connect(facility, customer);
        assertTrue(supplies.isConnected(facility, customer));

        left.release(facility);
        assertFalse(supplies.isConnected(facility, customer), "release drops the vertex and its edges");
        assertTrue(supplies.connectedFrom(facility).isEmpty(), "and querying a non-vertex is empty, not an error");

        right.contain(facility);
        assertFalse(
                supplies.isConnected(facility, customer),
                "re-containing does not restore what release removed");
    }
}
