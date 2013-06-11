package net.floodlightcontroller.flowcache.web;

import net.floodlightcontroller.flowcache.IFlowService;
import net.floodlightcontroller.util.FlowId;

import org.openflow.util.HexString;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MeasurementGetPerFlowInstallTimeFlowResource extends ServerResource {
    protected static Logger log = LoggerFactory.getLogger(MeasurementGetPerFlowInstallTimeFlowResource.class);

    @Get("json")
    public String retrieve() {
	String result = null;

        IFlowService flowService =
                (IFlowService)getContext().getAttributes().
                get(IFlowService.class.getCanonicalName());

        if (flowService == null) {
	    log.debug("ONOS Flow Service not found");
	    return result;
	}

	// Extract the arguments

	// Process the request
	result = flowService.measurementGetPerFlowInstallTime();

	log.debug("Measurement Get Install Paths Time (nsec): " + result);

	return result;
    }
}