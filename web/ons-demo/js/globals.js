/***************************************************************************************************
global variables
***************************************************************************************************/


/***************************************************************************************************
the latest update to the model
***************************************************************************************************/
var model;

/***************************************************************************************************
cached JSON representation of the model. used to detect model changes and update the UI.
***************************************************************************************************/
var modelString;

/***************************************************************************************************
the svg element for the topology view
***************************************************************************************************/
var svg;

/***************************************************************************************************
links that were created in the webui but which have not appeared in the links API response yet
these timeout after pendingTimeout
***************************************************************************************************/
var pendingLinks = {};

/***************************************************************************************************
current links including pending
***************************************************************************************************/
var links;

/***************************************************************************************************
a map from srcDPID => map of dstDPID=>link
***************************************************************************************************/
var linkMap;

/***************************************************************************************************
the flows that are displayed in the selected flow table
this may include pending flows which have not appeared in the flows API response yet
***************************************************************************************************/
var selectedFlows = [];

/***************************************************************************************************
a mapping from controller name to color used for color coding the topology and ONOS nodes views
***************************************************************************************************/
var controllerColorMap = {};