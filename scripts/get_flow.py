#! /usr/bin/env python
# -*- Mode: python; py-indent-offset: 4; tab-width: 8; indent-tabs-mode: t; -*-

import pprint
import os
import sys
import subprocess
import json
import argparse
import io
import time

from flask import Flask, json, Response, render_template, make_response, request

## Global Var ##
ControllerIP="127.0.0.1"
ControllerPort=8080

DEBUG=0
pp = pprint.PrettyPrinter(indent=4)

app = Flask(__name__)

## Worker Functions ##
def log_error(txt):
  print '%s' % (txt)

def debug(txt):
  if DEBUG:
    print '%s' % (txt)

# @app.route("/wm/onos/flows/get/<flow-id>/json")
# Sample output:
# {"flowId":{"value":"0x5"},"installerId":{"value":"FOOBAR"},"dataPath":{"srcPort":{"dpid":{"value":"00:00:00:00:00:00:00:01"},"port":{"value":0}},"dstPort":{"dpid":{"value":"00:00:00:00:00:00:00:02"},"port":{"value":0}},"flowEntries":[{"flowEntryId":"0x1389","flowEntryMatch":null,"flowEntryActions":null,"dpid":{"value":"00:00:00:00:00:00:00:01"},"inPort":{"value":0},"outPort":{"value":1},"flowEntryUserState":"FE_USER_DELETE","flowEntrySwitchState":"FE_SWITCH_NOT_UPDATED","flowEntryErrorState":null},{"flowEntryId":"0x138a","flowEntryMatch":null,"flowEntryActions":null,"dpid":{"value":"00:00:00:00:00:00:00:02"},"inPort":{"value":9},"outPort":{"value":0},"flowEntryUserState":"FE_USER_DELETE","flowEntrySwitchState":"FE_SWITCH_NOT_UPDATED","flowEntryErrorState":null}]}}

def parse_match(match):
  result = []

  inPort = match['inPort']
  matchInPort = match['matchInPort']
  srcMac = match['srcMac']
  matchSrcMac = match['matchSrcMac']
  dstMac = match['dstMac']
  matchDstMac = match['matchDstMac']
  ethernetFrameType = match['ethernetFrameType']
  matchEthernetFrameType = match['matchEthernetFrameType']
  vlanId = match['vlanId']
  matchVlanId = match['matchVlanId']
  vlanPriority = match['vlanPriority']
  matchVlanPriority = match['matchVlanPriority']
  srcIPv4Net = match['srcIPv4Net']
  matchSrcIPv4Net = match['matchSrcIPv4Net']
  dstIPv4Net = match['dstIPv4Net']
  matchDstIPv4Net = match['matchDstIPv4Net']
  ipProto = match['ipProto']
  matchIpProto = match['matchIpProto']
  ipToS = match['ipToS']
  matchIpToS = match['matchIpToS']
  srcTcpUdpPort = match['srcTcpUdpPort']
  matchSrcTcpUdpPort = match['matchSrcTcpUdpPort']
  dstTcpUdpPort = match['dstTcpUdpPort']
  matchDstTcpUdpPort = match['matchDstTcpUdpPort']
  if matchInPort == True:
    r = "inPort: %s" % inPort['value']
    result.append(r)
  if matchSrcMac == True:
    r = "srcMac: %s" % srcMac['value']
    result.append(r)
  if matchDstMac == True:
    r = "dstMac: %s" % dstMac['value']
    result.append(r)
  if matchEthernetFrameType == True:
    r = "ethernetFrameType: %s" % hex(ethernetFrameType)
    result.append(r)
  if matchVlanId == True:
    r = "vlanId: %s" % vlanId
    result.append(r)
  if matchVlanPriority == True:
    r = "vlanPriority: %s" % vlanPriority
    result.append(r)
  if matchSrcIPv4Net == True:
    r = "srcIPv4Net: %s" % srcIPv4Net['value']
    result.append(r)
  if matchDstIPv4Net == True:
    r = "dstIPv4Net: %s" % dstIPv4Net['value']
    result.append(r)
  if matchIpProto == True:
    r = "ipProto: %s" % ipProto
    result.append(r)
  if matchIpToS == True:
    r = "ipToS: %s" % ipToS
    result.append(r)
  if matchSrcTcpUdpPort == True:
    r = "srcTcpUdpPort: %s" % srcTcpUdpPort
    result.append(r)
  if matchDstTcpUdpPort == True:
    r = "dstTcpUdpPort: %s" % dstTcpUdpPort
    result.append(r)

  return result


def parse_actions(actions):
  result = []
  for a in actions:
    actionType = a['actionType']
    if actionType == "ACTION_OUTPUT":
      port = a['actionOutput']['port']['value']
      maxLen = a['actionOutput']['maxLen']
      r = "actionType: %s port: %s maxLen: %s" % (actionType, port, maxLen)
      result.append(r)
    if actionType == "ACTION_SET_VLAN_VID":
      vlanId = a['actionSetVlanId']['vlanId']
      r = "actionType: %s vlanId: %s" % (actionType, vlanId)
      result.append(r)
    if actionType == "ACTION_SET_VLAN_PCP":
      vlanPriority = a['actionSetVlanPriority']['vlanPriority']
      r = "actionType: %s vlanPriority: %s" % (actionType, vlanPriority)
      result.append(r)
    if actionType == "ACTION_STRIP_VLAN":
      stripVlan = a['actionStripVlan']['stripVlan']
      r = "actionType: %s stripVlan: %s" % (actionType, stripVlan)
      result.append(r)
    if actionType == "ACTION_SET_DL_SRC":
      setEthernetSrcAddr = a['actionSetEthernetSrcAddr']['addr']['value']
      r = "actionType: %s setEthernetSrcAddr: %s" % (actionType, setEthernetSrcAddr)
      result.append(r)
    if actionType == "ACTION_SET_DL_DST":
      setEthernetDstAddr = a['actionSetEthernetDstAddr']['addr']['value']
      r = "actionType: %s setEthernetDstAddr: %s" % (actionType, setEthernetDstAddr)
      result.append(r)
    if actionType == "ACTION_SET_NW_SRC":
      setIPv4SrcAddr = a['actionSetIPv4SrcAddr']['addr']['value']
      r = "actionType: %s setIPv4SrcAddr: %s" % (actionType, setIPv4SrcAddr)
      result.append(r)
    if actionType == "ACTION_SET_NW_DST":
      setIPv4DstAddr = a['actionSetIPv4DstAddr']['addr']['value']
      r = "actionType: %s setIPv4DstAddr: %s" % (actionType, setIPv4DstAddr)
      result.append(r)
    if actionType == "ACTION_SET_NW_TOS":
      setIpToS = a['actionSetIpToS']['ipToS']
      r = "actionType: %s setIpToS: %s" % (actionType, setIpToS)
      result.append(r)
    if actionType == "ACTION_SET_TP_SRC":
      setTcpUdpSrcPort = a['actionSetTcpUdpSrcPort']['port']
      r = "actionType: %s setTcpUdpSrcPort: %s" % (actionType, setTcpUdpSrcPort)
      result.append(r)
    if actionType == "ACTION_SET_TP_DST":
      setTcpUdpDstPort = a['actionSetTcpUdpDstPort']['port']
      r = "actionType: %s setTcpUdpDstPort: %s" % (actionType, setTcpUdpDstPort)
      result.append(r)
    if actionType == "ACTION_ENQUEUE":
      port = a['actionEnqueue']['port']['value']
      queueId = a['actionEnqueue']['queueId']
      r = "actionType: %s port: %s queueId: %s" % (actionType, port, queueId)
      result.append(r)

  return result


def print_flow_path(parsedResult):
  flowId = parsedResult['flowId']['value']
  installerId = parsedResult['installerId']['value']
  flowPathType = parsedResult['flowPathType']
  flowPathUserState = parsedResult['flowPathUserState']
  flowPathFlags = parsedResult['flowPathFlags']['flags']
  idleTimeout = parsedResult['idleTimeout']
  hardTimeout = parsedResult['hardTimeout']
  priority = parsedResult['priority']
  srcSwitch = parsedResult['dataPath']['srcPort']['dpid']['value']
  srcPort = parsedResult['dataPath']['srcPort']['port']['value']
  dstSwitch = parsedResult['dataPath']['dstPort']['dpid']['value']
  dstPort = parsedResult['dataPath']['dstPort']['port']['value']
  match = parsedResult['flowEntryMatch'];
  actions = parsedResult['flowEntryActions']['actions']

  flowPathFlagsStr = ""
  if (flowPathFlags & 0x1):
    if flowPathFlagsStr:
      flowPathFlagsStr += ","
    flowPathFlagsStr += "DISCARD_FIRST_HOP_ENTRY"
  if (flowPathFlags & 0x2):
    if flowPathFlagsStr:
      flowPathFlagsStr += ","
    flowPathFlagsStr += "KEEP_ONLY_FIRST_HOP_ENTRY"

  print "FlowPath: (flowId = %s installerId = %s flowPathType = %s flowPathUserState = %s flowPathFlags = 0x%x(%s) src = %s/%s dst = %s/%s idleTimeout = %s hardTimeout = %s priority = %s)" % (flowId, installerId, flowPathType, flowPathUserState, flowPathFlags, flowPathFlagsStr, srcSwitch, srcPort, dstSwitch, dstPort, idleTimeout, hardTimeout, priority)

  #
  # Print the common match conditions
  #
  if match == None:
    print "   Match: %s" % (match)
  else:
    parsedMatch = parse_match(match)
    for l in parsedMatch:
      print "    %s" % l

  #
  # Print the actions
  #
  parsedActions = parse_actions(actions)
  for l in parsedActions:
    print "    %s" % l

  #
  # Print each Flow Entry
  #
  for f in parsedResult['dataPath']['flowEntries']:
    flowEntryId = f['flowEntryId']
    idleTimeout = f['idleTimeout']
    hardTimeout = f['hardTimeout']
    priority = f['priority']
    dpid = f['dpid']['value']
    userState = f['flowEntryUserState']
    switchState = f['flowEntrySwitchState']
    match = f['flowEntryMatch'];
    actions = f['flowEntryActions']['actions']

    print "  FlowEntry: (%s, %s, %s, %s, idleTimeout = %s, hardTimeout = %s, priority = %s)" % (flowEntryId, dpid, userState, switchState, idleTimeout, hardTimeout, priority)

    #
    # Print the match conditions
    #
    if match == None:
      print "   Match: %s" % (match)
    else:
      parsedMatch = parse_match(match)
      for l in parsedMatch:
	print "    %s" % l
    #
    # Print the actions
    #
    parsedActions = parse_actions(actions)
    for l in parsedActions:
      print "    %s" % l


def get_flow_path(flow_id):
  try:
    command = "curl -s \"http://%s:%s/wm/onos/flows/get/%s/json\"" % (ControllerIP, ControllerPort, flow_id)
    debug("get_flow_path %s" % command)

    result = os.popen(command).read()
    debug("result %s" % result)
    if len(result) == 0:
      print "No Flow found"
      return;

    parsedResult = json.loads(result)
    debug("parsed %s" % parsedResult)
  except:
    log_error("Controller IF has issue")
    exit(1)

  print_flow_path(parsedResult)


def get_all_flow_paths():
  try:
    command = "curl -s \"http://%s:%s/wm/onos/flows/getall/json\"" % (ControllerIP, ControllerPort)
    debug("get_all_flow_paths %s" % command)

    result = os.popen(command).read()
    debug("result %s" % result)
    if len(result) == 0:
	print "No Flows found"
	return;

    parsedResult = json.loads(result)
    debug("parsed %s" % parsedResult)
  except:
    log_error("Controller IF has issue")
    exit(1)

  for flowPath in parsedResult:
    print_flow_path(flowPath)

if __name__ == "__main__":
  usage_msg1 = "Usage:\n"
  usage_msg2 = "%s <flow_id> : Print flow with Flow ID of <flow_id>\n" % (sys.argv[0])
  usage_msg3 = "                   all    : Print all flows\n"
  usage_msg = usage_msg1 + usage_msg2 + usage_msg3;

  # app.debug = False;

  # Usage info
  if len(sys.argv) > 1 and (sys.argv[1] == "-h" or sys.argv[1] == "--help"):
    print(usage_msg)
    exit(0)

  # Check arguments
  if len(sys.argv) < 2:
    log_error(usage_msg)
    exit(1)

  # Do the work
  if sys.argv[1] == "all":
    get_all_flow_paths()
  else:
    get_flow_path(sys.argv[1])