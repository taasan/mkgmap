/*
 * Copyright (C) 2011-2014.
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

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.Java2DConverter;

/**
 * Representation of an OSM Multipolygon Relation.<br/>
 * The different way of the multipolygon are joined to polygons and inner
 * polygons are cut out from the outer polygons.
 * 
 * @author WanMil
 */
public class MultiPolygonRelation extends Relation {
	private static final Logger log = Logger.getLogger(MultiPolygonRelation.class);

	public static final String STYLE_FILTER_TAG = "mkgmap:stylefilter";
	public static final String STYLE_FILTER_LINE = "polyline";
	public static final String STYLE_FILTER_POLYGON = "polygon";
	
	/** A tag that is set with value true on each polygon that is created by the mp processing */
	public static final String MP_CREATED_TAG = "mkgmap:mp_created";
	
	private final Map<Long, Way> tileWayMap;
	private final Map<Long, String> roleMap = new HashMap<>();
 
	private Map<Long, Way> mpPolygons = new LinkedHashMap<>();
	
	
	protected ArrayList<BitSet> containsMatrix;
	protected ArrayList<JoinedWay> polygons;
	protected Set<JoinedWay> intersectingPolygons;
	
	protected double largestSize;
	protected JoinedWay largestOuterPolygon;
	
	protected Set<Way> outerWaysForLineTagging;
	protected Map<String, String> outerTags;

	private final uk.me.parabola.imgfmt.app.Area tileBounds;
	private Area tileArea;
	
	private Coord cOfG = null;
	
	// the sum of all outer polygons area size 
	private double mpAreaSize = 0;
	
	/** 
	 * A point that has a lower or equal squared distance from 
	 * a line is treated as if it lies one the line.<br/>
	 * 1.0d is very exact. 2.0d covers rounding problems when converting
	 * OSM locations to mkgmap internal format. A larger value 
	 * is more tolerant against imprecise OSM data.
	 */
	private static final double OVERLAP_TOLERANCE_DISTANCE = 2.0d;
	
	/**
	 * Create an instance based on an existing relation. We need to do this
	 * because the type of the relation is not known until after all its tags
	 * are read in.
	 * 
	 * @param other
	 *            The relation to base this one on.
	 * @param wayMap
	 *            Map of all ways.
	 * @param bbox
	 *            The bounding box of the tile
	 */
	public MultiPolygonRelation(Relation other, Map<Long, Way> wayMap,
			uk.me.parabola.imgfmt.app.Area bbox) {
		this.tileWayMap = wayMap;
		this.tileBounds = bbox;
		// create an Area for the bbox to clip the polygons
		tileArea = Java2DConverter.createBoundsArea(tileBounds); 

		setId(other.getId());
		copyTags(other);
		this.setTagsIncomplete(other.getTagsIncomplete());

		if (log.isDebugEnabled()) {
			log.debug("Construct multipolygon", toBrowseURL(), toTagString());
		}

		for (Map.Entry<String, Element> pair : other.getElements()) {
			String role = pair.getKey();
			Element el = pair.getValue();
			if (log.isDebugEnabled()) {
				log.debug(" ", role, el.toBrowseURL(), el.toTagString());
			}
			if (roleMap.containsKey(el.getId()) )
				log.warn("repeated member with id ", el.getId(), "in multipolygon relation",this.getId(),"is ignored");
			else {
				addElement(role, el);
				roleMap.put(el.getId(), role);
			}
		}
	}
	

	/**
	 * Retrieves the center point of this multipolygon. This is set in the 
	 * {@link #processElements()} methods so it returns <code>null</code> 
	 * before that. It can also return <code>null</code> in case the 
	 * multipolygon could not be processed.<br/>
	 * The returned point may lie outside the multipolygon area. It is just
	 * the center point of it.
	 * 
	 * @return the center point of this multipolygon (maybe <code>null</code>)
	 */
	public Coord getCofG() {
		return cOfG;
	}
	
	/**
	 * Retrieves the mp role of the given element.
	 * 
	 * @param element
	 *            the element
	 * @return the role of the element
	 */
	protected String getRole(Element element) {
		String role = roleMap.get(element.getId());
		if (role != null && ("outer".equals(role) || "inner".equals(role))) {
			return role;
		}

		return null;
	}

	/**
	 * Try to join the two ways.
	 * 
	 * @param joinWay
	 *            the way to which tempWay is added in case both ways could be
	 *            joined and checkOnly is false.
	 * @param tempWay
	 *            the way to be added to joinWay
	 * @param checkOnly
	 *            <code>true</code> checks only and does not perform the join
	 *            operation
	 * @return <code>true</code> if tempWay way is (or could be) joined to
	 *         joinWay
	 */
	private static boolean joinWays(JoinedWay joinWay, JoinedWay tempWay, boolean checkOnly) {
		boolean reverseTempWay = false;
		int insIdx = -1;
		int firstTmpIdx = 1;
		boolean joinable = false;
		
		// use == or equals as comparator??
		if (joinWay.getFirstPoint() == tempWay.getFirstPoint()) {
			insIdx = 0;
			reverseTempWay = true;
			firstTmpIdx = 1;
			joinable = true;
		} else if (joinWay.getLastPoint() == tempWay.getFirstPoint()) {
			insIdx = joinWay.getPoints().size();
			reverseTempWay = false;
			firstTmpIdx = 1;
			joinable = true;
		} else if (joinWay.getFirstPoint() == tempWay.getLastPoint()) {
			insIdx = 0; 
			reverseTempWay = false;
			firstTmpIdx = 0;
			joinable = true;
		} else if (joinWay.getLastPoint() == tempWay.getLastPoint()) {
			insIdx = joinWay.getPoints().size();
			reverseTempWay = true;
			firstTmpIdx = 0;
			joinable = true;
		}
		
		if (!checkOnly && joinable){
			int lastIdx = tempWay.getPoints().size();
			if (firstTmpIdx == 0) {
				// the last temp point is already contained in the joined way - do not copy it
				lastIdx--;
			}
					
			List<Coord> tempCoords = tempWay.getPoints().subList(firstTmpIdx,lastIdx);
			
			if (reverseTempWay) {
				// the remp coords need to be reversed so copy the list
				tempCoords = new ArrayList<>(tempCoords);
				// and reverse it
				Collections.reverse(tempCoords);
			}
			
			joinWay.getPoints().addAll(insIdx, tempCoords);
			joinWay.addWay(tempWay);
		}
		return joinable;
	}

	/**
	 * Combine a list of way segments to a list of maximally joined ways
	 * 
	 * @param segments
	 *            a list of closed or unclosed ways
	 * @return a list of closed ways
	 */
	protected ArrayList<JoinedWay> joinWays(List<Way> segments) {
		// TODO check if the closed polygon is valid and implement a
		// backtracking algorithm to get other combinations

		ArrayList<JoinedWay> joinedWays = new ArrayList<>();
		if (segments == null || segments.isEmpty()) {
			return joinedWays;
		}

		// go through all segments and categorize them to closed and unclosed
		// list
		ArrayList<JoinedWay> unclosedWays = new ArrayList<>();
		for (Way orgSegment : segments) {
			JoinedWay jw = new JoinedWay(orgSegment);
			roleMap.put(jw.getId(), getRole(orgSegment));
			if (orgSegment.isClosed()) {
				if (orgSegment.isComplete() == false) {
					// the way is closed in planet but some points are missing in this tile
					// we can close it artificially
					if (log.isDebugEnabled())
						log.debug("Close incomplete but closed polygon:",orgSegment);
					jw.closeWayArtificially();
				}
				assert 	jw.hasIdenticalEndPoints() : "way is not closed";
				joinedWays.add(jw);
			} else {
				unclosedWays.add(jw);
			}
		}

		while (!unclosedWays.isEmpty()) {
			JoinedWay joinWay = unclosedWays.remove(0);

			// check if the current way is already closed or if it is the last
			// way
			if (joinWay.hasIdenticalEndPoints() || unclosedWays.isEmpty()) {
				joinedWays.add(joinWay);
				continue;
			}

			boolean joined = false;

			// if we have a way that could be joined but which has a wrong role
			// then store it here and check in the end if it's working
			JoinedWay wrongRoleWay = null;
			String joinRole = getRole(joinWay);

			// go through all ways and check if there is a way that can be
			// joined with it
			// in this case join the two ways
			// => add all points of tempWay to joinWay, remove tempWay and put
			// joinWay to the beginning of the list
			// (not optimal but understandable - can be optimized later)
			for (JoinedWay tempWay : unclosedWays) {
				if (tempWay.hasIdenticalEndPoints()) {
					continue;
				}

				String tempRole = getRole(tempWay);
				// if a role is not 'inner' or 'outer' then it is used as
				// universal
				// check if the roles of the ways are matching
				if ((!"outer".equals(joinRole) && !"inner"
						.equals(joinRole))
						|| (!"outer".equals(tempRole) && !"inner"
						.equals(tempRole))
						|| (joinRole != null && joinRole.equals(tempRole))) {
					// the roles are matching => try to join both ways
					joined = joinWays(joinWay, tempWay, false);
				} else {
					// the roles are not matching => test if both ways would
					// join

					// as long as we don't have an alternative way with wrong
					// role
					// or if the alternative way is shorter then check if
					// the way with the wrong role could be joined
					if (wrongRoleWay == null
							|| wrongRoleWay.getPoints().size() < tempWay
									.getPoints().size()) {
						if (joinWays(joinWay, tempWay, true)) {
							// save this way => maybe we will use it in the end
							// if we don't find any other way
							wrongRoleWay = tempWay;
						}
					}
				}

				if (joined) {
					// we have joined the way
					unclosedWays.remove(tempWay);
					break;
				}
			}

			if (!joined && wrongRoleWay != null) {

				log.warn("Join ways with different roles. Multipolygon: "
						+ toBrowseURL());
				log.warn("Way1 Role:", getRole(joinWay));
				logWayURLs(Level.WARNING, "-", joinWay);
				log.warn("Way2 Role:", getRole(wrongRoleWay));
				logWayURLs(Level.WARNING, "-", wrongRoleWay);

				joined = joinWays(joinWay, wrongRoleWay, false);
				if (joined) {
					// we have joined the way
					unclosedWays.remove(wrongRoleWay);
					break;
				}
			}

			if (joined) {
				if (joinWay.hasIdenticalEndPoints()) {
					// it's closed => don't process it again
					joinedWays.add(joinWay);
				} else if (unclosedWays.isEmpty()) {
					// no more ways to join with
					// it's not closed but we cannot join it more
					joinedWays.add(joinWay);
				} else {
					// it is not yet closed => process it once again
					unclosedWays.add(0, joinWay);
				}
			} else {
				// it's not closed but we cannot join it more
				joinedWays.add(joinWay);
			}
		}

		return joinedWays;
	}

	/**
	 * Try to close all unclosed ways in the given list of ways.
	 * 
	 * @param wayList
	 *            a list of ways
	 */
	protected void closeWays(ArrayList<JoinedWay> wayList, double maxCloseDist) {
		for (JoinedWay way : wayList) {
			if (way.hasIdenticalEndPoints() || way.getPoints().size() < 3) {
				continue;
			}
			Coord p1 = way.getFirstPoint();
			Coord p2 = way.getLastPoint();

			if (tileBounds.insideBoundary(p1) == false
					&& tileBounds.insideBoundary(p2) == false) {
				// both points lie outside the bbox or on the bbox

				// check if both points are on the same side of the bounding box
				if ((p1.getLatitude() <= tileBounds.getMinLat() && p2.getLatitude() <= tileBounds
						.getMinLat())
						|| (p1.getLatitude() >= tileBounds.getMaxLat() && p2
								.getLatitude() >= tileBounds.getMaxLat())
						|| (p1.getLongitude() <= tileBounds.getMinLong() && p2
								.getLongitude() <= tileBounds.getMinLong())
						|| (p1.getLongitude() >= tileBounds.getMaxLong() && p2
								.getLongitude() >= tileBounds.getMaxLong())) {
					// they are on the same side outside of the bbox
					// so just close them without worrying about if
					// they intersect itself because the intersection also
					// is outside the bbox
					way.closeWayArtificially();
					log.info("Endpoints of way", way,
							"are both outside the bbox. Closing it directly.");
					continue;
				}
			}
			
			Line2D closingLine = new Line2D.Float(p1.getLongitude(), p1
					.getLatitude(), p2.getLongitude(), p2.getLatitude());

			boolean intersects = false;
			Coord lastPoint = null;
			// don't use the first and the last point
			// the closing line can intersect only in one point or complete.
			// Both isn't interesting for this check
			for (Coord thisPoint : way.getPoints().subList(1,
					way.getPoints().size() - 1)) {
				if (lastPoint != null) {
					if (closingLine.intersectsLine(lastPoint.getLongitude(),
							lastPoint.getLatitude(), thisPoint.getLongitude(),
							thisPoint.getLatitude())) {
						intersects = true;
						break;
					}
				}
				lastPoint = thisPoint;
			}

			if (!intersects) {
				// close the polygon
				// the new way segment does not intersect the rest of the polygon
				boolean doClose = true;
				if (maxCloseDist > 0) {
					// calc the distance to close
					double closeDist = way.getFirstPoint().distance(way.getLastPoint());
					doClose = closeDist < maxCloseDist;
				}
				if (doClose) {
					if (log.isInfoEnabled()) {
						log.info("Closing way", way);
						log.info("from", way.getFirstPoint().toOSMURL());
						log.info("to", way.getLastPoint().toOSMURL());
					}
					// mark this ways as artificially closed
					way.closeWayArtificially();
				}
			}
		}
	}

	
	protected static class ConnectionData {
		public Coord c1;
		public Coord c2;
		public JoinedWay w1;
		public JoinedWay w2;
		// sometimes the connection of both points cannot be done directly but with an intermediate point 
		public Coord imC;
		public double distance;
		
		public ConnectionData() {
			
		}
	}
	
	protected boolean connectUnclosedWays(List<JoinedWay> allWays) {
		List<JoinedWay> unclosed = new ArrayList<>();

		for (JoinedWay w : allWays) {
			if (w.hasIdenticalEndPoints() == false) {
				unclosed.add(w);
			}
		}
		// try to connect ways lying outside or on the bbox
		if (unclosed.size() >= 2) {
			log.debug("Checking",unclosed.size(),"unclosed ways for connections outside the bbox");
			Map<Coord, JoinedWay> outOfBboxPoints = new IdentityHashMap<>();
			
			// check all ways for endpoints outside or on the bbox
			for (JoinedWay w : unclosed) {
				Coord c1 = w.getFirstPoint();
				Coord c2 = w.getLastPoint();
				if (tileBounds.insideBoundary(c1)==false) {
					log.debug("Point",c1,"of way",w.getId(),"outside bbox");
					outOfBboxPoints.put(c1, w);
				}

				if (tileBounds.insideBoundary(c2)==false) {
					log.debug("Point",c2,"of way",w.getId(),"outside bbox");
					outOfBboxPoints.put(c2, w);
				}
			}
			
			if (outOfBboxPoints.size() < 2) {
				log.debug(outOfBboxPoints.size(),"point outside the bbox. No connection possible.");
				return false;
			}
			
			List<ConnectionData> coordPairs = new ArrayList<>();
			ArrayList<Coord> coords = new ArrayList<>(outOfBboxPoints.keySet());
			for (int i = 0; i < coords.size(); i++) {
				for (int j = i + 1; j < coords.size(); j++) {
					ConnectionData cd = new ConnectionData();
					cd.c1 = coords.get(i);
					cd.c2 = coords.get(j);
					cd.w1 = outOfBboxPoints.get(cd.c1);					
					cd.w2 = outOfBboxPoints.get(cd.c2);					
					
					if (lineCutsBbox(cd.c1, cd.c2 )) {
						// Check if the way can be closed with one additional point
						// outside the bounding box.
						// The additional point is combination of the coords of both endpoints.
						// It works if the lines from the endpoints to the additional point does
						// not cut the bounding box.
						// This can be removed when the splitter guarantees to provide logical complete
						// multi-polygons.
						Coord edgePoint1 = new Coord(cd.c1.getLatitude(), cd.c2
								.getLongitude());
						Coord edgePoint2 = new Coord(cd.c2.getLatitude(), cd.c1
								.getLongitude());

						if (lineCutsBbox(cd.c1, edgePoint1) == false
								&& lineCutsBbox(edgePoint1, cd.c2) == false) {
							cd.imC = edgePoint1;
						} else if (lineCutsBbox(cd.c1, edgePoint2) == false
								&& lineCutsBbox(edgePoint2, cd.c2) == false) {
							cd.imC = edgePoint1;
						} else {
							// both endpoints are on opposite sides of the bounding box
							// automatically closing such points would create wrong polygons in most cases
							continue;
						}
						cd.distance = cd.c1.distance(cd.imC) + cd.imC.distance(cd.c2);
					} else {
						cd.distance = cd.c1.distance(cd.c2);
					}
					coordPairs.add(cd);
				}
			}
			
			if (coordPairs.isEmpty()) {
				log.debug("All potential connections cross the bbox. No connection possible.");
				return false;
			} else {
				// retrieve the connection with the minimum distance
				ConnectionData minCon = Collections.min(coordPairs,
						(o1, o2) -> Double.compare(o1.distance, o2.distance));

				if (minCon.w1 == minCon.w2) {
					log.debug("Close a gap in way",minCon.w1);
					if (minCon.imC != null)
						minCon.w1.getPoints().add(minCon.imC);
					minCon.w1.closeWayArtificially();
				} else {
					log.debug("Connect", minCon.w1, "with", minCon.w2);

					if (minCon.w1.getFirstPoint() == minCon.c1) {
						Collections.reverse(minCon.w1.getPoints());
					}
					if (minCon.w2.getFirstPoint() != minCon.c2) {
						Collections.reverse(minCon.w2.getPoints());
					}

					minCon.w1.getPoints().addAll(minCon.w2.getPoints());
					minCon.w1.addWay(minCon.w2);
					allWays.remove(minCon.w2);
					return true;
				}
			}
		}
		return false;
	}

	
	/**
	 * Removes all ways non closed ways from the given list (
	 * <code>{@link Way#hasIdenticalEndPoints()} == false</code>)
	 * 
	 * @param wayList
	 *            list of ways
	 */
	protected void removeUnclosedWays(ArrayList<JoinedWay> wayList) {
		Iterator<JoinedWay> it = wayList.iterator();
		boolean firstWarn = true;
		while (it.hasNext()) {
			JoinedWay tempWay = it.next();
			if (!tempWay.hasIdenticalEndPoints()) {
				// warn only if the way intersects the bounding box 
				boolean inBbox = tempWay.intersects(tileBounds);
				if (inBbox) {
					if (firstWarn) {
						log.warn(
							"Cannot join the following ways to closed polygons. Multipolygon",
							toBrowseURL(), toTagString());
						firstWarn = false;
					}
					logWayURLs(Level.WARNING, "- way:", tempWay);
					logFakeWayDetails(Level.WARNING, tempWay);
				}

				it.remove();
				
				if (inBbox) {
					String role = getRole(tempWay);
					if (role == null || "".equals(role) || "outer".equals(role)) {
						// anyhow add the ways to the list for line tagging
						outerWaysForLineTagging.addAll(tempWay.getOriginalWays());
					}
				}
			}
		}
	}

	/**
	 * Removes all ways that are completely outside the bounding box. 
	 * This reduces error messages from problems on the tile bounds.
	 * @param wayList list of ways
	 */
	protected void removeWaysOutsideBbox(ArrayList<JoinedWay> wayList) {
		ListIterator<JoinedWay> wayIter = wayList.listIterator();
		while (wayIter.hasNext()) {
			JoinedWay w = wayIter.next();
			boolean remove = true;
			// check all points
			for (Coord c : w.getPoints()) {
				if (tileBounds.contains(c)) {
					// if one point is in the bounding box the way should not be removed
					remove = false;
					break;
				}
			}

			if (remove) {
				// check if the polygon contains the complete bounding box
				if (w.getBounds().contains(tileArea.getBounds())) {
					remove = false;
				}
			}
			
			if (remove) {
				if (log.isDebugEnabled()) {
					log.debug("Remove way", w.getId(),
						"because it is completely outside the bounding box.");
				}
				wayIter.remove();
			}
		}
	}

	/**
	 * Find all polygons that are not contained by any other polygon.
	 * 
	 * @param candidates
	 *            all polygons that should be checked
	 * @param roleFilter
	 *            an additional filter
	 * @return all polygon indexes that are not contained by any other polygon
	 */
	private BitSet findOutmostPolygons(BitSet candidates, BitSet roleFilter) {
		BitSet realCandidates = ((BitSet) candidates.clone());
		realCandidates.and(roleFilter);
		return findOutmostPolygons(realCandidates);
	}

	/**
	 * Finds all polygons that are not contained by any other polygons and that match
	 * to the given role. All polygons with index given by <var>candidates</var>
	 * are used.
	 * 
	 * @param candidates
	 *            indexes of the polygons that should be used
	 * @return the bits of all outermost polygons are set to true
	 */
	protected BitSet findOutmostPolygons(BitSet candidates) {
		BitSet outmostPolygons = new BitSet();

		// go through all candidates and check if they are contained by any
		// other candidate
		for (int candidateIndex = candidates.nextSetBit(0); candidateIndex >= 0; candidateIndex = candidates
				.nextSetBit(candidateIndex + 1)) {
			// check if the candidateIndex polygon is not contained by any
			// other candidate polygon
			boolean isOutmost = true;
			for (int otherCandidateIndex = candidates.nextSetBit(0); otherCandidateIndex >= 0; otherCandidateIndex = candidates
					.nextSetBit(otherCandidateIndex + 1)) {
				if (contains(otherCandidateIndex, candidateIndex)) {
					// candidateIndex is not an outermost polygon because it is
					// contained by the otherCandidateIndex polygon
					isOutmost = false;
					break;
				}
			}
			if (isOutmost) {
				// this is an outermost polygon
				// put it to the bitset
				outmostPolygons.set(candidateIndex);
			}
		}

		return outmostPolygons;
	}

	protected ArrayList<PolygonStatus> getPolygonStatus(BitSet outmostPolygons,
			String defaultRole) {
		ArrayList<PolygonStatus> polygonStatusList = new ArrayList<>();
		for (int polyIndex = outmostPolygons.nextSetBit(0); polyIndex >= 0; polyIndex = outmostPolygons
				.nextSetBit(polyIndex + 1)) {
			// polyIndex is the polygon that is not contained by any other
			// polygon
			JoinedWay polygon = polygons.get(polyIndex);
			String role = getRole(polygon);
			// if the role is not explicitly set use the default role
			if (role == null || "".equals(role)) {
				role = defaultRole;
			} 
			polygonStatusList.add(new PolygonStatus("outer".equals(role), polyIndex, polygon));
		}
		// sort by role and then by number of points, this improves performance
		// in the routines which add the polygons to areas
		if (polygonStatusList.size() > 2){
			polygonStatusList.sort((o1, o2) -> {
				if (o1.outer != o2.outer)
					return (o1.outer) ? -1 : 1;
				return o1.polygon.getPoints().size() - o2.polygon.getPoints().size();
			});
		}
		return polygonStatusList;
	}

	/**
	 * Creates a list of all original ways of the multipolygon. 
	 * @return all source ways
	 */
	protected List<Way> getSourceWays() {
		ArrayList<Way> allWays = new ArrayList<>();

		for (Map.Entry<String, Element> r_e : getElements()) {
			if (r_e.getValue() instanceof Way) {
				if (((Way)r_e.getValue()).getPoints().isEmpty()) {
					log.warn("Way",r_e.getValue(),"has no points and cannot be used for the multipolygon",toBrowseURL());
				} else {
					allWays.add((Way) r_e.getValue());
				}
			} else if (r_e.getValue() instanceof Node == false || 
					("admin_centre".equals(r_e.getKey()) == false && "label".equals(r_e.getKey()) == false)) {
				log.warn("Non way member in role", r_e.getKey(), r_e.getValue().toBrowseURL(),
						"in multipolygon", toBrowseURL(), toTagString());
			}
		}
		return allWays;
	}
	
	
	// unfinishedPolygons marks which polygons are not yet processed
	protected BitSet unfinishedPolygons;

	// create bitsets which polygons belong to the outer and to the inner role
	protected BitSet innerPolygons;
	protected BitSet taggedInnerPolygons;
	protected BitSet outerPolygons;
	protected BitSet taggedOuterPolygons;

	/**
	 * Process the ways in this relation. Joins way with the role "outer" Adds
	 * ways with the role "inner" to the way with the role "outer"
	 */
	public void processElements() {
		log.info("Processing multipolygon", toBrowseURL());
		
		List<Way> allWays = getSourceWays();
		
		// check if it makes sense to process the mp 
		if (isMpProcessable(allWays) == false) {
			log.info("Do not process multipolygon",getId(),"because it has no style relevant tags.");
			return;
		}

		
		// join all single ways to polygons, try to close ways and remove non closed ways 
		polygons = joinWays(allWays);
		
		outerWaysForLineTagging = new HashSet<>();
		outerTags = new HashMap<>();
		
		do {
			closeWays(polygons, getMaxCloseDist());
		} while (connectUnclosedWays(polygons));

		removeUnclosedWays(polygons);

		// now only closed ways are left => polygons only

		// check if we have at least one polygon left
		boolean hasPolygons = !polygons.isEmpty();

		removeWaysOutsideBbox(polygons);

		if (polygons.isEmpty()) {
			// do nothing
			if (log.isInfoEnabled()) {
				if (hasPolygons)
					log.info("Multipolygon", toBrowseURL(),
							"is completely outside the bounding box. It is not processed.");
				else
					log.info("Multipolygon " + toBrowseURL() + " does not contain a closed polygon.");
			}
			tagOuterWays();
			cleanup();
			return;
		}

		// the intersectingPolygons marks all intersecting/overlapping polygons
		intersectingPolygons = new HashSet<>();
		
		// check which polygons lie inside which other polygon 
		createContainsMatrix(polygons);

		// unfinishedPolygons marks which polygons are not yet processed
		unfinishedPolygons = new BitSet(polygons.size());
		unfinishedPolygons.set(0, polygons.size());

		// create bitsets which polygons belong to the outer and to the inner role
		innerPolygons = new BitSet();
		taggedInnerPolygons = new BitSet();
		outerPolygons = new BitSet();
		taggedOuterPolygons = new BitSet();
		
		int wi = 0;
		for (Way w : polygons) {
			w.setFullArea(w.getFullArea()); // trigger setting area before start cutting...
			// do like this to disguise function with side effects
			String role = getRole(w);
			if ("inner".equals(role)) {
				innerPolygons.set(wi);
				taggedInnerPolygons.set(wi);
			} else if ("outer".equals(role)) {
				outerPolygons.set(wi);
				taggedOuterPolygons.set(wi);
			} else {
				// unknown role => it could be both
				innerPolygons.set(wi);
				outerPolygons.set(wi);
			}
			wi++;
		}

		if (outerPolygons.isEmpty()) {
			log.warn("Multipolygon", toBrowseURL(),
				"does not contain any way tagged with role=outer or empty role.");
			cleanup();
			return;
		}

		Queue<PolygonStatus> polygonWorkingQueue = new LinkedBlockingQueue<>();
		BitSet nestedOuterPolygons = new BitSet();
		BitSet nestedInnerPolygons = new BitSet();

		BitSet outmostPolygons;
		BitSet outmostInnerPolygons = new BitSet();
		boolean outmostInnerFound;
		do {
			outmostInnerFound = false;
			outmostPolygons = findOutmostPolygons(unfinishedPolygons);

			if (outmostPolygons.intersects(taggedInnerPolygons)) {
				outmostInnerPolygons.or(outmostPolygons);
				outmostInnerPolygons.and(taggedInnerPolygons);

				if (log.isDebugEnabled())
					log.debug("wrong inner polygons: " + outmostInnerPolygons);
				// do not process polygons tagged with role=inner but which are
				// not contained by any other polygon
				unfinishedPolygons.andNot(outmostInnerPolygons);
				outmostPolygons.andNot(outmostInnerPolygons);
				outmostInnerFound = true;
			}
		} while (outmostInnerFound);
		
		if (!outmostPolygons.isEmpty()) {
			polygonWorkingQueue.addAll(getPolygonStatus(outmostPolygons, "outer"));
		}

		boolean outmostPolygonProcessing = true;
		
	
		while (!polygonWorkingQueue.isEmpty()) {

			// the polygon is not contained by any other unfinished polygon
			PolygonStatus currentPolygon = polygonWorkingQueue.poll();

			// this polygon is now processed and should not be used by any
			// further step
			unfinishedPolygons.clear(currentPolygon.index);

			BitSet polygonContains = new BitSet();
			polygonContains.or(containsMatrix.get(currentPolygon.index));
			// use only polygon that are contained by the polygon
			polygonContains.and(unfinishedPolygons);
			// polygonContains is the intersection of the unfinished and
			// the contained polygons

			// get the holes
			// these are all polygons that are in the main polygon
			// and that are not contained by any other polygon
			boolean holesOk;
			BitSet holeIndexes;
			do {
				holeIndexes = findOutmostPolygons(polygonContains);
				holesOk = true;

				if (currentPolygon.outer) {
					// for role=outer only role=inner is allowed
					if (holeIndexes.intersects(taggedOuterPolygons)) {
						BitSet addOuterNestedPolygons = new BitSet();
						addOuterNestedPolygons.or(holeIndexes);
						addOuterNestedPolygons.and(taggedOuterPolygons);
						nestedOuterPolygons.or(addOuterNestedPolygons);
						holeIndexes.andNot(addOuterNestedPolygons);
						// do not process them
						unfinishedPolygons.andNot(addOuterNestedPolygons);
						polygonContains.andNot(addOuterNestedPolygons);
						
						// recalculate the holes again to get all inner polygons 
						// in the nested outer polygons
						holesOk = false;
					}
				} else {
					// for role=inner both role=inner and role=outer is supported
					// although inner in inner is not officially allowed
					if (holeIndexes.intersects(taggedInnerPolygons)) {
						// process inner in inner but issue a warning later
						BitSet addInnerNestedPolygons = new BitSet();
						addInnerNestedPolygons.or(holeIndexes);
						addInnerNestedPolygons.and(taggedInnerPolygons);
						nestedInnerPolygons.or(addInnerNestedPolygons);
					}
				}
			} while (!holesOk);

			ArrayList<PolygonStatus> holes = getPolygonStatus(holeIndexes, 
				(currentPolygon.outer ? "inner" : "outer"));

			// these polygons must all be checked for holes
			polygonWorkingQueue.addAll(holes);

			if (currentPolygon.outer) {
				// add the original ways to the list of ways that get the line tags of the mp
				// the joined ways may be changed by the auto closing algorithm
				outerWaysForLineTagging.addAll(currentPolygon.polygon.getOriginalWays());
			}
			
			// calculate the size of the polygon
			double outerAreaSize = currentPolygon.polygon.getSizeOfArea();
			if (outerAreaSize > largestSize) {
				// subtract the holes
				for (PolygonStatus hole : holes) {
					outerAreaSize -= hole.polygon.getSizeOfArea();
				}
				// is it still larger than the largest known polygon?
				if (outerAreaSize > largestSize) {
					largestOuterPolygon = currentPolygon.polygon;
					largestSize = outerAreaSize;
				}
			}
			
			// check if the polygon is an outer polygon or 
			// if there are some holes
			boolean processPolygon = currentPolygon.outer
					|| (holes.isEmpty()==false);

			if (processPolygon) {
				List<Way> singularOuterPolygons;
				if (holes.isEmpty()) {
					singularOuterPolygons = Collections
							.singletonList((Way) new JoinedWay(currentPolygon.polygon));
				} else {
					List<Way> innerWays = new ArrayList<>(holes.size());
					for (PolygonStatus polygonHoleStatus : holes) {
						innerWays.add(polygonHoleStatus.polygon);
					}

					MultiPolygonCutter cutter = new MultiPolygonCutter(this, tileArea);
					singularOuterPolygons = cutter.cutOutInnerPolygons(currentPolygon.polygon, innerWays);
				}
				
				if (singularOuterPolygons.isEmpty()==false) {
					// handle the tagging 
					if (currentPolygon.outer && hasStyleRelevantTags(this)) {
						// use the tags of the multipolygon
						for (Way p : singularOuterPolygons) {
							// overwrite all tags
							p.copyTags(this);
							p.deleteTag("type");
						}
						// remove the multipolygon tags in the original ways of the current polygon
						removeTagsInOrgWays(this, currentPolygon.polygon);
					} else {
						// use the tags of the original ways
						currentPolygon.polygon.mergeTagsFromOrgWays();
						for (Way p : singularOuterPolygons) {
							// overwrite all tags
							p.copyTags(currentPolygon.polygon);
						}
						// remove the current polygon tags in its original ways
						removeTagsInOrgWays(currentPolygon.polygon, currentPolygon.polygon);
					}
				
					if (currentPolygon.outer && outmostPolygonProcessing) {
						// this is the outer most polygon - copy its tags. They will be used
						// later for tagging of the lines

						// all cut polygons have the same tags - copy them from the first polygon
						Way outerWay = singularOuterPolygons.get(0);
						for (Entry<String, String> tag : outerWay.getTagEntryIterator()) {
							outerTags.put(tag.getKey(), tag.getValue());
						}
						outmostPolygonProcessing = false;
					}
					
					long fullArea = currentPolygon.polygon.getFullArea();
					for (Way mpWay : singularOuterPolygons) {
						// put the cut out polygons to the
						// final way map
						if (log.isDebugEnabled())
							log.debug(mpWay.getId(),mpWay.toTagString());
					
						mpWay.setFullArea(fullArea);
						// mark this polygons so that only polygon style rules are applied
						mpWay.addTag(STYLE_FILTER_TAG, STYLE_FILTER_POLYGON);
						mpWay.addTag(MP_CREATED_TAG, "true");
						
						if (currentPolygon.outer) {
							mpWay.addTag("mkgmap:mp_role", "outer");
							if (isAreaSizeCalculated())
								mpAreaSize += calcAreaSize(mpWay.getPoints());
						} else {
							mpWay.addTag("mkgmap:mp_role", "inner");
						}
						
						getMpPolygons().put(mpWay.getId(), mpWay);
					}
				}
			}
		}
		
		if (log.isLoggable(Level.WARNING) && 
				(outmostInnerPolygons.cardinality()+unfinishedPolygons.cardinality()+nestedOuterPolygons.cardinality()+nestedInnerPolygons.cardinality() >= 1)) {
			log.warn("Multipolygon", toBrowseURL(), toTagString(), "contains errors.");

			BitSet outerUnusedPolys = new BitSet();
			outerUnusedPolys.or(unfinishedPolygons);
			outerUnusedPolys.or(outmostInnerPolygons);
			outerUnusedPolys.or(nestedOuterPolygons);
			outerUnusedPolys.or(nestedInnerPolygons);
			outerUnusedPolys.or(unfinishedPolygons);
			// use only the outer polygons
			outerUnusedPolys.and(outerPolygons);
			for (JoinedWay w : getWaysFromPolygonList(outerUnusedPolys)) {
				outerWaysForLineTagging.addAll(w.getOriginalWays());
			}
			
			runIntersectionCheck(unfinishedPolygons);
			runOutmostInnerPolygonCheck(outmostInnerPolygons);
			runNestedOuterPolygonCheck(nestedOuterPolygons);
			runNestedInnerPolygonCheck(nestedInnerPolygons);
			runWrongInnerPolygonCheck(unfinishedPolygons, innerPolygons);

			// we have at least one polygon that could not be processed
			// Probably we have intersecting or overlapping polygons
			// one possible reason is if the relation overlaps the tile
			// bounds
			// => issue a warning
			List<JoinedWay> lostWays = getWaysFromPolygonList(unfinishedPolygons);
			for (JoinedWay w : lostWays) {
				log.warn("Polygon", w, "is not processed due to an unknown reason.");
				logWayURLs(Level.WARNING, "-", w);
			}
		}

		if (hasStyleRelevantTags(this) == false) {
			// add tags to the multipolygon that are taken from the outer ways
			// they may be required by some hooks (e.g. Area2POIHook)
			for (Entry<String, String> tags : outerTags.entrySet()) {
				addTag(tags.getKey(), tags.getValue());
			}
		}
		String mpAreaSizeStr = null;
		if (isAreaSizeCalculated()) {
			// calculate tag value for mkgmap:cache_area_size only once
			mpAreaSizeStr = String.format(Locale.US, "%.3f", mpAreaSize);
		}
		// Go through all original outer ways, create a copy, tag them
		// with the mp tags and mark them only to be used for polyline processing
		// This enables the style file to decide if the polygon information or
		// the simple line information should be used.
		for (Way orgOuterWay : outerWaysForLineTagging) {
			Way lineTagWay =  new Way(getOriginalId(), orgOuterWay.getPoints());
			lineTagWay.setFakeId();
			lineTagWay.addTag(STYLE_FILTER_TAG, STYLE_FILTER_LINE);
			lineTagWay.addTag(MP_CREATED_TAG, "true");
			if (mpAreaSizeStr != null) {
				// assign the area size of the whole multipolygon to all outer polygons
				lineTagWay.addTag("mkgmap:cache_area_size", mpAreaSizeStr);
			}
			for (Entry<String,String> tag : outerTags.entrySet()) {
				lineTagWay.addTag(tag.getKey(), tag.getValue());
				
				// remove the tag from the original way if it has the same value
				if (tag.getValue().equals(orgOuterWay.getTag(tag.getKey()))) {
					removeTagsInOrgWays(orgOuterWay, tag.getKey());
				}
			}
	
			if (log.isDebugEnabled())
				log.debug("Add line way", lineTagWay.getId(), lineTagWay.toTagString());
			tileWayMap.put(lineTagWay.getId(), lineTagWay);
		}
		
		postProcessing();
		cleanup();
	}
	
	protected double getMaxCloseDist() {
		return -1; // 
	}


	protected void postProcessing() {
		
		if (isAreaSizeCalculated()) {
			// assign the area size of the whole multipolygon to all outer polygons
			String mpAreaSizeStr = String.format(Locale.US, "%.3f", mpAreaSize); 
			addTag("mkgmap:cache_area_size", mpAreaSizeStr);
			for (Way w : mpPolygons.values()) {
				if ("outer".equals(w.getTag("mkgmap:mp_role"))) {
					w.addTag("mkgmap:cache_area_size", mpAreaSizeStr);
				}
			}
		}

		for (Way w : mpPolygons.values()) {
			w.deleteTag("mkgmap:mp_role");
		}
		// copy all polygons created by the multipolygon algorithm to the global way map
		tileWayMap.putAll(mpPolygons);
		
		if (largestOuterPolygon != null) {
			// check if the mp contains a node with role "label" 
			for (Map.Entry<String, Element> r_e : getElements()) {
				if (r_e.getValue() instanceof Node && "label".equals(r_e.getKey())) {
					// yes => use the label node as reference point
					cOfG = ((Node)r_e.getValue()).getLocation();
					break;
				} 
			}
			
			if (cOfG == null) {
				// use the center of the largest polygon as reference point
				cOfG = largestOuterPolygon.getCofG();
			}
		}
	}
	
	private void runIntersectionCheck(BitSet unfinishedPolys) {
		if (intersectingPolygons.isEmpty()) {
			// nothing to do
			return;
		}

		log.warn("Some polygons are intersecting. This is not allowed in multipolygons.");

		boolean oneOufOfBbox = false;
		for (JoinedWay polygon : intersectingPolygons) {
			int pi = polygons.indexOf(polygon);
			unfinishedPolys.clear(pi);

			boolean outOfBbox = false;
			for (Coord c : polygon.getPoints()) {
				if (!tileBounds.contains(c)) {
					outOfBbox = true;
					oneOufOfBbox = true;
					break;
				}
			}

			logWayURLs(Level.WARNING, (outOfBbox ? "*" : "-"), polygon);
		}
		
		for (JoinedWay polygon : intersectingPolygons) {
			// print out the details of the original ways
			logFakeWayDetails(Level.WARNING, polygon);
		}
		
		if (oneOufOfBbox) {
			log.warn("Some of these intersections/overlaps may be caused by incomplete data on bounding box edges (*).");
		}
	}

	private void runNestedOuterPolygonCheck(BitSet nestedOuterPolygons) {
		// just print out warnings
		// the check has been done before
		for (int wiIndex = nestedOuterPolygons.nextSetBit(0); wiIndex >= 0; wiIndex = nestedOuterPolygons
				.nextSetBit(wiIndex + 1)) {
			JoinedWay outerWay = polygons.get(wiIndex);
			log.warn("Polygon",	outerWay, "carries role outer but lies inside an outer polygon. Potentially its role should be inner.");
			logFakeWayDetails(Level.WARNING, outerWay);
		}
	}
	
	private void runNestedInnerPolygonCheck(BitSet nestedInnerPolygons) {
		// just print out warnings
		// the check has been done before
		for (int wiIndex = nestedInnerPolygons.nextSetBit(0); wiIndex >= 0; wiIndex = nestedInnerPolygons
				.nextSetBit(wiIndex + 1)) {
			JoinedWay innerWay = polygons.get(wiIndex);
			log.warn("Polygon",	innerWay, "carries role", getRole(innerWay), "but lies inside an inner polygon. Potentially its role should be outer.");
			logFakeWayDetails(Level.WARNING, innerWay);
		}
	}	
	
	private void runOutmostInnerPolygonCheck(BitSet outmostInnerPolygons) {
		// just print out warnings
		// the check has been done before
		for (int wiIndex = outmostInnerPolygons.nextSetBit(0); wiIndex >= 0; wiIndex = outmostInnerPolygons
				.nextSetBit(wiIndex + 1)) {
			JoinedWay innerWay = polygons.get(wiIndex);
			log.warn("Polygon",	innerWay, "carries role", getRole(innerWay), "but is not inside any other polygon. Potentially it does not belong to this multipolygon.");
			logFakeWayDetails(Level.WARNING, innerWay);
		}
	}

	private void runWrongInnerPolygonCheck(BitSet unfinishedPolygons,
			BitSet innerPolygons) {
		// find all unfinished inner polygons that are not contained by any
		BitSet wrongInnerPolygons = findOutmostPolygons(unfinishedPolygons, innerPolygons);
		if (log.isDebugEnabled()) {
			log.debug("unfinished", unfinishedPolygons);
			log.debug("inner", innerPolygons);
			// other polygon
			log.debug("wrong", wrongInnerPolygons);
		}
		if (!wrongInnerPolygons.isEmpty()) {
			// we have an inner polygon that is not contained by any outer polygon
			// check if
			for (int wiIndex = wrongInnerPolygons.nextSetBit(0); wiIndex >= 0; wiIndex = wrongInnerPolygons
					.nextSetBit(wiIndex + 1)) {
				BitSet containedPolygons = new BitSet();
				containedPolygons.or(unfinishedPolygons);
				containedPolygons.and(containsMatrix.get(wiIndex));

				JoinedWay innerWay = polygons.get(wiIndex);
				if (containedPolygons.isEmpty()) {
					log.warn("Polygon",	innerWay, "carries role", getRole(innerWay),
						"but is not inside any outer polygon. Potentially it does not belong to this multipolygon.");
					logFakeWayDetails(Level.WARNING, innerWay);
				} else {
					log.warn("Polygon",	innerWay, "carries role", getRole(innerWay),
						"but is not inside any outer polygon. Potentially the roles are interchanged with the following",
						(containedPolygons.cardinality() > 1 ? "ways" : "way"), ".");

					for (int wrIndex = containedPolygons.nextSetBit(0); wrIndex >= 0; wrIndex = containedPolygons
							.nextSetBit(wrIndex + 1)) {
						logWayURLs(Level.WARNING, "-", polygons.get(wrIndex));
						unfinishedPolygons.set(wrIndex);
						wrongInnerPolygons.set(wrIndex);
					}
					logFakeWayDetails(Level.WARNING, innerWay);
				}

				unfinishedPolygons.clear(wiIndex);
				wrongInnerPolygons.clear(wiIndex);
			}
		}
	}

	protected void cleanup() {
		mpPolygons = null;
		roleMap.clear();
		containsMatrix = null;
		polygons = null;
		tileArea = null;
		intersectingPolygons = null;
		outerWaysForLineTagging = null;
		outerTags = null;
		
		unfinishedPolygons = null;
		innerPolygons = null;
		taggedInnerPolygons = null;
		outerPolygons = null;
		taggedOuterPolygons = null;
		
		largestOuterPolygon = null;
	}

	/**
	 * Retrieves if the given element contains tags that may be relevant
	 * for style processing. If it has no relevant tag it will probably be 
	 * dropped by the style.
	 * 
	 * @param element the OSM element
	 * @return <code>true</code> has style relevant tags
	 */
	protected boolean hasStyleRelevantTags(Element element) {
		if (element instanceof MultiPolygonRelation) {
			if (((MultiPolygonRelation) element).getTagsIncomplete()) {
				return true;
			}
		}
		
		for (Map.Entry<String, String> tagEntry : element.getTagEntryIterator()) {
			String tagName = tagEntry.getKey();
			// all tags are style relevant
			// except: type (for relations), mkgmap:* 
			boolean isStyleRelevant = (element instanceof Relation && tagName.equals("type")) == false
					&& tagName.startsWith("mkgmap:") == false;
			if (isStyleRelevant) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Checks if this mp should be processed or if it is needless to process it
	 * because there is no result.
	 * @param ways the list of ways of the mp
	 * @return <code>true</code> the mp processing will have a result; 
	 * 		   <code>false</code> the mp processing will fail 
	 */
	private boolean isMpProcessable(Collection<Way> ways) {
		// Check if the multipolygon itself or the member ways have a
		// tag. If not it does not make sense to process the mp because 
		// the output will not change anything
		if (hasStyleRelevantTags(this)) {
			return true;
		}

		for (Way w : ways) {
			if (hasStyleRelevantTags(w)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Creates a matrix which polygon contains which polygon. A polygon does not
	 * contain itself.
	 * 
	 * @param polygonList
	 *            a list of polygons
	 */
	protected void createContainsMatrix(List<JoinedWay> polygonList) {
		containsMatrix = new ArrayList<>();
		for (int i = 0; i < polygonList.size(); i++) {
			containsMatrix.add(new BitSet());
		}

		long t1 = System.currentTimeMillis();

		if (log.isDebugEnabled())
			log.debug("createContainsMatrix listSize:", polygonList.size());

		// use this matrix to check which matrix element has been
		// calculated
		ArrayList<BitSet> finishedMatrix = new ArrayList<>(polygonList
				.size());

		for (int i = 0; i < polygonList.size(); i++) {
			BitSet matrixRow = new BitSet();
			// a polygon does not contain itself
			matrixRow.set(i);
			finishedMatrix.add(matrixRow);
		}

		for (int rowIndex = 0; rowIndex < polygonList.size(); rowIndex++) {
			JoinedWay potentialOuterPolygon = polygonList.get(rowIndex);
			BitSet containsColumns = containsMatrix.get(rowIndex);
			BitSet finishedCol = finishedMatrix.get(rowIndex);
			
			// the polygon need to be created only sometimes
			// so use a lazy creation to improve performance
			WayAndLazyPolygon lazyPotOuterPolygon = new WayAndLazyPolygon(potentialOuterPolygon);

			// get all non calculated columns of the matrix
			for (int colIndex = finishedCol.nextClearBit(0); colIndex >= 0
					&& colIndex < polygonList.size(); colIndex = finishedCol
					.nextClearBit(colIndex + 1)) {

				JoinedWay innerPolygon = polygonList.get(colIndex);

				if (potentialOuterPolygon.getBounds().intersects(
						innerPolygon.getBounds()))
				{
					boolean contains = contains(lazyPotOuterPolygon, innerPolygon);
					
					if (contains) {
						containsColumns.set(colIndex);

						// we also know that the inner polygon does not contain the
						// outer polygon
						// so we can set the finished bit for this matrix
						// element
						finishedMatrix.get(colIndex).set(rowIndex);

						// additionally we know that the outer polygon contains all
						// polygons that are contained by the inner polygon
						containsColumns.or(containsMatrix.get(colIndex));
						finishedCol.or(containsColumns);
					}
				} else {
					// both polygons do not intersect
					// we can flag both matrix elements as finished
					finishedMatrix.get(colIndex).set(rowIndex);
					finishedMatrix.get(rowIndex).set(colIndex);
				}
				// this matrix element is calculated now
				finishedCol.set(colIndex);
			}
		}

		if (log.isDebugEnabled()) {
			long t2 = System.currentTimeMillis();
			log.debug("createMatrix for", polygonList.size(), "polygons took",
				(t2 - t1), "ms");

			log.debug("Containsmatrix:");
			int i = 0;
			boolean noContained = true;
			for (BitSet b : containsMatrix) {
				if (b.isEmpty()==false) {
					log.debug(i,"contains",b);
					noContained = false;
				}
				i++;
			}
			if (noContained) {
				log.debug("Matrix is empty");
			}
		}
	}

	
	/**
	 * This is a helper class that creates a high precision polygon for a way 
	 * on request only.
	 */
	private static class WayAndLazyPolygon {
		private final JoinedWay way;
		private Polygon polygon;
		
		public WayAndLazyPolygon(JoinedWay way) {
			this.way = way;
		}

		public final JoinedWay getWay() {
			return this.way;
		}

		public final Polygon getPolygon() {
			if (this.polygon == null) {
				this.polygon = Java2DConverter.createHighPrecPolygon(this.way.getPoints());
			}
			return this.polygon;
		}
	}
	
	/**
	 * Checks if the polygon with polygonIndex1 contains the polygon with polygonIndex2.
	 * 
	 * @return true if polygon(polygonIndex1) contains polygon(polygonIndex2)
	 */
	private boolean contains(int polygonIndex1, int polygonIndex2) {
		return containsMatrix.get(polygonIndex1).get(polygonIndex2);
	}

	/**
	 * Checks if polygon1 contains polygon2.
	 * 
	 * @param polygon1
	 *            a closed way
	 * @param polygon2
	 *            a 2nd closed way
	 * @return true if polygon1 contains polygon2
	 */
	private boolean contains(WayAndLazyPolygon polygon1, JoinedWay polygon2) {
		if (!polygon1.getWay().hasIdenticalEndPoints()) {
			return false;
		}
		// check if the bounds of polygon2 are completely inside/enclosed the bounds
		// of polygon1
		if (!polygon1.getWay().getBounds().contains(polygon2.getBounds())) {
			return false;
		}

		// check first if one point of polygon2 is in polygon1

		// ignore intersections outside the bounding box
		// so it is necessary to check if there is at least one
		// point of polygon2 in polygon1 ignoring all points outside the bounding box
		boolean onePointContained = false;
		boolean allOnLine = true;
		for (Coord px : polygon2.getPoints()) {
			if (polygon1.getPolygon().contains(px.getHighPrecLon(), px.getHighPrecLat())){
				// there's one point that is in polygon1 and in the bounding
				// box => polygon1 may contain polygon2
				onePointContained = true;
				if (!locatedOnLine(px, polygon1.getWay().getPoints())) {
					allOnLine = false;
					break;
				}
			} else if (tileBounds.contains(px)) {
				// we have to check if the point is on one line of the polygon1
				
				if (!locatedOnLine(px, polygon1.getWay().getPoints())) {
					// there's one point that is not in polygon1 but inside the
					// bounding box => polygon1 does not contain polygon2
					//allOnLine = false;
					return false;
				}
			}
		}
		
		if (allOnLine) {
			onePointContained = false;
			// all points of polygon2 lie on lines of polygon1
			// => the middle of each line polygon must NOT lie outside polygon1
			ArrayList<Coord> middlePoints2 = new ArrayList<>(polygon2.getPoints().size());
			Coord p1 = null;
			for (Coord p2 : polygon2.getPoints()) {
				if (p1 != null) {
					Coord pm = p1.makeBetweenPoint(p2, 0.5);
					middlePoints2.add(pm);
				}
				p1 = p2;
			}
			
			for (Coord px : middlePoints2) {
				if (polygon1.getPolygon().contains(px.getHighPrecLon(), px.getHighPrecLat())){
					// there's one point that is in polygon1 and in the bounding
					// box => polygon1 may contain polygon2
					onePointContained = true;
					break;
				} else if (tileBounds.contains(px)) {
					// we have to check if the point is on one line of the polygon1
					
					if (!locatedOnLine(px, polygon1.getWay().getPoints())) {
						// there's one point that is not in polygon1 but inside the
						// bounding box => polygon1 does not contain polygon2
						return false;
					} 
				}
			}			
		}

		if (!onePointContained) {
			// no point of polygon2 is in polygon1 => polygon1 does not contain polygon2
			return false;
		}
		
		Iterator<Coord> it1 = polygon1.getWay().getPoints().iterator();
		Coord p1_1 = it1.next();

		while (it1.hasNext()) {
			Coord p1_2 = p1_1;
			p1_1 = it1.next();

			if (!polygon2.linePossiblyIntersectsWay(p1_1, p1_2)) {
				// don't check it - this segment of the outer polygon
				// definitely does not intersect the way
				continue;
			}

			int lonMin = Math.min(p1_1.getLongitude(), p1_2.getLongitude());
			int lonMax = Math.max(p1_1.getLongitude(), p1_2.getLongitude());
			int latMin = Math.min(p1_1.getLatitude(), p1_2.getLatitude());
			int latMax = Math.max(p1_1.getLatitude(), p1_2.getLatitude());

			// check all lines of way1 and way2 for intersections
			Iterator<Coord> it2 = polygon2.getPoints().iterator();
			Coord p2_1 = it2.next();

			// for speedup we divide the area around the second line into
			// a 3x3 matrix with lon(-1,0,1) and lat(-1,0,1).
			// -1 means below min lon/lat of bbox line p1_1-p1_2
			// 0 means inside the bounding box of the line p1_1-p1_2
			// 1 means above max lon/lat of bbox line p1_1-p1_2
			int lonField = p2_1.getLongitude() < lonMin ? -1 : p2_1
					.getLongitude() > lonMax ? 1 : 0;
			int latField = p2_1.getLatitude() < latMin ? -1 : p2_1
					.getLatitude() > latMax ? 1 : 0;

			int prevLonField = lonField;
			int prevLatField = latField;

			while (it2.hasNext()) {
				Coord p2_2 = p2_1;
				p2_1 = it2.next();

				int changes = 0;
				// check if the field of the 3x3 matrix has changed
				if ((lonField >= 0 && p1_1.getLongitude() < lonMin)
						|| (lonField <= 0 && p1_1.getLongitude() > lonMax)) {
					changes++;
					lonField = p1_1.getLongitude() < lonMin ? -1 : p1_1
							.getLongitude() > lonMax ? 1 : 0;
				}
				if ((latField >= 0 && p1_1.getLatitude() < latMin)
						|| (latField <= 0 && p1_1.getLatitude() > latMax)) {
					changes++;
					latField = p1_1.getLatitude() < latMin ? -1 : p1_1
							.getLatitude() > latMax ? 1 : 0;
				}

				// an intersection is possible if
				// latField and lonField has changed
				// or if we come from or go to the inner matrix field
				boolean intersectionPossible = (changes == 2)
						|| (latField == 0 && lonField == 0)
						|| (prevLatField == 0 && prevLonField == 0);

				boolean intersects = intersectionPossible
					&& linesCutEachOther(p1_1, p1_2, p2_1, p2_2);
				
				if (intersects) {
					if ((polygon1.getWay().isClosedArtificially() && !it1.hasNext())
							|| (polygon2.isClosedArtificially() && !it2.hasNext())) {
						// don't care about this intersection
						// one of the polygons is closed by this mp code and the
						// closing segment causes the intersection
						log.info("Polygon", polygon1, "may contain polygon", polygon2,
							". Ignoring artificial generated intersection.");
					} else if ((!tileBounds.contains(p1_1))
							|| (!tileBounds.contains(p1_2))
							|| (!tileBounds.contains(p2_1))
							|| (!tileBounds.contains(p2_2))) {
						// at least one point is outside the bounding box
						// we ignore the intersection because the ways may not
						// be complete
						// due to removals of the tile splitter or osmosis
						log.info("Polygon", polygon1, "may contain polygon", polygon2,
							". Ignoring because at least one point is outside the bounding box.");
					} else {
						// store them in the intersection polygons set
						// the error message will be printed out in the end of
						// the mp handling
						intersectingPolygons.add(polygon1.getWay());
						intersectingPolygons.add(polygon2);
						return false;
					}
				}

				prevLonField = lonField;
				prevLatField = latField;
			}
		}

		// don't have any intersection
		// => polygon1 contains polygon2
		return true;
	}

	/**
	 * Checks if the point p is located on one line of the given points.
	 * @param p a point
	 * @param points a list of points; all consecutive points are handled as lines
	 * @return true if p is located on one line given by points
	 */
	private static boolean locatedOnLine(Coord p, List<Coord> points) {
		Coord cp1 = null;
		for (Coord cp2 : points) {
			if (p.highPrecEquals(cp2)) { 
				return true;
			}

			try {
				if (cp1 == null) {
					// first init
					continue;
				}
				
				if (p.getHighPrecLon() < Math.min(cp1.getHighPrecLon(), cp2.getHighPrecLon())) {
					continue;
				}
				if (p.getHighPrecLon() > Math.max(cp1.getHighPrecLon(), cp2.getHighPrecLon())) {
					continue;
				}
				if (p.getHighPrecLat() < Math.min(cp1.getHighPrecLat(), cp2.getHighPrecLat())) {
					continue;
				}
				if (p.getHighPrecLat() > Math.max(cp1.getHighPrecLat(), cp2.getHighPrecLat())) {
					continue;
				}

				double dist = Line2D.ptSegDistSq(cp1.getHighPrecLon(), cp1.getHighPrecLat(),
						cp2.getHighPrecLon(), cp2.getHighPrecLat(),
						p.getHighPrecLon(), p.getHighPrecLat());

				if (dist <= OVERLAP_TOLERANCE_DISTANCE) {
					log.debug("Point", p, "is located on line between", cp1, "and",
						cp2, ". Distance:", dist);
					return true;
				}
			} finally {
				cp1 = cp2;
			}
		}
		return false;
	}

	private boolean lineCutsBbox(Coord p1_1, Coord p1_2) {
		Coord nw = new Coord(tileBounds.getMaxLat(), tileBounds.getMinLong());
		Coord sw = new Coord(tileBounds.getMinLat(), tileBounds.getMinLong());
		Coord se = new Coord(tileBounds.getMinLat(), tileBounds.getMaxLong());
		Coord ne = new Coord(tileBounds.getMaxLat(), tileBounds.getMaxLong());
		return linesCutEachOther(nw, sw, p1_1, p1_2)
				|| linesCutEachOther(sw, se, p1_1, p1_2)
				|| linesCutEachOther(se, ne, p1_1, p1_2)
				|| linesCutEachOther(ne, nw, p1_1, p1_2);
	}

	/**
	 * XXX: This code presumes that certain tests were already done!
	 * Check if the line p1_1 to p1_2 cuts line p2_1 to p2_2 in two pieces and vice versa.
	 * This is a form of intersection check where it is allowed that one line ends on the
	 * other line or that the two lines overlap.
	 * @param p1_1 first point of line 1
	 * @param p1_2 second point of line 1
	 * @param p2_1 first point of line 2
	 * @param p2_2 second point of line 2
	 * @return true if both lines intersect somewhere in the middle of each other
	 */
	private static boolean linesCutEachOther(Coord p1_1, Coord p1_2, Coord p2_1, Coord p2_2) {
		long width1 = p1_2.getHighPrecLon() - p1_1.getHighPrecLon();
		long width2 = p2_2.getHighPrecLon() - p2_1.getHighPrecLon();

		long height1 = p1_2.getHighPrecLat() - p1_1.getHighPrecLat();
		long height2 = p2_2.getHighPrecLat() - p2_1.getHighPrecLat();

		long denominator = ((height2 * width1) - (width2 * height1));
		if (denominator == 0) {
			// the lines are parallel
			// they might overlap but this is ok for this test
			return false;
		}
		
		long x1Mx3 = p1_1.getHighPrecLon() - p2_1.getHighPrecLon();
		long y1My3 = p1_1.getHighPrecLat() - p2_1.getHighPrecLat();

		double isx = (double)((width2 * y1My3) - (height2 * x1Mx3))
				/ denominator;
		if (isx <= 0 || isx >= 1) {
			return false;
		}
		
		double isy = (double)((width1 * y1My3) - (height1 * x1Mx3))
				/ denominator;

		if (isy <= 0 || isy >= 1) {
			return false;
		}
		return true;
	}

	private List<JoinedWay> getWaysFromPolygonList(BitSet selection) {
		if (selection.isEmpty()) {
			return Collections.emptyList();
		}
		List<JoinedWay> wayList = new ArrayList<>(selection
				.cardinality());
		for (int i = selection.nextSetBit(0); i >= 0; i = selection.nextSetBit(i + 1)) {
			wayList.add(polygons.get(i));
		}
		return wayList;
	}

	private static void logWayURLs(Level level, String preMsg, Way way) {
		if (log.isLoggable(level)) {
			if (way instanceof JoinedWay) {
				if (((JoinedWay) way).getOriginalWays().isEmpty()) {
					log.warn("Way", way, "does not contain any original ways");
				}
				for (Way segment : ((JoinedWay) way).getOriginalWays()) {
					if (preMsg == null || preMsg.length() == 0) {
						log.log(level, segment.toBrowseURL());
					} else {
						log.log(level, preMsg, segment.toBrowseURL());
					}
				}
			} else {
				if (preMsg == null || preMsg.length() == 0) {
					log.log(level, way.toBrowseURL());
				} else {
					log.log(level, preMsg, way.toBrowseURL());
				}
			}
		}
	}
	
	/**
	 * Logs the details of the original ways of a way with a fake id. This is
	 * primarily necessary for the sea multipolygon because it consists of 
	 * faked ways only. In this case logging messages can be improved by the
	 * start and end points of the faked ways.
	 * @param logLevel the logging level
	 * @param fakeWay a way composed by other ways with faked ids
	 */
	private void logFakeWayDetails(Level logLevel, JoinedWay fakeWay) {
		if (log.isLoggable(logLevel) == false) {
			return;
		}
		
		// only log if this is an artificial multipolygon
		if (FakeIdGenerator.isFakeId(getId()) == false) {
			return;
		}
		
		boolean containsOrgFakeWay = false;
		for (Way orgWay : fakeWay.getOriginalWays()) {
			if (FakeIdGenerator.isFakeId(orgWay.getId())) {
				containsOrgFakeWay = true;
			}
		}
		
		if (containsOrgFakeWay == false) {
			return;
		}
		
		// the fakeWay consists only of other faked ways
		// there should be more information about these ways
		// so that it is possible to retrieve the original
		// OSM ways
		// => log the start and end points
		
		for (Way orgWay : fakeWay.getOriginalWays()) {
			log.log(logLevel, " Way",orgWay.getId(),"is composed of other artificial ways. Details:");
			log.log(logLevel, "  Start:",orgWay.getFirstPoint().toOSMURL());
			if (orgWay.hasEqualEndPoints()) {
				// the way is closed so start and end are equal - log the point in the middle of the way
				int mid = orgWay.getPoints().size()/2;
				log.log(logLevel, "  Mid:  ",orgWay.getPoints().get(mid).toOSMURL());
			} else {
				log.log(logLevel, "  End:  ",orgWay.getLastPoint().toOSMURL());
			}
		}		
	}

	protected void tagOuterWays() {
		Map<String, String> tags;
		if (hasStyleRelevantTags(this)) {
			tags = new HashMap<>();
			for (Entry<String, String> relTag : getTagEntryIterator()) {
				tags.put(relTag.getKey(), relTag.getValue());
			}
		} else {
			tags = JoinedWay.getMergedTags(outerWaysForLineTagging);
		}
		
		
		// Go through all original outer ways, create a copy, tag them
		// with the mp tags and mark them only to be used for polyline processing
		// This enables the style file to decide if the polygon information or
		// the simple line information should be used.
		for (Way orgOuterWay : outerWaysForLineTagging) {
			Way lineTagWay =  new Way(getOriginalId(), orgOuterWay.getPoints());
			lineTagWay.setFakeId();
			lineTagWay.addTag(STYLE_FILTER_TAG, STYLE_FILTER_LINE);
			lineTagWay.addTag(MP_CREATED_TAG, "true");
			for (Entry<String,String> tag : tags.entrySet()) {
				lineTagWay.addTag(tag.getKey(), tag.getValue());
				
				// remove the tag from the original way if it has the same value
				if (tag.getValue().equals(orgOuterWay.getTag(tag.getKey()))) {
					removeTagsInOrgWays(orgOuterWay, tag.getKey());
				}
			}
			
			if (log.isDebugEnabled())
				log.debug("Add line way", lineTagWay.getId(), lineTagWay.toTagString());
			tileWayMap.put(lineTagWay.getId(), lineTagWay);
		}
	}
	
	
	/**
	 * Marks all tags of the original ways of the given JoinedWay that are also
	 * contained in the given tagElement for removal.
	 * 
	 * @param tagElement
	 *            an element contains the tags to be removed
	 * @param way
	 *            a joined way
	 */
	private void removeTagsInOrgWays(Element tagElement, JoinedWay way) {
		for (Entry<String, String> tag : tagElement.getTagEntryIterator()) {
			removeTagInOrgWays(way, tag.getKey(), tag.getValue());
		}
	}

	/**
	 * Mark the given tag of all original ways of the given JoinedWay.
	 * 
	 * @param way
	 *            a joined way
	 * @param tagname
	 *            the tag to be removed
	 * @param tagvalue
	 *            the value of the tag to be removed
	 */
	private void removeTagInOrgWays(JoinedWay way, String tagname,
			String tagvalue) {
		for (Way w : way.getOriginalWays()) {
			if (w instanceof JoinedWay) {
				// remove the tags recursively
				removeTagInOrgWays((JoinedWay) w, tagname, tagvalue);
				continue;
			}

			if (tagvalue.equals(w.getTag(tagname))) {
				if (log.isDebugEnabled()) {
					log.debug("Will remove", tagname + "=" + w.getTag(tagname), "from way", w.getId(), w.toTagString());
				}
				removeTagsInOrgWays(w, tagname);
			}
		}
	}
	
	protected void removeTagsInOrgWays(Way way, String tag) {
		if (tag == null || tag.isEmpty()) {
			return;
		}
		String tagsToRemove = way.getTag(ElementSaver.MKGMAP_REMOVE_TAG_KEY);
		
		if (tagsToRemove == null) {
			tagsToRemove = tag;
		} else if (tag.equals(tagsToRemove)) {
			return;
		} else {
			String[] keys = tagsToRemove.split(";");
			if (Arrays.asList(keys).contains(tag)) {
				return;
			}
			tagsToRemove += ";" + tag;
		} 
		way.addTag(ElementSaver.MKGMAP_REMOVE_TAG_KEY, tagsToRemove);
	}
	
	/**
	 * Flag if the area size of the mp should be calculated and added as tag.
	 * @return {@code true} area size should be calculated; {@code false} area size should not be calculated
	 */
	protected boolean isAreaSizeCalculated() {
		return true;
	}

	protected Map<Long, Way> getTileWayMap() {
		return tileWayMap;
	}

	protected Map<Long, Way> getMpPolygons() {
		return mpPolygons;
	}

	protected uk.me.parabola.imgfmt.app.Area getTileBounds() {
		return tileBounds;
	}
	
	/**
	 * Calculates a unitless number that gives a value for the size
	 * of the area. The calculation does not correct to any earth 
	 * coordinate system. It uses the simple rectangular coordinate
	 * system of garmin coordinates. 
	 * 
	 * @param polygon the points of the area
	 * @return the size of the area (unitless)
	 */
	public static double calcAreaSize(List<Coord> polygon) {
		if (polygon.size() < 4 || polygon.get(0) != polygon.get(polygon.size()-1)) {
			return 0; // line or not closed
		}
		long area = 0;
		Iterator<Coord> polyIter = polygon.iterator();
		Coord c2 = polyIter.next();
		while (polyIter.hasNext()) {
			Coord c1 = c2;
			c2 = polyIter.next();
			area += (long) (c2.getHighPrecLon() + c1.getHighPrecLon())
					* (c1.getHighPrecLat() - c2.getHighPrecLat());
		}
		//  convert from high prec to value in map units
		double areaSize = (double) area / (2 * (1<<Coord.DELTA_SHIFT) * (1<<Coord.DELTA_SHIFT));  
		return Math.abs(areaSize);
	}


	/**
	 * This is a helper class that gives access to the original
	 * segments of a joined way.
	 */
	public static final class JoinedWay extends Way {
		private final List<Way> originalWays;
		private boolean closedArtificially;

		private int minLat;
		private int maxLat;
		private int minLon;
		private int maxLon;
		private Rectangle bounds;

		public JoinedWay(Way originalWay) {
			super(originalWay.getOriginalId(), originalWay.getPoints());
			setFakeId();
			originalWays = new ArrayList<>();
			addWay(originalWay);

			// we have to initialize the min/max values
			Coord c0 = originalWay.getFirstPoint();
			minLat = maxLat = c0.getLatitude();
			minLon = maxLon = c0.getLongitude();

			updateBounds(originalWay.getPoints());
		}

		public void addPoint(int index, Coord point) {
			getPoints().add(index, point);
			updateBounds(point);
		}

		public void addPoint(Coord point) {
			super.addPoint(point);
			updateBounds(point);
		}

		private void updateBounds(List<Coord> pointList) {
			for (Coord c : pointList) {
				updateBounds(c.getLatitude(),c.getLongitude());
			}
		}

		private void updateBounds (JoinedWay other){
			updateBounds(other.minLat,other.minLon);
			updateBounds(other.maxLat,other.maxLon);
		}

		private void updateBounds(int lat, int lon) {
			if (lat < minLat) {
				minLat = lat;
				bounds = null;
			} else if (lat > maxLat) {
				maxLat = lat;
				bounds = null;
			}

			if (lon < minLon) {
				minLon = lon;
				bounds = null;
			} else if (lon > maxLon) {
				maxLon = lon;
				bounds = null;
			}

			
		}
		private void updateBounds(Coord point) {
			updateBounds(point.getLatitude(), point.getLongitude());
		}
		
		/**
		 * Checks if this way intersects the given bounding box at least with
		 * one point.
		 * 
		 * @param bbox
		 *            the bounding box
		 * @return <code>true</code> if this way intersects or touches the
		 *         bounding box; <code>false</code> else
		 */
		public boolean intersects(uk.me.parabola.imgfmt.app.Area bbox) {
			return (maxLat >= bbox.getMinLat() && 
					minLat <= bbox.getMaxLat() && 
					maxLon >= bbox.getMinLong() && 
					minLon <= bbox.getMaxLong());
		}

		public Rectangle getBounds() {
			if (bounds == null) {
				// note that we increase the rectangle by 1 because intersects
				// checks
				// only the interior
				bounds = new Rectangle(minLon - 1, minLat - 1, maxLon - minLon
						+ 2, maxLat - minLat + 2);
			}

			return bounds;
		}

		public boolean linePossiblyIntersectsWay(Coord p1, Coord p2) {
			return getBounds().intersectsLine(p1.getLongitude(),
					p1.getLatitude(), p2.getLongitude(), p2.getLatitude());
		}

		public void addWay(Way way) {
			if (way instanceof JoinedWay) {
				for (Way w : ((JoinedWay) way).getOriginalWays()) {
					addWay(w);
				}
				updateBounds((JoinedWay)way);
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Joined", this.getId(), "with", way.getId());
				}
				this.originalWays.add(way);
			}
		}

		public void closeWayArtificially() {
			addPoint(getPoints().get(0));
			closedArtificially = true;
		}

		public boolean isClosedArtificially() {
			return closedArtificially;
		}

		public static Map<String,String> getMergedTags(Collection<Way> ways) {
			Map<String,String> mergedTags = new HashMap<>();
			boolean first = true;
			for (Way way : ways) {
				if (first) {
					// the tags of the first way are copied completely 
					for (Map.Entry<String, String> tag : way.getTagEntryIterator()) {
						mergedTags.put(tag.getKey(), tag.getValue());
					}
					first = false;
				} else {
					// for all other ways all non matching tags are removed
					ArrayList<String> tagsToRemove = null;
					for (Map.Entry<String, String> tag : mergedTags.entrySet()) {
						String wayTagValue = way.getTag(tag.getKey());
						if (!tag.getValue().equals(wayTagValue)) {
							// the tags are different
							if (wayTagValue!= null) {
								if (tagsToRemove == null) {
									tagsToRemove=new ArrayList<>();
								}
								tagsToRemove.add(tag.getKey());
							}
						}
					}
					if (tagsToRemove!=null) {
						for (String tag : tagsToRemove) {
							mergedTags.remove(tag);
						}
					}
				}
			}
			return mergedTags;
		}
		
		/**
		 * Tags this way with a merge of the tags of all original ways.
		 */
		public void mergeTagsFromOrgWays() {
			if (log.isDebugEnabled()) {
				log.debug("Way",getId(),"merge tags from",getOriginalWays().size(),"ways");
			}
			removeAllTags();
			
			Map<String,String> mergedTags = getMergedTags(getOriginalWays());
			for (Entry<String,String> tag : mergedTags.entrySet()) {
				addTag(tag.getKey(),tag.getValue());
			}
		}

		public List<Way> getOriginalWays() {
			return originalWays;
		}
		
		/**
		 * Retrieves a measurement of the area covered by this polygon. The 
		 * returned value has no unit. It is just a rough comparable value
		 * because it uses a rectangular coordinate system without correction.
		 * @return size of the covered areas (0 if the way is not closed)
		 */
		public double getSizeOfArea() {
			return MultiPolygonRelation.calcAreaSize(getPoints());
		}

		public String toString() {
			StringBuilder sb = new StringBuilder(200);
			sb.append(getId());
			sb.append("(");
			sb.append(getPoints().size());
			sb.append("P)(");
			boolean first = true;
			for (Way w : getOriginalWays()) {
				if (first) {
					first = false;
				} else {
					sb.append(",");
				}
				sb.append(w.getId());
				sb.append("[");
				sb.append(w.getPoints().size());
				sb.append("P]");
			}
			sb.append(")");
			return sb.toString();
		}
	}

	public static class PolygonStatus {
		public final boolean outer;
		public final int index;
		public final JoinedWay polygon;

		public PolygonStatus(boolean outer, int index, JoinedWay polygon) {
			this.outer = outer;
			this.index = index;
			this.polygon = polygon;
		}
		
		public String toString() {
			return polygon+"_"+outer;
		}
	}
}
