/**
 *    Copyright 2011, Big Switch Networks, Inc.
 *    Originally created by David Erickson, Stanford University
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.core.web;

import java.util.HashMap;

import org.projectfloodlight.openflow.protocol.OFStatsType;
import org.projectfloodlight.openflow.util.HexString;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Return switch statistics information for specific switches
 * 
 * @author readams
 */
public class SwitchStatisticsResource extends SwitchResourceBase {
    protected final static Logger log =
            LoggerFactory.getLogger(SwitchStatisticsResource.class);

    @Get("json")
    public HashMap<String, Object> retrieve() {
        HashMap<String, Object> result = new HashMap<String, Object>();
        Object values = null;

        String switchId = (String) getRequestAttributes().get("switchId");
        String statType = (String) getRequestAttributes().get("statType");

        if (statType.equals("port")) {
            values = getSwitchPortStatistics(HexString.toLong(switchId));
        } else if (statType.equals("queue")) {
            values = getSwitchStatistics(switchId, OFStatsType.QUEUE);
        } else if (statType.equals("flow")) {
            values = getSwitchStatistics(switchId, OFStatsType.FLOW);
        } else if (statType.equals("aggregate")) {
            values = getSwitchStatistics(switchId, OFStatsType.AGGREGATE);
        } else if (statType.equals("desc")) {
            values = getSwitchStatistics(switchId, OFStatsType.DESC);
        } else if (statType.equals("table")) {
            values = getSwitchStatistics(switchId, OFStatsType.TABLE);
        } else if (statType.equals("features")) {
            values = getSwitchFeaturesReply(switchId);
        }

        result.put(switchId, values);
        return result;
        // return toRepresentation(result, null);
    }
}
