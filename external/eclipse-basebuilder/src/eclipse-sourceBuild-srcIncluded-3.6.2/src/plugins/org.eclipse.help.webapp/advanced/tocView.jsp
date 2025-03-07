<%--
 Copyright (c) 2000, 2010 IBM Corporation and others.
 All rights reserved. This program and the accompanying materials 
 are made available under the terms of the Eclipse Public License v1.0
 which accompanies this distribution, and is available at
 http://www.eclipse.org/legal/epl-v10.html
 
 Contributors:
     IBM Corporation - initial API and implementation
--%>
<%@ include file="../advanced/header.jsp"%>

<% 
	RequestData requestData = new RequestData(application,request, response);
	WebappPreferences prefs = requestData.getPrefs();
%>

<html lang="<%=ServletResources.getString("locale", request)%>">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">

<title><%=ServletResources.getString("Content", request)%></title>

<style type="text/css">
<%@ include file="tocTree.css"%>
</style>  
    
<base target="ContentViewFrame">
<script language="JavaScript" src="helptree.js"></script>
<script language="JavaScript" src="helptreechildren.js"></script>
<script language="JavaScript" src="xmlajax.js"></script>
<script language="JavaScript" src="utils.js"></script>
<script language="JavaScript" src="tocTree.js"></script>

<script language="JavaScript">

// Preload images
var imagesDirectory = "<%=prefs.getImagesDirectory()%>";
minus = new Image();
minus.src = imagesDirectory + "/minus.gif";
plus = new Image();
plus.src = imagesDirectory + "/plus.gif";
toc_open_img = new Image();
toc_open_img.src = imagesDirectory + "/toc_open.gif";
toc_closed_img = new Image();
toc_closed_img.src = imagesDirectory + "/toc_closed.gif";
folder_img = new Image();
folder_img.src = imagesDirectory + "/container_obj.gif";
folder_topic = new Image();
folder_topic.src = imagesDirectory + "/container_topic.gif";
topic_img = new Image();
topic_img.src = imagesDirectory + "/topic.gif";

var altTopic = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("altTopic", request))%>";
var altContainer = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("altContainer", request))%>";
var altContainerTopic = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("altContainerTopic", request))%>";
var altBookClosed = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("bookClosed", request))%>";
var altBookOpen = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("bookOpen", request))%>";
var altPlus = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("expandTopicTitles", request))%>";
var altMinus = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("collapseTopicTitles", request))%>";
var loadingMessage = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("Loading", request))%>";
var cookiesRequired = "<%=UrlUtil.JavaScriptEncode(ServletResources.getString("cookiesRequired", request))%>";

var isIE = navigator.userAgent.indexOf('MSIE') != -1;
var isRTL = <%=isRTL%>;

var tocTitle = "";
var tocId = "";
	
function onloadHandler()
{
    setRootAccessibility();
	loadChildren(null);
	
	// Set prefix for AJAX calls by removing tocView.jsp from location
	var locationHref = window.location.href;
    var slashAdvanced = locationHref.lastIndexOf('/tocView.jsp');
    if(slashAdvanced > 0) {
	    setAjaxPrefix(locationHref.substr(0, slashAdvanced));
	}
<%
    if (request.getParameter("topic") != null) {
        TocData data = new TocData(application,request, response);
	    if (data.getSelectedToc() != -1) {
%>
	var tocTopic = "<%=UrlUtil.JavaScriptEncode(data.getTocDescriptionTopic(data.getSelectedToc()))%>";
	var topicSelected=false;
	// select specified topic, or else the book
	var topic = "<%=UrlUtil.JavaScriptEncode(data.getSelectedTopic())%>";
	if (topic != "about:blank" && topic != tocTopic) {
		if (topic.indexOf(window.location.protocol) != 0 && topic.length > 2) {
			// remove the .. from topic
			topic = topic.substring(2);
			// remove advanced/tocView.jsp from path to obtain contextPath
			var contextPath = window.location.pathname;
			var slash = contextPath.lastIndexOf('/');
			if(slash > 0) {
				slash = contextPath.lastIndexOf('/', slash-1);
				if(slash >= 0) {
					contextPath = contextPath.substr(0, slash);
					topic = window.location.protocol + "//" +window.location.host + contextPath + topic;
				}
			}			
		}
		topicSelected = selectTopic(topic);
	} 
<%
	    }
	} else if (!"true".equalsIgnoreCase(request.getParameter("collapse"))) {
%>   
        if (isAutosynchEnabled()) {
	        selectTopic("<%=prefs.getHelpHome()%>", true);
	    }
<%
	} 
%>
}

function onunloadHandler() {
<%
// for large books, we want to avoid a long unload time
if (requestData.isIE()){
%>
	document.body.innerHTML = "";
<%
}
%>
}
</script>

</head>
<body dir="<%=direction%>" onload="onloadHandler()" onunload="onunloadHandler()">
  <DIV class = "group" id = "wai_application">
    <DIV class = "root" id = "tree_root">
    </DIV>
  </DIV>
</body>
</html>
