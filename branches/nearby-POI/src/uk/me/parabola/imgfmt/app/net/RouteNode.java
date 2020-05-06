/*
 * Copyright (C) 2008
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
 * Create date: 07-Jul-2008
 */
package uk.me.parabola.imgfmt.app.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.log.Logger;

/**
 * A routing node with its connections to other nodes via roads.
 *
 * @author Steve Ratcliffe
 */
public class RouteNode implements Comparable<RouteNode> {
	private static final Logger log = Logger.getLogger(RouteNode.class);

	/*
	 * 1. instantiate
	 * 2. setCoord, addArc
	 *      arcs, coords set
	 * 3. write
	 *      node offsets set in all nodes
	 * 4. writeSecond
	 */

	// Values for the first flag byte at offset 1
	private static final int MAX_DEST_CLASS_MASK = 0x07;
	private static final int F_BOUNDARY = 0x08;
	private static final int F_RESTRICTIONS = 0x10;
	private static final int F_LARGE_OFFSETS = 0x20;
	private static final int F_ARCS = 0x40;
	// only used internally in mkgmap
	private static final int F_DISCARDED = 0x100; // node has been discarded

	private int offsetNod1 = -1;

	// arcs from this node
	private final List<RouteArc> arcs = new ArrayList<>(4);
	// restrictions at (via) this node
	private final List<RouteRestriction> restrictions = new ArrayList<>();

	private int flags;

	private final CoordNode coord;
	private int latOff;
	private int lonOff;

	// contains the maximum of roads this node is on, written with the flags
	// field. It is also used for the calculation of the destination class on
	// arcs.
	private byte nodeClass;
	
	private byte nodeGroup = -1;

	public RouteNode(Coord coord) {
		this.coord = (CoordNode) coord;
		setBoundary(this.coord.getOnBoundary() || this.coord.getOnCountryBorder());
	}

	private boolean haveLargeOffsets() {
		return (flags & F_LARGE_OFFSETS) != 0;
	}

	protected void setBoundary(boolean b) {
		if (b)
			flags |= F_BOUNDARY;
		else
			flags &= (~F_BOUNDARY) & 0xff;
	}

	public boolean isBoundary() {
		return (flags & F_BOUNDARY) != 0;
	}

	public void addArc(RouteArc arc) {
		arcs.add(arc);
		byte cl = (byte) arc.getRoadDef().getRoadClass();
		if(log.isDebugEnabled())
			log.debug("adding arc", arc.getRoadDef(), cl);
		if (cl > nodeClass)
			nodeClass = cl;
		flags |= F_ARCS;
	}

	public void addRestriction(RouteRestriction restr) {
		restrictions.add(restr);
		flags |= F_RESTRICTIONS;
	}

	/**
	 * get all direct arcs to the given node and the given way id
	 * @param otherNode
	 * @param roadId
	 * @return list with the direct arcs
	 */
	public List<RouteArc> getDirectArcsTo(RouteNode otherNode, long roadId) {
		List<RouteArc> result = new ArrayList<>();
		for (RouteArc a : arcs) {
			if (a.isDirect() && a.getDest() == otherNode && a.getRoadDef().getId() == roadId) {
				result.add(a);
			}
		}
		return result;
	}

	/**
	 * get all direct arcs on a given way id  
	 * @param roadId
	 * @return @return list with the direct arcs
	 */
	public List<RouteArc> getDirectArcsOnWay(long roadId) {
		List<RouteArc> result = new ArrayList<>();
		for (RouteArc a : arcs) {
			if (a.isDirect() && a.getRoadDef().getId() == roadId) {
				result.add(a);
			}
		}
		return result;
	}
	
	/**
	 * Find arc to given node on given road. 
	 * @param otherNode
	 * @param roadDef
	 * @return
	 */
	public RouteArc getDirectArcTo(RouteNode otherNode, RoadDef roadDef) {
		for (RouteArc a : arcs) {
			if (a.isDirect() && a.getDest() == otherNode && a.getRoadDef() == roadDef) {
				return a;
			}
		}
		return null;
	}
	
	/**
	 * Provide an upper bound to the size (in bytes) that
	 * writing this node will take.
	 *
	 * Should be called only after arcs and restrictions
	 * have been set. The size of arcs depends on whether
	 * or not they are internal to the RoutingCenter.
	 */
	public int boundSize() {
		return 1 // table pointer
			+ 1 // flags
			+ 4 // assume large offsets required
			+ arcsSize()
			+ restrSize();
	}

	private int arcsSize() {
		int s = 0;
		for (RouteArc arc : arcs) {
			s += arc.boundSize();
		}
		return s;
	}

	private int restrSize() {
		return 2*restrictions.size();
	}

	/**
	 * Writes a nod1 entry.
	 */
	public void write(ImgFileWriter writer) {
		if(log.isDebugEnabled())
			log.debug("writing node, first pass, nod1", coord.getId());
		offsetNod1 = writer.position();
		assert offsetNod1 < 0x1000000 : "node offset doesn't fit in 3 bytes";

		assert (flags & F_DISCARDED) == 0 : "attempt to write discarded node";

		writer.put1u(0);  // will be overwritten later
		flags |= (nodeClass & MAX_DEST_CLASS_MASK); // max. road class of any outgoing road
		writer.put1u(flags);

		if (haveLargeOffsets()) {
			writer.put4((latOff << 16) | (lonOff & 0xffff));
		} else {
			writer.put3s((latOff << 12) | (lonOff & 0xfff));
		}

		if (!arcs.isEmpty()) {
			boolean useCompactDirs = true;
			IntArrayList initialHeadings = new IntArrayList(arcs.size()+1);
			RouteArc lastArc = null;
			for (RouteArc arc: arcs){
				if (lastArc == null || lastArc.getIndexA() != arc.getIndexA() || lastArc.isForward() != arc.isForward()){
					int dir = RouteArc.directionFromDegrees(arc.getInitialHeading());
					dir = (dir + 8) & 0xf0;
					if (initialHeadings.contains(dir)){
						useCompactDirs = false;
						break;
					}
					initialHeadings.add(dir);
				}
				lastArc = arc;
			}
			initialHeadings.add(0); // add dummy 0 so that we don't have to check for existence
			arcs.get(arcs.size() - 1).setLast();
			lastArc = null;
			
			int index = 0;
			for (RouteArc arc: arcs){
				Byte compactedDir = null;
				if (useCompactDirs && (lastArc == null || lastArc.getIndexA() != arc.getIndexA() || lastArc.isForward() != arc.isForward())){
					if (index % 2 == 0)
						compactedDir = (byte) ((initialHeadings.get(index) >> 4) | initialHeadings.getInt(index+1));
					index++;
				}
				arc.write(writer, lastArc, useCompactDirs, compactedDir);
				lastArc = arc;
			}
		}

		if (!restrictions.isEmpty()) {
			restrictions.get(restrictions.size() - 1).setLast();
			for (RouteRestriction restr : restrictions)
				restr.writeOffset(writer);
		}
	}

	/**
	 * Writes a nod3 /nod4 entry.
	 */
	public void writeNod3OrNod4(ImgFileWriter writer) {
		assert isBoundary() : "trying to write nod3 for non-boundary node";

		Utils.put3sLongitude(writer, coord.getLongitude());
		writer.put3s(coord.getLatitude()); 
		writer.put3u(offsetNod1);
	}

	public void discard() {
		// mark the node as having been discarded
		flags |= F_DISCARDED;
		if (isBoundary()) {
			log.error("intermal error? boundary node at",coord.toDegreeString(), "is discarded");
		}
	}

	public boolean isDiscarded() {
		return (flags &  F_DISCARDED) != 0;
	}

	public int getOffsetNod1() {
		if((flags & F_DISCARDED) != 0) {
			// return something so that the program can continue
			return 0;
		}
		assert offsetNod1 != -1: "failed for node " + coord.getId() + " at " + coord.toDegreeString();
		return offsetNod1;
	}

	public void setOffsets(Coord centralPoint) {
		if(log.isDebugEnabled())
			log.debug("center", centralPoint, ", coord", coord.toDegreeString());
		setLatOff(coord.getLatitude() - centralPoint.getLatitude());
		setLonOff(coord.getLongitude() - centralPoint.getLongitude());
	}

	public Coord getCoord() {
		return coord;
	}

	private void checkOffSize(int off) {
		if (off > 0x7ff || off < -0x800)
			// does off fit in signed 12 bit quantity?
			flags |= F_LARGE_OFFSETS;
		// does off fit in signed 16 bit quantity?
		assert (off <= 0x7fff && off >= -0x8000);
	}

	private void setLatOff(int latOff) {
		if(log.isDebugEnabled())
			log.debug("lat off", Integer.toHexString(latOff));
		this.latOff = latOff;
		checkOffSize(latOff);
	}

	private void setLonOff(int lonOff) {
		if(log.isDebugEnabled())
			log.debug("long off", Integer.toHexString(lonOff));
		this.lonOff = lonOff;
		checkOffSize(lonOff);
	}

	/**
	 * Second pass over the nodes. Fill in pointers and Table A indices.
	 */
	public void writeSecond(ImgFileWriter writer) {
		for (RouteArc arc : arcs)
			arc.writeSecond(writer);
	}

	/**
	 * Return the node's class, which is the maximum of
	 * classes of the roads it's on.
	 */
	public int getNodeClass() {
		return nodeClass;
	}

	public Iterable<RouteArc> arcsIteration() {
		return arcs::iterator;
	}

	public List<RouteRestriction> getRestrictions() {
		return restrictions;
	}

	public String toString() {
		return String.valueOf(coord.getId());
	}

	/*
	 * For sorting node entries in NOD 3.
	 */
	public int compareTo(RouteNode otherNode) {
		return coord.compareTo(otherNode.getCoord());
	}

	public void checkRoundabouts() {
		List<RouteArc> roundaboutArcs = new ArrayList<>();
		List<RouteArc> nonRoundaboutArcs = new ArrayList<>();
		int countNonRoundaboutRoads = 0;
		int countNonRoundaboutOtherHighways = 0;
		int countHighwaysInsideRoundabout = 0;
		for(RouteArc ra : arcs) {
			if (ra.isDirect()) {
				// ignore ways that have been synthesised by mkgmap
				RoadDef rd = ra.getRoadDef();
				if (!rd.isSynthesised()) {
					if (rd.isRoundabout())
						roundaboutArcs.add(ra);
					else
						nonRoundaboutArcs.add(ra);
				}
			}
		}
		if (!roundaboutArcs.isEmpty()) {
			// get the coordinates of a box bounding the junctions of the roundabout
			int minRoundaboutLat = coord.getHighPrecLat();
			int maxRoundaboutLat = minRoundaboutLat;
			int minRoundaboutLon = coord.getHighPrecLon();
			int maxRoundaboutLon = minRoundaboutLon;
			List<RouteNode> processedNodes = new ArrayList<>();
			processedNodes.add(this);
			for (RouteArc ra : roundaboutArcs) {
				if (ra.isForward()) {
					for (RouteArc ra1 : nonRoundaboutArcs) {
						if ((ra1.getDirectHeading() == ra.getDirectHeading()) && (ra1.getInitialHeading() == ra.getInitialHeading()) && (ra1.getFinalHeading() == ra.getFinalHeading()) && (ra1.getLengthInMeter() == ra.getLengthInMeter())) {
							// non roundabout highway overlaps roundabout
							nonRoundaboutArcs.remove(ra1);
							if(!ra.getRoadDef().messagePreviouslyIssued("roundabout forks/overlaps"))
								log.warn("Highway",ra1.getRoadDef(), "overlaps roundabout", ra.getRoadDef(), "at",coord.toOSMURL());
							break;
						}						
					}
					RouteNode rn = ra.getDest();
					while (rn != null && !processedNodes.contains(rn)) {
						processedNodes.add(rn);
						int lat = rn.coord.getHighPrecLat();
						int lon = rn.coord.getHighPrecLon();
						minRoundaboutLat = Math.min(minRoundaboutLat, lat);
						maxRoundaboutLat = Math.max(maxRoundaboutLat, lat);
						minRoundaboutLon = Math.min(minRoundaboutLon, lon);
						maxRoundaboutLon = Math.max(maxRoundaboutLon, lon);
						RouteNode nrn = null;
						for (RouteArc nra : rn.arcs) {
							if (nra.isDirect() && nra.isForward()) {
								RoadDef nrd = nra.getRoadDef();
								if (nrd.isRoundabout() && !nrd.isSynthesised())
									nrn = nra.getDest();
							}
						}
						rn = nrn;
					}
				}
			}
			if (nonRoundaboutArcs.size() > 1) {
				// get an approximate centre of the roundabout
				Coord roundaboutCentre =  Coord.makeHighPrecCoord((minRoundaboutLat + maxRoundaboutLat) / 2, (minRoundaboutLon + maxRoundaboutLon) / 2);
				for (RouteArc ra : nonRoundaboutArcs) {
					double distanceToCentre = roundaboutCentre.distance(coord);
					RoadDef rd = ra.getRoadDef();
					// ignore footpaths and ways with no access
					byte access = rd.getAccess();
					if (access != 0 && (access != AccessTagsAndBits.FOOT)) {
						// check whether the way is inside the roundabout by seeing if the next point is nearer to the centre of the bounding box than this
						RouteNode nextNode = ra.getSource().coord == coord ? ra.getDest() : ra.getSource();
						Coord nextCoord = nextNode.coord;
						for (RouteNode roundaboutNode : processedNodes) {
							if (roundaboutNode.coord.equals(nextCoord)) {
								// arc rejoins roundabout, so calculate another point to use half the distance away at the initial bearing
								double heading1 = ra.getSource().coord == coord ? ra.getInitialHeading() : 180 + ra.getFinalHeading();
								double distance = coord.distance(nextCoord) / 2;
								Coord nextCoord1 = coord.offset(heading1, distance);
								// now calculate a point the same distance away from the end point 180 degrees from the final bearing
								double heading2 = ra.getSource().coord == coord ? 180 + ra.getFinalHeading() : ra.getInitialHeading();
								Coord nextCoord2 = nextCoord.offset(heading2, distance);
								double distanceToCentreOfNextCoord = roundaboutCentre.distance(nextCoord);
								// use the point which has a bigger difference in distance from the centre to increase accuracy
								if (Math.abs(distanceToCentre - roundaboutCentre.distance(nextCoord1)) >= Math.abs(distanceToCentreOfNextCoord - roundaboutCentre.distance(nextCoord2)))
									nextCoord = nextCoord1;
								else {
									distanceToCentre = distanceToCentreOfNextCoord;
									nextCoord = nextCoord2;
								}
								break;
							}
						}
						double nextDistanceToCentre = roundaboutCentre.distance(nextCoord);
						if (Math.abs(nextDistanceToCentre - distanceToCentre) < 2)
							log.info("Way",rd,"unable to accurately determine whether",nextCoord.toOSMURL()," is inside roundabout");
						if (nextDistanceToCentre < distanceToCentre)
							countHighwaysInsideRoundabout++;
						else {
							if ((access & AccessTagsAndBits.CAR) != 0)
								countNonRoundaboutRoads++;
							else if ((access & (AccessTagsAndBits.BIKE | AccessTagsAndBits.BUS | AccessTagsAndBits.TAXI | AccessTagsAndBits.TRUCK)) != 0)
								countNonRoundaboutOtherHighways++;
						}
					}
				}
			
				RouteArc roundaboutArc = roundaboutArcs.get(0);
				if (arcs.size() > 1 && roundaboutArcs.size() == 1)
					log.warn("Roundabout",roundaboutArc.getRoadDef(),roundaboutArc.isForward() ? "starts at" : "ends at", coord.toOSMURL());
				if (countNonRoundaboutRoads > 1)
					log.warn("Roundabout",roundaboutArc.getRoadDef(),"is connected to more than one road at",coord.toOSMURL());
				else if (countNonRoundaboutRoads == 1) {
					if (countNonRoundaboutOtherHighways > 0) {
						if (countHighwaysInsideRoundabout > 0)
							log.warn("Roundabout",roundaboutArc.getRoadDef(),"is connected to a road",countNonRoundaboutOtherHighways,"other highway(s) and",countHighwaysInsideRoundabout,"highways inside the roundabout at",coord.toOSMURL());
						else
							log.warn("Roundabout",roundaboutArc.getRoadDef(),"is connected to a road and",countNonRoundaboutOtherHighways,"other highway(s) at",coord.toOSMURL());
					}
					else if (countHighwaysInsideRoundabout > 0)
						log.warn("Roundabout",roundaboutArc.getRoadDef(),"is connected to a road and",countHighwaysInsideRoundabout,"highway(s) inside the roundabout at",coord.toOSMURL());
				}
				else if (countNonRoundaboutOtherHighways > 0) {
					if (countHighwaysInsideRoundabout > 0)
						log.warn("Roundabout",roundaboutArc.getRoadDef(),"is connected to",countNonRoundaboutOtherHighways,"highway(s) and",countHighwaysInsideRoundabout,"inside the roundabout at",coord.toOSMURL());
					else if (countNonRoundaboutOtherHighways > 1)
						log.warn("Roundabout",roundaboutArc.getRoadDef(),"is connected to",countNonRoundaboutOtherHighways,"highways at",coord.toOSMURL());
				}
				else if (countHighwaysInsideRoundabout > 1)
					log.warn("Roundabout",roundaboutArc.getRoadDef(),"is connected to",countHighwaysInsideRoundabout,"highways inside the roundabout at",coord.toOSMURL());
				if(roundaboutArcs.size() > 2) {
					for(RouteArc fa : roundaboutArcs) {
						if(fa.isForward()) {
							RoadDef rd = fa.getRoadDef();
							for(RouteArc fb : roundaboutArcs) {
								if(fb != fa) { 
									if(fa.getPointsHash() == fb.getPointsHash() &&
									   ((fb.isForward() && fb.getDest() == fa.getDest()) ||
										(!fb.isForward() && fb.getSource() == fa.getDest()))) {
										if(!rd.messagePreviouslyIssued("roundabout forks/overlaps")) {
											log.warn("Roundabout " + rd + " overlaps " + fb.getRoadDef() + " at " + coord.toOSMURL());
										}
									}
									else if (fb.isForward()
											&& !rd.messagePreviouslyIssued("roundabout forks/overlaps")) {
										log.warn("Roundabout " + rd + " forks at " + coord.toOSMURL());
									}
								}
							}
						}
					}
				}
			}
		}
	}

	// determine "distance" between two nodes on a roundabout
	private static int roundaboutSegmentLength(final RouteNode n1, final RouteNode n2) {
		List<RouteNode> seen = new ArrayList<>();
		int len = 0;
		RouteNode n = n1;
		boolean checkMoreLinks = true;
		while(checkMoreLinks && !seen.contains(n)) {
			checkMoreLinks = false;
			seen.add(n);
			for(RouteArc a : n.arcs) {
				if(a.isForward() &&
				   a.getRoadDef().isRoundabout() &&
				   !a.getRoadDef().isSynthesised()) {
					len += a.getLength();
					n = a.getDest();
					if(n == n2)
						return len;
					checkMoreLinks = true;
					break;
				}
			}
		}
		// didn't find n2
		return Integer.MAX_VALUE;
	}

	// sanity check roundabout flare roads - the flare roads connect a
	// two-way road to a roundabout using short one-way segments so
	// the resulting sub-junction looks like a triangle with two
	// corners of the triangle being attached to the roundabout and
	// the last corner being connected to the two-way road

	public void checkRoundaboutFlares(int maxFlareLengthRatio) {
		for(RouteArc r : arcs) {
			// see if node has a forward arc that is part of a
			// roundabout
			if(!r.isForward() || !r.isDirect() || !r.getRoadDef().isRoundabout() || r.getRoadDef().isSynthesised())
				continue;

			// follow the arc to find the first node that connects the
			// roundabout to a non-roundabout segment
			RouteNode nb = r.getDest();
			List<RouteNode> seen = new ArrayList<>();
			seen.add(this);

			while (true) {

				if (seen.contains(nb)) {
					// looped - give up
					nb = null;
					break;
				}

				// remember we have seen this node
				seen.add(nb);

				boolean connectsToNonRoundaboutSegment = false;
				RouteArc nextRoundaboutArc = null;
				for (RouteArc nba : nb.arcs) {
					if (!nba.isDirect())
						continue;
					if (!nba.getRoadDef().isSynthesised()) {
						if (nba.getRoadDef().isRoundabout()) {
							if (nba.isForward())
								nextRoundaboutArc = nba;
						} else {
							connectsToNonRoundaboutSegment = true;
						}
					}
				}

				if (connectsToNonRoundaboutSegment) {
					// great, that's what we're looking for
					break;
				}

				if (nextRoundaboutArc == null) {
					// not so good, the roundabout stops in mid air?
					nb = null;
					break;
				}

				nb = nextRoundaboutArc.getDest();
			}

			if(nb == null) {
				// something is not right so give up
				continue;
			}

			// now try and find the two arcs that make up the
			// triangular "flare" connected to both ends of the
			// roundabout segment
			for(RouteArc fa : arcs) {
				if(!fa.isDirect() || !fa.getRoadDef().doFlareCheck())
					continue;

				for(RouteArc fb : nb.arcs) {
					if(!fb.isDirect() || !fb.getRoadDef().doFlareCheck())
						continue;
					if(fa.getDest() == fb.getDest()) {
						// found the 3rd point of the triangle that
						// should be connecting the two flare roads

						// first, special test required to cope with
						// roundabouts that have a single flare and no
						// other connections - only check the flare
						// for the shorter of the two roundabout
						// segments

						if(roundaboutSegmentLength(this, nb) >=
						   roundaboutSegmentLength(nb, this))
							continue;

						if(maxFlareLengthRatio > 0) {
							// if both of the flare roads are much
							// longer than the length of the
							// roundabout segment, they are probably
							// not flare roads at all but just two
							// roads that meet up - so ignore them
							final int maxFlareLength = roundaboutSegmentLength(this, nb) * maxFlareLengthRatio;
							if(maxFlareLength > 0 &&
							   fa.getLength() > maxFlareLength &&
							   fb.getLength() > maxFlareLength) {
								continue;
							}
						}

						// now check the flare roads for direction and
						// oneway

						// only issue one warning per flare
						if(!fa.isForward())
							log.warn("Outgoing roundabout flare road " + fa.getRoadDef() + " points in wrong direction? " + fa.getSource().coord.toOSMURL());
						else if(fb.isForward())
							log.warn("Incoming roundabout flare road " + fb.getRoadDef() + " points in wrong direction? " + fb.getSource().coord.toOSMURL());
						else if(!fa.getRoadDef().isOneway())
							log.warn("Outgoing roundabout flare road " + fa.getRoadDef() + " is not oneway? " + fa.getSource().coord.toOSMURL());

						else if(!fb.getRoadDef().isOneway())
							log.warn("Incoming roundabout flare road " + fb.getRoadDef() + " is not oneway? " + fb.getDest().coord.toOSMURL());
						else {
							// check that the flare road arcs are not
							// part of a longer way
							for(RouteArc a : fa.getDest().arcs) {
								if(a.isDirect() && a.getDest() != this && a.getDest() != nb) {
									if(a.getRoadDef() == fa.getRoadDef())
										log.warn("Outgoing roundabout flare road " + fb.getRoadDef() + " does not finish at flare? " + fa.getDest().coord.toOSMURL());
									else if(a.getRoadDef() == fb.getRoadDef())
										log.warn("Incoming roundabout flare road " + fb.getRoadDef() + " does not start at flare? " + fb.getDest().coord.toOSMURL());
								}
							}
						}
					}
				}
			}
		}
	}

	public void reportSimilarArcs() {
		for(int i = 0; i < arcs.size(); ++i) {
			RouteArc arci = arcs.get(i);
			if (!arci.isDirect())
				continue;
			for(int j = i + 1; j < arcs.size(); ++j) {
				RouteArc arcj = arcs.get(j);
				if (!arcj.isDirect())
					continue;
				if(arci.getDest() == arcj.getDest() &&
				   arci.getLength() == arcj.getLength() &&
				   arci.getPointsHash() == arcj.getPointsHash()) {
					log.warn("Similar arcs (" + arci.getRoadDef() + " and " + arcj.getRoadDef() + ") from " + coord.toOSMURL());
				}
			}
		}
	}

	/**
	 * For each arc on the road, check if we can add indirect arcs to 
	 * other nodes of the same road. This is done if the other node
	 * lies on a different road with a higher road class than the
	 * highest other road of the target node of the arc. We do this
	 * for both forward and reverse arcs. Multiple indirect arcs
	 * may be added for each Node. An indirect arc will 
	 * always point to a higher road than the previous arc.
	 * The length and direct bearing of the additional arc is measured 
	 * from the target node of the preceding arc to the new target node.
	 * The initial bearing doesn't really matter as it is not written
	 * for indirect arcs.  
	 * @param road
	 */
	public void addArcsToMajorRoads(RoadDef road){
		assert road.getNode() == this;
		RouteNode current = this;
		// the nodes of this road
		List<RouteNode> nodes = new ArrayList<>();
		// the forward arcs of this road
		List<RouteArc> forwardArcs = new ArrayList<>();
		// will contain the highest other road of each node 
		IntArrayList forwardArcPositions = new IntArrayList();
		List<RouteArc> reverseArcs = new ArrayList<>();
		IntArrayList reverseArcPositions = new IntArrayList();

		// collect the nodes of the road and remember the arcs between them
		nodes.add(current);
		while (current != null){
			RouteNode next = null;
			for (int i = 0; i < current.arcs.size(); i++){
				RouteArc arc = current.arcs.get(i);
				if (arc.getRoadDef() == road && arc.isDirect()){
					if (arc.isForward()){
						next = arc.getDest();
						nodes.add(next);
						forwardArcs.add(arc);
						forwardArcPositions.add(i);
					} else {
						reverseArcPositions.add(i);
						reverseArcs.add(arc);
					}
				}
			}
			current = next;
		}
		
		if (nodes.size() < 3)
			return;
		ArrayList<RouteArc> newArcs = new ArrayList<>(); 
		IntArrayList arcPositions = forwardArcPositions;
		List<RouteArc> roadArcs = forwardArcs;
		for (int dir = 0; dir < 2; dir++){
			// forward arcs first
			for (int i = 0; i + 2 < nodes.size(); i++){
				RouteNode sourceNode = nodes.get(i); // original source node of direct arc
				RouteNode stepNode = nodes.get(i+1); 
				RouteArc arcToStepNode = roadArcs.get(i);
				assert arcToStepNode.getDest() == stepNode;
				int currentClass = arcToStepNode.getArcDestClass();
				int finalClass = road.getRoadClass();
				if (finalClass <= currentClass)
					continue;
				newArcs.clear();
				double partialArcLength = 0;
				double pathLength = arcToStepNode.getLengthInMeter();
				for (int j = i+2; j < nodes.size(); j++){
					RouteArc arcToDest = roadArcs.get(j-1);
					partialArcLength += arcToDest.getLengthInMeter();
					pathLength += arcToDest.getLengthInMeter();
					int cl = nodes.get(j).getGroup();
					if (cl > currentClass){
						if (cl > finalClass)
							cl = finalClass;
						currentClass = cl;
						// create indirect arc from node i+1 to node j
						RouteNode destNode = nodes.get(j);
						Coord c1 = sourceNode.getCoord();
						Coord c2 = destNode.getCoord();
						RouteArc newArc = new RouteArc(road, 
								sourceNode, 
								destNode, 
								roadArcs.get(i).getInitialHeading(), // not used
								c1.bearingTo(c2),
								partialArcLength, // from stepNode to destNode on road
								pathLength, // from sourceNode to destNode on road
								c1.distance(c2), 
								c1.hashCode() + c2.hashCode());
						if (arcToStepNode.isDirect())
							arcToStepNode.setMaxDestClass(0);
						else 
							newArc.setMaxDestClass(cl);
						if (dir == 0)
							newArc.setForward();
						newArc.setIndirect();
						newArcs.add(newArc);
						arcToStepNode = newArc;
						
						partialArcLength = 0;
						if (cl >= finalClass)
							break;
					}
				}
				if (!newArcs.isEmpty()) {
					int directArcPos = arcPositions.getInt(i);
					assert nodes.get(i).arcs.get(directArcPos).isDirect();
					assert nodes.get(i).arcs.get(directArcPos).getRoadDef() == newArcs.get(0).getRoadDef();
					assert nodes.get(i).arcs.get(directArcPos).isForward() == newArcs.get(0).isForward();
					nodes.get(i).arcs.addAll(directArcPos + 1, newArcs);
					if (dir == 0 && i > 0){
						// check if the inserted arcs change the position of the direct reverse arc
						int reverseArcPos = reverseArcPositions.get(i-1); // i-1 because first node doesn't have reverse arc 
						if (directArcPos < reverseArcPos)
							reverseArcPositions.set(i - 1, reverseArcPos + newArcs.size());
					}
				}
			}
			if (dir > 0)
				break;
			// reverse the arrays for the other direction
			Collections.reverse(reverseArcs);
			Collections.reverse(reverseArcPositions);
			Collections.reverse(nodes);
			arcPositions = reverseArcPositions;
			roadArcs = reverseArcs;
		}
	}

	/**
	 * Find the class group of the node. Rules:
	 * 1. Find the highest class which is used more than once.
	 * 2. Otherwise: use the class if the only one, or else the n-1 class.
     * (eg: if [1,] then use 1, if [1,2,] then use 1, if [1,2,3,] then 	use 2.
 	 * 
	 * @return the class group
	 */
	public int getGroup() {
		if (nodeGroup < 0){
			if (arcs.isEmpty()) {
				nodeGroup = 0;
				return nodeGroup;
			}
			HashSet<RoadDef> roads = new HashSet<>();
			for (RouteArc arc: arcs){
				roads.add(arc.getRoadDef());
			}
			int[] classes = new int[5];
			int numClasses = 0;
			// find highest class that is used more than once
			for (RoadDef road: roads){
				int cl = road.getRoadClass();
				int n = ++classes[cl];
				if (n == 1)
					numClasses++;
				else if (n > 1 && cl > nodeGroup)
					nodeGroup = (byte) cl;
			}
			if (nodeGroup >= 0)
				return nodeGroup;
			if (numClasses == 1)
				nodeGroup = nodeClass; // only one class
			else {
				// find n-1 class 
				int n = 0;
				for (int cl = 4; cl >= 0; cl--){
					if (classes[cl] > 0){
						if (n == 1){
							nodeGroup = (byte) cl;
							break;
						}
						n++;
					}
				}
			}
		}
		return nodeGroup;
	}

	public List<RouteArc> getArcs() {
		return arcs;
	}
	
	@Override
	public int hashCode() {
		return getCoord().getId();	
	}

	public List<RouteArc> getDirectArcsBetween(RouteNode otherNode) {
		List<RouteArc> result = new ArrayList<>();
		for(RouteArc a : arcs){
			if(a.isDirect() && a.getDest() == otherNode){
				result.add(a);
			}
		}
		return result;
	}

	/** used to find routing island, but may also be used for other checks */
	private int visitId;
	
	public int getVisitID() {
		return visitId;
	}

	public void visitNet(int visitId, List<RouteNode> visited) {
		if (this.visitId != visitId) {
			Deque<RouteNode> toVisit = new ArrayDeque<>();
			toVisit.add(this);
			while(!toVisit.isEmpty()) {
				RouteNode n = toVisit.pop();
				if (n.visitId != visitId) {
					n.arcs.forEach(a -> toVisit.addLast(a.getDest()));
					visited.add(n);
					n.visitId = visitId;
				}
			}
		}
	}
}
