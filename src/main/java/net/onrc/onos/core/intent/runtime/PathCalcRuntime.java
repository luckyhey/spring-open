package net.onrc.onos.core.intent.runtime;

import java.util.HashMap;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.onrc.onos.core.intent.ConstrainedBFSTree;
import net.onrc.onos.core.intent.ConstrainedShortestPathIntent;
import net.onrc.onos.core.intent.ErrorIntent;
import net.onrc.onos.core.intent.ErrorIntent.ErrorType;
import net.onrc.onos.core.intent.Intent;
import net.onrc.onos.core.intent.Intent.IntentState;
import net.onrc.onos.core.intent.IntentMap;
import net.onrc.onos.core.intent.IntentOperation;
import net.onrc.onos.core.intent.IntentOperation.Operator;
import net.onrc.onos.core.intent.IntentOperationList;
import net.onrc.onos.core.intent.Path;
import net.onrc.onos.core.intent.PathIntent;
import net.onrc.onos.core.intent.PathIntentMap;
import net.onrc.onos.core.intent.ShortestPathIntent;
import net.onrc.onos.core.topology.Switch;
import net.onrc.onos.core.topology.MutableTopology;
import net.onrc.onos.core.util.Dpid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime used by PathCalcRuntimeModule class.
 * <p>
 * It calculates shortest-path and constrained-shortest-path.
 */
public class PathCalcRuntime implements IFloodlightService {
    private MutableTopology mutableTopology;
    private static final Logger log = LoggerFactory.getLogger(PathCalcRuntime.class);

    /**
     * Constructor.
     *
     * @param mutableTopology a topology object to use for the path calculation.
     */
    public PathCalcRuntime(MutableTopology mutableTopology) {
        this.mutableTopology = mutableTopology;
    }

    /**
     * Calculates shortest-path and constrained-shortest-path intents into low-level path intents.
     *
     * @param intentOpList IntentOperationList having instances of ShortestPathIntent/ConstrainedShortestPathIntent
     * @param pathIntents  a set of current low-level intents
     * @return IntentOperationList. PathIntent and/or ErrorIntent instances.
     */
    public IntentOperationList calcPathIntents(final IntentOperationList intentOpList,
            final IntentMap appIntents, final PathIntentMap pathIntents) {
        IntentOperationList pathIntentOpList = new IntentOperationList();
        HashMap<Switch, ConstrainedBFSTree> spfTrees = new HashMap<>();

        // TODO optimize locking of Topology
        mutableTopology.acquireReadLock();

        for (IntentOperation intentOp : intentOpList) {
            switch (intentOp.operator) {
                case ADD:
                    if (!(intentOp.intent instanceof ShortestPathIntent)) {
                        log.error("Unsupported intent type: {}", intentOp.intent.getClass().getName());
                        pathIntentOpList.add(Operator.ERROR, new ErrorIntent(
                                ErrorType.UNSUPPORTED_INTENT,
                                "Unsupported intent type.",
                                intentOp.intent));
                        continue;
                    }

                    ShortestPathIntent spIntent = (ShortestPathIntent) intentOp.intent;
                    Switch srcSwitch = mutableTopology.getSwitch(new Dpid(spIntent.getSrcSwitchDpid()));
                    Switch dstSwitch = mutableTopology.getSwitch(new Dpid(spIntent.getDstSwitchDpid()));
                    if (srcSwitch == null || dstSwitch == null) {
                        log.debug("Switch not found. src:{}, dst:{}",
                                spIntent.getSrcSwitchDpid(),
                                spIntent.getDstSwitchDpid());
                        pathIntentOpList.add(Operator.ERROR, new ErrorIntent(
                                ErrorType.SWITCH_NOT_FOUND,
                                "Switch not found.",
                                spIntent));
                        continue;
                    }

                    double bandwidth = 0.0;
                    ConstrainedBFSTree tree = null;
                    if (spIntent instanceof ConstrainedShortestPathIntent) {
                        bandwidth = ((ConstrainedShortestPathIntent) intentOp.intent).getBandwidth();
                        tree = new ConstrainedBFSTree(srcSwitch, pathIntents, bandwidth);
                    } else {
                        tree = spfTrees.get(srcSwitch);
                        if (tree == null) {
                            tree = new ConstrainedBFSTree(srcSwitch);
                            spfTrees.put(srcSwitch, tree);
                        }
                    }
                    Path path = tree.getPath(dstSwitch);
                    if (path == null) {
                        log.debug("Path not found. Intent: {}", spIntent.toString());
                        pathIntentOpList.add(Operator.ERROR, new ErrorIntent(
                                ErrorType.PATH_NOT_FOUND,
                                "Path not found.",
                                spIntent));
                        continue;
                    }

                    // generate new path-intent ID
                    String oldPathIntentId = spIntent.getPathIntentId();
                    String newPathIntentId;
                    if (oldPathIntentId == null) {
                        newPathIntentId = PathIntent.createFirstId(spIntent.getId());
                    } else {
                        newPathIntentId = PathIntent.createNextId(oldPathIntentId);
                    }

                    // create new path-intent
                    PathIntent newPathIntent = new PathIntent(newPathIntentId, path, bandwidth, spIntent);
                    newPathIntent.setState(IntentState.INST_REQ);

                    // create and add operation(s)
                    if (oldPathIntentId == null) {
                        // operation for new path-intent
                        spIntent.setPathIntentId(newPathIntent);
                        pathIntentOpList.add(Operator.ADD, newPathIntent);
                        log.debug("new intent:{}", newPathIntent);
                    } else {
                        PathIntent oldPathIntent = (PathIntent) pathIntents.getIntent(oldPathIntentId);
                        if (newPathIntent.hasSameFields(oldPathIntent)) {
                            // skip the same operation (reroute)
                            spIntent.setState(IntentState.INST_ACK);
                            log.debug("skip intent:{}", newPathIntent);
                        } else {
                            // update existing path-intent (reroute)
                            spIntent.setPathIntentId(newPathIntent);
                            pathIntentOpList.add(Operator.REMOVE, oldPathIntent);
                            pathIntentOpList.add(Operator.ADD, newPathIntent);
                            log.debug("update intent:{} -> {}", oldPathIntent, newPathIntent);
                        }
                    }

                    break;
                case REMOVE:
                    ShortestPathIntent targetAppIntent =
                            (ShortestPathIntent) appIntents.getIntent(
                                    intentOp.intent.getId());
                    if (targetAppIntent != null) {
                        String pathIntentId = targetAppIntent.getPathIntentId();
                        if (pathIntentId != null) {
                            Intent targetPathIntent = pathIntents.getIntent(pathIntentId);
                            if (targetPathIntent != null) {
                                pathIntentOpList.add(Operator.REMOVE, targetPathIntent);
                            }
                        }
                    }
                    break;
                case ERROR:
                    // just ignore
                    break;
                default:
                    log.error("Unknown intent operator {}", intentOp.operator);
                    break;
            }
        }
        // TODO optimize locking of Topology
        mutableTopology.releaseReadLock();

        return pathIntentOpList;
    }
}
