/*
 * Copyright (C) 2007 Steve Ratcliffe
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
 * Create date: Feb 17, 2008
 */
package uk.me.parabola.mkgmap.osmstyle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.regex.Pattern;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.Exit;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.net.NODHeader;
import uk.me.parabola.imgfmt.app.trergn.ExtTypeAttributes;
import uk.me.parabola.imgfmt.app.trergn.MapObject;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.build.LocatorUtil;
import uk.me.parabola.mkgmap.filters.LineSizeSplitterFilter;
import uk.me.parabola.mkgmap.general.AreaClipper;
import uk.me.parabola.mkgmap.general.Clipper;
import uk.me.parabola.mkgmap.general.LineAdder;
import uk.me.parabola.mkgmap.general.LineClipper;
import uk.me.parabola.mkgmap.general.MapCollector;
import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapExitPoint;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.general.RoadNetwork;
import uk.me.parabola.mkgmap.osmstyle.housenumber.HousenumberGenerator;
import uk.me.parabola.mkgmap.reader.osm.CoordPOI;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.FeatureKind;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.OsmConverter;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.RestrictionRelation;
import uk.me.parabola.mkgmap.reader.osm.Rule;
import uk.me.parabola.mkgmap.reader.osm.Style;
import uk.me.parabola.mkgmap.reader.osm.TypeResult;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.GpxCreator;

/**
 * Convert from OSM to the mkgmap intermediate format using a style.
 * A style is a collection of files that describe the mappings to be used
 * when converting.
 *
 * @author Steve Ratcliffe
 */
public class StyledConverter implements OsmConverter {
	private static final Logger log = Logger.getLogger(StyledConverter.class);

	private final List<String> nameTagList;

	private final MapCollector collector;

	private Clipper clipper = Clipper.NULL_CLIPPER;
	private Area bbox;

	// restrictions associates lists of turn restrictions with the
	// Coord corresponding to the restrictions' 'via' node
	private final Map<Coord, List<RestrictionRelation>> restrictions = new IdentityHashMap<Coord, List<RestrictionRelation>>();

	private final List<Relation> throughRouteRelations = new ArrayList<Relation>();

	// limit line length to avoid problems with portions of really
	// long lines being assigned to the wrong subdivision
	private static final int MAX_LINE_LENGTH = 40000;

	// limit arc lengths to what can be handled by RouteArc
	private static final int MAX_ARC_LENGTH = 20450000; // (1 << 22) * 16 / 3.2808 ~ 20455030*/

	private static final int MAX_NODES_IN_WAY = 64; // possibly could be increased

	// nodeIdMap maps a Coord into a nodeId
	private IdentityHashMap<Coord, Integer> nodeIdMap = new IdentityHashMap<Coord, Integer>();
	
	private List<Way> roads = new ArrayList<Way>();
	private List<GType> roadTypes = new ArrayList<GType>();
	private List<Way> lines = new ArrayList<Way>();
	private List<GType> lineTypes = new ArrayList<GType>();
	private HashMap<Long, Way> modifiedRoads = new HashMap<Long, Way>();
	private HashSet<Long> deletedRoads = new HashSet<Long>();
	private String gpxPath;

	private final double minimumArcLength;
	
	private int nextNodeId = 1;
	
	private HousenumberGenerator housenumberGenerator;
	
	private final Rule wayRules;
	private final Rule nodeRules;
	private final Rule lineRules;
	private final Rule polygonRules;

	private final boolean ignoreMaxspeeds;
	private boolean driveOnLeft;
	private boolean driveOnRight;
	private final boolean checkRoundabouts;
	private final boolean linkPOIsToWays;
	private static final Pattern ENDS_IN_MPH_PATTERN = Pattern.compile(".*mph");
	private static final Pattern REMOVE_MPH_PATTERN = Pattern.compile("[ \t]*mph");
	private static final Pattern REMOVE_KPH_PATTERN = Pattern.compile("[ \t]*kmh");
	private static final Pattern SEMI_PATTERN = Pattern.compile(";");

	class AccessMapping {
		private final String type;
		private final int index;
		AccessMapping(String type, int index) {
			this.type = type;
			this.index = index;
		}
	}

	private final AccessMapping[] accessMap = {
		new AccessMapping("access",     RoadNetwork.NO_MAX), // must be first in list
		new AccessMapping("bicycle",    RoadNetwork.NO_BIKE),
		new AccessMapping("carpool",    RoadNetwork.NO_CARPOOL),
		new AccessMapping("foot",       RoadNetwork.NO_FOOT),
		new AccessMapping("hgv",        RoadNetwork.NO_TRUCK),
		new AccessMapping("motorcar",   RoadNetwork.NO_CAR),
		new AccessMapping("motorcycle", RoadNetwork.NO_CAR),
		new AccessMapping("psv",        RoadNetwork.NO_BUS),
		new AccessMapping("taxi",       RoadNetwork.NO_TAXI),
		new AccessMapping("emergency",  RoadNetwork.NO_EMERGENCY),
		new AccessMapping("delivery",   RoadNetwork.NO_DELIVERY),
		new AccessMapping("goods",      RoadNetwork.NO_DELIVERY),
	};

	private LineAdder lineAdder = new LineAdder() {
		public void add(MapLine element) {
			if (element instanceof MapRoad)
				collector.addRoad((MapRoad) element);
			else
				collector.addLine(element);
		}
	};

	public StyledConverter(Style style, MapCollector collector, Properties props) {
		this.collector = collector;

		nameTagList = LocatorUtil.getNameTags(props);

		wayRules = style.getWayRules();
		nodeRules = style.getNodeRules();
		lineRules = style.getLineRules();
		polygonRules = style.getPolygonRules();
		
		housenumberGenerator = new HousenumberGenerator(props);

		ignoreMaxspeeds = props.getProperty("ignore-maxspeeds") != null;
		driveOnLeft = props.getProperty("drive-on-left") != null;
		// check if the setDriveOnLeft flag should be ignored 
		// (this is the case if precompiled sea is loaded)
		if (props.getProperty("ignore-drive-on-left") == null)
			// do not ignore the flag => initialize it
			NODHeader.setDriveOnLeft(driveOnLeft);
		driveOnRight = props.getProperty("drive-on-right") != null;
		checkRoundabouts = props.getProperty("check-roundabouts") != null;

		LineAdder overlayAdder = style.getOverlays(lineAdder);
		if (overlayAdder != null)
			lineAdder = overlayAdder;
		String rsa = props.getProperty("remove-short-arcs", "0");
		minimumArcLength = (!rsa.isEmpty())? Double.parseDouble(rsa) : 0.0;
		if (minimumArcLength > 0){
			System.err.println("Warning: remove-short-arcs=" + rsa + " overrides default 0." +
					" This is no longer recommended for a routable map.");
		}
		linkPOIsToWays = props.getProperty("link-pois-to-ways") != null;
		gpxPath = props.getProperty("gpx-dir", null);
		if (gpxPath != null){
			if (gpxPath.endsWith("/") == false && gpxPath.endsWith("\\") == false)
				gpxPath += "/";
		}
	}

	/**
	 * This takes the way and works out what kind of map feature it is and makes
	 * the relevant call to the mapper callback.
	 * <p>
	 * As a few examples we might want to check for the 'highway' tag, work out
	 * if it is an area of a park etc.
	 *
	 * @param way The OSM way.
	 */
	public void convertWay(final Way way) {
		if (way.getPoints().size() < 2)
			return;
		
		if (way.getTagCount() == 0) {
			// no tags => nothing to convert
			return;
		}

		preConvertRules(way);

		housenumberGenerator.addWay(way);
		
		Rule rules;
		if ("polyline".equals(way.getTag("mkgmap:stylefilter")))
			rules = lineRules;
		else if ("polygon".equals(way.getTag("mkgmap:stylefilter")))
			rules = polygonRules;
		else
			rules = wayRules;
		
		rules.resolveType(way, new TypeResult() {
			public void add(Element el, GType type) {
				if (type.isContinueSearch()) {
					// If not already copied, do so now
					if (el == way)
						el = way.copy();
				}
				postConvertRules(el, type);
				addConvertedWay((Way) el, type);
			}
		});
	}

	private void addConvertedWay(Way way, GType foundType) {
		if (foundType.getFeatureKind() == FeatureKind.POLYLINE) {
		    if(foundType.isRoad() &&
			   !MapObject.hasExtendedType(foundType.getType())){
		    	roads.add(way);
		    	roadTypes.add(new GType(foundType));
		    }
		    else {
		    	lines.add(way);
		    	lineTypes.add(new GType(foundType));
		    }
		}
		else
			addShape(way, foundType);
		}

	/**
	 * Takes a node (that has its own identity) and converts it from the OSM
	 * type to the Garmin map type.
	 *
	 * @param node The node to convert.
	 */
	public void convertNode(final Node node) {
		if (node.getTagCount() == 0) {
			// no tags => nothing to convert
			return;
		}

		preConvertRules(node);

		housenumberGenerator.addNode(node);
		
		nodeRules.resolveType(node, new TypeResult() {
			public void add(Element el, GType type) {
				if (type.isContinueSearch()) {
					// If not already copied, do so now
					if (el == node)
						el = node.copy();
				}
				postConvertRules(el, type);
				addPoint((Node) el, type);
			}
		});
	}

	/**
	 * Rules to run before converting the element.
	 */
	private void preConvertRules(Element el) {
		if (nameTagList == null)
			return;

		for (String t : nameTagList) {
			String val = el.getTag(t);
			if (val != null) {
				el.addTag("name", val);
				break;
			}
		}
	}

	/**
	 * Built in rules to run after converting the element.
	 */
	private void postConvertRules(Element el, GType type) {
		// Set the name from the 'name' tag or failing that from
		// the default_name.
		el.setName(el.getTag("name"));
		if (el.getName() == null)
			el.setName(type.getDefaultName());
	}

	/**
	 * Set the bounding box for this map.  This should be set before any other
	 * elements are converted if you want to use it. All elements that are added
	 * are clipped to this box, new points are added as needed at the boundary.
	 *
	 * If a node or a way falls completely outside the boundary then it would be
	 * omitted.  This would not normally happen in the way this option is typically
	 * used however.
	 *
	 * @param bbox The bounding area, must not be null.
	 */
	public void setBoundingBox(Area bbox) {
		this.clipper = new AreaClipper(bbox);
		this.bbox = bbox;

		// we calculate our own bounding box, now let the collector know about it.
		collector.addToBounds(new Coord(bbox.getMinLat(), bbox.getMinLong()));
		collector.addToBounds(new Coord(bbox.getMaxLat(), bbox.getMaxLong()));
	}

	public void end() {
		setHighwayCounts();
		findUnconnectedRoads();
		filterCoordPOI();
		removeWrongAngles();
//		removeShortArcsByMergingNodes();
		// make sure that copies of modified roads are have equal points 
		for (int i = 0; i < lines.size(); i++){
			Way line = lines.get(i);
			if (deletedRoads.contains(line.getId())){
				lines.set(i, null);
				continue;
			}
			Way modWay = modifiedRoads.get(line.getId());
			if (modWay != null){
				List<Coord> points = line.getPoints();
				points.clear();
				points.addAll(modWay.getPoints());
			}
		}
		deletedRoads = null;
		modifiedRoads = null;

		for (int i = 0; i < lines.size(); i++){
			Way line = lines.get(i);
			if (line == null)
				continue;
			GType gt = lineTypes.get(i);
			addLine(line, gt);
		}
		lines = null;
		lineTypes = null;

		// add the roads after the other lines
		for (int i = 0; i < roads.size(); i++){
			Way road = roads.get(i);
			if (road == null)
				continue;
			GType gt = roadTypes.get(i);
			addRoad(road, gt);
		}
		roads = null;
		roadTypes = null;
		
		housenumberGenerator.generate(lineAdder);
		
		Collection<List<RestrictionRelation>> lists = restrictions.values();
		for (List<RestrictionRelation> l : lists) {

			for (RestrictionRelation rr : l) {
				rr.addRestriction(collector);
			}
		}

		for(Relation relation : throughRouteRelations) {
			Node node = null;
			Way w1 = null;
			Way w2 = null;
			for(Map.Entry<String,Element> member : relation.getElements()) {
				if(member.getValue() instanceof Node) {
					if(node == null)
						node = (Node)member.getValue();
					else
						log.warn("Through route relation " + relation.toBrowseURL() + " has more than 1 node");
				}
				else if(member.getValue() instanceof Way) {
					Way w = (Way)member.getValue();
					if(w1 == null)
						w1 = w;
					else if(w2 == null)
						w2 = w;
					else
						log.warn("Through route relation " + relation.toBrowseURL() + " has more than 2 ways");
				}
			}

			Integer nodeId = null;
			if(node == null)
				log.warn("Through route relation " + relation.toBrowseURL() + " is missing the junction node");
			else {
				Coord junctionPoint = node.getLocation();
				if(bbox != null && !bbox.contains(junctionPoint)) {
					// junction is outside of the tile - ignore it
					continue;
				}
				nodeId = nodeIdMap.get(junctionPoint);
				if(nodeId == null)
					log.warn("Through route relation " + relation.toBrowseURL() + " junction node at " + junctionPoint.toOSMURL() + " is not a routing node");
			}

			if(w1 == null || w2 == null)
				log.warn("Through route relation " + relation.toBrowseURL() + " should reference 2 ways that meet at the junction node");

			if(nodeId != null && w1 != null && w2 != null)
				collector.addThroughRoute(nodeId, w1.getId(), w2.getId());
		}

		// return memory to GC
		nodeIdMap = null;
		throughRouteRelations.clear();
		restrictions.clear();
	}

	/**
	 * Check if roundabout has correct direction. Set driveOnRight or
	 * driveOnLeft is not yet set.
	 * 
	 * @param way
	 */
	private void checkRoundabout(Way way){
		if("roundabout".equals(way.getTag("junction"))) {
			List<Coord> points = way.getPoints();
			// if roundabout checking is enabled and roundabout has at
			// least 3 points and it has not been marked as "don't
			// check", check its direction
			if(checkRoundabouts &&
			   way.getPoints().size() > 2 &&
			   !way.isBoolTag("mkgmap:no-dir-check") &&
			   !way.isNotBoolTag("mkgmap:dir-check")) {
				Coord centre = way.getCofG();
				int dir = 0;
				// check every third segment
				for(int i = 0; (i + 1) < points.size(); i += 3) {
					Coord pi = points.get(i);
					Coord pi1 = points.get(i + 1);
					//TODO: check if high prec coords allow to use smaller distance 
					// don't check segments that are very short
					if(pi.distance(centre) > 2.5 &&
					   pi.distance(pi1) > 2.5) {
						// determine bearing from segment that starts with
						// point i to centre of roundabout
						double a = pi.bearingTo(pi1);
						double b = pi.bearingTo(centre) - a;
						while(b > 180)
							b -= 360;
						while(b < -180)
							b += 360;
						// if bearing to centre is between 15 and 165
						// degrees consider it trustworthy
						if(b >= 15 && b < 165)
							++dir;
						else if(b <= -15 && b > -165)
							--dir;
					}
				}
				if (dir == 0)
					log.info("Roundabout segment " + way.getId() + " direction unknown (see " + points.get(0).toOSMURL() + ")");
				else {
					boolean clockwise = dir > 0;
					if (points.get(0) == points.get(points.size() - 1)) {
						// roundabout is a loop
						if (!driveOnLeft && !driveOnRight) {
							if (clockwise) {
								log.info("Roundabout " + way.getId() + " is clockwise so assuming vehicles should drive on left side of road (" + centre.toOSMURL() + ")");
								driveOnLeft = true;
								NODHeader.setDriveOnLeft(true);
							} else {
								log.info("Roundabout " + way.getId() + " is anti-clockwise so assuming vehicles should drive on right side of road (" + centre.toOSMURL() + ")");
								driveOnRight = true;
							}
						}
						if (driveOnLeft && !clockwise ||
								driveOnRight && clockwise)
						{
							log.warn("Roundabout " + way.getId() + " direction is wrong - reversing it (see " + centre.toOSMURL() + ")");
							way.reverse();
						}
					} else if (driveOnLeft && !clockwise ||
							driveOnRight && clockwise)
					{
						// roundabout is a line
						log.warn("Roundabout segment " + way.getId() + " direction looks wrong (see " + points.get(0).toOSMURL() + ")");
					}
				}
			}
		}
		
	}

	/**
	 * Merge points with equal map unit coordinates.
	 * @param way
	 * @param replacements
	 * @return true if one or more points were merged 
	 */
	private boolean mergeEqualPoints(Way way, Map<Coord, Coord> replacements) {
		List<Coord> points = way.getPoints();
		int origNumPoints = points.size();
		boolean closed = (points.get(0) == points.get(origNumPoints-1));
		ArrayList<Coord> toReplace = new ArrayList<Coord>();
		for (int i = 0; i+1  < points.size(); i++){
			Coord p = points.get(i);
			if (p.getOnBoundary())
				continue;
			toReplace.clear();
			Node poiNode = null;
			if (p instanceof CoordPOI)
				poiNode = ((CoordPOI) p).getNode();
			int nextPos = i+1;
			Coord pNext = points.get(nextPos);
			double lat = p.getHighPrecLat();
			double lon = p.getHighPrecLon();
			int numPoints = 1;
			while (pNext.equals(p)){
				lat += pNext.getHighPrecLat();
				lon += pNext.getHighPrecLon();
				++numPoints;
				points.remove(nextPos);
				if (pNext.getHighwayCount() >=  ((closed && nextPos == points.size()-1) ? 3 : 2))
					toReplace.add(pNext);
				
				if (poiNode == null && pNext instanceof CoordPOI)
					poiNode = ((CoordPOI) pNext).getNode();
				if (nextPos >= points.size())
					break;
				pNext = points.get(nextPos);
			}
			if (numPoints > 1){
				modifiedRoads.put(way.getId(), way);
				Coord pNew = Coord.makeHighPrecCoord((int)Math.round(lat / numPoints), (int)Math.round(lon/numPoints));
				if (poiNode != null){
					pNew = new CoordPOI(pNew);
					((CoordPOI) pNew).setNode(poiNode);
				}
				pNew.incHighwayCount();
				points.set(i, pNew);
				// check if the merged point is a node
				if (p.getHighwayCount() >=  ((closed && i == 0) ? 3 : 2))
					toReplace.add(p);

				for (Coord co:toReplace){
					pNew.incHighwayCount();
					co.setReplaced(true);
					replacements.put(co, pNew);
				}
			}
		}
		return points.size() != origNumPoints;
	}

	/**
	 * Run the rules for this relation.  As this is not an end object, then
	 * the only useful rules are action rules that set tags on the contained
	 * ways or nodes.  Every rule should probably start with 'type=".."'.
	 *
	 * @param relation The relation to convert.
	 */
	public void convertRelation(Relation relation) {
		if (relation.getTagCount() == 0) {
			// no tags => nothing to convert
			return;
		}

		housenumberGenerator.addRelation(relation);

		// relation rules are not applied here because they are applied
		// earlier by the RelationStyleHook
		
		if(relation instanceof RestrictionRelation) {
			RestrictionRelation rr = (RestrictionRelation)relation;
			if(rr.isValid()) {
				List<RestrictionRelation> lrr = restrictions.get(rr.getViaCoord());
				if(lrr == null) {
					lrr = new ArrayList<RestrictionRelation>();
					restrictions.put(rr.getViaCoord(), lrr);
				}
				lrr.add(rr);
			}
		}
		else if("through_route".equals(relation.getTag("type"))) {
			throughRouteRelations.add(relation);
		}
	}

	private void addLine(Way way, GType gt) {
		List<Coord> wayPoints = way.getPoints();
		List<Coord> points = new ArrayList<Coord>(wayPoints.size());
		double lineLength = 0;
		Coord lastP = null;
		for(Coord p : wayPoints) {
			if (lastP != null && p.equals(lastP) && p instanceof CoordPOI == false && p instanceof CoordNode == false)
				continue;
			points.add(p);
			if(lastP != null) {
				lineLength += p.distance(lastP);
				if(lineLength >= MAX_LINE_LENGTH) {
					log.info("Splitting line " + way.toBrowseURL() + " at " + p.toOSMURL() + " to limit its length to " + (long)lineLength + "m");
					addLine(way, gt, points);
					points = new ArrayList<Coord>(wayPoints.size() - points.size() + 1);
					points.add(p);
					lineLength = 0;
				}
			}
			lastP = p;
		}

		if(points.size() > 1)
			addLine(way, gt, points);
	}

	private void addLine(Way way, GType gt, List<Coord> points) {
		MapLine line = new MapLine();
		elementSetup(line, gt, way);
		line.setPoints(points);

		if (way.isBoolTag("oneway"))
			line.setDirection(true);
		if (way.isBoolTag("mkgmap:skipSizeFilter"))
			line.setSkipSizeFilter(true);

		clipper.clipLine(line, lineAdder);
	}

	private void addShape(Way way, GType gt) {
		// This is deceptively simple. At the time of writing, splitter only retains points that are within
		// the tile and some distance around it.  Therefore a way that is closed in reality may not be closed
		// as we see it in its incomplete state.
		//
		// Here isClosed means that it is really closed in OSM, and therefore it is safe to clip the line
		// segment to the tile boundaries.
		if (!way.isClosed())
			return;

		final MapShape shape = new MapShape();
		elementSetup(shape, gt, way);
		shape.setPoints(way.getPoints());
		if (way.isBoolTag("mkgmap:skipSizeFilter"))
			shape.setSkipSizeFilter(true);

		clipper.clipShape(shape, collector);
	}

	private void addPoint(Node node, GType gt) {
		if (!clipper.contains(node.getLocation()))
			return;

		// to handle exit points we use a subclass of MapPoint
		// to carry some extra info (a reference to the
		// motorway associated with the exit)
		MapPoint mp;
		int type = gt.getType();
		if(type >= 0x2000 && type < 0x2800) {
			String ref = node.getTag(Exit.TAG_ROAD_REF);
			String id = node.getTag("mkgmap:osmid");
			if(ref != null) {
				String to = node.getTag(Exit.TAG_TO);
				MapExitPoint mep = new MapExitPoint(ref, to);
				String fd = node.getTag(Exit.TAG_FACILITY);
				if(fd != null)
					mep.setFacilityDescription(fd);
				if(id != null)
					mep.setOSMId(id);
				mp = mep;
			}
			else {
				mp = new MapPoint();
				log.warn("Motorway exit " + node.getName() + " (" + node.getLocation().toOSMURL() + ") has no motorway! (either make the exit share a node with the motorway or specify the motorway ref with a " + Exit.TAG_ROAD_REF + " tag)");
			}
		}
		else {
			mp = new MapPoint();
		}
		elementSetup(mp, gt, node);
		mp.setLocation(node.getLocation());

		collector.addPoint(mp);
	}

	private String combineRefs(Element element) {
		String ref = Label.squashSpaces(element.getTag("ref"));
		String int_ref = Label.squashSpaces(element.getTag("int_ref"));
		if(int_ref != null) {
			if(ref == null)
				ref = int_ref;
			else
				ref += ";" + int_ref;
		}
		String nat_ref = Label.squashSpaces(element.getTag("nat_ref"));
		if(nat_ref != null) {
			if(ref == null)
				ref = nat_ref;
			else
				ref += ";" + nat_ref;
		}
		String reg_ref = Label.squashSpaces(element.getTag("reg_ref"));
		if(reg_ref != null) {
			if(ref == null)
				ref = reg_ref;
			else
				ref += ";" + reg_ref;
		}

		return ref;
	}

	private boolean displayNameWarning = true;
	
	private void elementSetup(MapElement ms, GType gt, Element element) {
		String name = Label.squashSpaces(element.getName());
		String refs = combineRefs(element);
		
		// Insert mkgmap:display_name as first ref.
		// This causes mkgmap:display_name to be displayed in routing 
		// directions, instead of only the ref.
		String displayName = Label.squashSpaces(element.getTag("mkgmap:display_name"));
		
		// be downward compatible if old tag display_name is used
		if (displayName == null) {
			// get the old tag display_name which should not be used any more (Dec 2012)
			displayName = Label.squashSpaces(element.getTag("display_name"));
			if (displayName != null && displayNameWarning) {
				System.err.println("WARNING: Style uses tag 'display_name' which is deprecated " +
						"and will be removed soon. Please use the new tag 'mkgmap:display_name' instead.");
				log.warn("Style uses tag 'display_name' which is deprecated",
						"and will be removed soon. Please use the new tag 'mkgmap:display_name' instead.");
				displayNameWarning = false;
			}
		}
		
		if (displayName != null) {
			// substitute '/' for ';' in mkgmap:display_name to avoid it
			// getting split below
			displayName = displayName.replace(";","/");
			if (refs == null)
				refs = displayName;
			else
				refs = displayName + ";" + refs;
		}

		if(name == null && refs != null) {
			// use first ref as name
			String[] names = SEMI_PATTERN.split(refs);
			if (names.length > 0)
				name = names[0].trim();
		}
		else if(name != null) {
			// remove leading spaces (don't use trim() to avoid zapping
			// shield codes)
			char leadingCode = 0;
			if(name.length() > 1 &&
			   name.charAt(0) < 0x20 &&
			   name.charAt(1) == ' ') {
				leadingCode = name.charAt(0);
				name = name.substring(2);
			}
				
			while(!name.isEmpty() && name.charAt(0) == ' ')
				name = name.substring(1);

			if(leadingCode != 0)
				name = leadingCode + name;
		}

		if(name != null)
			ms.setName(name);
		if(refs != null)
			ms.setRef(refs);
		ms.setType(gt.getType());
		ms.setMinResolution(gt.getMinResolution());
		ms.setMaxResolution(gt.getMaxResolution());
		
		// Now try to get some address info for POIs
		
		String country      = element.getTag("mkgmap:country");
		String region       = element.getTag("mkgmap:region");
		String city         = element.getTag("mkgmap:city");
		String zip          = element.getTag("mkgmap:postal_code");
		String street 	    = element.getTag("mkgmap:street");
		String houseNumber  = element.getTag("mkgmap:housenumber");
		String phone        = element.getTag("mkgmap:phone");
		String isIn         = element.getTag("mkgmap:is_in");

		if(country != null)
			ms.setCountry(country);

		if(region != null)
			ms.setRegion(region);
		
		if(city != null)
			ms.setCity(city);
		  
		if(zip != null)
			ms.setZip(zip);
		  
		if(street != null)
			ms.setStreet(street);

		if(houseNumber != null)
			ms.setHouseNumber(houseNumber);
		  
		if(isIn != null)
			ms.setIsIn(isIn);
			
		if(phone != null)
			ms.setPhone(phone);


		
		if(MapObject.hasExtendedType(gt.getType())) {
			// pass attributes with mkgmap:xt- prefix (strip prefix)
			Map<String,String> xta = element.getTagsWithPrefix("mkgmap:xt-", true);
			// also pass all attributes with seamark: prefix (no strip prefix)
			xta.putAll(element.getTagsWithPrefix("seamark:", false));
			ms.setExtTypeAttributes(new ExtTypeAttributes(xta, "OSM id " + element.getId()));
		}
	}

	/**
	 * Add a way to the road network. May call itself recursively and
	 * might truncate the way if splitting is required. 
	 * @param way the way
	 * @param gt the type assigned by the style
	 */
	private void addRoad(Way way, GType gt) {
		if (way.getPoints().size() < 2){
			log.warn("road has < 2 points ",way.getId(),"(discarding)");
			return;
		}
		String oneWay = way.getTag("oneway");
		if("-1".equals(oneWay) || "reverse".equals(oneWay)) {
			// it's a oneway street in the reverse direction
			// so reverse the order of the nodes and change
			// the oneway tag to "yes"
			way.reverse();
			way.addTag("oneway", "yes");
			if("roundabout".equals(way.getTag("junction")))
				log.warn("Roundabout " + way.getId() + " has reverse oneway tag (" + way.getPoints().get(0).toOSMURL() + ")");
		}

		checkRoundabout(way);
		
		// process any Coords that have a POI associated with them
		if("true".equals(way.getTag("mkgmap:way-has-pois"))) {
			List<Coord> points = way.getPoints();

			// look for POIs that modify the way's road class or speed
			// this could be e.g. highway=traffic_signals that reduces the
			// road speed to cause a short increase of traveling time
			for(int i = 0; i < points.size(); ++i) {
				Coord p = points.get(i);
				if(p instanceof CoordPOI) {
					CoordPOI cp = (CoordPOI)p;
					Node node = cp.getNode();
					if ("true".equals(node.getTag("mkgmap:use-poi-in-way-"+way.getId()))){
						String roadClass = node.getTag("mkgmap:road-class");
						String roadSpeed = node.getTag("mkgmap:road-speed");
						if(roadClass != null || roadSpeed != null) {
							// if the way has more than one point
							// following this one, split the way at the
							// next point to limit the size of the
							// affected region
							if((i + 2) < points.size() &&
									safeToSplitWay(points, i + 1, i, points.size() - 1)) {
								Way tail = splitWayAt(way, i + 1);
								// recursively process tail of way
								addRoad(tail, gt);
							}
							// we can't modify the road class or type in
							// the GType as that's global so for now just
							// transfer the tags to the way
							if(roadClass != null) {
								way.addTag("mkgmap:road-class", roadClass);
								String val = node.getTag("mkgmap:road-class-min");
								if(val != null)
									way.addTag("mkgmap:road-class-min", val);
								val = node.getTag("mkgmap:road-class-max");
								if(val != null)
									way.addTag("mkgmap:road-class-max", val);
							}
							if(roadSpeed != null) {
								way.addTag("mkgmap:road-speed", roadSpeed);
								String val = node.getTag("mkgmap:road-speed-min");
								if(val != null)
									way.addTag("mkgmap:road-speed-min", val);
								val = node.getTag("mkgmap:road-speed-max");
								if(val != null)
									way.addTag("mkgmap:road-speed-max", val);
							}
						}
					}
				}

				// if this isn't the first (or last) point in the way
				// and the next point modifies the way's speed/class,
				// split the way at this point to limit the size of
				// the affected region
				if(i > 0 &&
				   (i + 1) < points.size() &&
				   points.get(i + 1) instanceof CoordPOI) {
					CoordPOI cp = (CoordPOI)points.get(i + 1);
					Node node = cp.getNode();
					if ("true".equals(node.getTag("mkgmap:use-poi-in-way-"+way.getId()))){
						if(node.getTag("mkgmap:road-class") != null ||
								node.getTag("mkgmap:road-speed") != null) {
							if(safeToSplitWay(points, i, i - 1, points.size() - 1)) {
								Way tail = splitWayAt(way, i);
								// recursively process tail of way
								addRoad(tail, gt);
							}
						}
					}
				}
			}

			// now look for POIs that have the "access" tag defined -
			// if they do, copy the access permissions to the way -
			// what we want to achieve is modifying the way's access
			// permissions where it passes through the POI without
			// affecting the rest of the way too much - to that end we
			// split the way before and after the POI - if necessary,
			// extra points are inserted before and after the POI to
			// limit the size of the affected region

			final double stubSegmentLength = 25; // metres
			for(int i = 0; i < points.size(); ++i) {
				Coord p = points.get(i);
				// check if this POI modifies access and if so, split
				// the way at the following point (if any) and then
				// copy its access restrictions to the way
				if(p instanceof CoordPOI) {
					CoordPOI cp = (CoordPOI)p;
					Node node = cp.getNode();
					if (node.getTag("access") != null &&
							"true".equals(node.getTag("mkgmap:use-poi-in-way-"+way.getId()))) {
						// if this or the next point are not the last
						// points in the way, split at the next point
						// taking care not to produce a short arc
						if((i + 1) < points.size()) {
							Coord p1 = points.get(i + 1);
							// check if the next point is further away
							// than we would like
							double dist = p.distance(p1);
							if(dist >= (2 * stubSegmentLength)) {
								// insert a new point after the POI to
								// make a short stub segment
								p1 = p.makeBetweenPoint(p1, stubSegmentLength / dist);
								p1.incHighwayCount();
								points.add(i + 1, p1);
							}

							// now split the way at the next point to
							// limit the region that has restricted
							// access
							if((i+2 < points.size() && 
									safeToSplitWay(points, i+1, 0, points.size()-1))) {
								Way tail = splitWayAt(way, i + 1);
								// recursively process tail of way
								addRoad(tail, gt);
							}
						}

						// make the POI a node so that the region with
						// restricted access is split into two as far
						// as routing is concerned - this should stop
						// routing across the POI when the start point
						// is within the restricted region and the
						// destination point is outside of the
						// restricted region on the other side of the
						// POI

						// however, this still doesn't stop routing
						// across the POI when both the start and end
						// points are either side of the POI and both
						// are in the restricted region
						if (p.getHighwayCount() < 2){
							if (i == 0|| i == points.size()-1 ||
									safeToSplitWay(points, i, 0, points.size()-1))
								p.incHighwayCount();
							else {
								points.set(i,new Coord(p));
								points.get(i).incHighwayCount();
							}
						}
						// copy all of the POI's access restrictions
						// to the way segment
						for (AccessMapping anAccessMap : accessMap) {
							String accessType = anAccessMap.type;
							String accessModifier = node.getTag(accessType);
							if(accessModifier != null)
								way.addTag(accessType, accessModifier);
						}
					}
				}

				// check if the next point modifies access and if so,
				// split the way either here or at a new point that's
				// closer to the POI taking care not to introduce a
				// short arc
				if((i + 1) < points.size()) {
					Coord p1 = points.get(i + 1);
					if(p1 instanceof CoordPOI) {
						CoordPOI cp = (CoordPOI)p1;
						Node node = cp.getNode();
						if(node.getTag("access") != null &&
								"true".equals(node.getTag("mkgmap:use-poi-in-way-"+way.getId()))) {
							// check if this point is further away
							// from the POI than we would like
							double dist = p.distance(p1);
							if(dist >= (2 * stubSegmentLength)) {
								// insert a new point to make a short
								// stub segment
								p1 = p1.makeBetweenPoint(p, stubSegmentLength / dist);
								p1.incHighwayCount();
								points.add(i + 1, p1);
								continue;
							}

							// now split the way here if it is not the
							// first point in the way
							if(i > 0 &&
							   safeToSplitWay(points, i, 0, points.size() - 1)) {
								Way tail = splitWayAt(way, i);
								// recursively process tail of road
								addRoad(tail, gt);
							}
						}
					}
				}
			}
		}

		// if there is a bounding box, clip the way with it

		List<Way> clippedWays = null;

		if(bbox != null) {
			List<List<Coord>> lineSegs = LineClipper.clip(bbox, way.getPoints());

			if (lineSegs != null) {

				clippedWays = new ArrayList<Way>();

				for (List<Coord> lco : lineSegs) {
					Way nWay = new Way(way.getId());
					nWay.setName(way.getName());
					nWay.copyTags(way);
					for(Coord co : lco) {
						nWay.addPoint(co);
						if(co.getOnBoundary()) {
							// this point lies on a boundary
							// make sure it becomes a node
							co.incHighwayCount();
						}
					}
					clippedWays.add(nWay);
				}
			}
		}

		if(clippedWays != null) {
			for(Way cw : clippedWays) {
				addRoadAfterSplittingLoops(cw, gt);
			}
		}
		else {
			// no bounding box or way was not clipped
			addRoadAfterSplittingLoops(way, gt);
		}
	}

	private void addRoadAfterSplittingLoops(Way way, GType gt) {
		// make sure the way has nodes at each end
		way.getPoints().get(0).incHighwayCount();
		way.getPoints().get(way.getPoints().size() - 1).incHighwayCount();

		// check if the way is a loop or intersects with itself

		boolean wayWasSplit = true; // aka rescan required

		while(wayWasSplit) {
			List<Coord> wayPoints = way.getPoints();
			int numPointsInWay = wayPoints.size();

			wayWasSplit = false; // assume way won't be split

			// check each point in the way to see if it is the same
			// point as a following point in the way (actually the
			// same object not just the same coordinates)
			for(int p1I = 0; !wayWasSplit && p1I < (numPointsInWay - 1); p1I++) {
				Coord p1 = wayPoints.get(p1I);
				if (p1.getHighwayCount() < 2)
					continue;
				for(int p2I = p1I + 1; !wayWasSplit && p2I < numPointsInWay; p2I++) {
					if(p1 == wayPoints.get(p2I)) {
						// way is a loop or intersects itself 
						// attempt to split it into two ways

						// start at point before intersection point
						// check that splitting there will not produce
						// a zero length arc - if it does try the
						// previous point(s)
						int splitI = p2I - 1;
						while(splitI > p1I &&
							  !safeToSplitWay(wayPoints, splitI, p1I, p2I)) {
								log.info("Looped way " + getDebugName(way) + " can't safely split at point[" + splitI + "], trying the preceeding point");
							--splitI;
						}

						if(splitI == p1I) {
							log.warn("Splitting looped way " + getDebugName(way) + " would make a zero length arc, so it will have to be pruned at " + wayPoints.get(p2I).toOSMURL());
							do {
								log.warn("  Pruning point[" + p2I + "]");
								wayPoints.remove(p2I);
								// next point to inspect has same index
								--p2I;
								// but number of points has reduced
								--numPointsInWay;
								if (p2I + 1 == numPointsInWay) 
									wayPoints.get(p2I).incHighwayCount();
								// if wayPoints[p2I] is the last point
								// in the way and it is so close to p1
								// that a short arc would be produced,
								// loop back and prune it
							} while(p2I > p1I &&
									(p2I + 1) == numPointsInWay &&
									p1.equals(wayPoints.get(p2I)));
						}
						else {
							// split the way before the second point
							log.info("Splitting looped way " + getDebugName(way) + " at " + wayPoints.get(splitI).toOSMURL() + " - it has " + (numPointsInWay - splitI - 1 ) + " following segment(s).");
							Way loopTail = splitWayAt(way, splitI);
							// recursively check (shortened) head for
							// more loops
							addRoadAfterSplittingLoops(way, gt);
							// now process the tail of the way
							way = loopTail;
							wayWasSplit = true;
						}
					}
				}
			}

			if(!wayWasSplit) {
				// no split required so make road from way
				addRoadWithoutLoops(way, gt);
			}
		}
	}

	/**
	 * safeToSplitWay() returns true if it is safe (no short arcs will be
	 * created) to split a way at a given position - assumes that the
	 * floor and ceiling points will become nodes even if they are not
	 * yet.
	 * @param points	the way's points
	 * @param pos	the position we are testing
	 * @param floor lower limit of points to test (inclusive)
	 * @param ceiling upper limit of points to test (inclusive)
	 * @return true if is OK to split as pos
	 */
	private boolean safeToSplitWay(List<Coord> points, int pos, int floor, int ceiling) {
		Coord candidate = points.get(pos);
		// avoid running off the ends of the list
		if(floor < 0)
			floor = 0;
		if(ceiling >= points.size())
			ceiling = points.size() - 1;
		double arcLength = 0;
		Coord prev = candidate;
		// test points after pos
		for(int i = pos + 1; i <= ceiling; ++i) {
			Coord p = points.get(i);
			arcLength += p.distance(prev);
			if(i == ceiling || p.getHighwayCount() > 1) {
				// point is going to be a node
				if(candidate.equals(p)) {
					// coordinates are equal, that's too close
					return false;
				}
				// no need to test further
				break;
			}
			prev = p;
		}
		if (arcLength == 0 || arcLength < minimumArcLength)
			return false;
		prev = candidate;
		arcLength = 0;
		// test points before pos
		for(int i = pos - 1; i >= floor; --i) {
			Coord p = points.get(i);
			arcLength += p.distance(prev);
			if(i == floor || p.getHighwayCount() > 1) {
				// point is going to be a node
				if(candidate.equals(p)) {
					// coordinates are equal, that's too close
					return false;
				}
				// no need to test further
				break;
			}
		}

		return (arcLength != 0 && arcLength >= minimumArcLength);
	}

	private static String getDebugName(Way way) {
		String name = way.getName();
		if(name == null)
			name = way.getTag("ref");
		if(name == null)
			name = "";
		else
			name += " ";
		return name + "(OSM id " + way.getId() + ")";
	}

	private void addRoadWithoutLoops(Way way, GType gt) {
		List<Integer> nodeIndices = new ArrayList<Integer>();
		List<Coord> points = way.getPoints();
		Way trailingWay = null;
		String debugWayName = getDebugName(way);

		// collect the Way's nodes and also split the way if any
		// inter-node arc length becomes excessive
		double arcLength = 0;
		// track the dimensions of the way's bbox so that we can
		// detect if it would be split by the LineSizeSplitterFilter
		class WayBBox {
			int minLat = Integer.MAX_VALUE;
			int maxLat = Integer.MIN_VALUE;
			int minLon = Integer.MAX_VALUE;
			int maxLon = Integer.MIN_VALUE;

			void addPoint(Coord co) {
				int lat = co.getLatitude();
				if(lat < minLat)
					minLat = lat;
				if(lat > maxLat)
					maxLat = lat;
				int lon = co.getLongitude();
				if(lon < minLon)
					minLon = lon;
				if(lon > maxLon)
					maxLon = lon;
			}

			boolean tooBig() {
				return LineSizeSplitterFilter.testDims(maxLat - minLat,
													   maxLon - minLon) >= 1.0;
			}
		}

		WayBBox wayBBox = new WayBBox();

		for(int i = 0; i < points.size(); ++i) {
			Coord p = points.get(i);

			wayBBox.addPoint(p);

			// check if we should split the way at this point to limit
			// the arc length between nodes
			if((i + 1) < points.size()) {
				Coord nextP = points.get(i + 1);
				double d = p.distance(nextP);
				// get arc size as a proportion of the max allowed - a
				// value greater than 1.0 indicate that the bbox is
				// too large in at least one dimension
				double arcProp = LineSizeSplitterFilter.testDims(nextP.getLatitude() -
																 p.getLatitude(),
																 nextP.getLongitude() -
																 p.getLongitude());
				if(arcProp >= 1.0 || d > MAX_ARC_LENGTH) {
					nextP = p.makeBetweenPoint(nextP, 0.95 * Math.min(1 / arcProp, MAX_ARC_LENGTH / d));
					nextP.incHighwayCount();
					points.add(i + 1, nextP);
					double newD = p.distance(nextP);
					log.info("Way " + debugWayName + " contains a segment that is " + (int)d + "m long but I am adding a new point to reduce its length to " + (int)newD + "m");
					d = newD;
				}

				wayBBox.addPoint(nextP);

				if((arcLength + d) > MAX_ARC_LENGTH) {
					assert i > 0 : "long arc segment was not split";
					assert trailingWay == null : "trailingWay not null #1";
					trailingWay = splitWayAt(way, i);
					// this will have truncated the current Way's
					// points so the loop will now terminate
					log.info("Splitting way " + debugWayName + " at " + points.get(i).toOSMURL() + " to limit arc length to " + (long)arcLength + "m");
				}
				else if(wayBBox.tooBig()) {
					assert i > 0 : "arc segment with big bbox not split";
					assert trailingWay == null : "trailingWay not null #2";
					trailingWay = splitWayAt(way, i);
					// this will have truncated the current Way's
					// points so the loop will now terminate
					log.info("Splitting way " + debugWayName + " at " + points.get(i).toOSMURL() + " to limit the size of its bounding box");
				}
				else {
					if(p.getHighwayCount() > 1) {
						// point is a node so zero arc length
						arcLength = 0;
					}

					arcLength += d;
				}
			}

			if(p.getHighwayCount() > 1) {
				// this point is a node connecting highways
				Integer nodeId = nodeIdMap.get(p);
				if(nodeId == null) {
					// assign a node id
					nodeIdMap.put(p, nextNodeId++);
				}

				// add this index to node Indexes (should not already be there)
				assert !nodeIndices.contains(i) : debugWayName + " has multiple nodes for point " + i + " new node is " + p.toOSMURL();
				nodeIndices.add(i);

				if((i + 1) < points.size() &&
				   nodeIndices.size() == MAX_NODES_IN_WAY) {
					// this isn't the last point in the way so split
					// it here to avoid exceeding the max nodes in way
					// limit
					assert trailingWay == null : "trailingWay not null #7";
					trailingWay = splitWayAt(way, i);
					// this will have truncated the current Way's
					// points so the loop will now terminate
					log.info("Splitting way " + debugWayName + " at " + points.get(i).toOSMURL() + " as it has at least " + MAX_NODES_IN_WAY + " nodes");
				}
			}
		}

		MapLine line = new MapLine();
		elementSetup(line, gt, way);
		line.setPoints(points);
		
		MapRoad road = new MapRoad(way.getId(), line);
		if (way.isBoolTag("mkgmap:skipSizeFilter"))
			road.setSkipSizeFilter(true);

		boolean doFlareCheck = true;
		if("roundabout".equals(way.getTag("junction"))) {
			road.setRoundabout(true);
			doFlareCheck = false;
		}

		if(way.isBoolTag("mkgmap:synthesised")) {
			road.setSynthesised(true);
			doFlareCheck = false;
		}

		if(way.isNotBoolTag("mkgmap:flare-check")) {
			doFlareCheck = false;
		}
		else if(way.isBoolTag("mkgmap:flare-check")) {
			doFlareCheck = true;
		}
		road.doFlareCheck(doFlareCheck);

		road.setLinkRoad(gt.getType() == 0x08 || gt.getType() == 0x09);

		// set road parameters 

		// road class (can be overridden by mkgmap:road-class tag)
		int roadClass = gt.getRoadClass();
		String val = way.getTag("mkgmap:road-class");
		if(val != null) {
			if(val.startsWith("-")) {
				roadClass -= Integer.decode(val.substring(1));
			}
			else if(val.startsWith("+")) {
				roadClass += Integer.decode(val.substring(1));
			}
			else {
				roadClass = Integer.decode(val);
			}
			val = way.getTag("mkgmap:road-class-max");
			int roadClassMax = 4;
			if(val != null)
				roadClassMax = Integer.decode(val);
			val = way.getTag("mkgmap:road-class-min");

			int roadClassMin = 0;
			if(val != null)
				roadClassMin = Integer.decode(val);
			if(roadClass > roadClassMax)
				roadClass = roadClassMax;
			else if(roadClass < roadClassMin)
				roadClass = roadClassMin;
			log.info("POI changing road class of " + way.getName() + " (" + way.getId() + ") to " + roadClass + " at " + points.get(0));
		}
		road.setRoadClass(roadClass);

		// road speed (can be overridden by maxspeed (OSM) tag or
		// mkgmap:road-speed tag)
		int roadSpeed = gt.getRoadSpeed();
		if(!ignoreMaxspeeds) {
			// maxspeed attribute overrides default for road type
			String maxSpeed = way.getTag("maxspeed");
			if(maxSpeed != null) {
				int rs = getSpeedIdx(maxSpeed);
				if(rs >= 0)
					roadSpeed = rs;
				log.debug(debugWayName + " maxspeed=" + maxSpeed + ", speedIndex=" + roadSpeed);
			}
		}
		val = way.getTag("mkgmap:road-speed");
		if(val != null) {
			if(val.startsWith("-")) {
				roadSpeed -= Integer.decode(val.substring(1));
			}
			else if(val.startsWith("+")) {
				roadSpeed += Integer.decode(val.substring(1));
			}
			else {
				roadSpeed = Integer.decode(val);
			}
			val = way.getTag("mkgmap:road-speed-max");
			int roadSpeedMax = 7;
			if(val != null)
				roadSpeedMax = Integer.decode(val);
			val = way.getTag("mkgmap:road-speed-min");

			int roadSpeedMin = 0;
			if(val != null)
				roadSpeedMin = Integer.decode(val);
			if(roadSpeed > roadSpeedMax)
				roadSpeed = roadSpeedMax;
			else if(roadSpeed < roadSpeedMin)
				roadSpeed = roadSpeedMin;
			log.info("POI changing road speed of " + way.getName() + " (" + way.getId() + ") to " + roadSpeed + " at " + points.get(0));
		}
		road.setSpeed(roadSpeed);
		
		if (way.isBoolTag("oneway")) {
			road.setDirection(true);
			road.setOneway();
			if (checkFixmeCoords(way))
				way.addTag("mkgmap:dead-end-check", "false");
			road.doDeadEndCheck(!way.isNotBoolTag("mkgmap:dead-end-check"));
		}

		String highwayType = way.getTag("highway");
		if(highwayType == null) {
			// it's a routable way but not a highway (e.g. a ferry)
			// use the value of the route tag as the highwayType for
			// the purpose of testing for access restrictions
			highwayType = way.getTag("route");
		}

		boolean[] noAccess = new boolean[RoadNetwork.NO_MAX];
		for (AccessMapping anAccessMap : accessMap) {
			int index = anAccessMap.index;
			String type = anAccessMap.type;
			String accessTagValue = way.getTag(type);
			if (accessTagValue == null)
				continue;
			if (accessExplicitlyDenied(accessTagValue)) {
				if (index == RoadNetwork.NO_MAX) {
					// everything is denied access
					for (int j = 1; j < accessMap.length; ++j)
						noAccess[accessMap[j].index] = true;
				} else {
					// just the specific vehicle class is denied
					// access
					noAccess[index] = true;
				}
				log.debug(type + " is not allowed in " + highwayType + " " + debugWayName);
			} else if (accessExplicitlyAllowed(accessTagValue)) {
				if (index == RoadNetwork.NO_MAX) {
					// everything is allowed access
					for (int j = 1; j < accessMap.length; ++j)
						noAccess[accessMap[j].index] = false;
				} else {
					// just the specific vehicle class is allowed
					// access
					noAccess[index] = false;
				}
				log.debug(type + " is allowed in " + highwayType + " " + debugWayName);
			}
			else if (accessTagValue.equalsIgnoreCase("destination")) {
				if (type.equals("motorcar") ||
				    type.equals("motorcycle")) {
					road.setNoThroughRouting();
				} else if (type.equals("access")) {
					log.info("access=destination only affects routing for cars in " + highwayType + " " + debugWayName);
					road.setNoThroughRouting();
				} else {
					log.info(type + "=destination ignored in " + highwayType + " " + debugWayName);
				}
			} else if (accessTagValue.equalsIgnoreCase("unknown")) {
				// implicitly allow access
			} else {
				log.info("Ignoring unsupported access tag value " + type + "=" + accessTagValue + " in " + highwayType + " " + debugWayName);
			}
		}

		if(way.isBoolTag("mkgmap:carpool")) {
			// to make a way into a "carpool lane" all access disable
			// bits must be set except for CARPOOL and EMERGENCY (BUS
			// can also be clear)
			road.setNoThroughRouting();
			for (int j = 1; j < accessMap.length; ++j)
				noAccess[accessMap[j].index] = true;
			noAccess[RoadNetwork.NO_CARPOOL] = false;
			noAccess[RoadNetwork.NO_EMERGENCY] = false;
			noAccess[RoadNetwork.NO_BUS] = false;
		}

		road.setAccess(noAccess);

		if(way.isBoolTag("toll"))
			road.setToll();

		// by default, ways are paved
		if(way.isBoolTag("mkgmap:unpaved"))
			road.paved(false);

		// by default, way's are not ferry routes
		if(way.isBoolTag("mkgmap:ferry"))
			road.ferry(true);

		int numNodes = nodeIndices.size();
		road.setNumNodes(numNodes);

		if(numNodes > 0) {
			// replace Coords that are nodes with CoordNodes
			boolean hasInternalNodes = false;
			CoordNode lastCoordNode = null;
			List<RestrictionRelation> lastRestrictions = null;
			for(int i = 0; i < numNodes; ++i) {
				int n = nodeIndices.get(i);
				if(n > 0 && n < points.size() - 1)
					hasInternalNodes = true;
				Coord coord = points.get(n);
				Integer nodeId = nodeIdMap.get(coord);
				assert nodeId != null : "Way " + debugWayName + " node " + i + " (point index " + n + ") at " + coord.toOSMURL() + " yields a null node id";
				boolean boundary = coord.getOnBoundary();
				if(boundary) {
					log.info("Way " + debugWayName + "'s point #" + n + " at " + points.get(n).toDegreeString() + " is a boundary node");
				}

				CoordNode thisCoordNode = new CoordNode(coord, nodeId, boundary);
				points.set(n, thisCoordNode);

				// see if this node plays a role in any turn
				// restrictions

				if(lastRestrictions != null) {
					// the previous node was the location of one or
					// more restrictions
					for(RestrictionRelation rr : lastRestrictions) {
						if(rr.getToWay().getId() == way.getId()) {
							rr.setToNode(thisCoordNode);
						}
						else if(rr.getFromWay().getId() == way.getId()) {
							rr.setFromNode(thisCoordNode);
						}
						else {
							rr.addOtherNode(thisCoordNode);
						}
					}
				}

				List<RestrictionRelation> theseRestrictions = restrictions.get(coord);
				if(theseRestrictions != null) {
					// this node is the location of one or more
					// restrictions
					for(RestrictionRelation rr : theseRestrictions) {
						rr.setViaNode(thisCoordNode);
						if(rr.getToWay().getId() == way.getId()) {
							if(lastCoordNode != null)
								rr.setToNode(lastCoordNode);
						}
						else if(rr.getFromWay().getId() == way.getId()) {
							if(lastCoordNode != null)
								rr.setFromNode(lastCoordNode);
						}
						else if(lastCoordNode != null) {
							rr.addOtherNode(lastCoordNode);
						}
					}
				}

				lastRestrictions = theseRestrictions;
				lastCoordNode = thisCoordNode;
			}

			road.setStartsWithNode(nodeIndices.get(0) == 0);
			road.setInternalNodes(hasInternalNodes);
		}

		// add the road to the housenumber generator
		// it will add the road later on to the lineAdder
		housenumberGenerator.addRoad(way, road);

		if(trailingWay != null)
			addRoadWithoutLoops(trailingWay, gt);
	}

	/**
	 * Check if the first or last of the coords of the way has the fixme flag set
	 * @param way the way to check 
	 * @return true if fixme flag was found
	 */
	private boolean checkFixmeCoords(Way way) {
		if (way.getPoints().get(0).isFixme())
			return true;
		if (way.getPoints().get(way.getPoints().size()-1).isFixme())
			return true;
		return false;
	}

	/**
	 * split a Way at the specified point and return the new Way (the
	 * original Way is truncated, both ways will contain the split point)
	 * @param way the way to split
	 * @param index the split position. 
	 * @return the trailing part of the way
	 */
	private Way splitWayAt(Way way, int index) {
		Way trailingWay = new Way(way.getId());
		List<Coord> wayPoints = way.getPoints();
		int numPointsInWay = wayPoints.size();

		for(int i = index; i < numPointsInWay; ++i)
			trailingWay.addPoint(wayPoints.get(i));

		// ensure split point becomes a node
		wayPoints.get(index).incHighwayCount();

		// copy the way's name and tags to the new way
		trailingWay.setName(way.getName());
		trailingWay.copyTags(way);

		// remove the points after the split from the original way
		// it's probably more efficient to remove from the end first
		for(int i = numPointsInWay - 1; i > index; --i)
			wayPoints.remove(i);

		return trailingWay;
	}

	private int getSpeedIdx(String tag) {
		double factor = 1.0;
		
		String speedTag = tag.toLowerCase().trim();
		
		if (ENDS_IN_MPH_PATTERN.matcher(speedTag).matches()) {
			// Check if it is a limit in mph
			speedTag = REMOVE_MPH_PATTERN.matcher(speedTag).replaceFirst("");
			factor = 1.61;
		} else
			speedTag = REMOVE_KPH_PATTERN.matcher(speedTag).replaceFirst("");  // get rid of kmh just in case

		double kmh;
		try {
			kmh = Integer.parseInt(speedTag) * factor;
		} catch (Exception e) {
			return -1;
		}
		
		if(kmh > 110)
			return 7;
		if(kmh > 90)
			return 6;
		if(kmh > 80)
			return 5;
		if(kmh > 60)
			return 4;
		if(kmh > 40)
			return 3;
		if(kmh > 20)
			return 2;
		if(kmh > 10)
			return 1;
		else
			return 0;
	}

	protected boolean accessExplicitlyAllowed(String val) {
		if (val == null)
			return false;

		return (val.equalsIgnoreCase("yes") ||
			val.equalsIgnoreCase("designated") ||
			val.equalsIgnoreCase("permissive") ||
			val.equalsIgnoreCase("official"));
	}

	protected boolean accessExplicitlyDenied(String val) {
		if (val == null)
			return false;

		return (val.equalsIgnoreCase("no") ||
			val.equalsIgnoreCase("private"));
	}
	
	private boolean isFootOnlyAccess(Way way){
		String debugWayName = getDebugName(way);		
		String highwayType = way.getTag("highway");
		if(highwayType == null) {
			// it's a routable way but not a highway (e.g. a ferry)
			// use the value of the route tag as the highwayType for
			// the purpose of testing for access restrictions
			highwayType = way.getTag("route");
		}

		boolean[] noAccess = new boolean[RoadNetwork.NO_MAX];
		for (AccessMapping anAccessMap : accessMap) {
			int index = anAccessMap.index;
			String type = anAccessMap.type;
			String accessTagValue = way.getTag(type);
			if (accessTagValue == null)
				continue;
			if (accessExplicitlyDenied(accessTagValue)) {
				if (index == RoadNetwork.NO_MAX) {
					// everything is denied access
					for (int j = 1; j < accessMap.length; ++j)
						noAccess[accessMap[j].index] = true;
				} else {
					// just the specific vehicle class is denied
					// access
					noAccess[index] = true;
				}
				log.debug(type + " is not allowed in " + highwayType + " " + debugWayName);
			} else if (accessExplicitlyAllowed(accessTagValue)) {
				if (index == RoadNetwork.NO_MAX) {
					// everything is allowed access
					for (int j = 1; j < accessMap.length; ++j)
						noAccess[accessMap[j].index] = false;
				} else {
					// just the specific vehicle class is allowed
					// access
					noAccess[index] = false;
				}
				log.debug(type + " is allowed in " + highwayType + " " + debugWayName);
			}
		}

		if(way.isBoolTag("mkgmap:carpool")) {
			// to make a way into a "carpool lane" all access disable
			// bits must be set except for CARPOOL and EMERGENCY (BUS
			// can also be clear)
			for (int j = 1; j < accessMap.length; ++j)
				noAccess[accessMap[j].index] = true;
			noAccess[RoadNetwork.NO_CARPOOL] = false;
			noAccess[RoadNetwork.NO_EMERGENCY] = false;
			noAccess[RoadNetwork.NO_BUS] = false;
		}
		for (AccessMapping anAccessMap : accessMap) {
			int index = anAccessMap.index;
			if (index == RoadNetwork.NO_MAX)
				continue;
			if (index == RoadNetwork.NO_FOOT) { 
				if(noAccess[index])
					return false;
			}
			else if (noAccess[index] == false ){
				return false;
			}
		}		
		return true;
	}
	/**
	 * Increment the highway counter for each coord of each road.
	 * As a result, all road junctions have a count > 1. 
	 */
	private void setHighwayCounts(){
		log.info("Maintaining highway counters");
		long lastId = 0;
		for (Way way :roads){
			if (way.getId() == lastId)
				continue;
			lastId = way.getId();
			List<Coord> points = way.getPoints();
			for (Coord p:points){
				p.incHighwayCount();
			}
		}
	}

	/**
	 * Detect roads that do not share any node with another road.
	 * If such a road has the mkgmap:set_unconnected_type tag, add it as line, not as a road. 
	 */
	private void findUnconnectedRoads(){
		
		Map<Coord, HashSet<Way>> connectors = new IdentityHashMap<Coord, HashSet<Way>>(roads.size()*2);
		// collect nodes that might connect roads
		long lastId = 0;
		for (Way way :roads){
			if (way.getId() == lastId)
				continue;
			lastId = way.getId();
			for (Coord p:way.getPoints()){
				if (p.getHighwayCount() > 1){
					HashSet<Way> ways = connectors.get(p);
					if (ways == null){
						ways = new HashSet<Way>(4);
						connectors.put(p, ways);
					}
					ways.add(way);
				}
			}
		}
		
		// find roads that are not connected
		for (int i = 0; i < roads.size(); i++){
			Way way = roads.get(i);
			String check_type = way.getTag("mkgmap:set_unconnected_type");
			if (check_type != null){
				boolean isConnected = false;
				boolean onBoundary = false;
				for (Coord p:way.getPoints()){
					if (p.getOnBoundary())
						onBoundary = true;
					if (p.getHighwayCount() > 1){
						HashSet<Way> ways = connectors.get(p);
						if (ways != null && ways.size() > 1){
							isConnected = true;
							break;
						}
					}
				}
				if (!isConnected){
					if (onBoundary){
						log.info("road not connected to other roads but is on boundary: " + way.toBrowseURL());
					} else {
						if ("none".equals(check_type))
							log.info("road not connected to other roads, is ignored: " + way.toBrowseURL());
						else {
							int type = -1;
							try{
								type = Integer.decode(check_type);
								if (GType.isRoutableLineType(type)){
									type = -1;
									log.error("type value in mkgmap:set_unconnected_type should not be a routable type: " + check_type);
								}
							} catch (NumberFormatException e){
								log.warn("invalid type value in mkgmap:set_unconnected_type: " + check_type);
							}
							if (type != -1 ){
								log.info("road not connected to other roads, added as line with type " + check_type + ": " + way.toBrowseURL());
								GType gt = new GType(roadTypes.get(i), check_type); 
								addLine(way, gt);
							} else {
								log.warn("road not connected to other roads, but replacement type is invalid. Dropped: " + way.toBrowseURL());
							}
						}
						roads.set(i, null);
						roadTypes.set(i, null);
					}
				}
			}
		}
	}
	
	/**
	 * Make sure that only CoordPOI which affect routing 
	 * will be treated as nodes in the short-arc-removal- 
	 * and split routines. 
	 */
	private void filterCoordPOI (){
		if (!linkPOIsToWays)
			return;
		log.info("Removing unused CoordPOI");
		for (Way way : roads) {
			if (way == null)
				continue;
			if("true".equals(way.getTag("mkgmap:way-has-pois"))) {
				boolean stillHasPOIs = false;
				boolean isFootWay = isFootOnlyAccess(way); 
				// check if the way is for pedestrians only 
				List<Coord> points = way.getPoints();
				int numPoints = points.size();
				for (int i = 0;i < numPoints; i++) {
					Coord p = points.get(i);
					if (p instanceof CoordPOI){
						CoordPOI cp = (CoordPOI) p;
						Node node = cp.getNode();
						boolean isUsableInThisWay = false;
						if (!isFootWay){
							if(node.getTag("access") != null || 
									node.getTag("mkgmap:road-class") != null ||
									node.getTag("mkgmap:road-speed") != null){
								isUsableInThisWay = true;
							}
						}
						if (!isUsableInThisWay && p.getHighwayCount() < 2){
							// replace this CoordPoi with a normal coord to avoid merging
							Coord replacement = new Coord(p);
							replacement.incHighwayCount();
							replacement.setFixme(p.isFixme()); 
							points.set(i, replacement);
							continue;
						}
						if (isUsableInThisWay){
							node.addTag("mkgmap:use-poi-in-way-"+way.getId(), "true");
							stillHasPOIs = true;
						}
					}
				}
				if (!stillHasPOIs){
					way.deleteTag("mkgmap:way-has-pois");
					log.info("ignoring CoordPOI(s) for way " + way.toBrowseURL() + " because routing is not affected.");
				}
			}
		}
	}
	/**
	 * Common code to handle replacements of points in roads.
	 * Checks for special cases regarding CoordPOI.
	 * @param p point to replace
	 * @param way way the contains p
	 * @param replacements the Map containing the replaced points
	 * @return the replacement
	 */
	private Coord getReplacement(Coord p, Way way, Map<Coord, Coord> replacements){
		// check if this point is to be replaced because
		// it was previously merged into another point
		if (p.isReplaced()){
			Coord replacement = null;
			Coord r = p;
			while ((r = replacements.get(r)) != null) {
				replacement = r;
			}

			if (replacement != null) {
				assert !p.getOnBoundary() : "Boundary node replaced";
				if (p instanceof CoordPOI){
					Node node = ((CoordPOI) p).getNode(); 
					if ("true".equals(node.getTag("mkgmap:use-poi-in-way-"+way.getId()))){
						if (replacement instanceof CoordPOI){
							Node rNode = ((CoordPOI) replacement).getNode();
							if ("true".equals(rNode.getTag("mkgmap:use-poi-in-way-"+way.getId())))
								log.warn("CoordPOI", node.getId(), "replaced by CoordPOI",rNode.getId(), "in way",  way.toBrowseURL());
							else
								log.warn("CoordPOI", node.getId(), "replaced by ignored CoordPOI",rNode.getId(), "in way",  way.toBrowseURL());
						}
						else 
							log.warn("CoordPOI", node.getId(),"replaced by simple coord in way", way.toBrowseURL());
					}
				}
				return replacement;
			}
		}
		return p;
	}
	
	/**
	 * mark points as node-alike and remove obsolete points
	 */
	private void prepareRoads() {
		log.info("prepare: marking points as node-alike and removing obsolete points");
		for (Way way : roads) {
			if (way == null)
				continue;
			List<Coord> points = way.getPoints();
			int numPoints = points.size();
			if (numPoints >= 2) {
				// all end points should be treated as nodes
				points.get(0).setTreatAsNode(true);
				points.get(numPoints - 1).setTreatAsNode(true);
				// non-end points have 2 arcs but ignore points that
				// are only in a single way
				Coord prev = points.get(numPoints - 1);
				for (int i = numPoints - 2; i >= 0; --i) {
					Coord p = points.get(i);
					// if this point is a CoordPOI it may become a
					// node later even if it isn't actually a connection
					// between roads at this time - so for the purposes
					// of short arc removal, consider it to be a node
					// if it is on a boundary it will become a node later
					if (p.getHighwayCount() > 1 || p instanceof CoordPOI || p.getOnBoundary())
						p.setTreatAsNode(true);
					// remove equal points
					if (p.equals(prev)) {
						int removePos = -1;
						if (prev.isTreatAsNode() == false){
							removePos = i+1;
							prev = p;
						}
						else if (p.isTreatAsNode() == false){
							removePos = i;
						}
						if (removePos >= 0){
							points.remove(removePos);
							if (log.isInfoEnabled())
								log.info("Way", way.toBrowseURL(), "has consecutive equal points at node numbers",i+1, "and",i+2,"(discarding",removePos+1,")");						
							modifiedRoads.put(way.getId(), way);
						}
					} else {
						prev = p;
					}
				}
			}
		}

	}
	/**
	 * Routing nodes must not be too close together as this 
	 * causes routing errors. We try to merge these nodes here.
	 */
	private void removeShortArcsByMergingNodes() {
		log.info("Removing short arcs (min arc length = " + minimumArcLength + "m)");
		prepareRoads();

		// replacements maps those nodes that have been replaced to
		// the node that replaces them
		Map<Coord, Coord> replacements = new IdentityHashMap<Coord, Coord>();
		Map<Way, Way> complainedAbout = new HashMap<Way, Way>();
		boolean anotherPassRequired = true;
		int pass = 0;
		int numWaysDeleted = 0;
		int numNodesMerged = 0;

		while (anotherPassRequired && pass < 10) {
			anotherPassRequired = false;
			log.info("Removing short arcs - PASS " + ++pass);
			for (int w = 0; w < roads.size(); w++){
				Way way = roads.get(w);
				if (way == null)
					continue;
				List<Coord> points = way.getPoints();
				if (points.size() < 2) {
					if (log.isInfoEnabled())
						log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has less than 2 points - deleting it");
					roads.set(w, null);
					deletedRoads.add(way.getId());
					++numWaysDeleted;
					continue;
				}
				// scan through the way's points looking for nodes and
				// check to see that the nodes are not too close to
				// each other
				int previousNodeIndex = 0; // first point will be a node
				Coord previousPoint = points.get(0);
				double arcLength = 0;

				for (int i = 0; i < points.size(); ++i) {
					Coord p = points.get(i);

					// check if this point is to be replaced because
					// it was previously merged into another point
					if (p.isReplaced()){
						Coord replacement = getReplacement(p, way, replacements);
						if (p != replacement){
							p = replacement;
							p.incHighwayCount();
							// replace point in way
							points.set(i, p);
							if (i == 0)
								previousPoint = p;
							modifiedRoads.put(way.getId(), way);
						}
					}
					if (i == 0) {
						// nothing more to do with this point
						continue;
					}

					// this is not the first point in the way
					if (p == previousPoint) {
						if (log.isInfoEnabled())
							log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has consecutive identical points at " + p.toOSMURL() + " - deleting the second point");
						points.remove(i);
						// hack alert! rewind the loop index
						--i;
						modifiedRoads.put(way.getId(), way);
						anotherPassRequired = true;
						continue;
					}

					if (minimumArcLength > 0){
						// we have to calculate the length of the arc
						arcLength += p.distance(previousPoint);
					}
					else {
						// if the points are not equal, the arc length is > 0
						if (!p.equals(previousPoint)){
							arcLength = 1; // just a value > 0	
						}
					}
					previousPoint = p;

					// do we treat this point as a node ?
					if (!p.isTreatAsNode()) {
						// it's not a node so go on to next point
						continue;
					}
					Coord previousNode = points.get(previousNodeIndex);
					if (p == previousNode) {
						// this node is the same point object as the
						// previous node - leave it for now and it
						// will be handled later by the road loop
						// splitter
						previousNodeIndex = i;
						arcLength = 0;
						continue;
					}

					boolean mergeNodes = false;

					if (p.equals(previousNode)) {
						// nodes have identical coordinates and are
						// candidates for being merged

						// however, to avoid trashing unclosed loops
						// (e.g. contours) we only want to merge the
						// nodes when the length of the arc between
						// the nodes is small

						if(arcLength == 0 || arcLength < minimumArcLength)
							mergeNodes = true;
						else if(complainedAbout.get(way) == null) {
							if (log.isInfoEnabled())
								log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has unmerged co-located nodes at " + p.toOSMURL() + " - they are joined by a " + (int)(arcLength * 10) / 10.0 + "m arc");
							complainedAbout.put(way, way);
						}
					}
					else if(minimumArcLength > 0 && minimumArcLength > arcLength) {
						// nodes have different coordinates but the
						// arc length is less than minArcLength so
						// they will be merged
						mergeNodes = true;
					}

					if (!mergeNodes) {
						// keep this node and go look at the next point
						previousNodeIndex = i;
						arcLength = 0;
						continue;
					}

					if (previousNode.getOnBoundary() && p.getOnBoundary()) {
						if (p.equals(previousNode)) {
							// the previous node has identical
							// coordinates to the current node so it
							// can be replaced but to avoid the
							// assertion above we need to forget that
							// it is on the boundary
							previousNode.setOnBoundary(false);
						} else {
							// both the previous node and this node
							// are on the boundary and they don't have
							// identical coordinates
							if(complainedAbout.get(way) == null) {
								if (log.isLoggable(Level.WARNING))
									log.warn("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has short arc (" + String.format("%.2f", arcLength) + "m) at " + p.toOSMURL() + " - but it can't be removed because both ends of the arc are boundary nodes!");
								complainedAbout.put(way, way);
							}
							break; // give up with this way
						}
					}

					// reset arc length
					arcLength = 0;

					// do the merge
					++numNodesMerged;
					if (p.getOnBoundary()) {
						// current point is a boundary node so we need
						// to merge the previous node into this node
						replacements.put(previousNode, p);
						previousNode.setReplaced(true);
						p.setTreatAsNode(true);
						// remove the preceding point(s) back to and
						// including the previous node
						for(int j = i - 1; j >= previousNodeIndex; --j) {
							points.remove(j);
						}
					} else {
						// current point is not on a boundary so merge
						// this node into the previous one
						replacements.put(p, previousNode);
						p.setReplaced(true);
						previousNode.setTreatAsNode(true);
						// reset previous point to be the previous
						// node
						previousPoint = previousNode;
						// remove the point(s) back to the previous
						// node
						for (int j = i; j > previousNodeIndex; --j) {
							points.remove(j);
						}
					}

					// hack alert! rewind the loop index
					i = previousNodeIndex;
					modifiedRoads.put(way.getId(), way);
					anotherPassRequired = true;
				}
			}
		}
		if (anotherPassRequired)
			log.error("Removing short arcs - didn't finish in " + pass + " passes, giving up!");
		else
			log.info("Removing short arcs - finished in", pass, "passes (", numNodesMerged, "nodes merged,", numWaysDeleted, "ways deleted)");
	}

	
	/**
	 *	Find wrong angles caused by rounding to map units. Try 
	 *  to fix them. 
	 */
	void removeWrongAngles(){
		Map<Coord, Coord> replacements = new IdentityHashMap<Coord, Coord>();
		Way fakeWay = new Way(0);

		// step 1
		Way lastWay = null;
		for (int w = 0; w < roads.size(); w++){
			Way way = roads.get(w);
			if (way == null)
				continue;
			if (way.equals(lastWay)){
				way.getPoints().clear();
				way.getPoints().addAll(lastWay.getPoints());
				continue;
			}
			lastWay = way;
			if (mergeEqualPoints(way, replacements) == true){
				// something was changed
				if (way.getPoints().size() < 2){
					if (log.isInfoEnabled())
						log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has less than 2 points - deleting it");
					roads.set(w, null);
					deletedRoads.add(way.getId());
				}
			}
		}
		boolean anotherPassRequired = true;
		final double criticalDist = 10;
		// step 2: find critical points
		for (int pass = 1; pass < 10 && anotherPassRequired; pass++){
			anotherPassRequired = false;
			IdentityHashMap<Coord, List<Coord>> centers = new IdentityHashMap<Coord, List<Coord>>();
			ArrayList<Coord> centerPoints = new ArrayList<Coord>(); 
			// replacements maps those nodes that have been replaced to
			// the node that replaces them
			lastWay = null;
			for (int w = 0; w < roads.size(); w++){
				Way way = roads.get(w);
				if (way == null)
					continue;
				if (way.equals(lastWay)){
					way.getPoints().clear();
					way.getPoints().addAll(lastWay.getPoints());
					continue;
				}
				lastWay = way;
				List<Coord> points = way.getPoints();


				// scan through the way's points looking for points which are
				// close together 
				int n = points.size();
				Coord prev = null;
				double lastDist = Double.MAX_VALUE;
				for (int i = 0; i < n; ++i) {
					Coord p = points.get(i);
					if (p.isReplaced()){
						p = getReplacement(p, way, replacements);
					}
					if (i > 0){
						if (p.equals(prev)){
							log.error("found equal consecutive points in pass "+ pass);
						}
						double dist = p.distance(prev);// maybe better to use dist between displayed points ?
						if (dist < criticalDist || lastDist < criticalDist){ 
							for (int k = -1; k <= 0; k++){
								p = points.get(i+k);
								if (p.getOnBoundary())
									continue;
								Coord other = points.get((k==0) ? i-1:i);
								List<Coord> list = centers.get(p);
								if (list == null){
									centerPoints.add(p);
									list = new ArrayList<Coord>(4);
									centers.put(p, list);
								}
								boolean isNew = true;
								// we want only different Coord instances here
								for (Coord co:list){
									if (co == other){
										isNew = false;
										break;
									}
								}
								if (isNew)
									list.add(other);
							}
						}
						lastDist = dist;
					}
					prev = p;

				}
			}
			System.out.println("Number of points with rather close neighbours (<= " + criticalDist + "m) :" + centers.size());
			long id = 0;

			for (Coord center: centerPoints){
				id++;
				List<Coord> neighbours = centers.get(center);
				if (neighbours.size() < 2){
					continue;
				}
				if (center.isReplaced())
					center = getReplacement(center, null, replacements);
				boolean ok = testCenter(center, neighbours, replacements);
				if (!ok){
					if (gpxPath != null){
						// print lines before change
						List<Coord> line = new ArrayList<Coord>();
						HashSet<Coord> grid = new HashSet<Coord>();
						grid.addAll(center.getAlternativePositions());
						for (int i = 0; i < neighbours.size(); i++){
							line.clear();
							line.add(center);
							Coord pn = neighbours.get(i);
							if (pn.isReplaced())
								pn = getReplacement(pn, fakeWay, replacements);
							line.add(neighbours.get(i));
							grid.addAll(neighbours.get(i).getAlternativePositions());
							if (i+1 == neighbours.size()){
								GpxCreator.createGpx(gpxPath + pass + "_" + id+ "_" + i, line, new ArrayList<Coord>(grid));
								if (grid.isEmpty()){
									log.error("no possible solution found");
								}
							}
							else 
								GpxCreator.createGpx(gpxPath + pass + "_" + id+ "_" + i, line);
						}
					}
					List<Coord> alternatives = center.getAlternativePositions();
					boolean solved = false;
					for (Coord altCenter: alternatives){
						if (testCenter(altCenter, neighbours, replacements) == false)
							continue;
//						System.out.println("found possible solution");
						boolean nice = true;
						//test all neighbours
						for (Coord n: neighbours){
							List<Coord> otherNeighbours = centers.get(n);
							if (otherNeighbours != null){
								if (testCenter(n, otherNeighbours, replacements, center,altCenter) == false){
//									System.out.println("not good for neighbour");
									nice = false;
									break;
								}
							}
						}
						if (nice){
							// replace center here and in neighbours
							center.setReplaced(true);
							replacements.put(center, altCenter);
							if (center instanceof CoordPOI){
								altCenter = new CoordPOI(altCenter);
								((CoordPOI) altCenter).setNode(((CoordPOI) center).getNode());
							}
							altCenter.incHighwayCount();
							if (center.getHighwayCount() >= 2)
								altCenter.incHighwayCount();
							if (gpxPath != null){
								// print lines after change
								List<Coord> line = new ArrayList<Coord>();
								for (int i = 0; i < neighbours.size(); i++){
									line.clear();
									line.add(altCenter);
									Coord pn = neighbours.get(i);
									if (pn.isReplaced())
										pn = getReplacement(pn, fakeWay, replacements);
									line.add(neighbours.get(i));
									GpxCreator.createGpx(gpxPath + pass + "_" + id+ "_m_" + i, line);
								}
							}
							solved = true;
							break;
						}
					}
					if (solved == false){
						if (center.getHighwayCount() < 2){
							if (center instanceof CoordPOI){
								log.debug("cannot fix wrong angle at coord poi " + center.toOSMURL());
							} else {
								center.setRemove(true);
								solved = true;
							}
						} else {
							//						System.out.println("found no alternative pos for center id " + id);
						}
					}
					if (solved){
						System.out.println("found correction for bad angle at " + center.toOSMURL() + " in pass " + pass);
					}
				}
			}
			lastWay = null;
			for (Way way: roads){
				if (way == null)
					continue;

				if (lastWay != null && lastWay.equals(way)){
					way.getPoints().clear();
					way.getPoints().addAll(lastWay.getPoints());
					continue;
				}
				lastWay = way;
				List<Coord> points = way.getPoints();
				for (int i = 0; i < points.size(); i++){
					Coord p = points.get(i);
					if (p.isToRemove()){
						points.remove(i);
						anotherPassRequired = true;
						i--;
						continue;
					}
					// check if this point is to be replaced because
					// it was previously moved 
					if (p.isReplaced()){
						Coord replacement = getReplacement(p, way, replacements);
						if (p != replacement){
							p = replacement;
							p.incHighwayCount();
							// replace point in way
							points.set(i, p);
							modifiedRoads.put(way.getId(), way);
						}
					}
				}
			}
		}
	}

	/**
	 * return true if no rounding problems at this center
	 * @param center
	 * @param neighbours
	 * @param replacements 
	 * @return
	 */
	private boolean testCenter(Coord center, List<Coord> neighbours, Map<Coord, Coord> replacements, Coord toRepl, Coord replacement){
		Way fakeWay = new Way(0);
		for (int i = 1; i < neighbours.size(); i++){
			Coord p1 = neighbours.get(i-1);
			p1 = getReplacement(p1, fakeWay, replacements);
			if (p1.isToRemove())
				continue;
			if (p1 == toRepl)
				p1 = replacement;
			Coord p2 = neighbours.get(i);
			p2 = getReplacement(p2, fakeWay, replacements);
			if (p2.isToRemove())
				continue;
			if (p2 == toRepl)
				p2 = replacement;
			if (p1.equals(center) || p2.equals(center)){
				return false;
			}
			double a1 = Utils.getAngle(p1, center, p2);
			double a2 = Utils.getDisplayedAngle(p1, center, p2);
			double err = Math.abs(a1-a2);
			if (err > 30){ 
				//TODO: 30 seems to be a good value. Maybe use argument ? Maybe try to find smallest possible error?
				return false;
			}
		}
		return  true;
	}
	
	private boolean testCenter(Coord center, List<Coord> neighbours, Map<Coord, Coord> replacements){
		return testCenter(center, neighbours, replacements, null,null);
	}
}

