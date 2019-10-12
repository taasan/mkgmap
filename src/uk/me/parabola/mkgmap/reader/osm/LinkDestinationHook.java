/*
 * Copyright (C) 2012
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package uk.me.parabola.mkgmap.reader.osm;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.osmstyle.function.LengthFunction;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.MultiHashMap;

/**
 * Copies the destination tag from motorway_link and trunk_link ways to the 
 * first adjacent non link way so that the Garmin is able to display a valid
 * destination.
 * @author WanMil
 */
public class LinkDestinationHook extends OsmReadingHooksAdaptor {
	private static final Logger log = Logger.getLogger(LinkDestinationHook.class);

	private ElementSaver saver;

	/** Maps which ways can be driven from a given Coord */
	private IdentityHashMap<Coord, Set<Way>> adjacentWays = new IdentityHashMap<Coord, Set<Way>>();
	/** Contains all _link ways that have to be processed */
	private Map<Long, Way> destinationLinkWays = new LinkedHashMap<Long, Way>();
	
	private final static Set<String> highwayTypes = new LinkedHashSet<String>(Arrays.asList(
			"motorway", "trunk", "primary", "secondary", "tertiary", 
			"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"));
	private HashSet<String> linkTypes = new HashSet<String>(Arrays.asList(
			"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"));
	

	/** Map way ids to its restriction relations so that the relations can easily be updated when the way is split. */
	private MultiHashMap<Long, RestrictionRelation> restrictions = new MultiHashMap<>();
	
	private NameFinder nameFinder;

	/** Maps which nodes contains to which ways */ 
	private IdentityHashMap<Coord, Set<Way>> wayNodes = new IdentityHashMap<Coord, Set<Way>>();
	
	private boolean processDestinations;
	private boolean processExits;
	
	public boolean init(ElementSaver saver, EnhancedProperties props) {
		this.saver = saver;
		nameFinder = new NameFinder(props);
		processDestinations = props.containsKey("process-destination");
		processExits = props.containsKey("process-exits");
		return processDestinations || processExits;
	}

	/**
	 * Fills the internal lists 
	 */
	private void retrieveWays() {
		// collect all ways tagged with highway 
		for (Way w : saver.getWays().values()) {
			if (w.getPoints().size() < 2) {
				// ignore one-node or zero-node ways
				continue;
			}
			String highwayTag = w.getTag("highway");
			if (highwayTag != null && highwayTypes.contains(highwayTag)) {
				// the points of the way are kept so that it is easy to get
				// the adjacent ways for a given _link way
				String directedDestination = null;
				String directedDestinationLanes = null;
				String directionSuffix = null;
				List<Coord> points;
				
				if (isOnewayInDirection(w)) {
					// oneway => don't need the last point because the
					// way cannot be driven standing at the last point
					points = w.getPoints().subList(0, w.getPoints().size() - 1);
					directedDestination = w.getTag("destination:forward");
					directedDestinationLanes = w.getTag("destination:lanes:forward");
					directionSuffix = "forward";
				} else if (isOnewayOppositeDirection(w)) {
					// reverse oneway => don't need the first point because the
					// way cannot be driven standing at the first point
					points = w.getPoints().subList(1, w.getPoints().size());
					directedDestination = w.getTag("destination:backward");
					directedDestinationLanes = w.getTag("destination:lanes:backward");
					directionSuffix = "backward";
				} else {
					points = w.getPoints();
				}
				for (Coord c : points) {
					Set<Way> ways = adjacentWays.get(c);
					if (ways == null) {
						ways = new HashSet<Way>(4);
						adjacentWays.put(c, ways);
					}
					ways.add(w);
				}
				registerPointsOfWay(w);

				// if the way is a link way and has a destination tag
				// put it the list of ways that have to be processed
				if (linkTypes.contains(highwayTag)) {
					String destSourceTagKey = "destination"; 
					String destinationTag = w.getTag("destination");
					if (destinationTag == null) {
						// destination is not set 
						// => check if destination:lanes is without any lane specific information (no |)
						destSourceTagKey = "destination:lanes";
						String destLanesTag = w.getTag(destSourceTagKey);
						if (destLanesTag == null && directedDestinationLanes != null){
							destLanesTag = directedDestinationLanes;
							destSourceTagKey += ":" + directionSuffix;
						}
						if (destLanesTag != null && destLanesTag.contains("|") == false) {
							// the destination:lanes tag contains no | => no lane specific information
							// use this tag as destination tag 
							destinationTag = destLanesTag;
						}
						if (destinationTag == null && directedDestination != null) {
							// use the destination:forward or :backward value
							destinationTag = directedDestination;
							destSourceTagKey = "destination:" + directionSuffix; 
						}
						if (destinationTag == null){
							// try to use the destination:street value
							destSourceTagKey = "destination:street";
							destinationTag = w.getTag(destSourceTagKey);
						}
						
					}
					if (destinationTag != null){
						w.addTag("mkgmap:dest_hint_work", destinationTag);
						if ("destination".equals(destSourceTagKey) == false){
							if (log.isDebugEnabled()){
								if (destSourceTagKey.startsWith("destination:lanes"))
									log.debug("Use",destSourceTagKey,"as destination tag because there is one lane information only. Way ",w.getId(),w.toTagString());
								else 
									log.debug("Use",destSourceTagKey,"as destination tag. Way ",w.getId(),w.toTagString());
							}
						}
						destinationLinkWays.put(w.getId(), w);
					}
				}
			}
		}
		
		// get all restriction relations
		// eventually they must be modified if one of its ways is split
		for (Relation rel : saver.getRelations().values()) {
			if (rel instanceof RestrictionRelation) {
				RestrictionRelation rrel = (RestrictionRelation) rel;
				for (Long wayId : rrel.getWayIds())
					restrictions.add(wayId, rrel);
			}
		}
	}
	
	
	/**
	 * Registers the points of the given way for the internal data structures.
	 * @param w a new way
	 */
	private void registerPointsOfWay(Way w) {
		for (Coord c : w.getPoints()) {
			Set<Way> ways = wayNodes.get(c);
			if (ways == null) {
				ways = new HashSet<Way>(4);
				wayNodes.put(c, ways);
			}
			ways.add(w);
		}			
	}
	
	/**
	 * Removes the points in range from to to from the way and the internal data structures.
	 * @param w way
	 * @param from first point to remove
	 * @param to range end to remove (exclusive)
	 */
	private void removePointsFromWay(Way w, int from, int to) {
		// first remove them from the wayNodes map
		for (Coord c : w.getPoints().subList(from, to)) {
			wayNodes.get(c).remove(w);
		}
		// second remove them from the way
		w.getPoints().subList(from, to).clear();

	}
	
	/**
	 * Check all restriction relations and eventually update the relations to use
	 * the split way if appropriate.
	 * 
	 * @param oldWay the original way
	 * @param newWay the split part of the old way
	 */
	private void changeWayIdInRelations(Way oldWay, Way newWay) {
		List<RestrictionRelation> wayRestrictions = restrictions.get(oldWay.getId());
		if (wayRestrictions.isEmpty()) {
			return;
		}
		if (oldWay.isViaWay()) {
			log.error("internal error: via way is split in", this.getClass().getSimpleName());
		}
		// create a copy because original list may be modified within the loop
		for (RestrictionRelation rr : new ArrayList<>(wayRestrictions)) {
			Coord lastPointNewWay = newWay.getPoints().get(0);
			List<Coord> viaCoords = rr.getViaCoords();
			for (Coord via : viaCoords){
				if (via == lastPointNewWay) {
					if (rr.isToWay(oldWay.getId())) {
						log.debug("Change to-way",oldWay.getId(),"to",newWay.getId(),"for relation",rr.getId(),"at",lastPointNewWay.toOSMURL());
						rr.replaceWay(oldWay.getId(), newWay.getId());
						restrictions.removeMapping(oldWay.getId(), rr);
						restrictions.add(newWay.getId(), rr);
						
					} else if (rr.isFromWay(oldWay.getId())){
						log.debug("Change from-way",oldWay.getId(),"to",newWay.getId(),"for relation",rr.getId(),"at",lastPointNewWay.toOSMURL());
						rr.replaceWay(oldWay.getId(), newWay.getId());
						restrictions.removeMapping(oldWay.getId(), rr);
						restrictions.add(newWay.getId(), rr);
					} 
				}
			}
		}
	}
	
	/**
	 * Cuts off at least minLength meter of the given way and returns the cut off way tagged
	 * identical to the given way.   
	 * @param w the way to be cut 
	 * @param maxLength the cut off way is no longer than this value
	 * @return the cut off way or <code>null</code> if cutting not possible
	 */
	private Way cutoffWay(Way w, double cutLength, double maxLength, Coord c1, Coord c2) {
		if (w.getPoints().size()<2) {
			return null;
		}

		if (w.getPoints().size() >= 3) {
			// try to use existing points - that does not deform the way
			Coord firstPoint = w.getPoints().get(0);
			Coord cutPoint = w.getPoints().get(1);

			// check if the maxLength is not exceeded
			double dist = firstPoint.distance(cutPoint);
			if (dist <= maxLength) {
				// create a new way with the first two points and identical tags
				Way precedingWay = new Way(w.getOriginalId(), w.getPoints().subList(0, 1 + 1));
				precedingWay.setFakeId();
				precedingWay.copyTags(w);

				saver.addWay(precedingWay);
				// remove the points of the new way from the original way
				removePointsFromWay(w, 0, 1);

				registerPointsOfWay(precedingWay);

				// check and update relations so that they use the new way if appropriate
				changeWayIdInRelations(w, precedingWay);
				
				log.debug("Cut way", w, "at existing point 1. New way:",
						precedingWay);

				// return the new way
				return precedingWay;
			} else {
				log.debug("Cannot cut way",	w,
						"on existing nodes because the first distance is too big:",	dist);
			}
		}
		
		double startSegmentLength = 0;
		
		Coord lastC = w.getPoints().get(0);
		for (int i = 1; i < w.getPoints().size(); i++) {
			Coord c = w.getPoints().get(i);
			double segmentLength = lastC.distance(c);
			
			if (startSegmentLength + segmentLength  >= cutLength) {
				double frac = (cutLength - startSegmentLength) / segmentLength;
				// insert a new point at the minimum distance  
				Coord cConnection = lastC.makeBetweenPoint(c, frac);

				if (c1 != null && c2 != null && cConnection != null) {
					// test if the way using the new point still uses the same
					// orientation to the main motorway
					double oldAngle = getAngle(c1, c2, c);
					double newAngle = getAngle(c1, c2, cConnection);
					if (Math.signum(oldAngle) != Math.signum(newAngle)) {
						double bestAngleDiff = 180.0d;
						Coord bestCoord = cConnection;
						for (Coord cNeighbour : getDirectNeighbours(cConnection)) {
							double neighbourAngle = getAngle(c1, c2, cNeighbour);
							if (Math.signum(oldAngle) == Math.signum(neighbourAngle) && 
									Math.abs(oldAngle - neighbourAngle) < bestAngleDiff) {
								bestAngleDiff = Math.abs(oldAngle - neighbourAngle);
								bestCoord = cNeighbour;
							}
						}
						if (log.isDebugEnabled()) {
							log.debug("Changed orientation:", oldAngle, "to",
									newAngle);
							log.debug("on Link", w);
							log.debug("Corrected coord ", cConnection, "to",
									bestCoord);
						}
						cConnection = bestCoord;
					}
				}
				
				// create the new way with identical tags
				w.getPoints().add(i,cConnection);
				Way  precedingWay = new Way(w.getOriginalId(), new ArrayList<Coord>(w.getPoints().subList(0, i+1)));
				precedingWay.setFakeId();
				precedingWay.copyTags(w);
				
				saver.addWay(precedingWay);
				
				// remove the points of the new way from the old way
				removePointsFromWay(w, 0, i);
				registerPointsOfWay(precedingWay);

				// check and update relations so that they use the new way if appropriate
				changeWayIdInRelations(w, precedingWay);

				// return the split way
				return precedingWay;			
			} 		
			lastC = c;
		}
		
		// way too short
		return null;
	}
	
	/**
	 * Retrieve a list of all Coords that are the direct neighbours of
	 * the given Coord. A neighbours latitude and longitude does not differ
	 * more than one Garmin unit from the given Coord.
	 * @param c the Coord for which the neighbours should be retrieved.
	 * @return all neighbours of c
	 */
	private List<Coord> getDirectNeighbours(Coord c) {
		List<Coord> neighbours = new ArrayList<Coord>(8);
		for (int dLat = -1; dLat<2; dLat++) {
			for (int dLon = -1; dLon < 2; dLon++) {
				if (dLat == 0 && dLon == 0) {
					continue;
				}
				neighbours.add(new Coord(c.getLatitude()+dLat, c.getLongitude()+dLon));//TODO: move to Coord class?
			}
		}
		return neighbours;
	}
	
	/**
	 * Retrieves if the given node is tagged as motorway exit. So it must contain at least the tags
	 * highway=motorway_junction and one of the tags ref, name or exit_to.
	 * @param node the node to check
	 * @return <code>true</code> the node is a motorway exit, <code>false</code> the node is not a 
	 * 		motorway exit  
	 */
	private boolean isTaggedAsExit(Node node) {
		if ("motorway_junction".equals(node.getTag("highway")) == false) {
			return false;
		}
		return node.getTag("ref") != null || 
				(nameFinder.getName(node) != null) || 
				node.getTag("exit_to") != null;
	}
	
	/**
	 * Retrieve all nodes that are connected to the given node either in
	 * driving direction or reverse.
	 * @param node a coord
	 * @param drivingDirection <code>true</code> driving direction; <code>false</code> reverse direction
	 * @return a list of all coords an the connection ways
	 */
	private List<Entry<Coord, Way>> getNextNodes(Coord node, boolean drivingDirection) {
		List<Entry<Coord, Way>> nextNodes = new ArrayList<Entry<Coord, Way>>();
		
		Set<Way> connectedWays = wayNodes.get(node);
		for (Way w : connectedWays) {
			// get the index of the node
			int index = w.getPoints().indexOf(node);
			if (index < 0) {
				// this should not happen
				log.error("Cannot find node "+node+" in way "+w);
				continue;
			}
			
			boolean oneWayDirection = isOnewayInDirection(w);
			// calc the index of the next node
			index += (drivingDirection ? 1 : -1) * (oneWayDirection ? 1 : -1);
			
			if (index >= 0 && index < w.getPoints().size()) {
				nextNodes.add(new AbstractMap.SimpleEntry<Coord, Way>(w.getPoints().get(index), w));
			}
		}
		
		return nextNodes;
	}
	
	/**
	 * Cuts motorway_link and trunk_link ways into three parts to be able to get
	 * a hint on Garmin GPS. This happens if the the option process-exits is set
	 * and the way is connected to an exit node (highway=motorway_junction)
	 * and/or the option process-destination is set and the destination tag is
	 * set. The mid part way is tagged additionally with the following tags:
	 * <ul>
	 * <li>mkgmap:dest_hint=* (for destinations)</li>
	 * <li>mkgmap:exit_hint=true (for exits)</li>
	 * <li>mkgmap:exit_hint_ref: Tagged with the ref tag value of the motorway
	 * junction node</li>
	 * <li>mkgmap:exit_hint_exit_to: Tagged with the exit_to tag value of the
	 * motorway junction node</li>
	 * <li>mkgmap:exit_hint_name: Tagged with the name tag value of the motorway
	 * junction node</li>
	 * </ul>
	 * Style implementors can use the common Garmin code 0x09 for motorway_links
	 * and any other routable id (except 0x08 and 0x09) for the links with
	 * mkgmap:exit_hint=true and/or mkgmap:dest_hint=*. The naming of this
	 * middle way can be typically assigned from destination, ref, destination:ref, 
	 * mkgmap:exit_hint_ref, mkgmap:exit_hint_name and/or mkgmap:exit_hint_exit_to.
	 */
	private void processWays() {
		// remove the adjacent links from the destinationLinkWays list
		// to avoid duplicate dest_hints
		Queue<Way> linksWithDestination = new ArrayDeque<Way>();
		linksWithDestination.addAll(destinationLinkWays.values());
		log.debug(destinationLinkWays.size(),"links with destination tag");
		while (linksWithDestination.isEmpty()== false) {
			Way linkWay = linksWithDestination.poll();
			String destination = linkWay.getTag("mkgmap:dest_hint_work");
			if (log.isDebugEnabled())
				log.debug("Check way",linkWay.getId(),linkWay.toTagString());
			
			// Retrieve all adjacent ways of the current link
			Coord c = linkWay.getPoints().get(linkWay.getPoints().size()-1);
			if (isOnewayOppositeDirection(linkWay)) {
				c = linkWay.getPoints().get(0);
			}
			
			Set<Way> nextWays = adjacentWays.get(c);
			if (nextWays != null) {
				for (Way connectedWay : nextWays) {
					String nextDest = connectedWay.getTag("mkgmap:dest_hint_work");
					
					if (log.isDebugEnabled())
						log.debug("Followed by",connectedWay.getId(),connectedWay.toTagString());

					// remove the way from destination handling only if both ways are connected with start/end points
					// otherwise it is a crossroads and therefore both ways need to be handled
					boolean startEndConnection = connectedWay.getPoints().isEmpty()==false && connectedWay.getPoints().get(0).equals(c);
					if (startEndConnection && connectedWay.equals(linkWay) == false 
							&& connectedWay.getTag("highway").endsWith("_link")
							&& destination.equals(nextDest)) {
						// do not use this way because there is another link before that with the same destination
						destinationLinkWays.remove(connectedWay.getId());
						if (log.isDebugEnabled())
							log.debug("Removed",connectedWay.getId(),connectedWay.toTagString());
					}
				}
			}
		}
		log.debug(destinationLinkWays.size(),"links with destination tag after cleanup");
		
		if (processExits) {
			// collect all nodes of highway=motorway/trunk ways so that we can check if an exit node
			// belongs to a motorway/trunk or is a "subexit" within a motorway/trunk junction
			
			Map<String, Set<Coord>> highwayCoords = new LinkedHashMap<>();
			for (String type : highwayTypes){
				highwayCoords.put(type, new HashSet<Coord>());
			}
			for (Way w : saver.getWays().values()) {
				String highwayTag = w.getTag("highway");
				if (highwayTag == null)
					continue;
				if (highwayTypes.contains(highwayTag)){
					Set<Coord> set = highwayCoords.get(highwayTag);
					set.addAll(w.getPoints());
				}
			}	
			
			// get all nodes tagged with highway=motorway_junction
			for (Node exitNode : saver.getNodes().values()) {
				if (isTaggedAsExit(exitNode) && saver.getBoundingBox().contains(exitNode.getLocation())) {
					String expectedHighwayTag = null;
					for (Entry<String, Set<Coord>> entry : highwayCoords.entrySet()){
						if (entry.getValue().contains(exitNode.getLocation())){
							expectedHighwayTag = entry.getKey();
							break;
						}
					}
					if (expectedHighwayTag == null){
						// use exits only if they are located on a motorway or trunk
						if (log.isDebugEnabled())
							log.debug("Skip non highway exit:", exitNode.toBrowseURL(), exitNode.toTagString());
						continue;
					}
					// retrieve all ways with this exit node
					Set<Way> exitWays = adjacentWays.get(exitNode.getLocation());
					if (exitWays==null) {
						log.debug("Exit node", exitNode, "has no connected ways. Skip it.");
						continue;
					}
				
					// retrieve the next node on the highway to be able to check if 
					// the inserted node has the correct orientation 
					List<Entry<Coord, Way>> nextNodes = getNextNodes(exitNode.getLocation(), true);
					Coord nextHighwayNode = null;
					int countMatches = 0;
					for (Entry<Coord, Way> nextNode : nextNodes) {
						if (expectedHighwayTag.equals(nextNode.getValue().getTag("highway"))) {
							nextHighwayNode = nextNode.getKey();
							countMatches++;
						} 	
					}
					if (countMatches > 1){
						// may happen when the highway is a link which splits further into two or more links
						// ignore the node 
						nextHighwayNode = null;
					}
				
					// use link ways only
					for (Way w : exitWays) {
						destinationLinkWays.remove(w.getId());
						if (isNotOneway(w)) {
							log.warn("Ignore way",w,"because it is not oneway");
							continue;
						}
						if (w.isViaWay()){
							log.warn("Ignore way",w,"because it is a via way in a restriction  relation");
							continue;
						}
						
						String highwayLinkTag = w.getTag("highway");
						if (highwayLinkTag.endsWith("_link")) {
							log.debug("Try to cut",highwayLinkTag, w, "into three parts for giving hint to exit", exitNode);
							// calc the way length to decide how to cut the way
							double wayLength = getLength(w);
							if (wayLength < 10 && w.getPoints().size() < 3) {
								log.info("Way", w, "is too short (", wayLength," m) to cut it into several pieces. Cannot place exit hint.");
								continue;
							}
							
							
							// now create three parts:
							// wayPart1: original tags only
							// hintWay: original tags plus the mkgmap:exit_hint* tags
							// w: rest of the original way 
							
							double cut1 =  Math.min(wayLength/2,20.0);
							double cut2 = Math.min(wayLength, 100);
							Way wayPart1 = cutoffWay(w,cut1, cut2, exitNode.getLocation(), nextHighwayNode);
							if (wayPart1 == null) {
								log.info("Way", w, "is too short to cut at least ",cut1,"m from it. Cannot create exit hint.");
								continue;
							} else {
								if (log.isDebugEnabled())
									log.debug("Cut off way", wayPart1, wayPart1.toTagString());
							}
							
							Way hintWay = w;
							if (wayLength > 50) {
								hintWay = cutoffWay(w, 10.0, 50.0, exitNode.getLocation(), nextHighwayNode);
							}
							if (hintWay == null) {
								log.info("Way", w, "is too short to cut at least 20m from it. Cannot create exit hint.");
							} else {
								hintWay.addTag("mkgmap:exit_hint", "true");
								if (processDestinations) {
									String hint = hintWay.getTag("mkgmap:dest_hint_work");
									if (hint != null){
										hintWay.deleteTag("mkgmap:dest_hint_work");
										hintWay.addTag("mkgmap:dest_hint", hint);
									}
								}
								if (exitNode.getTag("ref") != null)
									hintWay.addTag("mkgmap:exit_hint_ref", exitNode.getTag("ref"));
								if (countMatches == 1){
									if (exitNode.getTag("exit_to") != null){
										hintWay.addTag("mkgmap:exit_hint_exit_to", exitNode.getTag("exit_to"));
									}
								}
								if (nameFinder.getName(exitNode) != null){
									hintWay.addTag("mkgmap:exit_hint_name", nameFinder.getName(exitNode));
								}
								
								if (log.isInfoEnabled())
									log.info("Cut off exit hint way", hintWay, hintWay.toTagString());
							}
						}
					}
				}
			}
		}
		
		if (processDestinations) {
			// use link ways only
			while (destinationLinkWays.isEmpty() == false) {
				Way w = destinationLinkWays.values().iterator().next();
				destinationLinkWays.remove(w.getId());
				if (isNotOneway(w)) {
					log.warn("Ignore way",w,"because it is not oneway");
					continue;
				}
				if (w.isViaWay()){
					log.warn("Ignore way",w,"because it is a via way in a restriction  relation");
					continue;
				}
				
				String highwayLinkTag = w.getTag("highway");
				if (highwayLinkTag.endsWith("_link")) {
					log.debug("Try to cut",highwayLinkTag, w, "into three parts for giving hint");
	
					Coord firstNode = w.getPoints().get(0);
					Coord secondNode = w.getPoints().get(1);
					// retrieve the next node on the highway to be able to check if 
					// the inserted node has the correct orientation 
					List<Entry<Coord, Way>> nextNodes = getNextNodes(firstNode, true);
					Coord nextHighwayNode = null;
					double angle = Double.MAX_VALUE;
					for (Entry<Coord, Way> nextNode : nextNodes) {
						if (nextNode.getValue().equals(w)) {
							continue;
						}
						double thisAngle = getAngle(firstNode, secondNode, nextNode.getKey());
						if (Math.abs(thisAngle) < angle) {
							angle = Math.abs(thisAngle);
							nextHighwayNode = nextNode.getKey();
						}
					}
					
					// calc the way length to decide how to cut the way
					double wayLength = getLength(w);
					if (wayLength < 10) {
						log.info("Way", w, "is too short (", wayLength," m) to cut it into several pieces. Cannot place destination hint.");
						continue;
					}

					// now create three parts:
					// wayPart1: original tags only
					// hintWay: original tags plus the mkgmap:exit_hint* tags
					// w: rest of the original way 
					
					double cut1 = Math.min(wayLength/2, 20.0);
					double cut2 = Math.min(wayLength, 100);
					Way wayPart1 = cutoffWay(w, cut1, cut2, firstNode, nextHighwayNode);
					if (wayPart1 == null) {
						log.info("Way", w, "is too short to cut at least 10m from it. Cannot create destination hint.");
						continue;
					} else {
						if (log.isDebugEnabled())
							log.debug("Cut off way", wayPart1, wayPart1.toTagString());
					}
					
					Way hintWay = w;
					if (wayLength > 50) {
						hintWay = cutoffWay(w, 10.0, 50.0, firstNode, nextHighwayNode);
					}
					if (hintWay == null) {
						log.info("Way", w, "is too short to cut at least 20m from it. Cannot create destination hint.");
					} else {
						String hint = hintWay.getTag("mkgmap:dest_hint_work");
						if (hint != null){
							hintWay.deleteTag("mkgmap:dest_hint_work");
							hintWay.addTag("mkgmap:dest_hint", hint);
						} else {
							log.error("Internal error in process_destination with way",hintWay);
						}
						
						if (log.isInfoEnabled())
							log.info("Cut off exit hint way", hintWay, hintWay.toTagString());
					}
				}
			}
		}
	}
	
	/**
	 * Retrieves the angle in clockwise direction between the line (cCenter, c1)
	 * and the line (cCenter, c2). 
	 * @param cCenter the common point of both lines
	 * @param c1 point of the first line
	 * @param c2 point of the second line
	 * @return the angle [-180; 180]
	 */
	private double getAngle(Coord cCenter, Coord c1, Coord c2)
	{
	    double dx1 = c1.getLongitude() - cCenter.getLongitude();
	    double dy1 = -(c1.getLatitude() - cCenter.getLatitude());

	    double dx2 = c2.getLongitude() - cCenter.getLongitude();
	    double dy2 = -(c2.getLatitude() - cCenter.getLatitude());

	    double inRads1 = Math.atan2(dy1,dx1);
	    double inRads2 = Math.atan2(dy2,dx2);

	    return Math.toDegrees(inRads2) - Math.toDegrees(inRads1);
	}

	/**
	 * Cleans all internal data that is no longer used after the hook has been processed.
	 */
	private void cleanup() {
		adjacentWays = null;
		wayNodes = null;
		destinationLinkWays = null;
		linkTypes = null;
		saver = null;
		nameFinder = null;
	}

	public Set<String> getUsedTags() {
		if (!(processDestinations || processExits))
			return Collections.emptySet();
		// When processing destinations also load the destination:lanes,forward and backward tag 
		// to be able to copy the value to the destination tag
		// Do not load destination because it makes sense only if the tag is
		// referenced in the style file
		Set<String> tags = new HashSet<String>();
		tags.add("highway");
		tags.add("destination");
		tags.add("destination:lanes");
		tags.add("destination:lanes:forward");
		tags.add("destination:lanes:backward");
		tags.add("destination:forward");
		tags.add("destination:backward");
		tags.add("destination:street");
		if (processExits){
			tags.add("exit_to");
			tags.add("ref");
		}
		return tags;
	}	

	public void end() {
		log.info("LinkDestinationHook started");

		retrieveWays();
		
		if (processExits || processDestinations)
			processWays();
		
		cleanup();

		log.info("LinkDestinationHook finished");
	}

	/**
	 * Retrieves if the given way is tagged as oneway in the direction of the way.
	 * @param w the way
	 * @return <code>true</code> way is oneway
	 */
	private boolean isOnewayInDirection(Way w) {
		if (w.tagIsLikeYes("oneway")) {
			return true;
		}
		
		// check if oneway is set implicitly by the highway type (motorway and motorway_link)
		String onewayTag = w.getTag("oneway");
		String highwayTag = w.getTag("highway");
		if (onewayTag == null && highwayTag != null
				&& (highwayTag.equals("motorway") || highwayTag.equals("motorway_link"))) {
			return true;
		}
		return false;
	}

	/**
	 * Retrieves if the given way is tagged as oneway but in opposite direction of the way.
	 * @param w the way
	 * @return <code>true</code> way is oneway in opposite direction
	 */
	private boolean isOnewayOppositeDirection(Way w) {
		return "-1".equals(w.getTag("oneway"));
	}

	/**
	 * Retrieves if the given way is not oneway.
	 * @param w the way
	 * @return <code>true</code> way is not oneway
	 */
	private boolean isNotOneway(Way w) {
		return "no".equals(w.getTag("oneway")) || 
				(isOnewayInDirection(w) == false
				 && isOnewayOppositeDirection(w) == false);
	}
	
	/** Private length function without caching */
	private LengthFunction length = new LengthFunction() {
		public boolean isCached() {
			return false;
		}
	};
	
	/**
	 * Retrieve the length of the given way.
	 * @param w way
	 * @return length in m
	 */
	private double getLength(Way w) {
		String lengthValue = length.value(w);
		try {
			return Math.round(Double.valueOf(lengthValue));
		} catch (Exception exp) {
			return 0;
		}
	}
	
}
