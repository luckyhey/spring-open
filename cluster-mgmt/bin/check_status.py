#! /usr/bin/env python
import json
import os

urls="http://localhost:8080/wm/core/topology/switches/all/json http://localhost:8080/wm/core/topology/links/json http://localhost:8080/wm/registry/controllers/json http://localhost:8080/wm/registry/switches/json"
RestIP=os.environ.get("ONOS_CLUSTER_BASENAME")+"1"
RestPort="8080"

core_switches=["00:00:00:00:ba:5e:ba:11", "00:00:00:00:00:00:ba:12", "00:00:20:4e:7f:51:8a:35", "00:00:00:00:ba:5e:ba:13", "00:00:00:08:a2:08:f9:01", "00:00:00:16:97:08:9a:46"]
correct_nr_switch=[6,50,25,25,25,25,25,25]
correct_intra_link=[16, 98, 48, 48, 48, 48, 48, 48]


#nr_links=(switch[1]+switch[2]+switch[3]+switch[4]+switch[5]+switch[6]+switch[7]+len(switch)-1+8)*2
nr_links= (49 + 24 * 6 + 7 + 8) * 2

def get_json(url):
  print url
  try:
    command = "curl -s %s" % (url)
    result = os.popen(command).read()
    parsedResult = json.loads(result)
  except:
    print "REST IF %s has issue" % command
    parsedResult = ""

  if type(parsedResult) == 'dict' and parsedResult.has_key('code'):
    print "REST %s returned code %s" % (command, parsedResult['code'])
    parsedResult = ""

  return parsedResult 

def check_switch():
  url="http://%s:%s/wm/core/topology/switches/all/json" % (RestIP, RestPort)
  parsedResult = get_json(url)

  if parsedResult == "":
    return

  print "switch: total %d switches" % len(parsedResult)
  cnt = []
  active = []
  for r in range(8):
    cnt.append(0)
    active.append(0)
  for s in parsedResult:
    if s['dpid'] in core_switches:
      nw_index = 0
    else:
      nw_index =int(s['dpid'].split(':')[-2], 16) - 1
    cnt[nw_index] += 1

    if s['state']  == "ACTIVE":
      active[nw_index] += 1

  for r in range(8):
    print "switch: network %d : %d switches %d active" % (r+1, cnt[r], active[r])
    if correct_nr_switch[r] != cnt[r]:
      print "switch fail: network %d should have %d switches but has %d" % (r+1, correct_nr_switch[r], cnt[r])

    if correct_nr_switch[r] != active[r]:
      print "switch fail: network %d should have %d active switches but has %d" % (r+1, correct_nr_switch[r], active[r])

def check_link():
  url = "http://%s:%s/wm/core/topology/links/json" % (RestIP, RestPort)
  parsedResult = get_json(url)

  if parsedResult == "":
    return

  print "link: total %d links (correct : %d)" % (len(parsedResult), nr_links)
  intra = []
  interlink=0
  for r in range(8):
    intra.append(0)

  for s in parsedResult:
    if s['src-switch'] in core_switches:
      src_nw = 1
    else:
      src_nw =int(s['src-switch'].split(':')[-2], 16)
    
    if s['dst-switch'] in core_switches:
      dst_nw = 1
    else:
      dst_nw =int(s['dst-switch'].split(':')[-2], 16)

    src_swid =int(s['src-switch'].split(':')[-1], 16)
    dst_swid =int(s['dst-switch'].split(':')[-1], 16)
    if src_nw == dst_nw:
      intra[src_nw - 1] = intra[src_nw - 1] + 1 
    else:
      interlink += 1

  for r in range(8):
    if intra[r] != correct_intra_link[r]:
      print "link fail: network %d should have %d intra links but has %d" % (r+1, correct_intra_link[r], intra[r])

  if interlink != 14:
      print "link fail: There should be %d intra links (uni-directional) but %d" % (14, interlink)

def check_mastership():
  url = "http://%s:%s/wm/registry/switches/json" % (RestIP, RestPort)
  parsedResult = get_json(url)

  if parsedResult == "":
    return

  for s in parsedResult:
    #print s,len(s),s[0]['controllerId']
    ctrl=parsedResult[s][0]['controllerId']
    if s in core_switches:
      nw = 1
    else:
      nw =int(s.split(':')[-2], 16)

    if len(parsedResult[s]) > 1:
      print "ownership fail: switch %s has more than 1 ownership" % (s)
    elif int(ctrl[-1]) != nw:
      print "ownership fail: switch %s is owened by %s" % (s, ctrl)

def check_controllers():
  url = "http://%s:%s/wm/registry/controllers/json" % (RestIP, RestPort)
  parsedResult = get_json(url)

  if parsedResult == "":
    return

  unique=list(set(parsedResult))
  if len(unique) != 8:
    print "controller fail: there are %d controllers" % (len(parsedResult))

if __name__ == "__main__":
  check_switch()
  check_link()
  check_mastership()
  check_controllers()