package net.onrc.onos.api.flowmanager;

import java.util.Collection;
import java.util.EventListener;

/**
 * An interface class for flow manager. The role of the flow manager is to
 * manage a set of Match-Action entries based on the specified IFlow objects.
 * <p>
 * It compiles accepted IFlow objects to Match-Action entries by calculating the
 * match-action operation phases and allocating resources based on the
 * constrains described in the IFlow objects, and executes calculated phases
 * using Match-Action Service.
 * <p>
 * TODO: add more getter with filter for IFlow objects.
 */
public interface FlowManagerService {
    /**
     * Adds IFlow object, calculates match-action plan and executes it.
     *
     * @param flow IFlow object to be added
     * @return true if succeeded, false otherwise
     */
    boolean addFlow(Flow flow);

    /**
     * Removes IFlow object, calculates match-action plan and executes it.
     *
     * @param id ID for IFlow object to be removed
     * @return true if succeeded, false otherwise
     */
    boolean removeFlow(FlowId id);

    /**
     * Gets IFlow object.
     *
     * @param id ID of IFlow object
     * @return IFlow object if found, null otherwise
     */
    Flow getFlow(FlowId id);

    /**
     * Gets All IFlow objects.
     *
     * @return the collection of IFlow objects
     */
    Collection<Flow> getFlows();

    /**
     * Executes batch operation of IFlow object.
     *
     * @param ops flow operations to be executed
     * @return true if succeeded, false otherwise
     */
    boolean executeBatch(FlowBatchOperation ops);

    /**
     * Sets a conflict detection policy.
     *
     * @param policy ConflictDetectionPolicy object to be set
     */
    void setConflictDetectionPolicy(ConflictDetectionPolicy policy);

    /**
     * Gets the conflict detection policy.
     *
     * @return ConflictDetectionPolicy object being applied currently
     */
    ConflictDetectionPolicy getConflictDetectionPolicy();

    /**
     * Adds event listener to this service.
     *
     * @param listener EventListener to be added
     */
    void addEventListener(EventListener listener);

    /**
     * Removes event listener from this service.
     *
     * @param listener EventListener to be removed
     */
    void removeEventListener(EventListener listener);
}