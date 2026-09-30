package agentmodel;

import java.util.ArrayList;
import java.util.List;
import processmodel.foundation.AdmissionHandle;
import processmodel.foundation.ProcessListener;
import processmodel.foundation.ProcessStage;
import processmodel.foundation.ResourcePool;

/**
 * A Block that emits on demand. {@code core} depends on {@code foundation} alone and so has no
 * concrete Block to test against, which is the right dependency but means the test double has to
 * exist here.
 *
 * <p>{@link #listenerCount} is the point of writing it by hand rather than mocking: it lets a test
 * assert that detaching actually removed the registration, instead of inferring it from the
 * absence of events — which would pass just as well if the emit had silently stopped working.
 */
final class FakeStage<T> implements ProcessStage<T> {

    private final String name;
    private final List<ProcessListener<T>> listeners = new ArrayList<>();

    FakeStage(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public AdmissionHandle enter(T entity) {
        return AdmissionHandle.admitted();
    }

    @Override
    public void addProcessListener(ProcessListener<T> listener) {
        listeners.add(listener);
    }

    @Override
    public void removeProcessListener(ProcessListener<T> listener) {
        listeners.remove(listener);
    }

    int listenerCount() {
        return listeners.size();
    }

    void emitEntered(T entity, double tick) {
        for (ProcessListener<T> listener : new ArrayList<>(listeners)) {
            listener.entityEnteredStage(entity, this, tick);
        }
    }

    void emitExited(T entity, double tick) {
        for (ProcessListener<T> listener : new ArrayList<>(listeners)) {
            listener.entityExitedStage(entity, this, tick);
        }
    }

    void emitResourceAcquired(ResourcePool pool, Object holder, int units, double tick) {
        for (ProcessListener<T> listener : new ArrayList<>(listeners)) {
            listener.resourceAcquired(pool, holder, units, tick);
        }
    }

    void emitResourceReleased(ResourcePool pool, Object holder, int units, double tick) {
        for (ProcessListener<T> listener : new ArrayList<>(listeners)) {
            listener.resourceReleased(pool, holder, units, tick);
        }
    }
}
