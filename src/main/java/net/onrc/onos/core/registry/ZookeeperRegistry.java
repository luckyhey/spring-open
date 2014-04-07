package net.onrc.onos.core.registry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.restserver.IRestApiService;
import net.onrc.onos.core.registry.web.RegistryWebRoutable;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.atomic.AtomicValue;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.framework.recipes.leader.Participant;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.x.discovery.ServiceCache;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

/**
 * A registry service that uses Zookeeper. All data is stored in Zookeeper,
 * so this can be used as a global registry in a multi-node ONOS cluster.
 *
 * @author jono
 */
public class ZookeeperRegistry implements IFloodlightModule, IControllerRegistryService {

    protected final static Logger log = LoggerFactory.getLogger(ZookeeperRegistry.class);
    protected String controllerId = null;

    protected IRestApiService restApi;

    //This is the default, it's overwritten by the connectionString configuration parameter
    protected String connectionString = "localhost:2181";

    private final String namespace = "onos";
    private final String switchLatchesPath = "/switches";
    private final String CLUSTER_LEADER_PATH = "/cluster/leader";

    private final String SERVICES_PATH = "/"; //i.e. the root of our namespace
    private final String CONTROLLER_SERVICE_NAME = "controllers";

    protected CuratorFramework client;

    protected PathChildrenCache switchCache;

    protected ConcurrentHashMap<String, SwitchLeadershipData> switches;
    protected Map<String, PathChildrenCache> switchPathCaches;

    protected LeaderLatch clusterLeaderLatch;
    protected ClusterLeaderListener clusterLeaderListener;
    private static final long CLUSTER_LEADER_ELECTION_RETRY_MS = 100;

    private final String ID_COUNTER_PATH = "/flowidcounter";
    private final Long ID_BLOCK_SIZE = 0x100000000L;
    protected DistributedAtomicLong distributedIdCounter;

    //Zookeeper performance-related configuration
    protected static final int sessionTimeout = 5000;
    protected static final int connectionTimeout = 7000;

    //
    // Unique ID generation state
    // TODO: The implementation must be updated to use the Zookeeper
    // instead of a ramdon generator.
    //
    private static Random randomGenerator = new Random();
    private static int nextUniqueIdPrefix = 0;
    private static int nextUniqueIdSuffix = 0;

    private final BlockingQueue<SwitchLeaderEvent> switchLeadershipEvents =
            new LinkedBlockingQueue<SwitchLeaderEvent>();

    private ExecutorService eventThreadExecutorService;

    private static class SwitchLeaderEvent {
        public final long dpid;
        public final boolean isLeader;

        public SwitchLeaderEvent(long dpid, boolean isLeader) {
            this.dpid = dpid;
            this.isLeader = isLeader;
        }
    }

    /*
     * Dispatcher thread for leadership change events coming from Curator.
     */
    private void dispatchEvents() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SwitchLeaderEvent event = switchLeadershipEvents.take();
                SwitchLeadershipData swData = switches.get(HexString.toHexString(event.dpid));
                if (swData == null) {
                    log.debug("Leadership data {} not found", event.dpid);
                    continue;
                }

                swData.getCallback().controlChanged(event.dpid, event.isLeader);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Exception in registry event thread", e);
            }
        }
    }

    protected class SwitchLeaderListener implements LeaderLatchListener {
        String dpid;
        LeaderLatch latch;

        public SwitchLeaderListener(String dpid, LeaderLatch latch) {
            this.dpid = dpid;
            this.latch = latch;
        }

        @Override
        public void isLeader() {
            log.debug("Became leader for {}", dpid);

            switchLeadershipEvents.offer(new SwitchLeaderEvent(HexString.toLong(dpid), true));
        }

        @Override
        public void notLeader() {
            log.debug("Lost leadership for {}", dpid);

            switchLeadershipEvents.offer(new SwitchLeaderEvent(HexString.toLong(dpid), false));
        }
    }

    protected class SwitchPathCacheListener implements PathChildrenCacheListener {
        @Override
        public void childEvent(CuratorFramework client,
                               PathChildrenCacheEvent event) throws Exception {

            String strSwitch = null;
            if (event.getData() != null) {
                String[] splitted = event.getData().getPath().split("/");
                strSwitch = splitted[splitted.length - 1];
            }

            switch (event.getType()) {
                case CHILD_ADDED:
                case CHILD_UPDATED:
                    //Check we have a PathChildrenCache for this child, add one if not
                    synchronized (switchPathCaches) {
                        if (switchPathCaches.get(strSwitch) == null) {
                            PathChildrenCache pc = new PathChildrenCache(client,
                                    event.getData().getPath(), true);
                            pc.start(StartMode.NORMAL);
                            switchPathCaches.put(strSwitch, pc);
                        }
                    }
                    break;
                case CHILD_REMOVED:
                    //Remove our PathChildrenCache for this child
                    PathChildrenCache pc = null;
                    synchronized (switchPathCaches) {
                        pc = switchPathCaches.remove(strSwitch);
                    }
                    if (pc != null) {
                        pc.close();
                    }
                    break;
                default:
                    //All other switchLeadershipEvents are connection status switchLeadershipEvents. We don't need to
                    //do anything as the path cache handles these on its own.
                    break;
            }

        }
    }

    ;

    protected class ClusterLeaderListener implements LeaderLatchListener {
        LeaderLatch latch;

        public ClusterLeaderListener(LeaderLatch latch) {
            this.latch = latch;
        }

        //
        // NOTE: If we need to support callbacks when the
        // leadership changes, those should be called here.
        //

        @Override
        public void isLeader() {
            log.debug("Cluster leadership aquired");
        }

        @Override
        public void notLeader() {
            log.debug("Cluster leadership lost");
        }
    }

    /**
     * Listens for changes to the switch znodes in Zookeeper. This maintains
     * the second level of PathChildrenCaches that hold the controllers
     * contending for each switch - there's one for each switch.
     */
    PathChildrenCacheListener switchPathCacheListener = new SwitchPathCacheListener();
    protected ServiceDiscovery<ControllerService> serviceDiscovery;
    protected ServiceCache<ControllerService> serviceCache;


    @Override
    public void requestControl(long dpid, ControlChangeCallback cb) throws RegistryException {
        log.info("Requesting control for {}", HexString.toHexString(dpid));

        if (controllerId == null) {
            throw new RuntimeException("Must register a controller before calling requestControl");
        }

        String dpidStr = HexString.toHexString(dpid);
        String latchPath = switchLatchesPath + "/" + dpidStr;

        if (switches.get(dpidStr) != null) {
            log.debug("Already contesting {}, returning", HexString.toHexString(dpid));
            throw new RegistryException("Already contesting control for " + dpidStr);
        }

        LeaderLatch latch = new LeaderLatch(client, latchPath, controllerId);
        SwitchLeaderListener listener = new SwitchLeaderListener(dpidStr, latch);
        latch.addListener(listener);


        SwitchLeadershipData swData = new SwitchLeadershipData(latch, cb, listener);
        SwitchLeadershipData oldData = switches.putIfAbsent(dpidStr, swData);

        if (oldData != null) {
            //There was already data for that key in the map
            //i.e. someone else got here first so we can't succeed
            log.debug("Already requested control for {}", dpidStr);
            throw new RegistryException("Already requested control for " + dpidStr);
        }

        //Now that we know we were able to add our latch to the collection,
        //we can start the leader election in Zookeeper. However I don't know
        //how to handle if the start fails - the latch is already in our
        //switches list.
        //TODO seems like there's a Curator bug when latch.start is called when
        //there's no Zookeeper connection which causes two znodes to be put in
        //Zookeeper at the latch path when we reconnect to Zookeeper.
        try {
            latch.start();
        } catch (Exception e) {
            log.warn("Error starting leader latch: {}", e.getMessage());
            throw new RegistryException("Error starting leader latch for " + dpidStr, e);
        }

    }

    @Override
    public void releaseControl(long dpid) {
        log.info("Releasing control for {}", HexString.toHexString(dpid));

        String dpidStr = HexString.toHexString(dpid);

        SwitchLeadershipData swData = switches.remove(dpidStr);

        if (swData == null) {
            log.debug("Trying to release control of a switch we are not contesting");
            return;
        }

        LeaderLatch latch = swData.getLatch();

        latch.removeListener(swData.getListener());

        try {
            latch.close();
        } catch (IOException e) {
            //I think it's OK not to do anything here. Either the node got
            //deleted correctly, or the connection went down and the node got deleted.
            log.debug("releaseControl: caught IOException {}", dpidStr);
        }
    }

    @Override
    public boolean hasControl(long dpid) {
        String dpidStr = HexString.toHexString(dpid);

        SwitchLeadershipData swData = switches.get(dpidStr);

        if (swData == null) {
            log.warn("No leader latch for dpid {}", dpidStr);
            return false;
        }

        return swData.getLatch().hasLeadership();
    }

    @Override
    public boolean isClusterLeader() {
        return clusterLeaderLatch.hasLeadership();
    }

    @Override
    public String getControllerId() {
        return controllerId;
    }

    @Override
    public Collection<String> getAllControllers() throws RegistryException {
        log.debug("Getting all controllers");

        List<String> controllers = new ArrayList<String>();
        for (ServiceInstance<ControllerService> instance : serviceCache.getInstances()) {
            String id = instance.getPayload().getControllerId();
            if (!controllers.contains(id)) {
                controllers.add(id);
            }
        }

        return controllers;
    }

    @Override
    public void registerController(String id) throws RegistryException {
        if (controllerId != null) {
            throw new RegistryException(
                    "Controller already registered with id " + controllerId);
        }

        controllerId = id;

        try {
            ServiceInstance<ControllerService> thisInstance = ServiceInstance.<ControllerService>builder()
                    .name(CONTROLLER_SERVICE_NAME)
                    .payload(new ControllerService(controllerId))
                            //.port((int)(65535 * Math.random())) // in a real application, you'd use a common port
                            //.uriSpec(uriSpec)
                    .build();

            serviceDiscovery.registerService(thisInstance);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    @Override
    public String getControllerForSwitch(long dpid) throws RegistryException {
        String dpidStr = HexString.toHexString(dpid);

        PathChildrenCache switchCache = switchPathCaches.get(dpidStr);

        if (switchCache == null) {
            log.warn("Tried to get controller for non-existent switch");
            return null;
        }

        try {
            //We've seen issues with these caches get stuck out of date, so we'll have to
            //force them to refresh before each read. This slows down the method as it
            //blocks on a Zookeeper query, however at the moment only the cleanup thread
            //uses this and that isn't particularly time-sensitive.
            switchCache.rebuild();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        List<ChildData> sortedData = new ArrayList<ChildData>(switchCache.getCurrentData());

        Collections.sort(
                sortedData,
                new Comparator<ChildData>() {
                    private String getSequenceNumber(String path) {
                        return path.substring(path.lastIndexOf('-') + 1);
                    }

                    @Override
                    public int compare(ChildData lhs, ChildData rhs) {
                        return getSequenceNumber(lhs.getPath()).
                                compareTo(getSequenceNumber(rhs.getPath()));
                    }
                }
        );

        if (sortedData.size() == 0) {
            return null;
        }

        return new String(sortedData.get(0).getData(), Charsets.UTF_8);
    }

    @Override
    public Collection<Long> getSwitchesControlledByController(String controllerId) {
        //TODO remove this if not needed
        throw new RuntimeException("Not yet implemented");
    }


    //TODO what should happen when there's no ZK connection? Currently we just return
    //the cache but this may lead to false impressions - i.e. we don't actually know
    //what's in ZK so we shouldn't say we do
    @Override
    public Map<String, List<ControllerRegistryEntry>> getAllSwitches() {
        Map<String, List<ControllerRegistryEntry>> data =
                new HashMap<String, List<ControllerRegistryEntry>>();

        for (Map.Entry<String, PathChildrenCache> entry : switchPathCaches.entrySet()) {
            List<ControllerRegistryEntry> contendingControllers =
                    new ArrayList<ControllerRegistryEntry>();

            if (entry.getValue().getCurrentData().size() < 1) {
                //TODO prevent even having the PathChildrenCache in this case
                //log.info("Switch entry with no leader elections: {}", entry.getKey());
                continue;
            }

            for (ChildData d : entry.getValue().getCurrentData()) {

                String controllerId = new String(d.getData(), Charsets.UTF_8);

                String[] splitted = d.getPath().split("-");
                int sequenceNumber = Integer.parseInt(splitted[splitted.length - 1]);

                contendingControllers.add(new ControllerRegistryEntry(controllerId, sequenceNumber));
            }

            Collections.sort(contendingControllers);
            data.put(entry.getKey(), contendingControllers);
        }
        return data;
    }

    public IdBlock allocateUniqueIdBlock(long range) {
        try {
            AtomicValue<Long> result = null;
            do {
                result = distributedIdCounter.add(range);
            } while (result == null || !result.succeeded());

            return new IdBlock(result.preValue(), result.postValue() - 1, range);
        } catch (Exception e) {
            log.error("Error allocating ID block");
        }
        return null;
    }

    /**
     * Returns a block of IDs which are unique and unused.
     * Range of IDs is fixed size and is assigned incrementally as this method called.
     * Since the range of IDs is managed by Zookeeper in distributed way, this method may block when
     * requests come up simultaneously.
     */
    @Override
    public IdBlock allocateUniqueIdBlock() {
        return allocateUniqueIdBlock(ID_BLOCK_SIZE);
    }

    /**
     * Get a globally unique ID.
     *
     * @return a globally unique ID.
     */
    @Override
    public synchronized long getNextUniqueId() {
        //
        // Generate the next Unique ID.
        //
        // TODO: For now, the higher 32 bits are random, and
        // the lower 32 bits are sequential.
        // The implementation must be updated to use the Zookeeper
        // to allocate the higher 32 bits (globally unique).
        //
        if ((nextUniqueIdSuffix & 0xffffffffL) == 0xffffffffL) {
            nextUniqueIdPrefix = randomGenerator.nextInt();
            nextUniqueIdSuffix = 0;
        } else {
            nextUniqueIdSuffix++;
        }
        long result = (long) nextUniqueIdPrefix << 32;
        result = result | (0xffffffffL & nextUniqueIdSuffix);
        return result;
    }

    /*
     * IFloodlightModule
     */

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IControllerRegistryService.class);
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        Map<Class<? extends IFloodlightService>, IFloodlightService> m =
                new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
        m.put(IControllerRegistryService.class, this);
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(IRestApiService.class);
        return l;
    }

    //TODO currently blocks startup when it can't get a Zookeeper connection.
    //Do we support starting up with no Zookeeper connection?
    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        log.info("Initialising the Zookeeper Registry - Zookeeper connection required");

        //Read the Zookeeper connection string from the config
        Map<String, String> configParams = context.getConfigParams(this);
        String connectionString = configParams.get("connectionString");
        if (connectionString != null) {
            this.connectionString = connectionString;
        }
        log.info("Setting Zookeeper connection string to {}", this.connectionString);

        //
        // Initialize the Unique ID generator
        // TODO: This must be replaced by Zookeeper-based allocation
        //
        nextUniqueIdPrefix = randomGenerator.nextInt();

        restApi = context.getServiceImpl(IRestApiService.class);

        switches = new ConcurrentHashMap<String, SwitchLeadershipData>();
        //switchPathCaches = new HashMap<String, PathChildrenCache>();
        switchPathCaches = new ConcurrentHashMap<String, PathChildrenCache>();

        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        client = CuratorFrameworkFactory.newClient(this.connectionString,
                sessionTimeout, connectionTimeout, retryPolicy);

        client.start();
        client = client.usingNamespace(namespace);

        distributedIdCounter = new DistributedAtomicLong(
                client,
                ID_COUNTER_PATH,
                new RetryOneTime(100));

        switchCache = new PathChildrenCache(client, switchLatchesPath, true);
        switchCache.getListenable().addListener(switchPathCacheListener);

        //Build the service discovery object
        serviceDiscovery = ServiceDiscoveryBuilder.builder(ControllerService.class)
                .client(client).basePath(SERVICES_PATH).build();

        //We read the list of services very frequently (GUI periodically queries them)
        //so we'll cache them to cut down on Zookeeper queries.
        serviceCache = serviceDiscovery.serviceCacheBuilder()
                .name(CONTROLLER_SERVICE_NAME).build();


        try {
            serviceDiscovery.start();
            serviceCache.start();

            //Don't prime the cache, we want a notification for each child node in the path
            switchCache.start(StartMode.NORMAL);
        } catch (Exception e) {
            throw new FloodlightModuleException("Error initialising ZookeeperRegistry: "
                    + e.getMessage());
        }

        eventThreadExecutorService = Executors.newSingleThreadExecutor();
        eventThreadExecutorService.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        dispatchEvents();
                    }
                });
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        //
        // Cluster Leader election setup.
        // NOTE: We have to do it here, because during the init stage
        // we don't know the Controller ID.
        //
        if (controllerId == null) {
            log.error("Error on startup: unknown ControllerId");
        }
        clusterLeaderLatch = new LeaderLatch(client,
                CLUSTER_LEADER_PATH,
                controllerId);
        clusterLeaderListener = new ClusterLeaderListener(clusterLeaderLatch);
        clusterLeaderLatch.addListener(clusterLeaderListener);
        try {
            clusterLeaderLatch.start();
        } catch (Exception e) {
            log.error("Error on startup starting the cluster leader election: {}", e.getMessage());
        }

        // Keep trying until there is a cluster leader
        do {
            try {
                Participant leader = clusterLeaderLatch.getLeader();
                if (!leader.getId().isEmpty())
                    break;
                Thread.sleep(CLUSTER_LEADER_ELECTION_RETRY_MS);
            } catch (Exception e) {
                log.error("Error on startup waiting for cluster leader election: {}", e.getMessage());
            }
        } while (true);

        restApi.addRestletRoutable(new RegistryWebRoutable());
    }
}