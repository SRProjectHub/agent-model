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

class PopulationTest {

    static final class Owner extends AbstractAgent {
        Owner(String name) {
            super(name, () -> 0.0);
        }
    }

    /** Several types share this one class, which is why a type name is not the same as a class. */
    static final class Reactor extends AbstractAgent {
        Reactor(String typeName, String name) {
            super(typeName, name, () -> 0.0);
        }
    }

    static final class Enrichment extends AbstractAgent {
        Enrichment(String typeName, String name) {
            super(typeName, name, () -> 0.0);
        }
    }

    // -- the three-part identity -----------------------------------------------------------

    @Test
    void anAgentIsIdentifiedByItsClassItsTypeNameAndAMintedInstanceName() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);

        Reactor first = new Reactor("SFR", fleet.mintName());
        Reactor second = new Reactor("SFR", fleet.mintName());
        fleet.add(first);
        fleet.add(second);

        assertEquals(Reactor.class, first.getClass());
        assertEquals("SFR", first.typeName());
        assertEquals("sfr_fleet-1", first.getName());
        assertEquals("sfr_fleet-2", second.getName());
    }

    @Test
    void ordinalsAreNeverReusedSoANameIdentifiesOneAgentForTheWholeRun() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);

        Reactor first = new Reactor("SFR", fleet.mintName());
        fleet.add(first);
        fleet.remove(first);

        assertEquals("sfr_fleet-2", fleet.mintName(), "the departed agent's ordinal is not handed out again");
    }

    @Test
    void aOneOfAKindAgentTakesItsClassNameAsItsTypeName() {
        assertEquals("Owner", new Owner("gov").typeName());
    }

    @Test
    void aBlankTypeNameIsRefusedThoughAnAbsentOneIsFine() {
        assertThrows(IllegalArgumentException.class, () -> new Reactor("", "r-1"));
    }

    // -- one container, several populations -------------------------------------------------

    @Test
    void oneContainerHoldsSeveralPopulationsIncludingTwoOfTheSameClass() {
        Owner owner = new Owner("owner");
        Population<Reactor> sfr = owner.population("sfr_fleet", Reactor.class);
        Population<Reactor> lwr = owner.population("lwr_fleet", Reactor.class);
        Population<Enrichment> enrich = owner.population("enrich_fleet", Enrichment.class);

        sfr.add(new Reactor("SFR", sfr.mintName()));
        sfr.add(new Reactor("SFR", sfr.mintName()));
        lwr.add(new Reactor("LWR", lwr.mintName()));
        enrich.add(new Enrichment("centrifuge", enrich.mintName()));

        // Two populations of the same class stay distinct — the thing a type-filtered view could
        // not have done.
        assertEquals(2, sfr.size());
        assertEquals(1, lwr.size());
        assertEquals(1, enrich.size());
        assertEquals(
                Arrays.asList("sfr_fleet-1", "sfr_fleet-2"),
                Arrays.asList(sfr.members().get(0).getName(), sfr.members().get(1).getName()));
        assertEquals("lwr_fleet-1", lwr.members().get(0).getName());
    }

    @Test
    void twoPopulationsCannotShareANameSinceNamesMintIdentities() {
        Owner owner = new Owner("owner");
        owner.population("sfr_fleet", Reactor.class);
        assertThrows(IllegalStateException.class, () -> owner.population("sfr_fleet", Reactor.class));
    }

    @Test
    void aPopulationsMembersLiveInItsOwnContextNestedInTheContainers() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);
        Reactor reactor = new Reactor("SFR", fleet.mintName());
        fleet.add(reactor);

        assertTrue(fleet.context().contains(reactor));
        assertSame(owner, fleet.context().owner(), "a population subdivides what one agent holds");

        // Still transitive from the container: a root-level typed query finds population members.
        assertEquals(1, owner.subContext().getObjects(Reactor.class).size());
    }

    @Test
    void theContainerNotThePopulationIsWhatContainsAMember() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);
        Reactor reactor = new Reactor("SFR", fleet.mintName());
        fleet.add(reactor);

        assertSame(owner, reactor.container().get());
        assertTrue(owner.contained().contains(reactor));
        assertTrue(owner.isAbove(reactor));
    }

    // -- membership ------------------------------------------------------------------------

    @Test
    void eventsFromAMembersBlockStillForwardUpThroughTheContainer() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);
        Reactor reactor = new Reactor("SFR", fleet.mintName());
        FakeStage<String> core = new FakeStage<>("core");
        reactor.hold(core);
        fleet.add(reactor);

        List<String> seen = new ArrayList<>();
        owner.addAgentListener(new AgentListener() {
            @Override
            public void entityEntered(Agent agent, ProcessStage<?> stage, Object entity, double tick) {
                seen.add(agent.getName() + "/" + stage.getName());
            }
        });

        core.emitEntered("fuel", 1.0);
        assertEquals(Arrays.asList("sfr_fleet-1/core"), seen);
    }

    @Test
    void joiningAndLeavingAreNotified() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);

        List<String> events = new ArrayList<>();
        fleet.addPopulationListener(new PopulationListener<Reactor>() {
            @Override
            public void joined(Population<Reactor> population, Reactor agent) {
                events.add("joined " + population.getName() + "/" + agent.getName());
            }

            @Override
            public void left(Population<Reactor> population, Reactor agent) {
                events.add("left " + population.getName() + "/" + agent.getName());
            }
        });

        Reactor reactor = new Reactor("SFR", fleet.mintName());
        fleet.add(reactor);
        fleet.remove(reactor);

        assertEquals(Arrays.asList("joined sfr_fleet/sfr_fleet-1", "left sfr_fleet/sfr_fleet-1"), events);
    }

    @Test
    void removingLeavesTheAgentUncontainedAndOutOfTheContext() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);
        Reactor reactor = new Reactor("SFR", fleet.mintName());
        fleet.add(reactor);

        assertTrue(fleet.remove(reactor));
        assertFalse(fleet.contains(reactor));
        assertFalse(reactor.container().isPresent());
        assertTrue(fleet.isEmpty());
        assertFalse(fleet.remove(reactor), "removing a non-member reports false rather than throwing");
    }

    @Test
    void anAgentCannotBeInTwoPopulationsAtOnce() {
        Owner owner = new Owner("owner");
        Population<Reactor> sfr = owner.population("sfr_fleet", Reactor.class);
        Population<Reactor> lwr = owner.population("lwr_fleet", Reactor.class);
        Reactor reactor = new Reactor("SFR", sfr.mintName());
        sfr.add(reactor);

        assertThrows(IllegalStateException.class, () -> lwr.add(reactor));
    }

    @Test
    void lookupByInstanceName() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);
        fleet.add(new Reactor("SFR", fleet.mintName()));
        fleet.add(new Reactor("SFR", fleet.mintName()));

        assertEquals("sfr_fleet-2", fleet.byName("sfr_fleet-2").get().getName());
        assertFalse(fleet.byName("sfr_fleet-99").isPresent());
    }

    @Test
    void aMembersOwnChildrenAreNotCountedAsMembersOfThePopulation() {
        // getObjects recurses into subcontexts, so a reactor containing a reactor would otherwise
        // inflate the fleet it belongs to.
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);
        Reactor parent = new Reactor("SFR", fleet.mintName());
        fleet.add(parent);
        parent.contain(new Reactor("subassembly", "sub-1"));

        assertEquals(1, fleet.size(), "the nested reactor is not a member of this fleet");
        assertEquals(2, owner.subContext().getObjects(Reactor.class).size(), "though both are below the owner");
    }

    @Test
    void membersIsASnapshotAndNotAWayToMutateThePopulation() {
        Owner owner = new Owner("owner");
        Population<Reactor> fleet = owner.population("sfr_fleet", Reactor.class);
        fleet.add(new Reactor("SFR", fleet.mintName()));
        assertThrows(UnsupportedOperationException.class, () -> fleet.members().clear());
    }
}
