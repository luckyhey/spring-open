function startFlowAnimation(flow) {
	if (flow.select('animate').empty()) {
		flow.append('svg:animate')
			.attr('attributeName', 'stroke-dashoffset')
			.attr('attributeType', 'xml')
			.attr('from', '500')
			.attr('to', '-500')
			.attr('dur', '20s')
			.attr('repeatCount', 'indefinite');
	}
}

function stopFlowAnimation(flow) {
	flow.select('animate').remove();
}


function updateSelectedFlowsTopology() {
	// DRAW THE FLOWS
	var topologyFlows = [];
	selectedFlows.forEach(function (flow) {
		if (flow) {
			topologyFlows.push(flow);
		}
	});

	var flows = flowLayer.selectAll('.flow').data(topologyFlows);

	flows.enter().append("svg:path").attr('class', 'flow')
		.attr('stroke-dasharray', '4, 10')

	flows.exit().remove();

	flows.attr('d', function (d) {
			if (!d) {
				return;
			}
			var pts = [];
			if (d.createPending) {
				// create a temporary vector to indicate the pending flow
				var s1 = d3.select(document.getElementById(d.srcDpid));
				var s2 = d3.select(document.getElementById(d.dstDpid));

				var pt1 = document.querySelector('svg').createSVGPoint();
				pt1.x = s1.attr('x');
				pt1.y = s1.attr('y');
				if (drawingRings) {
					pt1 = pt1.matrixTransform(s1[0][0].getCTM());
				}
				pts.push(pt1);

				var pt2 = document.querySelector('svg').createSVGPoint();
				pt2.x = s2.attr('x');
				pt2.y = s2.attr('y');
				if (drawingRings) {
					pt2 = pt2.matrixTransform(s2[0][0].getCTM());
				}
				pts.push(pt2);

			} else if (d.dataPath && d.dataPath.flowEntries) {
				d.dataPath.flowEntries.forEach(function (flowEntry) {
					var s = d3.select(document.getElementById(flowEntry.dpid.value));
					// s[0] is null if the flow entry refers to a non-existent switch
					if (s[0][0]) {
						var pt = document.querySelector('svg').createSVGPoint();
						pt.x = s.attr('x');
						pt.y = s.attr('y');
						if (drawingRings) {
							pt = pt.matrixTransform(s[0][0].getCTM());
						}
						pts.push(pt);
					} else {
						console.log('flow refers to non-existent switch: ' + flowEntry.dpid.value);
					}
				});
			}
			if (pts.length) {
				return line(pts);
			} else {
				return "M0,0";
			}
		})
		.attr('id', function (d) {
			if (d) {
				return makeFlowKey(d);
			}
		})
		.classed('pending', function (d) {
			return d && (d.createPending || d.deletePending);
		});
}

function updateSelectedFlowsTable() {
	function rowEnter(d) {
		var row = d3.select(this);
		row.append('div').classed('deleteFlow', true);
		row.append('div').classed('flowId', true);
		row.append('div').classed('srcDPID', true);
		row.append('div').classed('dstDPID', true);
		row.append('div').classed('iperf', true);

		row.select('.iperf')
			.append('div')
			.attr('class', 'iperf-container')
			.append('svg:svg')
			.attr('viewBox', '0 0 1000 32')
			.attr('preserveAspectRatio', 'none')
			.append('svg:g')
			.append('svg:path')
			.attr('class', 'iperfdata');

		row.on('mouseover', function (d) {
			if (d) {
				var path = document.getElementById(makeFlowKey(d));
				d3.select(path).classed('highlight', true);
			}
		});
		row.on('mouseout', function (d) {
			if (d) {
				var path = document.getElementById(makeFlowKey(d));
				d3.select(path).classed('highlight', false);
			}
		});
	}

	function rowUpdate(d) {
		var row = d3.select(this);
		row.attr('id', function (d) {
			if (d) {
				return makeSelectedFlowKey(d);
			}
		});

		if (!d || !hasIPerf(d)) {
			row.select('.iperfdata')
				.attr('d', 'M0,0');
		}

		row.select('.deleteFlow').on('click', function () {
			deselectFlow(d);
		});
		row.on('dblclick', function () {
			if (d) {
				var prompt = 'Delete flow ' + d.flowId + '?';
				doConfirm(prompt, function (result) {
					if (result) {
						deleteFlow(d);
						d.deletePending = true;
						updateSelectedFlows();

						setTimeout(function () {
							d.deletePending = false;
							updateSelectedFlows();
						}, pendingTimeout)
					};
				});
			}
		});

		row.select('.flowId')
			.text(function (d) {
				if (d) {
					if (d.flowId) {
						return d.flowId;
					} else {
						return '0x--';
					}
				}
			})
			.classed('pending', function (d) {
				return d && (d.createPending || d.deletePending);
			});

		row.select('.srcDPID')
			.text(function (d) {
				if (d) {
					return d.srcDpid;
				}
			});

		row.select('.dstDPID')
			.text(function (d) {
				if (d) {
					return d.dstDpid;
				}
			});
	}

	var flows = d3.select('#selectedFlows')
		.selectAll('.selectedFlow')
		.data(selectedFlows);

	flows.enter()
		.append('div')
		.classed('selectedFlow', true)
		.each(rowEnter);

	flows.each(rowUpdate);

	flows.exit().remove();
}

function startIPerfForFlow(flow) {
	var duration = 10000; // seconds
	var interval = 100; // ms. this is defined by the server
	var updateRate = 2000; // ms
	var pointsToDisplay = 1000;

	function makePoints() {
		var pts = [];
		var i;
		for (i=0; i < pointsToDisplay; ++i) {
			var sample = flow.iperfData.samples[i];
			var height = 28 * sample/1000000;
			if (height > 28)
				height = 28;
			pts.push({
				x: i * 1000/(pointsToDisplay-1),
				y: 30 - height
			})
		}
		return pts;
	}

	if (flow.flowId) {
		console.log('starting iperf for: ' + flow.flowId);
		startIPerf(flow, duration, updateRate/interval);
		flow.iperfDisplayInterval = setInterval(function () {
			if (flow.iperfData) {
				while (flow.iperfData.samples.length < pointsToDisplay) {
					flow.iperfData.samples.push(0);
				}
				var iperfPath = d3.select(document.getElementById(makeSelectedFlowKey(flow))).select('path');
				iperfPath.attr('d', line(makePoints()));
				flow.iperfData.samples.shift();
			}


		}, interval);

		var animationTimeout;

		flow.iperfFetchInterval = setInterval(function () {
			getIPerfData(flow, function (data) {
				try {
					if (!flow.iperfData) {
						flow.iperfData = {
							samples: []
						};
						var i;
						for (i = 0; i < pointsToDisplay; ++i) {
							flow.iperfData.samples.push(0);
						}
					}

					var iperfData = JSON.parse(data);

//				console.log(iperfData.timestamp);

					// if the data is fresh
					if (flow.iperfData.timestamp && iperfData.timestamp != flow.iperfData.timestamp) {

						var flowSelection = d3.select(document.getElementById(makeFlowKey(flow)));
						startFlowAnimation(flowSelection);
						clearTimeout(animationTimeout);
						// kill the animation if iperfdata stops flowing
						animationTimeout = setTimeout(function () {
							stopFlowAnimation(flowSelection);
						}, updateRate*1.5);

						while (flow.iperfData.samples.length > pointsToDisplay + iperfData.samples.length) {
							flow.iperfData.samples.shift();
						}

						iperfData.samples.forEach(function (s) {
							flow.iperfData.samples.push(s);
						});
					}
					flow.iperfData.timestamp = iperfData.timestamp;
				} catch (e) {
					console.log('bad iperf data: ' + data);
				}
//				console.log(data);
			});
		}, updateRate/2); // over sample to avoid gaps
	}
}

function updateSelectedFlows() {
	// make sure that all of the selected flows are either
	// 1) valid (meaning they are in the latest list of flows)
	// 2) pending
	if (model) {
		var flowMap = {};
		model.flows.forEach(function (flow) {
			flowMap[makeFlowKey(flow)] = flow;
		});

		var newSelectedFlows = [];
		selectedFlows.forEach(function (flow) {
			if (flow) {
				var liveFlow = flowMap[makeFlowKey(flow)];
				if (liveFlow) {
					newSelectedFlows.push(liveFlow);
					liveFlow.deletePending = flow.deletePending;
					liveFlow.iperfFetchInterval = flow.iperfFetchInterval;
					liveFlow.iperfDisplayInterval = flow.iperfDisplayInterval;
				} else if (flow.createPending) {
					newSelectedFlows.push(flow);
				} else if (hasIPerf(flow)) {
					clearIPerf(flow);
				}
			}
		});
		selectedFlows = newSelectedFlows;
	}
	selectedFlows.forEach(function (flow) {
		if (!hasIPerf(flow)) {
			startIPerfForFlow(flow);
		}
	});
	while (selectedFlows.length < 3) {
		selectedFlows.push(null);
	}

	updateSelectedFlowsTable();
	// on app init, the table is updated before the svg is constructed
	if (flowLayer) {
		updateSelectedFlowsTopology();
	}
}

function selectFlow(flow) {
	var flowKey = makeFlowKey(flow);
	var alreadySelected = false;
	selectedFlows.forEach(function (f) {
		if (f && makeFlowKey(f) === flowKey) {
			alreadySelected = true;
		}
	});

	if (!alreadySelected) {
		selectedFlows.unshift(flow);
		selectedFlows = selectedFlows.slice(0, 3);
		updateSelectedFlows();
	}
}

function hasIPerf(flow) {
	return flow && flow.iperfFetchInterval;
}

function clearIPerf(flow) {
	console.log('clearing iperf interval for: ' + flow.flowId);
	clearInterval(flow.iperfFetchInterval);
	delete flow.iperfFetchInterval;
	clearInterval(flow.iperfDisplayInterval);
	delete flow.iperfDisplayInterval;
	delete flow.iperfData;
}

function deselectFlow(flow, ifCreatePending) {
	if (!flow) {
		return;
	}

	var flowKey = makeFlowKey(flow);
	var newSelectedFlows = [];
	selectedFlows.forEach(function (flow) {
		if (!flow ||
				flowKey !== makeFlowKey(flow) ||
				flowKey === makeFlowKey(flow) && ifCreatePending && !flow.createPending ) {
			newSelectedFlows.push(flow);
		} else {
			if (hasIPerf(flow)) {
				clearIPerf(flow);
			}
		}
	});
	selectedFlows = newSelectedFlows;
	while (selectedFlows.length < 3) {
		selectedFlows.push(null);
	}

	updateSelectedFlows();
}

function deselectFlowIfCreatePending(flow) {
	deselectFlow(flow, true);
}

function showFlowChooser() {
	function rowEnter(d) {
		var row = d3.select(this);

		row.append('div')
			.classed('black-eye', true).
			on('click', function () {
				selectFlow(d);
			});

		row.append('div')
			.classed('flowId', true)
			.text(function (d) {
				return d.flowId;
			});

		row.append('div')
			.classed('srcDPID', true)
			.text(function (d) {
				return d.srcDpid;
			});


		row.append('div')
			.classed('dstDPID', true)
			.text(function (d) {
				return d.dstDpid;
			});

	}

	var flowChooser = d3.select(document.getElementById('flowChooser'));
	flowChooser.html('');
	flowChooser.style('-webkit-transform', 'translate3d(-100%, 0, 0)')
		.style('-webkit-transition');

	var flows = flowChooser
		.append('div')
		.style('pointer-events', 'auto')
		.selectAll('.selectedFlow')
		.data(model.flows)
		.enter()
		.append('div')
		.classed('selectedFlow', true)
		.each(rowEnter);


	setTimeout(function () {
		flowChooser.style('-webkit-transition', '-webkit-transform .25s');
		setTimeout(function () {
			flowChooser.style('-webkit-transform', 'translate3d(0,0,0)');
		}, 0);


		d3.select(document.body).on('click', function () {
			flowChooser.style('-webkit-transform', 'translate3d(-100%, 0, 0)')
			d3.select(document.body).on('click', null);
		});
	}, 0);
}