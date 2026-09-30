package agentmodel;

import repast.simphony.engine.schedule.ISchedulableAction;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.Schedule;

/**
 * The same drive loop both sibling libraries settled on, and for the same hard-won reasons.
 *
 * <p>Runs every action scheduled at or before {@code endTick}, then stops, leaving the clock wherever
 * the last one put it. Driven by peeking at the next queued action rather than by asking "did the
 * tick advance", which is wrong twice over: a cancelled action stays in Repast's queue as a
 * placeholder that consumes an {@code execute()} without advancing the tick, so a stalled tick does
 * not mean the queue is empty; and {@code execute()} jumps straight to the next queued action however
 * far past {@code endTick} it sits.
 */
final class ScheduleTestSupport {

    private ScheduleTestSupport() {
    }

    static void drive(ISchedule schedule, double endTick) {
        Schedule concrete = (Schedule) schedule;
        while (concrete.getActionCount() > 0) {
            ISchedulableAction next = concrete.peekNextAction();
            if (next == null || next.getNextTime() > endTick) {
                return;
            }
            concrete.execute();
        }
    }

    /** Advances the clock to {@code tick} even with no model action there, per the sibling skills. */
    static void advanceTo(ISchedule schedule, double tick) {
        schedule.schedule(
                repast.simphony.engine.schedule.ScheduleParameters.createOneTime(tick), () -> { });
        drive(schedule, tick);
    }
}
