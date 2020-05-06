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
 * Create date: 04-Aug-2008
 */
package uk.me.parabola.mkgmap.reader.polish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.net.AccessTagsAndBits;
import uk.me.parabola.imgfmt.app.net.Numbers;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapRoad;

/**
 * Used to remember all the road relevant parameters in a definition which
 * can occur in any order. Also remembers routing nodes and makes sure
 * the generated MapRoads all have the same RoutingNode objects.
 *
 * Use one instance of RoadHelper per file, and reset after reading
 * each road.
 */
class RoadHelper {
	private static final Logger log = Logger.getLogger(RoadHelper.class);

	// routing node store, persistent over resets
	private final Map<Long, CoordNode> nodeCoords = new HashMap<>();

	private int roadId;
	private final List<NodeIndex> nodes = new ArrayList<>();

	private int speed;
	private int roadClass;

	private boolean oneway;
	private boolean toll;

	private byte mkgmapAccess;
	private SortedMap<Integer, Numbers> numbersMap;

	public RoadHelper() {
		clear();
	}

	public void clear() {
		roadId = 0;
		nodes.clear();

		speed = 0;
		roadClass = 0;
		oneway = false;
		toll = false;
		numbersMap = null;
	}

	public void setRoadId(int roadId) {
		this.roadId = roadId;
	}

	public void addNode(String value) {
		String[] f = value.split(",");
		nodes.add(new NodeIndex(f));
	}

	/**
	 * @param param cgpsmapper manual:
	 * RouteParam=speed,road_class,one_way,toll,
	 * denied_emergency,denied_delivery,denied_car,denied_bus,denied_taxi,denied_pedestrain,denied_bicycle,denied_truck
	 */
	public void setParam(String param) {
		String[] f = param.split(",");
		speed = Integer.parseInt(f[0]);
		if (speed < 0)
			speed = 0;
		if (speed > 7)
			speed = 7;
		roadClass = Integer.parseInt(f[1]);
		if (roadClass < 0)
			roadClass = 0;
		if (roadClass > 4)
			roadClass = 4;
		oneway = f.length > 2 && Integer.parseInt(f[2]) > 0;
		toll = f.length > 3 && Integer.parseInt(f[3]) > 0;
		byte noAccess = 0;
		for (int j = 0; j < f.length - 4; j++){
			if (Integer.parseInt(f[4+j]) == 0)
				continue;
			switch (j){
			case 0: noAccess |= AccessTagsAndBits.EMERGENCY; break; 
			case 1: noAccess |= AccessTagsAndBits.DELIVERY; break; 
			case 2: noAccess |= AccessTagsAndBits.CAR; break; 
			case 3: noAccess |= AccessTagsAndBits.BUS; break; 
			case 4: noAccess |= AccessTagsAndBits.TAXI; break; 
			case 5: noAccess |= AccessTagsAndBits.FOOT; break; 
			case 6: noAccess |= AccessTagsAndBits.BIKE; break; 
			case 7: noAccess |= AccessTagsAndBits.TRUCK; break; 
			}
		}
		mkgmapAccess = (byte) ~noAccess; // we store the allowed vehicles
	}

	public MapRoad makeRoad(MapLine l) {
		assert roadId != 0;
		if (log.isDebugEnabled())
			log.debug("finishing road id " + roadId);

		MapRoad road = new MapRoad(roadId, roadId, l);

		// Set parameters.
		road.setRoadClass(roadClass);
		road.setSpeed(speed);
		if (oneway)
			road.setOneway();
		if (toll)
			road.setToll();
		road.setAccess(mkgmapAccess);

		List<Coord> points = road.getPoints();
		for (NodeIndex ni : nodes) {
			int n = ni.index;
			if (log.isDebugEnabled())
				log.debug("road has " + points.size() +" points");
			if (n < 0 || n >= points.size()) {
				throw new MapFailedException("bad node index " + n + " in road id " + roadId);
			}
			Coord coord = points.get(n);
			long id = coord.getId();
			if (id == 0) {
				CoordNode node = nodeCoords.get((long) ni.nodeId);
				if (node == null) {
					node = new CoordNode(coord, ni.nodeId, ni.boundary, false);
					nodeCoords.put((long) ni.nodeId, node);
				}
				points.set(n, node);
			} else if (id != ni.nodeId) {
				log.warn("Inconsistant node ids");
			}
		}
		if (numbersMap != null) {
			if (numbersMap.values().stream().anyMatch(n -> !n.isEmpty())) {
				convertNodesForHouseNumbers(road);
			} else { 
				numbersMap = null;
			}
		}

		return road;
	}

	/**
	 * Make sure that each node which is referenced by the house
	 * numbers is a number node. Some of them will later be changed
	 * to routing nodes.
	 * Only called if numbers is non-null and not empty.
	 */
	private void convertNodesForHouseNumbers(MapRoad road) {
		List<Coord> points = road.getPoints();
		if (points.isEmpty())
			return;
		// make sure all number nodes are marked as such
		for (Integer idx : numbersMap.keySet()) {
			if (idx < 0 || idx >= points.size()) {
				throw new MapFailedException("bad number node index " + idx + " in road id " + roadId);
			}
			road.getPoints().get(idx).setNumberNode(true);
		}
		
		points.get(0).setNumberNode(true); 
		int roadNodeNumber = 0;
		for (int i = 0; i < points.size(); i++) {
			if (points.get(i).isNumberNode()) {
				Numbers nums = numbersMap.get(i);
				if (nums != null) {
					// we have numbers for this node
					nums.setIndex(roadNodeNumber);
				} else {
					// no numbers given, default is the empty interval (N,-1,-1,N,-1,-1)
				}
				roadNodeNumber++;
			}
		}
		road.setNumbers(new ArrayList<>(numbersMap.values()));
	}

	public boolean isRoad() {
		return roadId != 0;
	}

	public Map<Long, CoordNode> getNodeCoords() {
		return nodeCoords;
	}

	public void addNumbers(Numbers nums) {
		if (numbersMap == null)
			numbersMap = new TreeMap<>();
		numbersMap.put(nums.getPolishIndex(),nums);
	}

	private static class NodeIndex {
		private final int index;
		private final int nodeId;
		private boolean boundary;

		private NodeIndex(String[] f) {
			// f[0] is the index into the line
			// f[1] is the node id
			// f[2] is whether it's a boundary node
			index = Integer.parseInt(f[0]);
			nodeId = Integer.parseInt(f[1]);
			if (f.length > 2)
				boundary = Integer.parseInt(f[2]) > 0;
			if (log.isDebugEnabled())
				log.debug("ind=" + index + "node=" + nodeId + "bound=" + boundary);
		}

		public String toString() {
			return String.format("%d,%d,%b", index, nodeId, boundary);
		}
	}
}
