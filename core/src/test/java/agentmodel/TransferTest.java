package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import processmodel.foundation.ProcessStage;

/**
 * Transferring an agent between containers — a plant changing hands. The point of a distinct
 * operation is that plain release-and-contain silently destroys the agent's relations, which is
 * right for a departure and wrong for a move.
 */
class TransferTest {

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

    @Test
    void aTransferredAgentKeepsItsRelationsWhereReleaseAndContainWouldHaveLostThem() {
        Owner top = new Owner("top");
        Owner seller = new Owner("seller");
        Owner buyer = new Owner("buyer");
        top.contain(seller);
        top.contain(buyer);

        Facility plant = new Facility("plant", "p-1");
        Facility customer = new Facility("plant", "c-1");
        seller.contain(plant);
        top.contain(customer);

        AgentNetwork<String> supplies = AgentNetwork.declare(
                top.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        supplies.connect(plant, customer);

        buyer.transfer(plant);

        assertSame(buyer, plant.container().get(), "the plant changed hands");
        assertTrue(supplies.isConnected(plant, customer), "and kept supplying its customer");
        assertEquals(Arrays.asList((Agent) customer), supplies.connectedFrom(plant));
    }

    @Test
    void relationsAreKeptInBothDirectionsAndAcrossSeveralAtOnce() {
        Owner top = new Owner("top");
        Owner seller = new Owner("seller");
        Owner buyer = new Owner("buyer");
        top.contain(seller);
        top.contain(buyer);

        Facility plant = new Facility("plant", "p-1");
        Facility upstream = new Facility("mine", "m-1");
        Facility downstream = new Facility("fab", "f-1");
        Facility peer = new Facility("plant", "p-2");
        seller.contain(plant);
        for (Facility other : Arrays.asList(upstream, downstream, peer)) {
            top.contain(other);
        }

        AgentNetwork<String> supplies = AgentNetwork.declare(
                top.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        AgentNetwork<String> peers = AgentNetwork.declare(
                top.subContext(), "peers", Direction.UNDIRECTED, Cardinality.MANY, String.class);

        supplies.connect(upstream, plant);   // incoming
        supplies.connect(plant, downstream); // outgoing
        peers.connect(plant, peer);          // undirected, a second relation

        buyer.transfer(plant);

        assertTrue(supplies.isConnected(upstream, plant), "incoming survived");
        assertTrue(supplies.isConnected(plant, downstream), "outgoing survived");
        assertTrue(peers.isConnected(plant, peer), "the other relation survived too");
        assertEquals(2, supplies.degree(plant));
        assertEquals(1, peers.degree(plant));
    }

    @Test
    void aTransferredAgentKeepsWhatItHoldsAndKeepsForwardingToItsNewContainer() {
        Owner top = new Owner("top");
        Owner seller = new Owner("seller");
        Owner buyer = new Owner("buyer");
        top.contain(seller);
        top.contain(buyer);

        Facility plant = new Facility("plant", "p-1");
        FakeStage<String> line = new FakeStage<>("line");
        plant.hold(line);
        seller.contain(plant);

        List<String> atSeller = new ArrayList<>();
        List<String> atBuyer = new ArrayList<>();
        seller.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                atSeller.add(agent.getName());
            }
        });
        buyer.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                atBuyer.add(agent.getName());
            }
        });

        buyer.transfer(plant);
        line.emitEntered("batch", 1.0);

        assertEquals(1, plant.heldBlocks().size(), "a transfer does not strip what the agent holds");
        assertEquals(Arrays.asList("p-1"), atBuyer, "events now reach the new container");
        assertTrue(atSeller.isEmpty(), "and no longer reach the old one");
    }

    @Test
    void transferringBetweenPopulationsNotifiesBothSides() {
        Owner top = new Owner("top");
        Owner seller = new Owner("seller");
        Owner buyer = new Owner("buyer");
        top.contain(seller);
        top.contain(buyer);
        Population<Facility> sold = seller.population("sold_fleet", Facility.class);
        Population<Facility> bought = buyer.population("bought_fleet", Facility.class);

        List<String> events = new ArrayList<>();
        sold.addPopulationListener(new PopulationListener<Facility>() {
            @Override
            public void left(Population<Facility> population, Facility agent) {
                events.add("left " + population.getName());
            }
        });
        bought.addPopulationListener(new PopulationListener<Facility>() {
            @Override
            public void joined(Population<Facility> population, Facility agent) {
                events.add("joined " + population.getName());
            }
        });

        Facility plant = new Facility("plant", sold.mintName());
        sold.add(plant);
        bought.transfer(plant);

        assertEquals(Arrays.asList("left sold_fleet", "joined bought_fleet"), events);
        assertTrue(bought.contains(plant));
        assertFalse(sold.contains(plant));
        assertSame(buyer, plant.container().get());
    }

    @Test
    void anAgentKeepsItsMintedNameWhenItChangesPopulation() {
        // The name records where an agent came from, not where it currently sits. Renaming on
        // transfer would break every record already written against the old name.
        Owner top = new Owner("top");
        Owner seller = new Owner("seller");
        Owner buyer = new Owner("buyer");
        top.contain(seller);
        top.contain(buyer);
        Population<Facility> sold = seller.population("sold_fleet", Facility.class);
        Population<Facility> bought = buyer.population("bought_fleet", Facility.class);

        Facility plant = new Facility("plant", sold.mintName());
        sold.add(plant);
        bought.transfer(plant);

        assertEquals("sold_fleet-1", plant.getName());
        assertEquals("bought_fleet-1", bought.mintName(), "the new population's own sequence is untouched");
    }

    @Test
    void transferringSomethingUncontainedIsRefused() {
        Owner buyer = new Owner("buyer");
        assertThrows(IllegalStateException.class, () -> buyer.transfer(new Facility("plant", "p-1")));
    }

    @Test
    void transferringIntoTheSamePlaceIsANoOp() {
        Owner owner = new Owner("owner");
        Facility plant = new Facility("plant", "p-1");
        owner.contain(plant);

        owner.transfer(plant);

        assertSame(owner, plant.container().get());
        assertEquals(1, owner.contained().size(), "not contained twice over");
    }

    @Test
    void transferringIntoOneOfItsOwnDescendantsIsRefused() {
        Owner top = new Owner("top");
        Owner middle = new Owner("middle");
        Owner inner = new Owner("inner");
        top.contain(middle);
        middle.contain(inner);

        assertThrows(IllegalStateException.class, () -> inner.transfer(middle));
        assertSame(top, middle.container().get(), "the refused transfer left the tree alone");
    }

    @Test
    void aConnectionToSomethingThatHasItselfDepartedIsNotRecreated() {
        Owner top = new Owner("top");
        Owner seller = new Owner("seller");
        Owner buyer = new Owner("buyer");
        top.contain(seller);
        top.contain(buyer);

        Facility plant = new Facility("plant", "p-1");
        Facility gone = new Facility("plant", "g-1");
        seller.contain(plant);
        top.contain(gone);

        AgentNetwork<String> supplies = AgentNetwork.declare(
                top.subContext(), "supplies", Direction.DIRECTED, Cardinality.MANY, String.class);
        supplies.connect(plant, gone);

        top.release(gone); // the counterparty leaves entirely
        buyer.transfer(plant);

        assertEquals(0, supplies.degree(plant), "a relation to something no longer there is not restored");
        assertSame(buyer, plant.container().get());
    }
}
