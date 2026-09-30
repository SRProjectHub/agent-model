package agentmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class InspectionTest {

    static final class Group extends AbstractAgent {
        Group(String name) {
            super(name, () -> 0.0);
        }
    }

    static final class Unit extends AbstractAgent {
        Unit(String typeName, String name) {
            super(typeName, name, () -> 0.0);
        }
    }

    /** What a capability looks like: answers from state it already has, changes nothing. */
    interface Reporter {
        double report();
    }

    /** A second capability, to show lookups do not collide. */
    interface Tagged {
        String tag();
    }

    /** Counts its own calls, so a test can show that inspecting twice really asked twice. */
    static final class CountingReporter implements Reporter {
        final double value;
        int calls;

        CountingReporter(double value) {
            this.value = value;
        }

        @Override
        public double report() {
            calls++;
            return value;
        }
    }

    // -- polling many at once --------------------------------------------------------------

    @Test
    void pollingKeepsOnlyTheAgentsThatHoldTheCapability() {
        Group group = new Group("group");
        Unit answers = new Unit("alpha", "u-1");
        Unit silent = new Unit("alpha", "u-2");
        Unit alsoAnswers = new Unit("beta", "u-3");
        answers.hold(Reporter.class, () -> 1.0);
        alsoAnswers.hold(Reporter.class, () -> 3.0);
        for (Unit u : Arrays.asList(answers, silent, alsoAnswers)) {
            group.contain(u);
        }

        List<Inspection<Reporter>> answered = Inspection.over(group.contained(), Reporter.class);

        assertEquals(2, answered.size(), "the agent without the capability is dropped, not reported as absent");
        List<String> names = new ArrayList<>();
        for (Inspection<Reporter> one : answered) {
            names.add(one.agent().getName());
        }
        assertTrue(names.contains("u-1") && names.contains("u-3"));
        assertTrue(!names.contains("u-2"));
    }

    @Test
    void pollingAcrossARelationFollowsConnectionOrderAndIsStable() {
        // Pinned deliberately: JUNG's adjacency iteration happens to preserve insertion order, which
        // is what makes a ranked decision reproducible when candidates tie. It is an observed
        // property of the implementation rather than a documented Repast guarantee, so if an upgrade
        // changes it this test is where it surfaces.
        Group group = new Group("group");
        Unit hub = new Unit("alpha", "hub");
        group.contain(hub);

        List<Unit> spokes = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Unit u = new Unit("alpha", "u-" + i);
            u.hold(Reporter.class, () -> 0.0);
            group.contain(u);
            spokes.add(u);
        }
        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);

        // Connect out of natural name order, so insertion order and name order differ.
        for (int index : new int[] {3, 1, 4, 0, 2}) {
            dependsOn.connect(hub, spokes.get(index));
        }

        List<String> polled = new ArrayList<>();
        for (Inspection<Reporter> one : Inspection.over(dependsOn.connectedFrom(hub), Reporter.class)) {
            polled.add(one.agent().getName());
        }

        assertEquals(Arrays.asList("u-4", "u-2", "u-5", "u-1", "u-3"), polled);
    }

    @Test
    void thePollItselfIsTheOnlyThingThatCallsTheCapability() {
        // Building the list must not invoke anything: a poll that measured as a side effect of being
        // assembled would make the count depend on how many times the caller iterated it.
        Group group = new Group("group");
        Unit unit = new Unit("alpha", "u-1");
        CountingReporter reporter = new CountingReporter(7.0);
        unit.hold(Reporter.class, reporter);
        group.contain(unit);

        List<Inspection<Reporter>> answered = Inspection.over(group.contained(), Reporter.class);
        assertEquals(0, reporter.calls, "discovering the capability does not exercise it");

        assertEquals(7.0, answered.get(0).capability().report(), 1e-9);
        assertEquals(1, reporter.calls);
    }

    @Test
    void repeatingAPollGivesTheSameAnswersSincePollingChangesNothing() {
        Group group = new Group("group");
        Unit one = new Unit("alpha", "u-1");
        Unit two = new Unit("alpha", "u-2");
        one.hold(Reporter.class, () -> 1.0);
        two.hold(Reporter.class, () -> 2.0);
        group.contain(one);
        group.contain(two);

        List<Double> first = valuesOf(Inspection.over(group.contained(), Reporter.class));
        List<Double> again = valuesOf(Inspection.over(group.contained(), Reporter.class));

        assertEquals(first, again);
        assertEquals(Arrays.asList(1.0, 2.0), first);
    }

    private static List<Double> valuesOf(List<Inspection<Reporter>> answered) {
        List<Double> values = new ArrayList<>();
        for (Inspection<Reporter> one : answered) {
            values.add(one.capability().report());
        }
        return values;
    }

    @Test
    void twoCapabilitiesOnTheSameAgentsDoNotCollide() {
        Group group = new Group("group");
        Unit both = new Unit("alpha", "u-1");
        Unit reporterOnly = new Unit("alpha", "u-2");
        both.hold(Reporter.class, () -> 1.0);
        both.hold(Tagged.class, () -> "tagged");
        reporterOnly.hold(Reporter.class, () -> 2.0);
        group.contain(both);
        group.contain(reporterOnly);

        assertEquals(2, Inspection.over(group.contained(), Reporter.class).size());
        List<Inspection<Tagged>> tagged = Inspection.over(group.contained(), Tagged.class);
        assertEquals(1, tagged.size());
        assertSame(both, tagged.get(0).agent());
        assertEquals("tagged", tagged.get(0).capability().tag());
    }

    // -- the shape a ranked decision takes -------------------------------------------------

    @Test
    void aCoordinatorPollsItsCandidatesAndRanksWhatComesBack() {
        // The operation the whole contract exists for: ask everyone reachable, keep those that can
        // answer, decide locally. No batching machinery, no mediation — nothing is being changed.
        Group group = new Group("group");
        Unit coordinator = new Unit("alpha", "coordinator");
        group.contain(coordinator);

        double[] offers = {9.0, 3.0, 7.0};
        List<Unit> candidates = new ArrayList<>();
        for (int i = 0; i < offers.length; i++) {
            Unit candidate = new Unit("beta", "c-" + (i + 1));
            final double offer = offers[i];
            candidate.hold(Reporter.class, () -> offer);
            group.contain(candidate);
            candidates.add(candidate);
        }
        Unit cannotAnswer = new Unit("beta", "c-4");
        group.contain(cannotAnswer);

        AgentNetwork<String> dependsOn = AgentNetwork.declare(
                group.subContext(), "dependsOn", Direction.DIRECTED, Cardinality.MANY, String.class);
        for (Unit candidate : candidates) {
            dependsOn.connect(coordinator, candidate);
        }
        dependsOn.connect(coordinator, cannotAnswer);

        List<Inspection<Reporter>> answered =
                Inspection.over(dependsOn.connectedFrom(coordinator), Reporter.class);
        List<Inspection<Reporter>> ranked = new ArrayList<>(answered);
        Collections.sort(ranked, (a, b) -> Double.compare(a.capability().report(), b.capability().report()));

        assertEquals(3, answered.size(), "the one that cannot answer never entered the ranking");
        assertEquals("c-2", ranked.get(0).agent().getName(), "cheapest offer wins");
        assertEquals("c-1", ranked.get(2).agent().getName());
    }

    // -- over a population -----------------------------------------------------------------

    @Test
    void aPopulationCanBePolledDirectly() {
        Group group = new Group("group");
        Population<Unit> units = group.population("unit_group", Unit.class);
        Unit answers = new Unit("alpha", units.mintName());
        Unit silent = new Unit("alpha", units.mintName());
        answers.hold(Reporter.class, () -> 5.0);
        units.add(answers);
        units.add(silent);

        List<Inspection<Reporter>> answered = units.inspect(Reporter.class);

        assertEquals(1, answered.size());
        assertEquals("unit_group-1", answered.get(0).agent().getName());
        assertEquals(5.0, answered.get(0).capability().report(), 1e-9);
    }

    @Test
    void pollingSomethingWhereNobodyAnswersIsEmptyRatherThanAnError() {
        Group group = new Group("group");
        group.contain(new Unit("alpha", "u-1"));
        assertTrue(Inspection.over(group.contained(), Reporter.class).isEmpty());
        assertTrue(Inspection.over(Collections.<Agent>emptyList(), Reporter.class).isEmpty());
    }

    @Test
    void theResultIsASnapshotAndNotAWayToChangeTheCandidateSet() {
        Group group = new Group("group");
        Unit unit = new Unit("alpha", "u-1");
        unit.hold(Reporter.class, () -> 1.0);
        group.contain(unit);

        List<Inspection<Reporter>> answered = Inspection.over(group.contained(), Reporter.class);
        assertThrows(UnsupportedOperationException.class, () -> answered.clear());
    }

    @Test
    void pollingRefusesMissingArguments() {
        assertThrows(IllegalArgumentException.class, () -> Inspection.over(null, Reporter.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> Inspection.over(Collections.<Agent>emptyList(), null));
    }
}
