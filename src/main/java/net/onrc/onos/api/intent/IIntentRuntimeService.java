package net.onrc.onos.api.intent;

import java.util.Collection;
import java.util.EventListener;

import net.onrc.onos.api.batchoperation.BatchOperation;
import net.onrc.onos.api.flowmanager.ConflictDetectionPolicy;
import net.onrc.onos.api.flowmanager.IFlow;

/**
 * An interface class for the Intent-Runtime Service. The role of the
 * Intent-Runtime Service is to manage a set of IFlow objects based on the
 * specified Intent objects.
 * <p>
 * It compiles accepted Intents to IFlow objects by allocating resources and
 * calculating paths based on the constrains described in the Intents, and
 * executes installation/uninstallation of the IFlow objects using FlowManager
 * Service.
 */
public interface IIntentRuntimeService {
    /**
     * Adds specified intent.
     *
     * @param intent Intent to be added.
     * @return true if succeeded, false otherwise.
     */
    boolean addIntent(Intent intent);

    /**
     * Removes specified intent.
     *
     * @param id ID of the intent to be removed.
     * @return true if succeeded, false otherwise.
     */
    boolean removeIntent(IntentId id);

    /**
     * Overwrites existing intent by new specified intent.
     *
     * @param id ID of the existing intent to be overwritten.
     * @param intent The new intent to be added.
     * @return true if succeeded, false otherwise.
     */
    boolean updateIntent(IntentId id, Intent intent);

    /**
     * Gets specific intent.
     *
     * @param id ID of the intent should be retrieved
     * @return Intent if it exists, null otherwise.
     */
    Intent getIntent(IntentId id);

    /**
     * Gets all intents.
     *
     * @return collection of intents.
     */
    Collection<Intent> getIntents();

    /**
     * Executes batch operation of intents.
     *
     * @param ops BatchOperations to be executed.
     * @return true if succeeded, false otherwise.
     */
    boolean executeBatch(BatchOperation<Intent> ops);

    /**
     * Gets IFlow objects managed by the specified intent.
     *
     * @param intentId ID of the target Intent.
     * @return Collection of IFlow objects if exists, null otherwise.
     */
    Collection<IFlow> getFlows(String intentId);

    /**
     * Gets Intent object which manages the specified IFlow object.
     *
     * @param flowId ID of the target IFlow object.
     * @return Intent which manages the specified IFlow object, null otherwise.
     */
    Intent getIntentByFlow(String flowId);

    /**
     * Sets a conflict detection policy.
     *
     * @param policy ConflictDetectionPolicy object to be set.
     */
    void setConflictDetectionPolicy(ConflictDetectionPolicy policy);

    /**
     * Gets the conflict detection policy.
     *
     * @return ConflictDetectionPolicy object being applied currently.
     */
    ConflictDetectionPolicy getConflictDetectionPolicy();

    /**
     * Adds event listener to this service.
     *
     * @param listener EventListener to be added.
     */
    void addEventListener(EventListener listener);

    /**
     * Removes event listener from this service.
     *
     * @param listener EventListener to be removed.
     */
    void removeEventListener(EventListener listener);
}
