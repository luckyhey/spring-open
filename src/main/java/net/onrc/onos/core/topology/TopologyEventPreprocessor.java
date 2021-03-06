package net.onrc.onos.core.topology;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.floodlightcontroller.core.IFloodlightProviderService.Role;
import net.onrc.onos.core.registry.IControllerRegistryService;
import net.onrc.onos.core.registry.RegistryException;
import net.onrc.onos.core.util.Dpid;
import net.onrc.onos.core.util.EventEntry;
import net.onrc.onos.core.util.OnosInstanceId;

import static com.google.common.base.Preconditions.checkNotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Topology Event pre-processor. It is used by the Topology Manager for
 * pre-processing Topology events before applying them to the Topology.
 * <p/>
 * The pre-processor itself keeps internal state about the most recent
 * ADD events. It also might keep state about reordered events that cannot
 * be applied.
 * <p/>
 * As part of the pre-processing logic, a previously suppressed event might
 * be genenerated later because of some other event.
 */
public class TopologyEventPreprocessor {
    private static final Logger log = LoggerFactory
        .getLogger(TopologyEventPreprocessor.class);
    private final IControllerRegistryService registryService;

    //
    // Reordered ADD events that need to be reapplied
    //
    // TODO: For now, this field is accessed by the TopologyManager as well
    // This should be refactored, and change them to private.
    //
    Map<ByteBuffer, TopologyEvent> reorderedEvents = new HashMap<>();

    //
    // Topology ADD event state per ONOS instance
    //
    private Map<OnosInstanceId, OnosInstanceLastAddEvents> instanceState =
        new HashMap<>();

    //
    // Switch mastership state (updated by the topology events)
    //
    private Map<Dpid, OnosInstanceId> switchMastership = new HashMap<>();

    /**
     * Constructor for a given Registry Service.
     *
     * @param registryService the Registry Service to use.
     */
    TopologyEventPreprocessor(IControllerRegistryService registryService) {
        this.registryService = registryService;
    }

    /**
     * Class to store the last ADD Topology Events per ONOS Instance.
     */
    private final class OnosInstanceLastAddEvents {
        private final OnosInstanceId onosInstanceId;

        // The last ADD events received from this ONOS instance
        private Map<ByteBuffer, TopologyEvent> topologyEvents = new HashMap<>();

        /**
         * Constructor for a given ONOS Instance ID.
         *
         * @param onosInstanceId the ONOS Instance ID.
         */
        private OnosInstanceLastAddEvents(OnosInstanceId onosInstanceId) {
            this.onosInstanceId = checkNotNull(onosInstanceId);
        }

        /**
         * Processes an event originated by this ONOS instance.
         *
         * @param event the event to process.
         * @return true if the event should be applied to the final Topology
         * as well, otherwise false.
         */
        private boolean processEvent(EventEntry<TopologyEvent> event) {
            TopologyEvent topologyEvent = event.eventData();
            ByteBuffer id = topologyEvent.getIDasByteBuffer();
            OnosInstanceId masterId = null;
            boolean isConfigured = false;

            // Get the Master of the Origin DPID
            Dpid dpid = topologyEvent.getOriginDpid();
            if (dpid != null) {
                masterId = switchMastership.get(dpid);
            }

            if (topologyEvent.getConfigState() == ConfigState.CONFIGURED) {
                isConfigured = true;
            }

            //
            // Apply the event based on its type
            //
            switch (event.eventType()) {
            case ENTRY_ADD:
                topologyEvents.put(id, topologyEvent);
                reorderedEvents.remove(id);
                // Allow the ADD only if the event was originated by the Master
                return isConfigured || onosInstanceId.equals(masterId);

            case ENTRY_REMOVE:
                reorderedEvents.remove(id);
                // Don't allow the REMOVE event if there was no ADD before
                if (topologyEvents.remove(id) == null) {
                    return false;
                }
                //
                // Allow the REMOVE if the event was originated by the Master,
                // or there is no Master at all.
                //
                if (masterId == null) {
                    return true;
                }
                return isConfigured || onosInstanceId.equals(masterId);

            default:
                log.error("Unknown topology event {}", event.eventType());
            }

            return false;
        }

        /**
         * Gets the postponed events for a given DPID.
         * Those are the events that couldn't be applied earlier to the
         * Topology, because the ONOS Instance originating the events
         * was not the Master for the Switch.
         *
         * @param dpid the DPID to use.
         * @return a list of postponed events for the given DPID.
         */
        private List<EventEntry<TopologyEvent>> getPostponedEvents(Dpid dpid) {
            List<EventEntry<TopologyEvent>> result = new LinkedList<>();

            //
            // Search all events, and keep only those that match the DPID
            //
            // TODO: This could be slow, and the code should be optimized
            // for speed. The processing complexity is O(N*N) where N is
            // the number of Switches: for each Switch Mastership we call
            // getPostponedEvents(), and then for each call we
            // search all previously added events.
            // The code can be optimized by adding additional lookup map:
            //  Dpid -> List<TopologyEvent>
            //
            for (TopologyEvent te : topologyEvents.values()) {
                if (dpid.equals(te.getOriginDpid())) {
                    result.add(new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_ADD, te));
                }
            }

            return result;
        }
    }

    /**
     * Extracts previously reordered events that should be applied again
     * to the Topology.
     *
     * @return a list of previously reordered events.
     */
    private List<EventEntry<TopologyEvent>> extractReorderedEvents() {
        List<EventEntry<TopologyEvent>> result = new LinkedList<>();

        //
        // Search all previously reordered events, and extract only if
        // the originator is the Master.
        //
        List<TopologyEvent> leftoverEvents = new LinkedList<>();
        for (TopologyEvent te : reorderedEvents.values()) {
            Dpid dpid = te.getOriginDpid();
            OnosInstanceId masterId = null;
            if (dpid != null) {
                masterId = switchMastership.get(dpid);
            }
            if (te.getOnosInstanceId().equals(masterId)) {
                result.add(new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_ADD, te));
            } else {
                leftoverEvents.add(te);
            }
        }

        //
        // Add back the leftover events
        //
        reorderedEvents.clear();
        for (TopologyEvent te : leftoverEvents) {
            reorderedEvents.put(te.getIDasByteBuffer(), te);
        }

        return result;
    }

    /**
     * Processes a Mastership Event.
     *
     * @param instance the ONOS instance state to use.
     * @param event the event to process.
     * @return a list of postponed events if the processed event is a
     * Mastership Event for a new Master, otherwise an empty list.
     */
    private List<EventEntry<TopologyEvent>> processMastershipData(
                        OnosInstanceLastAddEvents instance,
                        EventEntry<TopologyEvent> event) {
        TopologyEvent topologyEvent = event.eventData();
        MastershipData mastershipData = topologyEvent.getMastershipData();

        if (mastershipData == null) {
            return Collections.emptyList();  // Not a Mastership Event
        }

        OnosInstanceId onosInstanceId = topologyEvent.getOnosInstanceId();
        Dpid dpid = mastershipData.getDpid();
        boolean newMaster = false;

        //
        // Update the Switch Mastership state:
        //  - If ADD a MASTER and the Mastership is confirmed by the
        //    Registry Service, or if the ADD is explicitly CONFIGURED,
        //    then add to the Mastership map and fetch the postponed
        //    events from the originating ONOS Instance.
        //  - Otherwise, remove from the Mastership map, but only if it is
        //    the current MASTER.
        //
        if ((event.eventType() == EventEntry.Type.ENTRY_ADD) &&
            (mastershipData.getRole() == Role.MASTER)) {

            //
            // Accept if explicitly configured, otherwise check
            // with the Registry Service.
            //
            if (topologyEvent.getConfigState() == ConfigState.CONFIGURED) {
                newMaster = true;
            } else {
                //
                // Check with the Registry Service as well
                //
                try {
                    String rc =
                        registryService.getControllerForSwitch(dpid.value());
                    if ((rc != null) &&
                        onosInstanceId.equals(new OnosInstanceId(rc))) {
                        newMaster = true;
                    }
                } catch (RegistryException e) {
                    log.error("Caught RegistryException while pre-processing Mastership Event", e);
                }
            }
        }

        if (newMaster) {
            // Add to the map
            switchMastership.put(dpid, onosInstanceId);
            return instance.getPostponedEvents(dpid);
        }

        // Not a Master: eventually remove from the map
        OnosInstanceId oldId = switchMastership.get(dpid);
        if (onosInstanceId.equals(oldId)) {
            switchMastership.remove(dpid);
        }
        return Collections.emptyList();
    }

    /**
     * Pre-processes a list of events.
     *
     * @param events the events to pre-process.
     * @return a list of pre-processed events.
     */
    List<EventEntry<TopologyEvent>> processEvents(
                List<EventEntry<TopologyEvent>> events) {
        List<EventEntry<TopologyEvent>> result = new LinkedList<>();

        //
        // Process the events
        //
        for (EventEntry<TopologyEvent> event : events) {
            List<EventEntry<TopologyEvent>> postponedEvents;

            // Ignore NO-OP events
            if (event.isNoop()) {
                continue;
            }

            TopologyEvent topologyEvent = event.eventData();
            OnosInstanceId onosInstanceId = topologyEvent.getOnosInstanceId();

            log.debug("Topology event {}: {}", event.eventType(),
                      topologyEvent);

            // Get the ONOS instance state
            OnosInstanceLastAddEvents instance =
                instanceState.get(onosInstanceId);
            if (instance == null) {
                instance = new OnosInstanceLastAddEvents(onosInstanceId);
                instanceState.put(onosInstanceId, instance);
            }

            postponedEvents = processMastershipData(instance, event);

            //
            // Process the event and eventually store it in the
            // per-Instance state.
            //
            if (instance.processEvent(event)) {
                result.add(event);
            }

            // Add the postponed events (if any)
            result.addAll(postponedEvents);
        }

        // Extract and add the previously reordered events
        result.addAll(extractReorderedEvents());

        return reorderEventsForTopology(result);
    }

    /**
     * Classifies and reorders a list of events, and suppresses matching
     * events.
     * <p/>
     * The result events can be applied to the Topology in the following
     * order: REMOVE events followed by ADD events. The ADD events are in the
     * natural order to build a Topology: MastershipData, SwitchData,
     * PortData, LinkData, HostData. The REMOVE events are in the reverse
     * order.
     *
     * @param events the events to classify and reorder.
     * @return the classified and reordered events.
     */
    private List<EventEntry<TopologyEvent>> reorderEventsForTopology(
                List<EventEntry<TopologyEvent>> events) {
        // Local state for computing the final set of events
        Map<ByteBuffer, EventEntry<TopologyEvent>> addedMastershipDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> removedMastershipDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> addedSwitchDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> removedSwitchDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> addedPortDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> removedPortDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> addedLinkDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> removedLinkDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> addedHostDataEntries =
            new HashMap<>();
        Map<ByteBuffer, EventEntry<TopologyEvent>> removedHostDataEntries =
            new HashMap<>();

        //
        // Classify and suppress matching events
        //
        // NOTE: We intentionally use the event payload as the key ID
        // (i.e., we exclude the ONOS Instance ID from the key),
        // so we can suppress transient events across multiple ONOS instances.
        //
        for (EventEntry<TopologyEvent> event : events) {
            TopologyEvent topologyEvent = event.eventData();

            // Get the event itself
            MastershipData mastershipData =
                topologyEvent.getMastershipData();
            SwitchData switchData = topologyEvent.getSwitchData();
            PortData portData = topologyEvent.getPortData();
            LinkData linkData = topologyEvent.getLinkData();
            HostData hostData = topologyEvent.getHostData();

            //
            // Extract the events
            //
            switch (event.eventType()) {
            case ENTRY_ADD:
                if (mastershipData != null) {
                    ByteBuffer id = mastershipData.getIDasByteBuffer();
                    addedMastershipDataEntries.put(id, event);
                    removedMastershipDataEntries.remove(id);
                }
                if (switchData != null) {
                    ByteBuffer id = switchData.getIDasByteBuffer();
                    addedSwitchDataEntries.put(id, event);
                    removedSwitchDataEntries.remove(id);
                }
                if (portData != null) {
                    ByteBuffer id = portData.getIDasByteBuffer();
                    addedPortDataEntries.put(id, event);
                    removedPortDataEntries.remove(id);
                }
                if (linkData != null) {
                    ByteBuffer id = linkData.getIDasByteBuffer();
                    addedLinkDataEntries.put(id, event);
                    removedLinkDataEntries.remove(id);
                }
                if (hostData != null) {
                    ByteBuffer id = hostData.getIDasByteBuffer();
                    addedHostDataEntries.put(id, event);
                    removedHostDataEntries.remove(id);
                }
                break;
            case ENTRY_REMOVE:
                if (mastershipData != null) {
                    ByteBuffer id = mastershipData.getIDasByteBuffer();
                    addedMastershipDataEntries.remove(id);
                    removedMastershipDataEntries.put(id, event);
                }
                if (switchData != null) {
                    ByteBuffer id = switchData.getIDasByteBuffer();
                    addedSwitchDataEntries.remove(id);
                    removedSwitchDataEntries.put(id, event);
                }
                if (portData != null) {
                    ByteBuffer id = portData.getIDasByteBuffer();
                    addedPortDataEntries.remove(id);
                    removedPortDataEntries.put(id, event);
                }
                if (linkData != null) {
                    ByteBuffer id = linkData.getIDasByteBuffer();
                    addedLinkDataEntries.remove(id);
                    removedLinkDataEntries.put(id, event);
                }
                if (hostData != null) {
                    ByteBuffer id = hostData.getIDasByteBuffer();
                    addedHostDataEntries.remove(id);
                    removedHostDataEntries.put(id, event);
                }
                break;
            default:
                log.error("Unknown topology event {}", event.eventType());
            }
        }

        //
        // Prepare the result by adding the events in the appropriate order:
        //  - First REMOVE, then ADD
        //  - The REMOVE order is: Host, Link, Port, Switch, Mastership
        //  - The ADD order is the reverse: Mastership, Switch, Port, Link,
        //    Host
        //
        List<EventEntry<TopologyEvent>> result = new LinkedList<>();
        result.addAll(removedHostDataEntries.values());
        result.addAll(removedLinkDataEntries.values());
        result.addAll(removedPortDataEntries.values());
        result.addAll(removedSwitchDataEntries.values());
        result.addAll(removedMastershipDataEntries.values());
        //
        result.addAll(addedMastershipDataEntries.values());
        result.addAll(addedSwitchDataEntries.values());
        result.addAll(addedPortDataEntries.values());
        result.addAll(addedLinkDataEntries.values());
        result.addAll(addedHostDataEntries.values());

        return result;
    }
}
