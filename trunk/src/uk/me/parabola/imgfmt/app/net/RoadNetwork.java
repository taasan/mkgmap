/*
 * Copyright (C) 2008 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 13-Jul-2008
 */
package uk.me.parabola.imgfmt.app.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

/**
 * This holds the road network.  That is all the roads and the nodes
 * that connect them together.
 * 
 * @see <a href="http://www.movable-type.co.uk/scripts/latlong.html">Distance / bearing calculations</a>
 * @author Steve Ratcliffe
 */
public class RoadNetwork {
	private static final Logger log = Logger.getLogger(RoadNetwork.class);

	private final static int MAX_RESTRICTIONS_ARCS = 7;
	private final Map<Integer, RouteNode> nodes = new LinkedHashMap<>();

	// boundary nodes
	// a node should be in here if the nodes boundary flag is set
	private final List<RouteNode> boundary = new ArrayList<>();
	private final List<RoadDef> roadDefs = new ArrayList<>();
	private List<RouteCenter> centers = new ArrayList<>();
	private AngleChecker angleChecker = new AngleChecker();

	private boolean checkRoundabouts;
	private boolean checkRoundaboutFlares;
	private int maxFlareLengthRatio ;
	private boolean reportSimilarArcs;

	public void config(EnhancedProperties props) {
		checkRoundabouts = props.getProperty("check-roundabouts", false);
		checkRoundaboutFlares = props.getProperty("check-roundabout-flares", false);
		maxFlareLengthRatio = props.getProperty("max-flare-length-ratio", 0);
		reportSimilarArcs = props.getProperty("report-similar-arcs", false);
		angleChecker.config(props);
	}

	public void addRoad(RoadDef roadDef, List<Coord> coordList) {
		roadDefs.add(roadDef);

		CoordNode lastCoord = null;
		int lastIndex = 0;
		double roadLength = 0;
		double arcLength = 0;
		int pointsHash = 0;

		int npoints = coordList.size();
		int numCoordNodes = 0;
		boolean hasInternalNodes = false;
		int numNumberNodes = 0;
		BitSet nodeFlags = new BitSet();
		for (int index = 0; index < npoints; index++) {
			Coord co = coordList.get(index);
			int id = co.getId();

			if (id != 0){
				nodeFlags.set(numNumberNodes);
				++numCoordNodes;
				if(index > 0 && index < npoints - 1)
					hasInternalNodes = true;
			}
			if (co.isNumberNode())
				++numNumberNodes;
			if (index == 0){
				if (id == 0)
					roadDef.setStartsWithNode(false);
				
			} else { 
				double d = co.distance(coordList.get(index-1));
				arcLength += d;
				roadLength += d;
			}
			if (roadDef.skipAddToNOD())
				continue;
			
			pointsHash += co.hashCode();

			if (id == 0)
				// not a routing node
				continue;

			// The next coord determines the heading
			// If this is the not the first node, then create an arc from
			// the previous node to this one (and back again).
			if (lastCoord != null) {
				int lastId = lastCoord.getId();
				if(log.isDebugEnabled()) {
					log.debug("lastId = " + lastId + " curId = " + id);
					log.debug("from " + lastCoord.toDegreeString() 
							  + " to " + co.toDegreeString());
					log.debug("arclength=" + arcLength + " roadlength=" + roadLength);
				}

				RouteNode node1 = getOrAddNode(lastId, lastCoord);
				RouteNode node2 = getOrAddNode(id, co);
				if(node1 == node2)
					log.error("Road " + roadDef + " contains consecutive identical nodes at " + co.toOSMURL() + " - routing will be broken");
				else if(arcLength == 0)
					log.warn("Road " + roadDef + " contains zero length arc at " + co.toOSMURL());

				Coord forwardBearingPoint = coordList.get(lastIndex + 1);
				if(lastCoord.equals(forwardBearingPoint) || forwardBearingPoint.isAddedNumberNode()) {
					// bearing point is too close to last node to be
					// useful - try some more points
					for(int bi = lastIndex + 2; bi <= index; ++bi) {
						Coord coTest = coordList.get(bi);
						if (coTest.isAddedNumberNode() || lastCoord.equals(coTest))
							continue;
						forwardBearingPoint = coTest;
						break;
					}
				}
				Coord reverseBearingPoint = coordList.get(index - 1);
				if(co.equals(reverseBearingPoint) || reverseBearingPoint.isAddedNumberNode()) {
					// bearing point is too close to this node to be
					// useful - try some more points
					for(int bi = index - 2; bi >= lastIndex; --bi) {
						Coord coTest = coordList.get(bi);
						if (coTest.isAddedNumberNode() || co.equals(coTest))
							continue;
						reverseBearingPoint = coTest;
						break;
					}
				}
				
				double forwardInitialBearing = lastCoord.bearingTo(forwardBearingPoint);
				double forwardDirectBearing = (co == forwardBearingPoint) ? forwardInitialBearing: lastCoord.bearingTo(co); 

				double reverseInitialBearing = co.bearingTo(reverseBearingPoint);
				double directLength = (lastIndex + 1 == index) ? arcLength : lastCoord.distance(co);
				double reverseDirectBearing = 0;
				if (directLength > 0){
					// bearing on rhumb line is a constant, so we can simply revert
					reverseDirectBearing = (forwardDirectBearing <= 0) ? 180 + forwardDirectBearing: -(180 - forwardDirectBearing) % 180.0;
				}
				// Create forward arc from node1 to node2
				RouteArc arc = new RouteArc(roadDef,
											node1,
											node2,
											forwardInitialBearing,
											forwardDirectBearing,
											arcLength,
											arcLength,
											directLength,
											pointsHash);
				arc.setForward();
				node1.addArc(arc);
				
				// Create the reverse arc
				RouteArc reverseArc = new RouteArc(roadDef,
								   node2, node1,
								   reverseInitialBearing,
								   reverseDirectBearing,
								   arcLength,
								   arcLength,
								   directLength,
								   pointsHash);
				node2.addArc(reverseArc);
				// link the two arcs
				arc.setReverseArc(reverseArc);
				reverseArc.setReverseArc(arc);
			} else {
				// This is the first node in the road
				roadDef.setNode(getOrAddNode(id, co));
			}

			lastCoord = (CoordNode) co;
			lastIndex = index;
			arcLength = 0;
			pointsHash = co.hashCode();
		}
		if (roadDef.hasHouseNumbers()){
			// we ignore number nodes when we have no house numbers
			if (numCoordNodes < numNumberNodes)
				hasInternalNodes = true;
			roadDef.setNumNodes(numNumberNodes);
			roadDef.setNod2BitSet(nodeFlags);
		} else {
			roadDef.setNumNodes(numCoordNodes);
		}
		if (hasInternalNodes)
			roadDef.setInternalNodes(true);
		roadDef.setLength(roadLength);
	}

	private RouteNode getOrAddNode(int id, Coord coord) {
		RouteNode node = nodes.get(id);
		if (node == null) {
			node = new RouteNode(coord);
			nodes.put(id, node);
			if (node.isBoundary())
				boundary.add(node);
		}
		return node;
	}

	public List<RoadDef> getRoadDefs() {
		return roadDefs;
	}

	/**
	 * Split the network into RouteCenters.
	 *
	 * The resulting centers must satisfy several constraints,
	 * documented in NOD1Part.
	 */
	private void splitCenters() {
		if (nodes.isEmpty())
			return;
		assert centers.isEmpty() : "already subdivided into centers";

		// sort nodes by NodeGroup 
		List<RouteNode> nodeList = new ArrayList<>(nodes.values());
		nodes.clear(); // return to GC
		for (int group = 0; group <= 4; group++){
			NOD1Part nod1 = new NOD1Part();
			int n = 0;
			for (RouteNode node : nodeList) {
				if (node.getGroup() != group)
					continue;
				if(!node.isBoundary()) {
					if(checkRoundabouts)
						node.checkRoundabouts();
					if(checkRoundaboutFlares)
						node.checkRoundaboutFlares(maxFlareLengthRatio);
					if(reportSimilarArcs)
						node.reportSimilarArcs();
				}
				
				nod1.addNode(node);
				n++;
			}
			if (n > 0)
				centers.addAll(nod1.subdivide());
		}
	}

	public List<RouteCenter> getCenters() {
		if (centers.isEmpty()){
			angleChecker.check(nodes);
			addArcsToMajorRoads();
			splitCenters();
		}
		return centers;
	}

	/**
	 * add indirect arcs for each road class (in descending order)
	 */
	private void addArcsToMajorRoads() {
		long t1 = System.currentTimeMillis();
		
		for (RoadDef rd: roadDefs){
			if (rd.skipAddToNOD())
				continue;
			if (rd.getRoadClass() >= 1)
				rd.getNode().addArcsToMajorRoads(rd);
		}
		log.info(" added major road arcs in " + (System.currentTimeMillis() - t1) + " ms");
	}

	/**
	 * Get the list of nodes on the boundary of the network.
	 *
	 * Currently empty.
	 */
	public List<RouteNode> getBoundary() {
		return boundary;
	}

	/**
	 * One restriction forbids to travel a specific combination of arcs.
	 * We know two kinds: 3 nodes with two arcs and one via node or 4 nodes with 3 arcs
	 * and two via nodes. Maybe more nodes are possible, but we don't know for sure how
	 * to write them (2014-04-02). 
	 * Depending on the data in grr we create one or more such restrictions.
	 * A restriction with 4 (or more) nodes is added to each via node.     
	 * 
	 * The OSM restriction gives a from way id and a to way id and one or more 
	 * via nodes. It is possible that the to-way is a loop, so we have to identify
	 * the correct arc. 
	 * @param grr the object that holds the details about the route restriction
	 */
	public int addRestriction(GeneralRouteRestriction grr) {
		if (grr.getType() == GeneralRouteRestriction.RestrType.TYPE_NO_TROUGH)
			return addNoThroughRoute(grr);
		String sourceDesc = grr.getSourceDesc();
		
		List<RouteNode> viaNodes = new ArrayList<>();
		for (CoordNode via : grr.getViaNodes()){
			RouteNode vn = nodes.get(via.getId());
			if (vn == null){
				log.error(sourceDesc, "can't locate 'via' RouteNode with id", via.getId());
				return 0;
			}
			viaNodes.add(vn);
		}
		
		int firstViaId = grr.getViaNodes().get(0).getId();
		int lastViaId = grr.getViaNodes().get(grr.getViaNodes().size()-1).getId();
		RouteNode firstViaNode = nodes.get(firstViaId);
		RouteNode lastViaNode = nodes.get(lastViaId);
		List<List<RouteArc>> viaArcsList = new ArrayList<>();
		if (grr.getViaNodes().size() != grr.getViaWayIds().size() + 1){
			log.error(sourceDesc, "internal error: number of via nodes and via ways doesn't fit");
			return 0;
		}
		for (int i = 1; i < grr.getViaNodes().size(); i++){
			RouteNode vn = viaNodes.get(i-1);
			Long viaWayId = grr.getViaWayIds().get(i-1);
			List<RouteArc> viaArcs = vn.getDirectArcsTo(viaNodes.get(i), viaWayId);
			if (viaArcs.isEmpty()){
				log.error(sourceDesc, "can't locate arc from 'via' node at",vn.getCoord().toOSMURL(),"to next 'via' node on way",viaWayId);
				return 0;
			}
			viaArcsList.add(viaArcs);
		}
		
		// determine the from node and arc(s)
		int fromId = 0;
		RouteNode fn = null;
		if (grr.getFromNode() != null){
			fromId = grr.getFromNode().getId();
			// polish input data provides id
			fn = nodes.get(fromId);
			if (fn == null ){
				log.error(sourceDesc, "can't locate 'from' RouteNode with id", fromId);
				return 0; 
			}
		} else {
			List<RouteArc> possibleFromArcs = firstViaNode.getDirectArcsOnWay(grr.getFromWayId());
			for (RouteArc arc : possibleFromArcs){
				if (fn == null)
					fn = arc.getDest();
				else if (fn != arc.getDest()){
					log.warn(sourceDesc, "found different 'from' arcs for way",grr.getFromWayId(),"restriction is ignored");
					return 0;
				}
			}
			if (fn == null){
				log.warn(sourceDesc, "can't locate 'from' RouteNode for 'from' way", grr.getFromWayId());
				return 0;
			} 
			fromId = fn.getCoord().getId();
		}
		List<RouteArc> fromArcs = fn.getDirectArcsTo(firstViaNode, grr.getFromWayId()); 
		if (fromArcs.isEmpty()){
			log.error(sourceDesc, "can't locate arc from 'from' node ",fromId,"to 'via' node",firstViaId,"on way",grr.getFromWayId());
			return 0;
		}
		
		// a bit more complex: determine the to-node and arc(s) 
		RouteNode tn = null;
		int toId = 0; 
		List<RouteArc> toArcs;
		if (grr.getToNode() != null){ 
			// polish input data provides id
			toId = grr.getToNode().getId();
			tn = nodes.get(toId);
			if (tn == null ){
				log.error(sourceDesc, "can't locate 'to' RouteNode with id", toId);
				return 0; 
			}
			toArcs = lastViaNode.getDirectArcsTo(tn, grr.getToWayId()); 
		} else {
			// we can have multiple arcs between last via node and to node. The 
			// arcs can be on the same OSM way or on different OSM ways.
			// We can have multiple arcs with different RoadDef objects that refer to the same 
			// OSM way id. The direction indicator tells us what arc is probably meant.
			List<RouteArc> possibleToArcs = lastViaNode.getDirectArcsOnWay(grr.getToWayId());
			RouteArc fromArc = fromArcs.get(0);

			boolean ignoreAngle = false;
			if (fromArc.getLengthInMeter() <= 0.0001)
				ignoreAngle = true;
			if (grr.getDirIndicator() == '?')
				ignoreAngle = true;
			log.info(sourceDesc, "found", possibleToArcs.size(), "candidates for to-arc");

			// group the available arcs by angle 
			Map<Integer, List<RouteArc>> angleMap = new TreeMap<>();
			for (RouteArc arc : possibleToArcs){
				if (arc.getLengthInMeter() <= 0.0001)
					ignoreAngle = true;
				Integer angle = Math.round(getAngle(fromArc, arc));
				List<RouteArc> list = angleMap.get(angle);
				if (list == null){
					list = new ArrayList<>();
					angleMap.put(angle, list);
				}
				list.add(arc);
			}

			// find the group that fits best 
			Iterator<Entry<Integer, List<RouteArc>>> iter = angleMap.entrySet().iterator();
			Integer bestAngle = null;
			while (iter.hasNext()){
				Entry<Integer, List<RouteArc>> entry = iter.next();
				if (ignoreAngle || matchDirectionInfo(entry.getKey(), grr.getDirIndicator()) ){
					if (bestAngle == null)
						bestAngle = entry.getKey();
					else {
						bestAngle = getBetterAngle(bestAngle, entry.getKey(), grr.getDirIndicator());
					}
				}
			}
			
			if (bestAngle == null){
				log.warn(sourceDesc,"the angle of the from and to way don't match the restriction");
				return 0;
			} 
			toArcs = angleMap.get(bestAngle);
		}
		if (toArcs.isEmpty()){
			log.error(sourceDesc, "can't locate arc from 'via' node ",lastViaId,"to 'to' node",toId,"on way",grr.getToWayId());
			return 0;
		}
		
		List<RouteArc> badArcs = new ArrayList<>();
		
		if (grr.getType() == GeneralRouteRestriction.RestrType.TYPE_NOT){
			for (RouteArc toArc: toArcs){
				badArcs.add(toArc);
			}
		}
		else if (grr.getType() == GeneralRouteRestriction.RestrType.TYPE_ONLY){
			// this is the inverse logic, grr gives the allowed path, we have to find the others
			for (RouteArc badArc : lastViaNode.arcsIteration()){
				if (!badArc.isDirect() || toArcs.contains(badArc))
					continue;
				badArcs.add(badArc);
			}
			if (badArcs.isEmpty()){
				log.warn(sourceDesc, "restriction ignored because it has no effect");
				return 0;
			}
		}
		
		// create all possible paths for which the restriction applies 
		List<List<RouteArc>> arcLists = new ArrayList<>();
		arcLists.add(fromArcs);
		arcLists.addAll(viaArcsList);
		arcLists.add(badArcs);
		if (arcLists.size() > MAX_RESTRICTIONS_ARCS){
			log.warn(sourceDesc, "has more than", MAX_RESTRICTIONS_ARCS, "arcs, this is not supported");
			return 0;
		}
		
		// remove arcs which cannot be travelled by the vehicles listed in the restriction
		for (int i = 0; i < arcLists.size(); i++){
			List<RouteArc> arcs =  arcLists.get(i);
			int countNoEffect = 0;
			int countOneway= 0;
			for (int j = arcs.size()-1; j >= 0; --j){
				RouteArc arc = arcs.get(j);
				if (isUsable(arc.getRoadDef().getAccess(), grr.getExceptionMask()) == false){
					countNoEffect++;
					arcs.remove(j);
				}
				else if (arc.getRoadDef().isOneway()){
					if (!arc.isForward()){
						countOneway++;
						arcs.remove(j);
					}
				}
			}
			String arcType = null;
			if (arcs.isEmpty()){
				if (i == 0)
					arcType = "from way is";
				else if (i == arcLists.size()-1){
					if (grr.getType() == GeneralRouteRestriction.RestrType.TYPE_ONLY)
						arcType = "all possible other ways are";
					else 
						arcType = "to way is";
				}
				else 
					arcType = "via way is";
				String reason;
				if (countNoEffect > 0 & countOneway > 0)
					reason = "wrong direction in oneway or not accessible for restricted vehicles";
				else if (countNoEffect > 0)
					reason = "not accessible for restricted vehicles";
				else 
					reason = "wrong direction in oneway";
				log.warn(sourceDesc, "restriction ignored because",arcType,reason);
				return 0;
			}
		}
		if (viaNodes.contains(fn)){
			log.warn(sourceDesc, "restriction not written because from node appears also as via node");
			return 0;
		}
		// determine all possible combinations of arcs. In most cases,
		// this will be 0 or one, but if the style creates multiple roads for one
		// OSM way, this can be a larger number
		int numCombis = 1;
		int [] indexes = new int[arcLists.size()];
		for (int i = 0; i < indexes.length; i++){
			List<RouteArc> arcs =  arcLists.get(i);
			numCombis *= arcs.size();
		}
		List<RouteArc> path = new ArrayList<>();
		int added = 0;
		for (int i = 0; i < numCombis; i++){
			for (RouteNode vn : viaNodes){
				path.clear();
				boolean viaNodeFound = false;
				byte pathNoAccessMask = 0;
				for (int j = 0; j < indexes.length; j++){
					RouteArc arc = arcLists.get(j).get(indexes[j]);
					if (arc.getDest() == vn || viaNodeFound == false){
						arc = arc.getReverseArc();
					}
					if (arc.getSource() == vn)
						viaNodeFound = true;
					if (arc.getDest() == vn){
						if (added > 0)
							log.error(sourceDesc, "restriction incompletely written because dest in arc is via node");
						else 
							log.warn(sourceDesc, "restriction not written because dest in arc is via node");
						return added;
					}
					pathNoAccessMask |= ~arc.getRoadDef().getAccess();
					path.add(arc);
				}
				byte pathAccessMask = (byte)~pathNoAccessMask;
				if (isUsable(pathAccessMask, grr.getExceptionMask())){
					vn.addRestriction(new RouteRestriction(vn, path, grr.getExceptionMask()));
					++added;
				} 
			}
			// get next combination of arcs
			++indexes[indexes.length-1];
			for (int j = indexes.length-1; j > 0; --j){
				if (indexes[j] >= arcLists.get(j).size()){
					indexes[j] = 0;
					indexes[j-1]++;
				}
			}
		}

		// double check
		if (indexes[0] != arcLists.get(0).size())
			log.error(sourceDesc, " failed to generate all possible paths");
		log.info(sourceDesc, "added",added,"route restriction(s) to img file");
		return added;
	}

	/**
	 * Compare the disallowed vehicles for the path with the exceptions from the restriction
	 * @param roadNoAccess
	 * @param exceptionMask
	 * @return
	 */
	private static boolean isUsable(byte roadAccess, byte exceptionMask) {
		if ((roadAccess & (byte) ~exceptionMask) == 0)
			return false; // no allowed vehicle is concerned by this restriction
		return true;
	}

	private int addNoThroughRoute(GeneralRouteRestriction grr) {
		assert grr.getViaNodes() != null;
		assert grr.getViaNodes().size() == 1;
		int viaId = grr.getViaNodes().get(0).getId();
		RouteNode vn = nodes.get(viaId);
		if (vn == null){
			log.error(grr.getSourceDesc(), "can't locate 'via' RouteNode with id", viaId);
			return 0;
		}
		int added = 0;
		
		for (RouteArc out: vn.arcsIteration()){
			if (!out.isDirect())
				continue;
			for (RouteArc in: vn.arcsIteration()){
				if (!in.isDirect() || in == out || in.getDest() == out.getDest())
					continue;
				byte pathAccessMask = (byte) (out.getRoadDef().getAccess() & in.getRoadDef().getAccess());
				if (isUsable(pathAccessMask, grr.getExceptionMask())){
					vn.addRestriction(new RouteRestriction(vn, Arrays.asList(in,out), grr.getExceptionMask()));
					added++;
				} else {
					if (log.isDebugEnabled())
						log.debug(grr.getSourceDesc(),"ignored no-through-route",in,"to",out);
				}
			}
		}
		return added;
	}
	
	/**
	 * Calculate the "angle" between to arcs. The arcs may not be connected.
	 * We do this by "virtually" moving the toArc so that its source 
	 * node lies on the destination node of the from arc.
	 * This makes only sense if move is not over a large distance, we assume that this 
	 * is the case as via ways should be short. 
	 * @param fromArc arc with from node as source and first via node as destination
	 * @param toArc arc with last via node as source
	 * @return angle at in degree [-180;180]
	 */
	private static float getAngle(RouteArc fromArc, RouteArc toArc){
		// note that the values do not depend on the isForward() attribute
		float headingFrom = fromArc.getFinalHeading();
		float headingTo = toArc.getInitialHeading();
		float angle = headingTo - headingFrom;
		while(angle > 180)
			angle -= 360;
		while(angle < -180)
			angle += 360;
		return angle;
	}
	
	/**
	 * Find the angle that comes closer to the direction indicated.
	 * 
	 * @param angle1 1st angle -180:180 degrees
	 * @param angle2 2nd angle -180:180 degrees
	 * @param dirIndicator l:left, r:right, u:u_turn, s: straight_on
	 * @return
	 */
	private static Integer getBetterAngle (Integer angle1, Integer angle2, char dirIndicator){
		switch (dirIndicator){
		case 'l':
			if (Math.abs(-90-angle2) < Math.abs(-90-angle1))
				return angle2; // closer to -90
			break;
		case 'r':
			if (Math.abs(90-angle2) < Math.abs(90-angle1))
				return angle2; // closer to 90
			break;
		case 'u': 
			double d1 = (angle1 < 0 ) ? -180-angle1 : 180-angle1; 
			double d2 = (angle2 < 0 ) ? -180-angle2 : 180-angle2; 
			if (Math.abs(d2) < Math.abs(d1))
				return angle2; // closer to -180
			break;
		case 's': 
			if (Math.abs(angle2) < Math.abs(angle1))
				return angle2; // closer to 0
			break;
		}
		
		return angle1;
	}
	
	/**
	 * Check if angle is in the range indicated by the direction
	 * @param angle the angle -180:180 degrees
	 * @param dirIndicator l:left, r:right, u:u_turn, s: straight_on 
	 * @return
	 */
	private static boolean matchDirectionInfo (float angle, char dirIndicator){
		switch (dirIndicator){
		case 'l':
			if (angle < -3 && angle > - 177)
				return true;
			break;
		case 'r':
			if (angle > 3 && angle < 177)
				return true;
			break;
		case 'u':
			if (angle < -87 || angle > 93)
				return true;
			break;
		case 's':
			if (angle > -87 && angle < 87)
				return true;
			break;
		case '?':
			return true;
		}
		return false;
	}
	
	
}
