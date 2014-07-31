package net.onrc.onos.core.matchaction;

import java.util.Collection;
import java.util.EventListener;

import net.onrc.onos.api.flowmanager.ConflictDetectionPolicy;

/**
 * Manages Match-Action entries.
 * <p>
 * TODO: Make all methods thread-safe
 */
public class MatchActionModule implements IMatchActionService {

    @Override
    public boolean addMatchAction(MatchAction matchAction) {
        MatchActionPhase phase = new MatchActionPhase();
        phase.addAddOperation(matchAction);
        MatchActionPlan plan = new MatchActionPlan();
        plan.addPhase(phase);
        return executePlan(plan);
    }

    @Override
    public boolean removeMatchAction(MatchActionId id) {
        MatchActionPhase phase = new MatchActionPhase();
        phase.addRemoveOperation(id);
        MatchActionPlan plan = new MatchActionPlan();
        plan.addPhase(phase);
        return executePlan(plan);
    }

    @Override
    public Collection<MatchAction> getMatchActions() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean executePlan(MatchActionPlan plan) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void setConflictDetectionPolicy(ConflictDetectionPolicy policy) {
        // TODO Auto-generated method stub

    }

    @Override
    public ConflictDetectionPolicy getConflictDetectionPolicy() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void addEventListener(EventListener listener) {
        // TODO Auto-generated method stub

    }

    @Override
    public void removeEventListener(EventListener listener) {
        // TODO Auto-generated method stub

    }
}
