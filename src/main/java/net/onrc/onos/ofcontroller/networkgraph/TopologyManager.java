package net.onrc.onos.ofcontroller.networkgraph;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import net.onrc.onos.datagrid.IDatagridService;
import net.onrc.onos.datagrid.IEventChannel;
import net.onrc.onos.datagrid.IEventChannelListener;
import net.onrc.onos.datastore.topology.RCLink;
import net.onrc.onos.datastore.topology.RCPort;
import net.onrc.onos.datastore.topology.RCSwitch;
import net.onrc.onos.ofcontroller.networkgraph.PortEvent.SwitchPort;
import net.onrc.onos.ofcontroller.util.EventEntry;
import net.onrc.onos.ofcontroller.util.Dpid;
import net.onrc.onos.registry.controller.IControllerRegistryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "NB" read-only Network Map.
 *
 * - Maintain Invariant/Relationships between Topology Objects.
 *
 * TODO To be synchronized based on TopologyEvent Notification.
 *
 * TODO TBD: Caller is expected to maintain parent/child calling order. Parent
 * Object must exist before adding sub component(Add Switch -> Port).
 *
 * TODO TBD: This class may delay the requested change to handle event
 * re-ordering. e.g.) Link Add came in, but Switch was not there.
 *
 */
public class TopologyManager implements NetworkGraphDiscoveryInterface {

    private static final Logger log = LoggerFactory
	    .getLogger(TopologyManager.class);

    private IEventChannel<byte[], TopologyEvent> eventChannel;
    private static final String EVENT_CHANNEL_NAME = "onos.topology";
    private EventHandler eventHandler = new EventHandler();

    private final NetworkGraphDatastore datastore;
    private final NetworkGraphImpl networkGraph = new NetworkGraphImpl();
    private final IControllerRegistryService registryService;
    private CopyOnWriteArrayList<INetworkGraphListener> networkGraphListeners;

    //
    // Local state for keeping track of reordered events.
    // NOTE: Switch Events are not affected by the event reordering.
    //
    private Map<ByteBuffer, PortEvent> reorderedAddedPortEvents =
	new HashMap<ByteBuffer, PortEvent>();
    private Map<ByteBuffer, LinkEvent> reorderedAddedLinkEvents =
	new HashMap<ByteBuffer, LinkEvent>();
    private Map<ByteBuffer, DeviceEvent> reorderedAddedDeviceEvents =
	new HashMap<ByteBuffer, DeviceEvent>();

    //
    // Local state for keeping track of the application event notifications
    //
    List<SwitchEvent> apiAddedSwitchEvents = new LinkedList<SwitchEvent>();
    List<SwitchEvent> apiRemovedSwitchEvents = new LinkedList<SwitchEvent>();
    List<PortEvent> apiAddedPortEvents = new LinkedList<PortEvent>();
    List<PortEvent> apiRemovedPortEvents = new LinkedList<PortEvent>();
    List<LinkEvent> apiAddedLinkEvents = new LinkedList<LinkEvent>();
    List<LinkEvent> apiRemovedLinkEvents = new LinkedList<LinkEvent>();
    List<DeviceEvent> apiAddedDeviceEvents = new LinkedList<DeviceEvent>();
    List<DeviceEvent> apiRemovedDeviceEvents = new LinkedList<DeviceEvent>();

    /**
     * Constructor.
     *
     * @param registryService the Registry Service to use.
     * @param networkGraphListeners the collection of Network Graph Listeners
     * to use.
     */
    public TopologyManager(IControllerRegistryService registryService,
			   CopyOnWriteArrayList<INetworkGraphListener> networkGraphListeners) {
	datastore = new NetworkGraphDatastore();
	this.registryService = registryService;
	this.networkGraphListeners = networkGraphListeners;
    }

    /**
     * Get the Network Graph.
     *
     * @return the Network Graph.
     */
    NetworkGraph getNetworkGraph() {
	return networkGraph;
    }

    /**
     * Event handler class.
     */
    private class EventHandler extends Thread implements
	IEventChannelListener<byte[], TopologyEvent> {
	private BlockingQueue<EventEntry<TopologyEvent>> topologyEvents =
	    new LinkedBlockingQueue<EventEntry<TopologyEvent>>();

	/**
	 * Startup processing.
	 */
	private void startup() {
	    //
	    // TODO: Read all state from the database:
	    //
	    // Collection<EventEntry<TopologyEvent>> collection =
	    //    readWholeTopologyFromDB();
	    //
	    // For now, as a shortcut we read it from the datagrid
	    //
	    Collection<TopologyEvent> topologyEvents =
		eventChannel.getAllEntries();
	    Collection<EventEntry<TopologyEvent>> collection =
		new LinkedList<EventEntry<TopologyEvent>>();

	    for (TopologyEvent topologyEvent : topologyEvents) {
		EventEntry<TopologyEvent> eventEntry =
		    new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_ADD,
						  topologyEvent);
		collection.add(eventEntry);
	    }
	    processEvents(collection);
	}

	/**
	 * Run the thread.
	 */
	@Override
	public void run() {
	    Collection<EventEntry<TopologyEvent>> collection =
		new LinkedList<EventEntry<TopologyEvent>>();

	    this.setName("TopologyManager.EventHandler " + this.getId());
	    startup();

	    //
	    // The main loop
	    //
	    try {
		while (true) {
		    EventEntry<TopologyEvent> eventEntry = topologyEvents.take();
		    collection.add(eventEntry);
		    topologyEvents.drainTo(collection);

		    processEvents(collection);
		    collection.clear();
		}
	    } catch (Exception exception) {
		log.debug("Exception processing Topology Events: ", exception);
	    }
	}

	/**
	 * Process all topology events.
	 *
	 * @param events the events to process.
	 */
	private void processEvents(Collection<EventEntry<TopologyEvent>> events) {
	    // Local state for computing the final set of events
	    Map<ByteBuffer, SwitchEvent> addedSwitchEvents =
		new HashMap<ByteBuffer, SwitchEvent>();
	    Map<ByteBuffer, SwitchEvent> removedSwitchEvents =
		new HashMap<ByteBuffer, SwitchEvent>();
	    Map<ByteBuffer, PortEvent> addedPortEvents =
		new HashMap<ByteBuffer, PortEvent>();
	    Map<ByteBuffer, PortEvent> removedPortEvents =
		new HashMap<ByteBuffer, PortEvent>();
	    Map<ByteBuffer, LinkEvent> addedLinkEvents =
		new HashMap<ByteBuffer, LinkEvent>();
	    Map<ByteBuffer, LinkEvent> removedLinkEvents =
		new HashMap<ByteBuffer, LinkEvent>();
	    Map<ByteBuffer, DeviceEvent> addedDeviceEvents =
		new HashMap<ByteBuffer, DeviceEvent>();
	    Map<ByteBuffer, DeviceEvent> removedDeviceEvents =
		new HashMap<ByteBuffer, DeviceEvent>();

	    //
	    // Classify and suppress matching events
	    //
	    for (EventEntry<TopologyEvent> event : events) {
		TopologyEvent topologyEvent = event.eventData();
		SwitchEvent switchEvent = topologyEvent.switchEvent;
		PortEvent portEvent = topologyEvent.portEvent;
		LinkEvent linkEvent = topologyEvent.linkEvent;
		DeviceEvent deviceEvent = topologyEvent.deviceEvent;

		//
		// Extract the events
		//
		switch (event.eventType()) {
		case ENTRY_ADD:
		    log.debug("Topology event ENTRY_ADD: {}", topologyEvent);
		    if (switchEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(switchEvent.getID());
			addedSwitchEvents.put(id, switchEvent);
			removedSwitchEvents.remove(id);
			// Switch Events are not affected by event reordering
		    }
		    if (portEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(portEvent.getID());
			addedPortEvents.put(id, portEvent);
			removedPortEvents.remove(id);
			reorderedAddedPortEvents.remove(id);
		    }
		    if (linkEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(linkEvent.getID());
			addedLinkEvents.put(id, linkEvent);
			removedLinkEvents.remove(id);
			reorderedAddedLinkEvents.remove(id);
		    }
		    if (deviceEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(deviceEvent.getID());
			addedDeviceEvents.put(id, deviceEvent);
			removedDeviceEvents.remove(id);
			reorderedAddedDeviceEvents.remove(id);
		    }
		    break;
		case ENTRY_REMOVE:
		    log.debug("Topology event ENTRY_REMOVE: {}", topologyEvent);
		    if (switchEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(switchEvent.getID());
			addedSwitchEvents.remove(id);
			removedSwitchEvents.put(id, switchEvent);
			// Switch Events are not affected by event reordering
		    }
		    if (portEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(portEvent.getID());
			addedPortEvents.remove(id);
			removedPortEvents.put(id, portEvent);
			reorderedAddedPortEvents.remove(id);
		    }
		    if (linkEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(linkEvent.getID());
			addedLinkEvents.remove(id);
			removedLinkEvents.put(id, linkEvent);
			reorderedAddedLinkEvents.remove(id);
		    }
		    if (deviceEvent != null) {
			ByteBuffer id = ByteBuffer.wrap(deviceEvent.getID());
			addedDeviceEvents.remove(id);
			removedDeviceEvents.put(id, deviceEvent);
			reorderedAddedDeviceEvents.remove(id);
		    }
		    break;
		}
	    }

	    //
	    // Apply the classified events.
	    //
	    // Apply the "add" events in the proper order:
	    //   switch, port, link, device
	    //
	    for (SwitchEvent switchEvent : addedSwitchEvents.values())
		addSwitch(switchEvent);
	    for (PortEvent portEvent : addedPortEvents.values())
		addPort(portEvent);
	    for (LinkEvent linkEvent : addedLinkEvents.values())
		addLink(linkEvent);
	    for (DeviceEvent deviceEvent : addedDeviceEvents.values())
		addDevice(deviceEvent);
	    //
	    // Apply the "remove" events in the reverse order:
	    //   device, link, port, switch
	    //
	    for (DeviceEvent deviceEvent : removedDeviceEvents.values())
		removeDevice(deviceEvent);
	    for (LinkEvent linkEvent : removedLinkEvents.values())
		removeLink(linkEvent);
	    for (PortEvent portEvent : removedPortEvents.values())
		removePort(portEvent);
	    for (SwitchEvent switchEvent : removedSwitchEvents.values())
		removeSwitch(switchEvent);

	    //
	    // Apply reordered events
	    //
	    applyReorderedEvents(! addedSwitchEvents.isEmpty(),
				 ! addedPortEvents.isEmpty());

	    //
	    // Dispatch the Topology Notification Events to the applications
	    //
	    dispatchNetworkGraphEvents();
	}

	/**
	 * Receive a notification that an entry is added.
	 *
	 * @param value the value for the entry.
	 */
	@Override
	public void entryAdded(TopologyEvent value) {
	    EventEntry<TopologyEvent> eventEntry =
		new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_ADD,
					      value);
	    topologyEvents.add(eventEntry);
	}

	/**
	 * Receive a notification that an entry is removed.
	 *
	 * @param value the value for the entry.
	 */
	@Override
	public void entryRemoved(TopologyEvent value) {
	    EventEntry<TopologyEvent> eventEntry =
		new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_REMOVE,
					      value);
	    topologyEvents.add(eventEntry);
	}

	/**
	 * Receive a notification that an entry is updated.
	 *
	 * @param value the value for the entry.
	 */
	@Override
	public void entryUpdated(TopologyEvent value) {
	    // NOTE: The ADD and UPDATE events are processed in same way
	    entryAdded(value);
	}
    }

    /**
     * Startup processing.
     *
     * @param datagridService the datagrid service to use.
     */
    void startup(IDatagridService datagridService) {
	eventChannel = datagridService.addListener(EVENT_CHANNEL_NAME,
						   eventHandler,
						   byte[].class,
						   TopologyEvent.class);
	eventHandler.start();
    }

    /**
     * Dispatch Network Graph Events to the listeners.
     */
    private void dispatchNetworkGraphEvents() {
	if (apiAddedSwitchEvents.isEmpty() &&
	    apiRemovedSwitchEvents.isEmpty() &&
	    apiAddedPortEvents.isEmpty() &&
	    apiRemovedPortEvents.isEmpty() &&
	    apiAddedLinkEvents.isEmpty() &&
	    apiRemovedLinkEvents.isEmpty() &&
	    apiAddedDeviceEvents.isEmpty() &&
	    apiRemovedDeviceEvents.isEmpty()) {
	    return;		// No events to dispatch
	}

	// Deliver the events
	for (INetworkGraphListener listener : this.networkGraphListeners) {
	    // TODO: Should copy before handing them over to listener?
	    listener.networkGraphEvents(apiAddedSwitchEvents,
					apiRemovedSwitchEvents,
					apiAddedPortEvents,
					apiRemovedPortEvents,
					apiAddedLinkEvents,
					apiRemovedLinkEvents,
					apiAddedDeviceEvents,
					apiRemovedDeviceEvents);
	}

	//
	// Cleanup
	//
	apiAddedSwitchEvents.clear();
	apiRemovedSwitchEvents.clear();
	apiAddedPortEvents.clear();
	apiRemovedPortEvents.clear();
	apiAddedLinkEvents.clear();
	apiRemovedLinkEvents.clear();
	apiAddedDeviceEvents.clear();
	apiRemovedDeviceEvents.clear();
    }

    /**
     * Apply reordered events.
     *
     * @param hasAddedSwitchEvents true if there were Added Switch Events.
     * @param hasAddedPortEvents true if there were Added Port Events.
     */
    private void applyReorderedEvents(boolean hasAddedSwitchEvents,
				      boolean hasAddedPortEvents) {
	if (! (hasAddedSwitchEvents || hasAddedPortEvents))
	    return;		// Nothing to do

	//
	// Try to apply the reordered events.
	//
	// NOTE: For simplicity we try to apply all events of a particular
	// type if any "parent" type event was processed:
	//  - Apply reordered Port Events if Switches were added
	//  - Apply reordered Link and Device Events if Switches or Ports
	//    were added
	//
	Map<ByteBuffer, PortEvent> portEvents = reorderedAddedPortEvents;
	Map<ByteBuffer, LinkEvent> linkEvents = reorderedAddedLinkEvents;
	Map<ByteBuffer, DeviceEvent> deviceEvents = reorderedAddedDeviceEvents;
	reorderedAddedPortEvents = new HashMap<>();
	reorderedAddedLinkEvents = new HashMap<>();
	reorderedAddedDeviceEvents = new HashMap<>();
	//
	// Apply reordered Port Events if Switches were added
	//
	if (hasAddedSwitchEvents) {
	    for (PortEvent portEvent : portEvents.values())
		addPort(portEvent);
	}
	//
	// Apply reordered Link and Device Events if Switches or Ports
	// were added.
	//
	for (LinkEvent linkEvent : linkEvents.values())
	    addLink(linkEvent);
	for (DeviceEvent deviceEvent : deviceEvents.values())
	    addDevice(deviceEvent);
    }

    /* ******************************
     * NetworkGraphDiscoveryInterface methods
     * ******************************/

    @Override
    public void putSwitchDiscoveryEvent(SwitchEvent switchEvent,
					Collection<PortEvent> portEvents) {
	if (datastore.addSwitch(switchEvent, portEvents)) {
	    // Send out notification
	    TopologyEvent topologyEvent = new TopologyEvent(switchEvent);
	    eventChannel.addEntry(topologyEvent.getID(), topologyEvent);

	    for (PortEvent portEvent : portEvents) {
		topologyEvent = new TopologyEvent(portEvent);
		eventChannel.addEntry(topologyEvent.getID(), topologyEvent);
	    }
	}
    }

    @Override
    public void removeSwitchDiscoveryEvent(SwitchEvent switchEvent) {
	// TODO: Use a copy of the port events previously added for that switch
	Collection<PortEvent> portEvents = new LinkedList<PortEvent>();

	if (datastore.deactivateSwitch(switchEvent, portEvents)) {
	    // Send out notification
	    eventChannel.removeEntry(switchEvent.getID());

	    for (PortEvent portEvent : portEvents) {
		eventChannel.removeEntry(portEvent.getID());
	    }
	}
    }

    @Override
    public void putPortDiscoveryEvent(PortEvent portEvent) {
	if (datastore.addPort(portEvent)) {
	    // Send out notification
	    TopologyEvent topologyEvent = new TopologyEvent(portEvent);
	    eventChannel.addEntry(topologyEvent.getID(), topologyEvent);
	}
    }

    @Override
    public void removePortDiscoveryEvent(PortEvent portEvent) {
	if (datastore.deactivatePort(portEvent)) {
	    // Send out notification
	    eventChannel.removeEntry(portEvent.getID());
	}
    }

    @Override
    public void putLinkDiscoveryEvent(LinkEvent linkEvent) {
	if (datastore.addLink(linkEvent)) {
	    // Send out notification
	    TopologyEvent topologyEvent = new TopologyEvent(linkEvent);
	    eventChannel.addEntry(topologyEvent.getID(), topologyEvent);
	}
    }

    @Override
    public void removeLinkDiscoveryEvent(LinkEvent linkEvent) {
	if (datastore.removeLink(linkEvent)) {
	    // Send out notification
	    eventChannel.removeEntry(linkEvent.getID());
	}
    }

    @Override
    public void putDeviceDiscoveryEvent(DeviceEvent deviceEvent) {
	if (datastore.addDevice(deviceEvent)) {
	    // Send out notification
	    TopologyEvent topologyEvent = new TopologyEvent(deviceEvent);
	    eventChannel.addEntry(topologyEvent.getID(), topologyEvent);
	}
    }

    @Override
    public void removeDeviceDiscoveryEvent(DeviceEvent deviceEvent) {
	if (datastore.removeDevice(deviceEvent)) {
	    // Send out notification
	    eventChannel.removeEntry(deviceEvent.getID());
	}
    }

    /* ************************************************
     * Internal methods to maintain the network graph
     * ************************************************/
    private void addSwitch(SwitchEvent switchEvent) {
	Switch sw = networkGraph.getSwitch(switchEvent.getDpid());
	if (sw == null) {
	    sw = new SwitchImpl(networkGraph, switchEvent.getDpid());
	    networkGraph.putSwitch(sw);
	} else {
	    // TODO: Update the switch attributes
	    // TODO: Nothing to do for now
	}
	apiAddedSwitchEvents.add(switchEvent);
    }

    private void removeSwitch(SwitchEvent switchEvent) {
	Switch sw = networkGraph.getSwitch(switchEvent.getDpid());
	if (sw == null) {
	    log.warn("Switch {} already removed, ignoring", switchEvent);
	    return;
	}

	//
	// Remove all Ports on the Switch
	//
	ArrayList<PortEvent> portsToRemove = new ArrayList<>();
	for (Port port : sw.getPorts()) {
	    log.warn("Port {} on Switch {} should be removed prior to removing Switch. Removing Port now.",
		     port, switchEvent);
	    PortEvent portEvent = new PortEvent(port.getDpid(),
						port.getNumber());
	    portsToRemove.add(portEvent);
	}
	for (PortEvent portEvent : portsToRemove)
	    removePort(portEvent);

	networkGraph.removeSwitch(switchEvent.getDpid());
	apiRemovedSwitchEvents.add(switchEvent);
    }

    private void addPort(PortEvent portEvent) {
	Switch sw = networkGraph.getSwitch(portEvent.getDpid());
	if (sw == null) {
	    // Reordered event: delay the event in local cache
	    ByteBuffer id = ByteBuffer.wrap(portEvent.getID());
	    reorderedAddedPortEvents.put(id, portEvent);
	    return;
	}
	SwitchImpl switchImpl = getSwitchImpl(sw);

	Port port = sw.getPort(portEvent.getNumber());
	if (port == null) {
	    port = new PortImpl(networkGraph, sw, portEvent.getNumber());
	    switchImpl.addPort(port);
	} else {
	    // TODO: Update the port attributes
	}
	apiAddedPortEvents.add(portEvent);
    }

    private void removePort(PortEvent portEvent) {
	Switch sw = networkGraph.getSwitch(portEvent.getDpid());
	if (sw == null) {
	    log.warn("Parent Switch for Port {} already removed, ignoring",
		     portEvent);
	    return;
	}

	Port port = sw.getPort(portEvent.getNumber());
	if (port == null) {
	    log.warn("Port {} already removed, ignoring", portEvent);
	    return;
	}

	//
	// Remove all Devices attached to the Port
	//
	ArrayList<DeviceEvent> devicesToRemove = new ArrayList<>();
	for (Device device : port.getDevices()) {
	    log.debug("Removing Device {} on Port {}", device, portEvent);
	    DeviceEvent deviceEvent = new DeviceEvent(device.getMacAddress());
	    SwitchPort switchPort = new SwitchPort(port.getSwitch().getDpid(),
						   port.getNumber());
	    deviceEvent.addAttachmentPoint(switchPort);
	    devicesToRemove.add(deviceEvent);
	}
	for (DeviceEvent deviceEvent : devicesToRemove)
	    removeDevice(deviceEvent);

	//
	// Remove all Links connected to the Port
	//
	Set<Link> links = new HashSet<>();
	links.add(port.getOutgoingLink());
	links.add(port.getIncomingLink());
	ArrayList<LinkEvent> linksToRemove = new ArrayList<>();
	for (Link link : links) {
	    if (link == null)
		continue;
	    log.debug("Removing Link {} on Port {}", link, portEvent);
	    LinkEvent linkEvent = new LinkEvent(link.getSrcSwitch().getDpid(),
						link.getSrcPort().getNumber(),
						link.getDstSwitch().getDpid(),
						link.getDstPort().getNumber());
	    linksToRemove.add(linkEvent);
	}
	for (LinkEvent linkEvent : linksToRemove)
	    removeLink(linkEvent);

	// Remove the Port from the Switch
	SwitchImpl switchImpl = getSwitchImpl(sw);
	switchImpl.removePort(port);

	apiRemovedPortEvents.add(portEvent);
    }

    private void addLink(LinkEvent linkEvent) {
	Port srcPort = networkGraph.getPort(linkEvent.getSrc().dpid,
					    linkEvent.getSrc().number);
	Port dstPort = networkGraph.getPort(linkEvent.getDst().dpid,
					    linkEvent.getDst().number);
	if ((srcPort == null) || (dstPort == null)) {
	    // Reordered event: delay the event in local cache
	    ByteBuffer id = ByteBuffer.wrap(linkEvent.getID());
	    reorderedAddedLinkEvents.put(id, linkEvent);
	    return;
	}

	// Get the Link instance from the Destination Port Incoming Link
	Link link = dstPort.getIncomingLink();
	assert(link == srcPort.getOutgoingLink());
	if (link == null) {
	    link = new LinkImpl(networkGraph, srcPort, dstPort);
	    PortImpl srcPortImpl = getPortImpl(srcPort);
	    PortImpl dstPortImpl = getPortImpl(dstPort);
	    srcPortImpl.setOutgoingLink(link);
	    dstPortImpl.setIncomingLink(link);

	    // Remove all Devices attached to the Ports
	    ArrayList<DeviceEvent> devicesToRemove = new ArrayList<>();
	    ArrayList<Port> ports = new ArrayList<>();
	    ports.add(srcPort);
	    ports.add(dstPort);
	    for (Port port : ports) {
		for (Device device : port.getDevices()) {
		    log.error("Device {} on Port {} should have been removed prior to adding Link {}",
			      device, port, linkEvent);
		    DeviceEvent deviceEvent =
			new DeviceEvent(device.getMacAddress());
		    SwitchPort switchPort =
			new SwitchPort(port.getSwitch().getDpid(),
				       port.getNumber());
		    deviceEvent.addAttachmentPoint(switchPort);
		    devicesToRemove.add(deviceEvent);
		}
	    }
	    for (DeviceEvent deviceEvent : devicesToRemove)
		removeDevice(deviceEvent);
	} else {
	    // TODO: Update the link attributes
	}

	apiAddedLinkEvents.add(linkEvent);
    }

    private void removeLink(LinkEvent linkEvent) {
	Port srcPort = networkGraph.getPort(linkEvent.getSrc().dpid,
					    linkEvent.getSrc().number);
	if (srcPort == null) {
	    log.warn("Src Port for Link {} already removed, ignoring",
		     linkEvent);
	    return;
	}

	Port dstPort = networkGraph.getPort(linkEvent.getDst().dpid,
					    linkEvent.getDst().number);
	if (dstPort == null) {
	    log.warn("Dst Port for Link {} already removed, ignoring",
		     linkEvent);
	    return;
	}

	//
	// Remove the Link instance from the Destination Port Incoming Link
	// and the Source Port Outgoing Link.
	//
	Link link = dstPort.getIncomingLink();
	if (link == null) {
	    log.warn("Link {} already removed on destination Port", linkEvent);
	}
	link = srcPort.getOutgoingLink();
	if (link == null) {
	    log.warn("Link {} already removed on src Port", linkEvent);
	}
	getPortImpl(dstPort).setIncomingLink(null);
	getPortImpl(srcPort).setOutgoingLink(null);

	apiRemovedLinkEvents.add(linkEvent);
    }

    // TODO: Device-related work is incomplete
    private void addDevice(DeviceEvent deviceEvent) {
	Device device = networkGraph.getDeviceByMac(deviceEvent.getMac());
	if (device == null) {
	    device = new DeviceImpl(networkGraph, deviceEvent.getMac());
	}
	DeviceImpl deviceImpl = getDeviceImpl(device);

	// Update the IP addresses
	for (InetAddress ipAddr : deviceEvent.getIpAddresses())
	    deviceImpl.addIpAddress(ipAddr);

	// Process each attachment point
	boolean attachmentFound = false;
	for (SwitchPort swp : deviceEvent.getAttachmentPoints()) {
	    // Attached Ports must exist
	    Port port = networkGraph.getPort(swp.dpid, swp.number);
	    if (port == null) {
		// Reordered event: delay the event in local cache
		ByteBuffer id = ByteBuffer.wrap(deviceEvent.getID());
		reorderedAddedDeviceEvents.put(id, deviceEvent);
		continue;
	    }
	    // Attached Ports must not have Link
	    if (port.getOutgoingLink() != null ||
		port.getIncomingLink() != null) {
		log.warn("Link (Out:{},In:{}) exist on the attachment point, skipping mutation.",
			 port.getOutgoingLink(),
			 port.getIncomingLink());
		continue;
	    }

	    // Add Device <-> Port attachment
	    PortImpl portImpl = getPortImpl(port);
	    portImpl.addDevice(device);
	    deviceImpl.addAttachmentPoint(port);
	    attachmentFound = true;
	}

	// Update the device in the Network Graph
	if (attachmentFound) {
	    networkGraph.putDevice(device);
	    apiAddedDeviceEvents.add(deviceEvent);
	}
    }

    private void removeDevice(DeviceEvent deviceEvent) {
	Device device = networkGraph.getDeviceByMac(deviceEvent.getMac());
	if (device == null) {
	    log.warn("Device {} already removed, ignoring", deviceEvent);
	    return;
	}
	DeviceImpl deviceImpl = getDeviceImpl(device);

	// Process each attachment point
	for (SwitchPort swp : deviceEvent.getAttachmentPoints()) {
	    // Attached Ports must exist
	    Port port = networkGraph.getPort(swp.dpid, swp.number);
	    if (port == null) {
		log.warn("Port for the attachment point {} did not exist. skipping attachment point mutation", swp);
		continue;
	    }

	    // Remove Device <-> Port attachment
	    PortImpl portImpl = getPortImpl(port);
	    portImpl.removeDevice(device);
	    deviceImpl.removeAttachmentPoint(port);
	}

	networkGraph.removeDevice(device);
	apiRemovedDeviceEvents.add(deviceEvent);
    }

    /**
     *
     * @param switchEvent
     * @return true if ready to accept event.
     */
    private boolean prepareForAddSwitchEvent(SwitchEvent switchEvent) {
	// No show stopping precondition
	return true;
    }

    private boolean prepareForRemoveSwitchEvent(SwitchEvent switchEvent) {
	// No show stopping precondition
	return true;
    }

    private boolean prepareForAddPortEvent(PortEvent portEvent) {
	// Parent Switch must exist
	if (networkGraph.getSwitch(portEvent.getDpid()) == null) {
	    log.warn("Dropping add port event because switch doesn't exist: {}",
		     portEvent);
	    return false;
	}
	// Prep: None
	return true;
    }

    private boolean prepareForRemovePortEvent(PortEvent portEvent) {
	Port port = networkGraph.getPort(portEvent.getDpid(),
					 portEvent.getNumber());
	if (port == null) {
	    log.debug("Port already removed? {}", portEvent);
	    // let it pass
	    return true;
	}

	// Prep: Remove Link and Device Attachment
	ArrayList<DeviceEvent> deviceEvents = new ArrayList<>();
	for (Device device : port.getDevices()) {
	    log.debug("Removing Device {} on Port {}", device, portEvent);
	    DeviceEvent devEvent = new DeviceEvent(device.getMacAddress());
	    devEvent.addAttachmentPoint(new SwitchPort(port.getSwitch().getDpid(),
						     port.getNumber()));
	    deviceEvents.add(devEvent);
	}
	for (DeviceEvent devEvent : deviceEvents) {
	    // calling Discovery API to wipe from DB, etc.
	    removeDeviceDiscoveryEvent(devEvent);
	}

	Set<Link> links = new HashSet<>();
	links.add(port.getOutgoingLink());
	links.add(port.getIncomingLink());
	for (Link link : links) {
	    if (link == null) {
		continue;
	    }
	    log.debug("Removing Link {} on Port {}", link, portEvent);
	    LinkEvent linkEvent =
		new LinkEvent(link.getSrcSwitch().getDpid(),
			      link.getSrcPort().getNumber(),
			      link.getDstSwitch().getDpid(),
			      link.getDstPort().getNumber());
	    // calling Discovery API to wipe from DB, etc.

	    // Call internal remove Link, which will check
	    // ownership of DST dpid and modify DB only if it is the owner
	    removeLinkDiscoveryEvent(linkEvent, true);
	}
	return true;
    }

    private boolean prepareForAddLinkEvent(LinkEvent linkEvent) {
	// Src/Dst Port must exist
	Port srcPort = networkGraph.getPort(linkEvent.getSrc().dpid,
					    linkEvent.getSrc().number);
	Port dstPort = networkGraph.getPort(linkEvent.getDst().dpid,
					    linkEvent.getDst().number);
	if (srcPort == null || dstPort == null) {
	    log.warn("Dropping add link event because port doesn't exist: {}",
		     linkEvent);
	    return false;
	}

	// Prep: remove Device attachment on both Ports
	ArrayList<DeviceEvent> deviceEvents = new ArrayList<>();
	for (Device device : srcPort.getDevices()) {
	    DeviceEvent devEvent = new DeviceEvent(device.getMacAddress());
	    devEvent.addAttachmentPoint(new SwitchPort(srcPort.getSwitch().getDpid(), srcPort.getNumber()));
	    deviceEvents.add(devEvent);
	}
	for (Device device : dstPort.getDevices()) {
	    DeviceEvent devEvent = new DeviceEvent(device.getMacAddress());
	    devEvent.addAttachmentPoint(new SwitchPort(dstPort.getSwitch().getDpid(),
						     dstPort.getNumber()));
	    deviceEvents.add(devEvent);
	}
	for (DeviceEvent devEvent : deviceEvents) {
	    // calling Discovery API to wipe from DB, etc.
	    removeDeviceDiscoveryEvent(devEvent);
	}

	return true;
    }

    private boolean prepareForRemoveLinkEvent(LinkEvent linkEvent) {
	// Src/Dst Port must exist
	Port srcPort = networkGraph.getPort(linkEvent.getSrc().dpid,
					    linkEvent.getSrc().number);
	Port dstPort = networkGraph.getPort(linkEvent.getDst().dpid,
					    linkEvent.getDst().number);
	if (srcPort == null || dstPort == null) {
	    log.warn("Dropping remove link event because port doesn't exist {}", linkEvent);
	    return false;
	}

	Link link = srcPort.getOutgoingLink();

	// Link is already gone, or different Link exist in memory
	// XXX Check if we should reject or just accept these cases.
	// it should be harmless to remove the Link on event from DB anyways
	if (link == null ||
	    !link.getDstPort().getNumber().equals(linkEvent.getDst().number)
	    || !link.getDstSwitch().getDpid().equals(linkEvent.getDst().dpid)) {
	    log.warn("Dropping remove link event because link doesn't exist: {}", linkEvent);
	    return false;
	}
	// Prep: None
	return true;
    }

    /**
     *
     * @param deviceEvent Event will be modified to remove inapplicable attachemntPoints/ipAddress
     * @return false if this event should be dropped.
     */
    private boolean prepareForAddDeviceEvent(DeviceEvent deviceEvent) {
	boolean preconditionBroken = false;
	ArrayList<PortEvent.SwitchPort> failedSwitchPort = new ArrayList<>();
	for ( PortEvent.SwitchPort swp : deviceEvent.getAttachmentPoints() ) {
	    // Attached Ports must exist
	    Port port = networkGraph.getPort(swp.dpid, swp.number);
	    if (port == null) {
		preconditionBroken = true;
		failedSwitchPort.add(swp);
		continue;
	    }
	    // Attached Ports must not have Link
	    if (port.getOutgoingLink() != null ||
		port.getIncomingLink() != null) {
		preconditionBroken = true;
		failedSwitchPort.add(swp);
		continue;
	    }
	}

	// Rewriting event to exclude failed attachmentPoint
	// XXX Assumption behind this is that inapplicable device event should
	// be dropped, not deferred. If we decide to defer Device event,
	// rewriting can become a problem
	List<SwitchPort>  attachmentPoints = deviceEvent.getAttachmentPoints();
	attachmentPoints.removeAll(failedSwitchPort);
	deviceEvent.setAttachmentPoints(attachmentPoints);

	if (deviceEvent.getAttachmentPoints().isEmpty() &&
	    deviceEvent.getIpAddresses().isEmpty()) {
	    // return false to represent: Nothing left to do for this event.
	    // Caller should drop event
	    return false;
	}

	// Should we return false to tell caller that the event was trimmed?
	// if ( preconditionBroken ) {
	//     return false;
	// }

	return true;
    }

    private boolean prepareForRemoveDeviceEvent(DeviceEvent deviceEvent) {
	// No show stopping precondition?
	// Prep: none
	return true;
    }

    private SwitchImpl getSwitchImpl(Switch sw) {
	if (sw instanceof SwitchImpl) {
	    return (SwitchImpl) sw;
	}
	throw new ClassCastException("SwitchImpl expected, but found: " + sw);
    }

    private PortImpl getPortImpl(Port p) {
	if (p instanceof PortImpl) {
	    return (PortImpl) p;
	}
	throw new ClassCastException("PortImpl expected, but found: " + p);
    }

    private LinkImpl getLinkImpl(Link l) {
	if (l instanceof LinkImpl) {
	    return (LinkImpl) l;
	}
	throw new ClassCastException("LinkImpl expected, but found: " + l);
    }

    private DeviceImpl getDeviceImpl(Device d) {
	if (d instanceof DeviceImpl) {
	    return (DeviceImpl) d;
	}
	throw new ClassCastException("DeviceImpl expected, but found: " + d);
    }

    @Deprecated
    private Collection<EventEntry<TopologyEvent>> readWholeTopologyFromDB() {
	Collection<EventEntry<TopologyEvent>> collection =
	    new LinkedList<EventEntry<TopologyEvent>>();

	// XXX May need to clear whole topology first, depending on
	// how we initially subscribe to replication events

	// Add all active switches
	for (RCSwitch sw : RCSwitch.getAllSwitches()) {
	    if (sw.getStatus() != RCSwitch.STATUS.ACTIVE) {
		continue;
	    }

	    SwitchEvent switchEvent = new SwitchEvent(sw.getDpid());
	    TopologyEvent topologyEvent = new TopologyEvent(switchEvent);
	    EventEntry<TopologyEvent> eventEntry =
		new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_ADD,
					      topologyEvent);
	    collection.add(eventEntry);
	}

	// Add all active ports
	for (RCPort p : RCPort.getAllPorts()) {
	    if (p.getStatus() != RCPort.STATUS.ACTIVE) {
		continue;
	    }

	    PortEvent portEvent = new PortEvent(p.getDpid(), p.getNumber());
	    TopologyEvent topologyEvent = new TopologyEvent(portEvent);
	    EventEntry<TopologyEvent> eventEntry =
		new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_ADD,
					      topologyEvent);
	    collection.add(eventEntry);
	}

	// TODO Is Device going to be in DB? If so, read from DB.
	//	for (RCDevice d : RCDevice.getAllDevices()) {
	//	    DeviceEvent devEvent = new DeviceEvent( MACAddress.valueOf(d.getMac()) );
	//	    for (byte[] portId : d.getAllPortIds() ) {
	//		devEvent.addAttachmentPoint( new SwitchPort( RCPort.getDpidFromKey(portId), RCPort.getNumberFromKey(portId) ));
	//	    }
	//	}

	for (RCLink l : RCLink.getAllLinks()) {
	    // check if src/dst switch/port exist before triggering event
	    Port srcPort = networkGraph.getPort(l.getSrc().dpid,
						l.getSrc().number);
	    Port dstPort = networkGraph.getPort(l.getDst().dpid,
						l.getDst().number);
	    if (srcPort == null || dstPort == null) {
		continue;
	    }

	    LinkEvent linkEvent = new LinkEvent(l.getSrc().dpid,
						l.getSrc().number,
						l.getDst().dpid,
						l.getDst().number);
	    TopologyEvent topologyEvent = new TopologyEvent(linkEvent);
	    EventEntry<TopologyEvent> eventEntry =
		new EventEntry<TopologyEvent>(EventEntry.Type.ENTRY_ADD,
					      topologyEvent);
	    collection.add(eventEntry);
	}

	return collection;
    }

    @Deprecated
    private void removeLinkDiscoveryEvent(LinkEvent linkEvent,
					  boolean dstCheckBeforeDBmodify) {
	if (prepareForRemoveLinkEvent(linkEvent)) {
	    if (dstCheckBeforeDBmodify) {
		// write to DB only if it is owner of the dst dpid
	    // XXX this will cause link remove events to be dropped
		// if the dst switch just disconnected
		if (registryService.hasControl(linkEvent.getDst().dpid)) {
		    datastore.removeLink(linkEvent);
		}
	    } else {
		datastore.removeLink(linkEvent);
	    }
	    removeLink(linkEvent);
	    // Send out notification
	    eventChannel.removeEntry(linkEvent.getID());
	}
	// TODO handle invariant violation
    }
}
