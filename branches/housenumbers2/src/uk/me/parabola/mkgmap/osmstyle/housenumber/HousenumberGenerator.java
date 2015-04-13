/*
 * Copyright (C) 2013.
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

package uk.me.parabola.mkgmap.osmstyle.housenumber;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.net.Numbers;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LineAdder;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.HousenumberHooks;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.POIGeneratorHook;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.TagDict;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.KdTree;
import uk.me.parabola.util.Locatable;
import uk.me.parabola.util.MultiHashMap;
import uk.me.parabola.util.MultiHashSet;

/**
 * Collects all data required for OSM house number handling and adds the
 * house number information to the roads.
 * 
 * @author WanMil
 */
public class HousenumberGenerator {
	private static final Logger log = Logger.getLogger(HousenumberGenerator.class);

	/** Gives the maximum distance between house number element and the matching road */
	public static final double MAX_DISTANCE_TO_ROAD = 150d;
	
	private boolean numbersEnabled;

	// options for handling of unnamed (service?) roads	
	private boolean nameServiceRoads;
	private int nameSearchDepth;

	private int nextNodeId;
	
	private MultiHashMap<String, MapRoad> roadsByName;
	private MultiHashMap<String, HousenumberIvl> interpolationWays;
	private List<MapRoad> roads;
	private MultiHashMap<String, Element> houseNumbers;
	private Map<Long,Node> houseNodes;
	private MultiHashMap<String, Element> placeHouseNumbers;


	
	private static final short streetTagKey = TagDict.getInstance().xlate("mkgmap:street");
	private static final short addrStreetTagKey = TagDict.getInstance().xlate("addr:street");
	private static final short addrInterpolationTagKey = TagDict.getInstance().xlate("addr:interpolation");
	private static final short addrPlaceTagKey = TagDict.getInstance().xlate("addr:place");
	
	
	public HousenumberGenerator(EnhancedProperties props) {
		this.roadsByName = new MultiHashMap<>();
		this.houseNumbers = new MultiHashMap<>();
		this.roads = new ArrayList<MapRoad>();
		this.houseNodes = new HashMap<>();
		this.interpolationWays = new MultiHashMap<>();
		
		this.placeHouseNumbers = new MultiHashMap<>(); 

		numbersEnabled=props.containsKey("housenumbers");

		int n = props.getProperty("name-service-roads", 0);
		if (n > 0){
			nameServiceRoads = true;
			nameSearchDepth = Math.min(25,n);
			if (nameSearchDepth != n)
				log.warn("name-service-roads="+n,"was changed to name-service-roads="+nameSearchDepth);
		}
	}

	/**
	 * Retrieves the street name of this element.
	 * @param e an OSM element
	 * @return the street name (or {@code null} if no street name set)
	 */
	private static String getStreetname(Element e) {
		String streetname = e.getTag(streetTagKey);
		if (streetname == null) {
			streetname = e.getTag(addrStreetTagKey);
		}	
		return streetname;
	}
	
	/**
	 * Adds a node for house number processing.
	 * @param n an OSM node
	 */
	public void addNode(Node n) {
		if (numbersEnabled == false) {
			return;
		}
		if ("true".equals(n.getTag(POIGeneratorHook.AREA2POI_TAG)))
			return; 		
		if (HousenumberMatch.getHousenumber(n) != null) {
			String streetname = getStreetname(n);
			if (streetname == null){
				String place = n.getTag(addrPlaceTagKey);
				if (place != null){
					placeHouseNumbers.add(place, n);
				} else {
					if (log.isDebugEnabled())
						log.debug(n.toBrowseURL()," ignored, doesn't contain a street or place name.");
				}

			} else {
				houseNumbers.add(streetname, n);
				if (n.getTag(HousenumberHooks.partOfInterpolationTagKey) != null)
					houseNodes.put(n.getId(),n);
			}
		} 
	}
	
	/**
	 * Adds a way for house number processing.
	 * @param w a way
	 */
	public void addWay(Way w) {
		if (numbersEnabled == false) {
			return;
		}
		String ai = w.getTag(addrInterpolationTagKey);
		if (ai != null){
			String nodeIds = w.getTag(HousenumberHooks.mkgmapNodeIdsTagKey);
			if (nodeIds != null){
				List<Node> nodes = new ArrayList<>();
				String[] ids = nodeIds.split(",");
				for (String id : ids){
					Node node = houseNodes.get(Long.decode(id));
					if (node != null){
						nodes.add(node);
					}
				}
				interpretInterpolationWay(w, nodes);
			}
		}
		
		
		if (HousenumberMatch.getHousenumber(w) != null) {
			String streetname = getStreetname(w);
			if (streetname != null) {
				houseNumbers.add(streetname, w);
			} else {
				String place = w.getTag(addrPlaceTagKey);
				if (place != null){
					placeHouseNumbers.add(place, w);
				} else {
					if (log.isDebugEnabled()){
						if (FakeIdGenerator.isFakeId(w.getId()))
							log.debug("mp-created way ignored, doesn't contain a street name. Tags:",w.toTagString());
						else 
							log.debug(w.toBrowseURL()," ignored, doesn't contain a street or place name.");
					}				
				}
			}
		}
	}
	
	/**
	 * Use the information provided by the addr:interpolation tag
	 * to generate additional house number elements. This increases
	 * the likelihood that a road segment is associated with the right 
	 * number ranges. 
	 * @param w the way
	 * @param nodes list of nodes
	 */
	private void interpretInterpolationWay(Way w, List<Node> nodes) {
		int numNodes = nodes.size();
		String addrInterpolationMethod = w.getTag(addrInterpolationTagKey);
		int step = 0;
		switch (addrInterpolationMethod) {
		case "all":
		case "1":
			step = 1;
			break;
		case "even":
		case "odd":
		case "2":
			step = 2;
			break;
		default:
			break;
		}
		if (step == 0)
			return; // should not happen here
		int pos = 0;
		for (int i = 0; i+1 < numNodes; i++){
			// the way have other points, find the sequence including the pair of nodes    
			Node n1 = nodes.get(i);
			Node n2 = nodes.get(i+1);
			int pos1 = -1, pos2 = -1;
			for (int k = pos; k < w.getPoints().size(); k++){
				if (w.getPoints().get(k) == n1.getLocation()){
					pos1 = k;
					break;
				}
			}
			if (pos1 < 0){
				log.error("addr:interpolation node not found in way",w);
				return;
			}
			for (int k = pos1+1; k < w.getPoints().size(); k++){
				if (w.getPoints().get(k) == n2.getLocation()){
					pos2 = k;
					break;
				}
			}
			if (pos2 < 0){
				log.error("addr:interpolation node not found in way",w);
				return;
			}
			pos = pos2;
			String street1 = getStreetname(n1);
			String street2 = getStreetname(n2);
			if (street1 != null && street1.equals(street2)){
				try {
					HousenumberMatch m1 = new HousenumberMatch(n1);
					HousenumberMatch m2 = new HousenumberMatch(n2);
					int start = m1.getHousenumber();
					int end = m2.getHousenumber();
					int steps;
					if (start < end){
						steps = (end - start) / step - 1;

						
					} else {
						steps = (start - end) / step - 1;

					}
					HousenumberIvl info = new HousenumberIvl(street1, w, n1, n2);
					info.setStart(start);
					info.setEnd(end);
					info.setStep(step);
					info.setSteps(steps);
					info.setPoints(w.getPoints().subList(pos1, pos2+1));
					interpolationWays.add(street1, info);
					if (start == end && m1.getSign().equals(m2.getSign())){
						// handle special case from CanVec imports  
						if (pos1 == 0 && pos2 +1 == w.getPoints().size()){
							info.setEqualEnds();
							log.warn(w.toBrowseURL(),"addr:interpolation way connects two points with equal numbers, numbers are ignored");
							return;
						}
					}
					if (steps <= 0){
						if (log.isDebugEnabled())
							log.debug(w.toBrowseURL(),"addr:interpolation way segment ignored, no number between",start,"and",end);
						info.setBad(true);
					}
					if ("even".equals(addrInterpolationMethod) && (start % 2 != 0 || end % 2 != 0)){
						if (log.isInfoEnabled())
							log.info(w.toBrowseURL(),"addr:interpolation=even is used with odd housenumber(s)",start,end);
						info.setBad(true);
					}
					if ("odd".equals(addrInterpolationMethod) && (start % 2 == 0 || end % 2 == 0)){
						if (log.isInfoEnabled())
							log.info(w.toBrowseURL(),"addr:interpolation=odd is used with even housenumber(s)",start,end);
						info.setBad(true);
					}
				} catch (IllegalArgumentException exp) {
					log.debug(exp);
				}
			}
		}
	}

	
	/**
	 * Adds a road to be processed by the house number generator.
	 * @param osmRoad the OSM way the defines the road 
	 * @param road a road
	 */
	public void addRoad(Way osmRoad, MapRoad road) {
		roads.add(road);
		if (numbersEnabled) {
			/*
			 * If the style adds the same OSM way as two or more routable ways, we use
			 * only the first. This ensures that we don't try to assign numbers from bad
			 * matches to these copies.
			 */
			if (road.getRoadDef().ferry())
				road.setSkipHousenumberProcessing(true);
			else if(!road.isSkipHousenumberProcessing()){
				String name = road.getStreet(); 
				if (name != null) {
					if (log.isDebugEnabled())
						log.debug("Housenumber - Streetname:", name, "Way:",osmRoad.getId(),osmRoad.toTagString());
					roadsByName.add(name, road);
				}
				
			}
		} 
	}
	
	/**
	 * Evaluate type=associatedStreet relations.
	 */
	public void addRelation(Relation r) {
		if (numbersEnabled == false) 
			return;
		String relType = r.getTag("type");
		// the wiki says that we should also evaluate type=street
		if ("associatedStreet".equals(relType) || "street".equals(relType)){
			List<Element> houses= new ArrayList<>();
			List<Element> streets = new ArrayList<>();
			for (Map.Entry<String, Element> member : r.getElements()) {
				if (member.getValue() instanceof Node) {
					Node node = (Node) member.getValue();
					houses.add(node);
				} else if (member.getValue() instanceof Way) {
					Way w = (Way) member.getValue();
					String role = member.getKey();
					switch (role) {
					case "house":
					case "addr:houselink":
					case "address":
						houses.add(w);
						break;
					case "street":
						streets.add(w);
						break;
					case "":
						if (w.getTag("highway") != null){
							streets.add(w);
							continue;
						}
						String buildingTag = w.getTag("building");
						if (buildingTag != null)
							houses.add(w);
						else 
							log.warn("Relation",r.toBrowseURL(),": role of member",w.toBrowseURL(),"unclear");
						break;
					default:
						if ("associatedStreet".equals(relType)) 
							log.warn("Relation",r.toBrowseURL(),": don't know how to handle member with role",role);
						break;
					}
				}
			}
			String streetName = r.getTag("name");
			String streetNameFromRoads = null;
			List<Element> unnamedStreetElems = new ArrayList<>();
			boolean nameFromStreetsIsUnclear = false;
			if (streets.isEmpty() == false) {
				for (Element street : streets) {
					// line style was not yet processed, we assume that tag name
					// contains now what mkmgap:street will contain later
					String roadName = street.getTag("name");  
					if (roadName == null){
						unnamedStreetElems.add(street);
						continue;
					}
					if (streetNameFromRoads == null)
						streetNameFromRoads = roadName;
					else if (streetNameFromRoads.equals(roadName) == false)
						nameFromStreetsIsUnclear = true;
				}
			}
			if (streetName == null){
				if (nameFromStreetsIsUnclear == false)
					streetName = streetNameFromRoads;
				else {
					log.warn("Relation",r.toBrowseURL(),": ignored, street name is not clear.");
					return;
				}

			} else {
				if (streetNameFromRoads != null){
					if (nameFromStreetsIsUnclear == false && streetName.equals(streetNameFromRoads) == false){
						if (unnamedStreetElems.isEmpty() == false){
							log.warn("Relation",r.toBrowseURL(),": ignored, street name is not clear.");
							return;
						}
						log.warn("Relation",r.toBrowseURL(),": street name is not clear, using the name from the way, not that of the relation.");
						streetName = streetNameFromRoads;
					} 
					else if (nameFromStreetsIsUnclear == true){
						log.warn("Relation",r.toBrowseURL(),": street name is not clear, using the name from the relation.");
					}
				} 
			}
			int countModHouses = 0;
			if (streetName != null && streetName.isEmpty() == false){
				for (Element house : houses) {
					if (addStreetTagFromRel(r, house, streetName) )
						countModHouses++;
				}
				for (Element street : unnamedStreetElems) {
					street.addTag(streetTagKey, streetName);
				}
			}
			if (log.isInfoEnabled()){
				if (countModHouses > 0 || !unnamedStreetElems.isEmpty()){
					if (countModHouses > 0)
						log.info("Relation",r.toBrowseURL(),": added tag mkgmap:street=",streetName,"to",countModHouses,"of",houses.size(),"house members" );
					if (!unnamedStreetElems.isEmpty())
						log.info("Relation",r.toBrowseURL(),": added tag mkgmap:street=",streetName,"to",unnamedStreetElems.size(),"of",streets.size(),"street members" );
				}
				else 
					log.info("Relation",r.toBrowseURL(),": ignored, no house or street member was changed");
			}

		}
	}

	/**
	 * Add the tag mkgmap:street=streetName to the element of the 
	 * relation if it does not already have a street name tag.
	 */
	private static boolean addStreetTagFromRel(Relation r, Element house, String streetName){
		String addrStreet = getStreetname(house);
		if (addrStreet == null){
			house.addTag(streetTagKey, streetName);
			if (log.isDebugEnabled())
				log.debug("Relation",r.toBrowseURL(),": adding tag mkgmap:street=" + streetName, "to house",house.toBrowseURL());
			return true;
		}
		else if (addrStreet.equals(streetName) == false){
			if (house.getTag(streetTagKey) != null){
				log.warn("Relation",r.toBrowseURL(),": street name from relation doesn't match existing mkgmap:street tag for house",house.toBrowseURL(),"the house seems to be member of another type=associatedStreet relation");
				house.deleteTag(streetTagKey);
			}
			else 
				log.warn("Relation",r.toBrowseURL(),": street name from relation doesn't match existing name for house",house.toBrowseURL());
		}
		return false;
	}
	
	
	/**
	 * 
	 * @param adder
	 * @param naxNodeId the highest nodeId used before
	 */
	public void generate(LineAdder adder, int naxNodeId) {
		if (numbersEnabled) {
			KdTree<Locatable> roadSearchTree = buildRoadPointSearchTree();
			if (placeHouseNumbers.isEmpty() == false){
				nextNodeId = ++naxNodeId;
//				generateFakeRoadsForPlaces(adder);
				findRoadsNearPlaces(roadSearchTree);
			}
			if (nameServiceRoads)
				identifyServiceRoads();
			TreeSet<String> sortedStreetNames = new TreeSet<>();
			for (Entry<String, List<Element>> numbers : houseNumbers.entrySet()) {
				sortedStreetNames.add(numbers.getKey());
			}

			// process the roads in alphabetical order. This is not needed
			// but helps when comparing results in the log.
			for (String streetName: sortedStreetNames){
				List<MapRoad> possibleRoads = roadsByName.get(streetName);
				if (possibleRoads.isEmpty()) {
					continue;
				}
				
				List<Element> numbers = houseNumbers.get(streetName);
				MultiHashMap<Integer, MapRoad> clusters = buildRoadClusters(possibleRoads);
				List<HousenumberMatch> houses = convertElements(streetName, numbers);
				List<HousenumberIvl> interpolationInfos = interpolationWays.get(streetName);
				attachInterpolationInfoToHouses(streetName, houses, interpolationInfos);
				for (List<MapRoad> cluster: clusters.values()){
					matchCluster(streetName, houses, cluster, interpolationInfos);
				}
				for (HousenumberIvl hivl : interpolationInfos){
					if (hivl.foundCluster() == false && hivl.isBad() == false)
						log.warn("found no matching road near addr:interpolation way:",hivl);
				}
				for (HousenumberMatch house : houses){
					log.warn("found no street for house number element",streetName,house.getSign(),house.getElement().toBrowseURL(),", distance to next possible road:",Math.round(house.getDistance()),"m");
				}
			} 		
		}
		
		for (MapRoad r : roads) {
			adder.add(r);
		}
		
		houseNumbers.clear();
		roadsByName.clear();
		roads.clear();
	}
	
	/**
	 * process option --x-name-service-roads=n
	 * The program identifies unnamed roads which are only connected to one
	 * road with a name or to multiple roads with the same name. The process is
	 * repeated n times. If n > 1 the program will also use unnamed roads which
	 * are connected to unnamed roads if those are connected to named roads.
	 * Higher values for n mean deeper search, but reasonable values are
	 * probably between 1 and 5.
	 * 
	 * These roads are then used for house number processing like the named
	 * ones. If house numbers are assigned to these roads, they are named so
	 * that address search will find them.
	 */
	private void identifyServiceRoads() {
		Int2ObjectOpenHashMap<String> roadNamesByNodeIds = new Int2ObjectOpenHashMap<>();
		MultiHashMap<MapRoad, Coord> coordNodesUnnamedRoads = new MultiHashMap<>();
		HashSet<Integer> unclearNodeIds = new HashSet<>();
		long t1 = System.currentTimeMillis();
		List<MapRoad> unnamedRoads = new ArrayList<>();
		for (MapRoad road : roads){
			if (road.isSkipHousenumberProcessing())
				continue;
			if (road.getStreet() == null){
				if (road.getName() == null){
					unnamedRoads.add(road);
					for (Coord co : road.getPoints()){
						if (co.getId() != 0)
							coordNodesUnnamedRoads.add(road, co);
					}
				}
			} else {
				identifyNodes(road.getPoints(), road.getStreet(), roadNamesByNodeIds, unclearNodeIds);
			}
		}
		int numUnnamedRoads = unnamedRoads.size();
		long t2 = System.currentTimeMillis();
		if (log.isDebugEnabled())
			log.debug("identifyServiceRoad step 1 took",(t2-t1),"ms, found",roadNamesByNodeIds.size(),"nodes to check and",numUnnamedRoads,"unnamed roads" );
		long t3 = System.currentTimeMillis();
		int named = 0;
		MapRoad deepest = null;
		String lastName = null;
		for (int pass = 1; pass <= nameSearchDepth; pass ++){
			int unnamed = 0;
			List<MapRoad> namedRoads = new ArrayList<>();
			for (int j = 0; j < unnamedRoads.size(); j++){
				MapRoad road = unnamedRoads.get(j);
				if (road == null)
					continue;
				
				unnamed++;
				List<Coord> coordNodes = coordNodesUnnamedRoads.get(road); 
				String name = null;
				for (Coord co : coordNodes){
					if (unclearNodeIds.contains(co.getId())){
						name = null;
						unnamedRoads.set(j, null); // don't process again
						break;
					}
					String possibleName = roadNamesByNodeIds.get(co.getId());
					if (possibleName == null)
						continue;
					if (name == null)
						name = possibleName;
					else if (name.equals(possibleName) == false){
						name = null;
						unnamedRoads.set(j, null); // don't process again
						break;
					}
				}
				if (name != null){
					named++;
					road.setStreet(name);
					namedRoads.add(road);
					deepest = road;
					lastName = name;
					unnamedRoads.set(j, null); // don't process again
				}
			}
			for (MapRoad road : namedRoads){
				String name = road.getStreet();
				if (log.isDebugEnabled())
					log.debug("pass",pass,"using unnamed road for housenumber processing,id=",road.getRoadDef().getId(),":",name);
				roadsByName.add(name, road);
				List<Coord> coordNodes = coordNodesUnnamedRoads.get(road); 
				identifyNodes(coordNodes, name, roadNamesByNodeIds, unclearNodeIds);
			}

			if (namedRoads.isEmpty())
				break;
			if (log.isDebugEnabled())
				log.debug("pass",pass,unnamed,named);
		}
		long t4 = System.currentTimeMillis();
		if (log.isDebugEnabled()){
			log.debug("indentifyServiceRoad step 2 took",(t4-t3),"ms, found a name for",named,"roads" );
			if (named > 0)
				log.debug("last named road was",deepest.getRoadDef().getId(),lastName);
		}
		return;
	}

	private void identifyNodes(List<Coord> roadPoints,
			String streetName, Int2ObjectOpenHashMap<String> roadNamesByNodes, HashSet<Integer> unclearNodes) {
		for (Coord co : roadPoints){
			if (co.getId() != 0){
				String prevName = roadNamesByNodes.put(co.getId(), streetName);
				if (prevName != null){
					if (prevName.equals(streetName) == false)
						unclearNodes.add(co.getId());
				}
			}
		}			
	}

	/**
	 * 
	 * @param streetName 
	 * @param houses
	 * @param interpolationInfos
	 * @param clusters
	 * @return true if addr:interpolation ways should be evaluated
	 */
	private void attachInterpolationInfoToHouses(String streetName, List<HousenumberMatch> houses,
			List<HousenumberIvl> interpolationInfos) {
		if (interpolationInfos.isEmpty())
			return;
		HashMap<Element, HousenumberMatch>  nodes = new HashMap<>();
		for (HousenumberMatch house : houses){
			if (house.getElement() instanceof Node)
				nodes.put(house.getElement(), house);
		}
		Iterator<HousenumberIvl> iter = interpolationInfos.iterator();
		while (iter.hasNext()){
			HousenumberIvl hivl = iter.next();
			if (hivl.setNodeRefs(nodes) == false)
				iter.remove();
		}
	}

	/**
	 * Find a first match between the roads in a cluster and the houses.
	 * @param streetName
	 * @param houses
	 * @param roadsInCluster
	 * @param interpolationInfos.isEmpty()  
	 */
	private static void matchCluster(String streetName, List<HousenumberMatch> houses,
			List<MapRoad> roadsInCluster, List<HousenumberIvl> interpolationInfos ) {
		
		List<HousenumberMatch> housesNearCluster = new ArrayList<>();
		Iterator<HousenumberMatch> iter = houses.iterator();
		while (iter.hasNext()){
			HousenumberMatch house = iter.next();
			boolean foundRoad = false;
			for (MapRoad r : roadsInCluster) {
				Coord c1 = null;
				for (Coord c2 : r.getPoints()) {
					if (c1 != null) {
						Coord cx = house.getLocation();
						double dist = cx.shortestDistToLineSegment(c1, c2);
						if (house.getDistance() > dist)
							house.setDistance(dist);
						if (dist <= MAX_DISTANCE_TO_ROAD){
							housesNearCluster.add(house);
							foundRoad = true;
							break;
						}
					}
					c1 = c2;
				}
				if (foundRoad){
					iter.remove();
					break;
				}
			}
		}
		if (housesNearCluster.isEmpty())
			return;
		// we now have a list of houses and a list of roads that are in one area
		assignHouseNumbersToRoads(streetName, housesNearCluster, roadsInCluster) ;
		// we have now a first guess for the road and segment of each plausible house number element
		if (interpolationInfos.isEmpty() == false){
			useInterpolationInfo(streetName, housesNearCluster, roadsInCluster, interpolationInfos);
			Collections.sort(housesNearCluster, new HousenumberMatchByNumComparator());
		}
		MultiHashMap<MapRoad, HousenumberMatch> roadNumbers = new MultiHashMap<>(); 

		for (HousenumberMatch house : housesNearCluster){
			if (house.getRoad() == null || house.isIgnored())
				continue;
			roadNumbers.add(house.getRoad(), house);
		}
		if (roadNumbers.size() > 1){
			removeSimpleDuplicates(streetName, housesNearCluster, roadNumbers);
			checkDubiousRoadMatches(streetName, housesNearCluster, roadNumbers);
		}
		
		buildNumberIntervals(streetName, roadsInCluster, roadNumbers);
		List<HousenumberMatch> failed = checkPlausibility(streetName, roadsInCluster, housesNearCluster);
		return;
		
	}

	private static void assignHouseNumbersToRoads(String streetName,
			List<HousenumberMatch> housesNearCluster,
			List<MapRoad> roadsInCluster) {
		if (housesNearCluster.isEmpty())
			return;
		if (log.isDebugEnabled())
			log.debug("processing cluster",streetName,"with roads", roadsInCluster);

		Collections.sort(housesNearCluster, new HousenumberMatchByNumComparator());

		HousenumberMatch prev = null;
		for (HousenumberMatch house : housesNearCluster) {
			if (house.isIgnored())
				continue;
			house.setDistance(Double.POSITIVE_INFINITY); // make sure that we don't use an old match
			house.setRoad(null);
			List<HousenumberMatch> matchingRoads = new ArrayList<>();
			for (MapRoad r : roadsInCluster){
				HousenumberMatch test = new HousenumberMatch(house.getElement(), house.getHousenumber(), house.getSign());
				findClosestRoadSegment(test, r);
				if (test.getRoad() != null){
					matchingRoads.add(test);
				}
			}
			if (matchingRoads.isEmpty()){
				house.setIgnored(true);
				continue;
			}
			
			
			HousenumberMatch closest, best; 
			best = closest  = matchingRoads.get(0);
			
			if (matchingRoads.size() > 1){
				// multiple roads, we assume that the closest is the best
				// but we may have to check the alternatives as well
				
				Collections.sort(matchingRoads, new Comparator<HousenumberMatch>() {
					// sort by distance (smallest first)
					public int compare(HousenumberMatch o1, HousenumberMatch o2) {
						if (o1 == o2)
							return 0;
						int d = Double.compare(o1.getDistance(), o2.getDistance());
						if (d != 0)
							return d;
						return 0;
						
					}
				});
				closest  = matchingRoads.get(0);
				best = checkAngle(closest, matchingRoads);	
			}
			house.setDistance(best.getDistance());
			house.setSegmentFrac(best.getSegmentFrac());
			house.setRoad(best.getRoad());
			house.setSegment(best.getSegment());
			for (HousenumberMatch altHouse : matchingRoads){
				if (altHouse.getRoad() != best.getRoad() && altHouse.getDistance() < MAX_DISTANCE_TO_ROAD)
					house.addAlternativeRoad(altHouse.getRoad());
			}
				
			if (house.getRoad() == null) {
				house.setIgnored(true);
			} else {
				Coord c1 = house.getRoad().getPoints().get(house.getSegment());
				Coord c2 = house.getRoad().getPoints().get(house.getSegment()+1);
				house.setLeft(isLeft(c1, c2, house.getLocation()));
			}
			// plausibility check for duplicate house numbers
			if (prev != null && prev.getHousenumber() == house.getHousenumber()){
				// duplicate number (e.g. 10 and 10 or 10 and 10A or 10A and 10B)
				if (prev.getSign().equals(house.getSign())){
					prev.setDuplicate(true);
					house.setDuplicate(true);
				}
				
			}
			
			if (house.getRoad() == null) {
				if (house.isIgnored() == false)
					log.warn("found no plausible road for house number element",house.getElement().toBrowseURL(),"(",streetName,house.getSign(),")");
			}
			if (!house.isIgnored())
				prev = house;
		}
	}

	/**
	 * Find house number nodes which are parts of addr:interpolation ways.
	 *  Check earch addr:interpolation way for plausibility. 
	 *  If the check is ok, interpolate the numbers, if not, ignore 
	 *  also the numbers connected to the implausible way.
	 *  
	 *  XXX: Known problem: Doesn't work well when the road was
	 *  clipped at the tile boundary.
	 * @param streetName
	 * @param housesNearCluster
	 * @param roadsInCluster
	 * @param interpolationInfos 
	 */
	private static void useInterpolationInfo(String streetName,
			List<HousenumberMatch> housesNearCluster,
			List<MapRoad> roadsInCluster, List<HousenumberIvl> interpolationInfos) {
		HashSet<String> simpleDupCheckSet = new HashSet<>();
		HashSet<HousenumberIvl> badIvls = new HashSet<>();
		Long2ObjectOpenHashMap<HousenumberIvl> id2IvlMap = new Long2ObjectOpenHashMap<>();
		Int2ObjectOpenHashMap<HousenumberMatch> interpolatedNumbers = new Int2ObjectOpenHashMap<>();
		Int2ObjectOpenHashMap<HousenumberMatch> existingNumbers = new Int2ObjectOpenHashMap<>();
		HashMap<HousenumberIvl, List<HousenumberMatch>> housesToAdd = new LinkedHashMap<>();
		for (HousenumberMatch house : housesNearCluster){
			existingNumbers.put(house.getHousenumber(), house);
			// should we try to handle duplicates before interpolation?
		}
		int inCluster = 0;
		boolean allOK = true;
		for (HousenumberIvl hivl : interpolationInfos){
			if (hivl.inCluster(housesNearCluster) == false)
				continue;
			++inCluster;
			if (hivl.checkRoads() == false){
				allOK = false;
			} else {
				String hivlDesc = hivl.getDesc();
				if (simpleDupCheckSet.contains(hivlDesc)){
					// happens often in Canada (CanVec imports): two or more addr:interpolation ways with similar meaning
					// sometimes at completely different road parts, sometimes at exactly the same
					log.warn("found additional addr:interpolation way with same meaning, is ignored:",streetName, hivl);
					badIvls.add(hivl);
					allOK = false;
					continue;
				}
				simpleDupCheckSet.add(hivlDesc);
				
				id2IvlMap.put(hivl.getId(), hivl);
				List<HousenumberMatch> interpolatedHouses = hivl.getInterpolatedHouses();
				if (interpolatedHouses.isEmpty() == false){
					if (interpolatedHouses.get(0).getRoad() == null)
						assignHouseNumbersToRoads(streetName, interpolatedHouses, roadsInCluster);
					
					boolean foundDup = false;
					for (HousenumberMatch house : interpolatedHouses){
						if (house.getRoad() == null || house.getDistance() > HousenumberIvl.MAX_INTERPOLATION_DISTANCE_TO_ROAD)
							continue;
						boolean ignoreGenOnly = false;
						HousenumberMatch old = interpolatedNumbers.put(house.getHousenumber(), house);
						if (old == null){
							ignoreGenOnly = true;
							old = existingNumbers.get(house.getHousenumber());
						}
						if (old != null){
							// forget both or only one ? Which one?
							house.setIgnored(true);
							if (old.getLocation().distance(house.getLocation()) > 5){
								foundDup = true;
								if (!ignoreGenOnly){
									old.setIgnored(true);
									long ivlId = old.getElement().getOriginalId();
									HousenumberIvl bad = id2IvlMap.get(ivlId);
									if (bad != null)
										badIvls.add(bad);
								}
							}
						}
					}
					if (foundDup)
						badIvls.add(hivl);
					else
						housesToAdd.put(hivl, interpolatedHouses);
				}
			}
		}
		if (inCluster == 0)
			return;
		for (HousenumberIvl badIvl: badIvls){
			allOK = false;
			badIvl.ignoreNodes();
			housesToAdd.remove(badIvl);
		}
		Iterator<Entry<HousenumberIvl, List<HousenumberMatch>>> iter = housesToAdd.entrySet().iterator();
		while (iter.hasNext()){
			Entry<HousenumberIvl, List<HousenumberMatch>> entry = iter.next();
			if (log.isInfoEnabled())
				log.info("using generated house numbers from addr:interpolation way",entry.getKey());
			for (HousenumberMatch house : entry.getValue()){
				if (house.getRoad() != null && house.isIgnored() == false)
					housesNearCluster.add(house);
			}
		}
		if (log.isDebugEnabled()){
			if (allOK)
				log.debug("found no problems with interpolated numbers from addr:interpolations ways for roads with name",streetName);
			else 
				log.debug("found problems with interpolated numbers from addr:interpolations ways for roads with name",streetName);
		}
	}

	private static void removeSimpleDuplicates(String streetName,
			List<HousenumberMatch> housesNearCluster, MultiHashMap<MapRoad, HousenumberMatch> roadNumbers) {
		int n = housesNearCluster.size();
		for (int i = 1; i < n; i++){
			HousenumberMatch house1 = housesNearCluster.get(i-1);
			HousenumberMatch house2 = housesNearCluster.get(i);
			if (house1.getHousenumber() != house2.getHousenumber())
				continue;
			if (house1.getRoad() == house2.getRoad()){
				if (house1.isFarDuplicate())
					house2.setFarDuplicate(true);
				continue; // handled later
			}
			// we have two equal house numbers in different roads
			// check if they should be treated alike
			boolean markFarDup = false;
			double dist = house1.getLocation().distance(house2.getLocation());
			if (dist > 100)
				markFarDup = true;
			else {
				String city1 = house1.getElement().getTag("mkgmap:city");
				String city2 = house2.getElement().getTag("mkgmap:city");
				if (city1 != null && city1.equals(city2) == false){
					markFarDup = true;
				}
			}
			if (markFarDup){
				if (log.isDebugEnabled())
					log.debug("keeping duplicate numbers assigned to different roads in cluster ", streetName, house1,house2);
				house1.setFarDuplicate(true);
				house2.setFarDuplicate(true);
				continue;
			}
			boolean ignore2nd = false;
			if (dist < 30){
				ignore2nd = true;
			} else {
				Coord c1s = house1.getRoad().getPoints().get(house1.getSegment());
				Coord c1e = house1.getRoad().getPoints().get(house1.getSegment() + 1);
				Coord c2s = house2.getRoad().getPoints().get(house2.getSegment());
				Coord c2e = house2.getRoad().getPoints().get(house2.getSegment() + 1);
				if (c1s == c2s || c1s == c2e || c1e == c2s || c1e == c2e){
					// roads are directly connected
					ignore2nd = true;
				} 
			}
			if (ignore2nd){
				house2.setIgnored(true);
				if (log.isDebugEnabled()){
					if (house1.getSign().equals(house2.getSign()))
						log.debug("duplicate number is ignored",streetName,house2.getSign(),house2.getElement().toBrowseURL() );
					else 
						log.info("using",streetName,house1.getSign(), "in favor of",house2.getSign(),"as target for address search");
				}
			} else {
				if (log.isDebugEnabled())
					log.debug("keeping duplicate numbers assigned to different roads in cluster ", streetName, house1,house2);
				house1.setFarDuplicate(true);
				house2.setFarDuplicate(true);
			}
		}
	}

	/**
	 * If we find a sequence of house numbers like 1,3,5 or 1,2,3
	 * where the house in the middle is assigned to a different road,
	 * it is likely that this match is wrong.
	 * This typically happens when a house is rather far away from two 
	 * possible roads, but a bit closer to the wrong match. The two roads
	 * typically form an L, U, or O shape. 
	 * @param streetName common name tag (for debugging) 
	 * @param sortedHouses house number elements sorted by number
	 * @param roadNumbers the existing map which should be corrected
	 */
	private static void checkDubiousRoadMatches(String streetName,
			List<HousenumberMatch> sortedHouses,
			MultiHashMap<MapRoad, HousenumberMatch> roadNumbers) {
		int n = sortedHouses.size();
		for (int pos1 = 0; pos1 < n; pos1++){
			HousenumberMatch house1 = sortedHouses.get(pos1);
			if (house1.isIgnored() || house1.hasAlternativeRoad() == false)
				continue;
			int confirmed = 0;
			int falsified = 0;
			int pos2 = pos1;
			HousenumberMatch bestAlternative = null;
			double bestAlternativeDist = Double.POSITIVE_INFINITY;
			
			while (pos2 > 0){
				HousenumberMatch house2 = sortedHouses.get(pos2);
				if (house1.getHousenumber() - house2.getHousenumber() > 2)
					break;
					--pos2;
			}
			for (; pos2 < n; pos2++){
				if (confirmed > 0)
					break;
				if (pos2 == pos1)
					continue;
				HousenumberMatch house2 = sortedHouses.get(pos2);
				if (house2.isIgnored() || house2.getRoad() == null)
					continue;
				int deltaNum = house2.getHousenumber() - house1.getHousenumber();
				if (deltaNum > 2)
					break;
				if (deltaNum < -2)
					continue;
				double distHouses = house1.getLocation().distance(house2.getLocation());
				if (house2.getRoad() == house1.getRoad()){
					if (Math.abs(house1.getSegment() - house2.getSegment()) < 2){
						if (distHouses < 1.5 * bestAlternativeDist)
							confirmed++;
					} 
					continue;
				}
				
				Coord c1 = house2.getRoad().getPoints().get(house2.getSegment());
				Coord c2 = house2.getRoad().getPoints().get(house2.getSegment()+1);
				double frac2 = getFrac(c1,c2, house1.getLocation());
				double dist2 = distanceToSegment(c1,c2,house1.getLocation(),frac2);
				if (distHouses > dist2)
					continue;
				if (distHouses > house1.getDistance())
					continue;
				Coord c3 = house1.getRoad().getPoints().get(house1.getSegment());
				Coord c4 = house1.getRoad().getPoints().get(house1.getSegment()+1);
				if (c1 == c3 && Math.abs(Utils.getAngle(c2, c1, c4)) < 10 ||
						c1 == c4 && Math.abs(Utils.getAngle(c2, c1, c3)) < 10 ||
						c2 == c3 && Math.abs(Utils.getAngle(c1, c2, c4)) < 10 ||
						c2 == c4 && Math.abs(Utils.getAngle(c1, c2, c3)) < 10){
					confirmed++;
					continue;
				}
				++falsified;
				if (bestAlternative == null || dist2 < bestAlternativeDist){
					bestAlternative = house2;
					bestAlternativeDist = dist2;
				}
				if (log.isDebugEnabled())
					log.debug("road check house-1:",house1.getRoad(),house1,house1.getDistance(),",house-2:", house2.getRoad(),house2,house2.getDistance(),distHouses,dist2,frac2,"house-1 is falsified");
			}
			if (confirmed == 0 && falsified > 0){
				if (log.isInfoEnabled())
					log.info("house number element assigned to road",house1.getRoad(),house1,house1.getElement().toBrowseURL(),"is closer to more plausible houses at road",bestAlternative.getRoad());
				roadNumbers.removeMapping(house1.getRoad(), house1);
				Coord c1 = bestAlternative.getRoad().getPoints().get(bestAlternative.getSegment());
				Coord c2 = bestAlternative.getRoad().getPoints().get(bestAlternative.getSegment()+1);
				double frac2 = getFrac(c1,c2, house1.getLocation());
				double dist2 = distanceToSegment(c1,c2,house1.getLocation(),frac2);
				if (dist2 >= MAX_DISTANCE_TO_ROAD){
					log.info("house number element assigned to road",house1.getRoad(),house1,house1.getElement().toBrowseURL(),"is too far from more plausible road, is ignored");
					house1.setIgnored(true);
				} else {
					house1.setRoad(bestAlternative.getRoad());
					house1.setSegment(bestAlternative.getSegment());
					house1.setSegmentFrac(frac2);
					house1.setDistance(dist2);
					house1.setLeft(isLeft(c1, c2, house1.getLocation()));
					roadNumbers.add(house1.getRoad(), house1);
				} 
			} else if (confirmed == 0 && house1.isDuplicate()){
				// special ?
			}
		}
	}

	public static void findClosestRoadSegment(HousenumberMatch house, MapRoad r) {
		findClosestRoadSegment(house, r, 0, r.getPoints().size());
	}
	
	/**
	 * Fill/overwrite the fields in house which depend on the assigned road.  
	 */
	public static void findClosestRoadSegment(HousenumberMatch house, MapRoad r, int firstSeg, int stopSeg) {
		Coord cx = house.getLocation();

		double oldDist = house.getDistance();
		MapRoad oldRoad = house.getRoad();
		house.setRoad(null);
		house.setDistance(Double.POSITIVE_INFINITY);
		boolean foundGroupLink = false;
		int end = Math.min(r.getPoints().size(), stopSeg+1);
		for (int node = firstSeg; node + 1 < end; node++){
			Coord c1 = r.getPoints().get(node);
			Coord c2 = r.getPoints().get(node + 1);
			double frac = getFrac(c1, c2, cx);
			double dist = distanceToSegment(c1,c2,cx,frac);
			if (house.getGroup() != null && house.getGroup().linkNode == c1){
				if (c1.highPrecEquals(c2) == false){
					log.debug("block doesn't have zero length segment! Road:",r,house);
				}
				foundGroupLink = true;
				house.setDistance(dist);
				house.setSegmentFrac(frac);
				house.setRoad(r);
				house.setSegment(node);
				break;
			} else if (dist < house.getDistance()) {
				house.setDistance(dist);
				house.setSegmentFrac(frac);
				house.setRoad(r);
				house.setSegment(node);
			} 
		}
		
		if (house.getGroup() != null && house.getGroup().linkNode != null && foundGroupLink == false){
			log.debug(r,house,"has a group but the link was not found, should only happen after split of zero-length-segment");
		}
		if (oldRoad == r){
			if (house.getDistance() > MAX_DISTANCE_TO_ROAD + 2.5 && oldDist <= MAX_DISTANCE_TO_ROAD ){
				log.warn("line distorted? Road segment was moved by more than",
						String.format("%.2f m", 2.5), ", from address", r, house.getSign());
			}
		}
	}

	/**
	 * Distribute the houses to the roads and build as many numbers instances
	 * as needed.
	 * @param streetName
	 * @param roadsInCluster
	 * @param roadNumbers
	 * @param badMatches
	 */
	private static void buildNumberIntervals(String streetName, List<MapRoad> roadsInCluster,
			MultiHashMap<MapRoad, HousenumberMatch> roadNumbers) {
		// go through all roads and apply the house numbers
		ArrayList<HousenumberRoad> housenumberRoads = new ArrayList<>();
		for (MapRoad r : roadsInCluster){
			List<HousenumberMatch> potentialNumbersThisRoad = roadNumbers.get(r);
			if (potentialNumbersThisRoad.isEmpty()) 
				continue;
			HousenumberRoad hnr = new HousenumberRoad(streetName, r, potentialNumbersThisRoad);
			hnr.buildIntervals();
			housenumberRoads.add(hnr);
		}
		boolean optimized = false;
		for (int loop = 0; loop < 10; loop++){
			for (HousenumberRoad hnr : housenumberRoads){
				hnr.checkIntervals();
			}
			checkWrongRoadAssignmments(housenumberRoads);
			boolean changed = hasChanges(housenumberRoads);
			if (!optimized && !changed){
				improveSearchResults(housenumberRoads);
				changed = hasChanges(housenumberRoads);
				optimized = true;
			}
			if (!changed)
				break;
		}
		for (HousenumberRoad hnr : housenumberRoads){
			hnr.setNumbers();
		}
	}

	
	private static boolean hasChanges(
			ArrayList<HousenumberRoad> housenumberRoads) {
		for (HousenumberRoad hnr : housenumberRoads){
			if (hnr.isChanged())
				return true;
		}
		return false;
	}

	/**
	 * 
	 * @param streetName
	 * @param housenumberRoads
	 */
	private static void checkWrongRoadAssignmments(ArrayList<HousenumberRoad> housenumberRoads) {
		if (housenumberRoads.size() < 2)
			return;
		for (int loop = 0; loop < 10; loop++){
			boolean changed = false;
			for (int i = 0; i+1 < housenumberRoads.size(); i++){
				HousenumberRoad hnr1 = housenumberRoads.get(i);
				hnr1.setChanged(false);
				for (int j = i+1; j < housenumberRoads.size(); j++){
					HousenumberRoad hnr2 = housenumberRoads.get(j);
					hnr2.setChanged(false);
//					if (hnr1.getRoad().getRoadDef().getId() == hnr2.getRoad().getRoadDef().getId()){
//						long dd = 4;
//						if (hnr1.getRoad() == hnr2.getRoad()){
//							long ddd = 4;
//						}
//					}
					hnr1.checkWrongRoadAssignmments(hnr2);
					if (hnr1.isChanged()){
						changed = true;
						hnr1.checkIntervals();
					}
					if (hnr2.isChanged()){
						changed = true;
						hnr2.checkIntervals();
					}
				}
			}
			if (!changed)
				return;
		}
	}

	/**
	 * 
	 * @param housenumberRoads
	 */
	private static void improveSearchResults(ArrayList<HousenumberRoad> housenumberRoads) {
		for (HousenumberRoad hnr : housenumberRoads){
			hnr.improveSearchResults();
		}
	}

	/**
	 * Sorts house numbers by roads, road segments and position of the house number.
	 * @author WanMil
	 */
	public static class HousenumberMatchByPosComparator implements Comparator<HousenumberMatch> {

		public int compare(HousenumberMatch o1, HousenumberMatch o2) {
			if (o1 == o2) {
				return 0;
			}
			if (o1.getRoad() == null || o2.getRoad() == null){
				log.error("road is null in sort comparator",o1,o2);
				throw new MapFailedException("internal error in housenumber processing"); 
			}
			if (o1.getRoad() != o2.getRoad()) {
				// should not happen
				return o1.getRoad().hashCode() - o2.getRoad().hashCode();
			} 
			
			int dSegment = o1.getSegment() - o2.getSegment();
			if (dSegment != 0) {
				return dSegment;
			}
			
			double dFrac = o1.getSegmentFrac() - o2.getSegmentFrac();
			if (dFrac != 0d) {
				return (int)Math.signum(dFrac);
			}
			
			int d = o1.getHousenumber() - o2.getHousenumber();
			if (d != 0)
				return d;
			d = o1.getSign().compareTo(o2.getSign());
			if (d != 0)
				return d;
			return 0;
		}
		
	}
	
	/**
	 * Sorts house numbers by house number and segment 
	 * @author Gerd Petermann
	 */
	public static class HousenumberMatchByNumComparator implements Comparator<HousenumberMatch> {
		public int compare(HousenumberMatch o1, HousenumberMatch o2) {
			if (o1 == o2)
				return 0;
			int d = o1.getHousenumber() - o2.getHousenumber();
			if (d != 0)
				return d;
			d = o1.getSign().compareTo(o2.getSign());
			if (d != 0)
				return d;
			d = o1.getSegment() - o2.getSegment();
			if (d != 0)
				return d;
			double dDist = o1.getDistance() - o2.getDistance();
			if (dDist != 0d) {
				return (int)Math.signum(dDist);
			}
			if (d != 0)
				return d;
			d  = Long.compare(o1.getElement().getId(), o2.getElement().getId());
			return d;
		}
	}
	
	private static List<HousenumberMatch> checkPlausibility(String streetName, List<MapRoad> clusteredRoads,
			List<HousenumberMatch> housesNearCluster) {
		int countError = 0;
		int countTested = 0;
		List<HousenumberMatch> failed = new ArrayList<>();
		Int2IntOpenHashMap tested = new Int2IntOpenHashMap();
		tested.defaultReturnValue(-1);
		for (HousenumberMatch house : housesNearCluster){
			if (house.isIgnored())
				continue;
			++countTested;
			int num = house.getHousenumber();
			int countPlaces = 0;
			int countRoads = 0;
			int prevRes = tested.get(num);
			if (prevRes == 0)
				continue;
			boolean reported = false;
			for (MapRoad r : clusteredRoads){
				int countMatches = checkRoad(r, house.getHousenumber());
				if (countMatches == 0)
					continue;
				countRoads++;
				if (countMatches > 1){
					log.warn(streetName,house.getSign(),house.getElement().toBrowseURL(),"is coded in",countMatches,"different road segments");
					reported = true;
				}
				countPlaces += countMatches;
			}
			if (countPlaces == 1){
				tested.put(num,0);
				continue;
			}
			failed.add(house);
			++countError;
			
			if (countPlaces == 0 && house.getRoad() != null) {
				log.warn(streetName, house.getSign(), house.getElement().toBrowseURL(), "is not found in expected road", house.getRoad());
				reported = true;
			}
			if (countRoads > 1){
				log.warn(streetName, house.getSign(), house.getElement().toBrowseURL(), "is coded in", countRoads, "different roads");
				reported = true;
			}
			if (!reported)
				log.error(streetName, house.getSign(), house.getElement().toBrowseURL(), "unexpected result in plausibility check, counters:",countRoads, countPlaces);
		}
		if (countTested == 0)
			log.warn("plausibility check for road cluster found no valid numbers",clusteredRoads );
		else if (countError > 0)
			log.warn("plausibility check for road cluster failed with", countError, "detected problems:", clusteredRoads);
		else if (log.isInfoEnabled()) 
			log.info("plausibility check for road cluster found no problems", clusteredRoads);
		return failed; 
	}

	/**
	 * Count all segments that contain the house number
	 * @param r
	 * @param hn
	 * @return
	 */
	private static int checkRoad(MapRoad r, int hn) {
		if (r.getNumbers() == null)
			return 0;

		int matches = 0;
		Numbers last = null;
		Numbers firstMatch = null;
		for (Numbers numbers : r.getNumbers()){
			if (numbers.isEmpty())
				continue;
			int n = numbers.countMatches(hn);
			if (n > 0 && firstMatch == null)
				firstMatch = numbers;
			
			if (n == 1 && matches > 0){
				if (last.getLeftEnd() == numbers.getLeftStart() && last.getLeftEnd() == hn || 
						last.getRightEnd() == numbers.getRightStart() && last.getRightEnd() == hn ||
						last.getLeftStart() == numbers.getLeftEnd() && last.getLeftStart() == hn||
						last.getRightStart() == numbers.getRightEnd() && last.getRightStart() == hn){
					n = 0; // intervals are overlapping, probably two houses (e.g. 2a,2b) at a T junction
				}
			}
			
			matches += n;
			last = numbers;
		}
		return matches;
	}

	/**
	 * If the closest point to a road is a junction, try to find the road
	 * segment that forms a right angle with the house 
	 * @param closestMatch one match that is closest to the node
	 * @param otherMatches  the other matches with the same distance
	 * @return the best match
	 */
	private static HousenumberMatch checkAngle(HousenumberMatch closestMatch,
			List<HousenumberMatch> otherMatches) {
		
		if (otherMatches.isEmpty())
			return closestMatch;
		HousenumberMatch bestMatch = closestMatch;
		for (HousenumberMatch alternative : otherMatches){
			if (alternative == closestMatch)
				continue;
			if (closestMatch.getDistance() < alternative.getDistance())
				break;
			// a house has the same distance to different road objects
			// if this happens at a T-junction, make sure not to use the end of the wrong road 
			Coord c1 = closestMatch.getRoad().getPoints().get(closestMatch.getSegment());
			Coord c2 = closestMatch.getRoad().getPoints().get(closestMatch.getSegment()+1);
			Coord cx = closestMatch.getLocation();
			double dist = closestMatch.getDistance();
			double dist1 = cx.distance(c1);
			double angle, altAngle;
			if (dist1 == dist)
				angle = Utils.getAngle(c2, c1, cx);
			else 
				angle = Utils.getAngle(c1, c2, cx);
			Coord c3 = alternative.getRoad().getPoints().get(alternative.getSegment());
			Coord c4 = alternative.getRoad().getPoints().get(alternative.getSegment()+1);
			
			double dist3 = cx.distance(c3);
			if (dist3 == dist)
				altAngle = Utils.getAngle(c4, c3, cx);
			else 
				altAngle = Utils.getAngle(c3, c4, cx);
			double delta = 90 - Math.abs(angle);
			double deltaAlt = 90 - Math.abs(altAngle);
			if (delta > deltaAlt){
				bestMatch = alternative;
				c1 = c3;
				c2 = c4;
			} 
		}
		if (log.isInfoEnabled()){
			if (closestMatch.getRoad() != bestMatch.getRoad()){
				log.info("check angle: using road",bestMatch.getRoad().getRoadDef().getId(),"instead of",closestMatch.getRoad().getRoadDef().getId(),"for house number",bestMatch.getSign(),bestMatch.getElement().toBrowseURL());
			} else if (closestMatch != bestMatch){
				log.info("check angle: using road segment",bestMatch.getSegment(),"instead of",closestMatch.getSegment(),"for house number element",bestMatch.getElement().toBrowseURL());
			}
		}
		return bestMatch;
	}

	/**
	 * Evaluates if the given point lies on the left side of the line spanned by spoint1 and spoint2.
	 * @param spoint1 first point of line
	 * @param spoint2 second point of line
	 * @param point the point to check
	 * @return {@code true} point lies on the left side; {@code false} point lies on the right side
	 */
	public static boolean isLeft(Coord spoint1, Coord spoint2, Coord point) {
		if (spoint1.distance(spoint2) == 0){
			log.warn("road segment length is 0 in left/right evaluation");
		}

		return ((spoint2.getHighPrecLon() - spoint1.getHighPrecLon())
				* (point.getHighPrecLat() - spoint1.getHighPrecLat()) - (spoint2
				.getHighPrecLat() - spoint1.getHighPrecLat())
				* (point.getHighPrecLon() - spoint1.getHighPrecLon())) > 0;
	}
	
	/**
	 * Calculates the distance to the given segment in meter.
	 * @param spoint1 segment point 1
	 * @param spoint2 segment point 2
	 * @param point point
	 * @return the distance in meter
	 */
	public static double distanceToSegment(Coord spoint1, Coord spoint2, Coord point, double frac) {

		if (frac <= 0) {
			return spoint1.distance(point);
		} else if (frac >= 1) {
			return spoint2.distance(point);
		} else {
			return point.distToLineSegment(spoint1, spoint2);
		}

	}
	
	/**
	 * Calculates the fraction at which the given point is closest to 
	 * the infinite line going through both points.
	 * @param spoint1 segment point 1
	 * @param spoint2 segment point 2
	 * @param point point
	 * @return the fraction (can be <= 0 or >= 1 if the perpendicular is not on
	 * the line segment between spoint1 and spoint2) 
	 */
	public static double getFrac(Coord spoint1, Coord spoint2, Coord point) {
		int aLon = spoint1.getHighPrecLon();
		int bLon = spoint2.getHighPrecLon();
		int pLon = point.getHighPrecLon();
		int aLat = spoint1.getHighPrecLat();
		int bLat = spoint2.getHighPrecLat();
		int pLat = point.getHighPrecLat();
		
		double deltaLon = bLon - aLon;
		double deltaLat = bLat - aLat;

		if (deltaLon == 0 && deltaLat == 0) 
			return 0;
		else {
			// scale for longitude deltas by cosine of average latitude  
			double scale = Math.cos(Coord.int30ToRadians((aLat + bLat + pLat) / 3) );
			double deltaLonAP = scale * (pLon - aLon);
			deltaLon = scale * deltaLon;
			if (deltaLon == 0 && deltaLat == 0)
				return 0;
			else 
				return (deltaLonAP * deltaLon + (pLat - aLat) * deltaLat) / (deltaLon * deltaLon + deltaLat * deltaLat);
		}
	}

	/**
	 * @param length
	 * @return string with length, e.g. "0.23 m" or "116.12 m"
	 */
	public static String formatLen(double length){
		return String.format("%.2f m", length);
	}
	
	private static MultiHashMap<Integer, MapRoad> buildRoadClusters(List<MapRoad> roads){
		// find roads which are very close to each other
		MultiHashMap<Integer, MapRoad> clusters = new MultiHashMap<>();
		List<MapRoad> remaining = new ArrayList<>(roads);
		
		for (int i = 0; i < remaining.size(); i++){
			MapRoad r = remaining.get(i);
			Area bbox = r.getBounds();
			clusters.add(i, r);
			while (true){
				boolean changed = false;
				for (int j = remaining.size() - 1; j > i; --j){
					MapRoad r2 = remaining.get(j);
					Area bbox2 = r2.getBounds();
					boolean combine = false;
					if (r.getRoadDef().getId() == r2.getRoadDef().getId())
						combine = true; // probably clipped at tile boundary or split loop  
					else if (bbox.intersects(bbox2))
						combine = true;
					else if (bbox.getCenter().distance(bbox2.getCenter()) < MAX_DISTANCE_TO_ROAD)
						combine = true;
					else {
						List<Coord> toCheck = bbox2.toCoords();
						for (Coord co1: bbox.toCoords()){
							for (Coord co2 : toCheck){
								double dist = co1.distance(co2);
								if (dist < MAX_DISTANCE_TO_ROAD){
									combine = true;
									break;
								}
							}
							if (combine)
								break;
						}
					}
					if (combine){
						clusters.add(i, r2);
						bbox = new Area(Math.min(bbox.getMinLat(), bbox2.getMinLat()), 
								Math.min(bbox.getMinLong(), bbox2.getMinLong()), 
								Math.max(bbox.getMaxLat(), bbox2.getMaxLat()), 
								Math.max(bbox.getMaxLong(), bbox2.getMaxLong()));
						remaining.remove(j);
						changed = true;
					}
				}
				if (!changed)
					break;
			} 
		}
		return clusters;
	}
	
	private static List<HousenumberMatch> convertElements(String streetName, List<Element> elements){
		List<HousenumberMatch> houses = new ArrayList<HousenumberMatch>(
				elements.size());
		for (Element element : elements) {
			try {
				HousenumberMatch match = new HousenumberMatch(element);
				if (match.getLocation() == null) {
					// there has been a report that indicates match.getLocation() == null
					// could not reproduce so far but catching it here with some additional
					// information. (WanMil)
					log.error("OSM element seems to have no point.");
					log.error("Element: "+element.toBrowseURL()+" " +element);
					log.error("Please report on the mkgmap mailing list.");
					log.error("Continue creating the map. This should be possible without a problem.");
				} else {
					if (match.getHousenumber() < 1_000_000)
						houses.add(match);
					else {
						log.warn("Number too large, house number element",match,match.getElement().toBrowseURL(),"looks wrong, is ignored");
					}
				}
			} catch (IllegalArgumentException exp) {
				log.debug(exp);
			}
		}
		return houses;
	}

	private class RoadPoint implements Locatable{
		final Coord p;
		final MapRoad r;
		final int segment;
		MultiHashMap<String, HousenumberMatch> houses;
		public RoadPoint(MapRoad road, Coord co, int s) {
			this.p = co;
			this.r = road;
			this.segment = s;
		}
		public void addHouse(String name, HousenumberMatch house){
			if (houses == null)
				houses = new MultiHashMap<>();
			houses.add(name, house);	
		}
		@Override
		public Coord getLocation() {
			return p;
		}
	}

	/**
	 * TODO: use k-d-tree for clusters
	 * @return
	 */
	private KdTree<Locatable> buildRoadPointSearchTree(){
		// fill k-d-tree with points on roads
		KdTree<Locatable> searchTree = new KdTree<>();
		for (MapRoad road : roads){
			List<Coord> points = road.getPoints();
			for (int i = 0; i + 1 < points.size(); i++){
				Coord c1 = points.get(i);
				Coord c2 = points.get(i + 1);
				searchTree.add(new RoadPoint(road, c1, i));
				while (true){
					double segLen = c1.distance(c2);
					double frac = 100 / segLen;
					if (frac > 0.75)
						break;
					// if points are not close enough, add extra point
					c1 = c1.makeBetweenPoint(c2, frac);
					searchTree.add(new RoadPoint(road, c1, i));
					segLen -= 100;
				}
			}
			int last = points.size() - 1;
			searchTree.add(new RoadPoint(road, points.get(last) , last));
		}
		return searchTree;
	}

	private void findRoadsNearPlaces(KdTree<Locatable> roadSearchTree){
		long t1 = System.currentTimeMillis();
		MultiHashSet<MapRoad, RoadPoint> usedSegments = new MultiHashSet<>();
		MultiHashMap<MapRoad, String> potentialRoadNames = new MultiHashMap<>();
		// now find closest road(s) for houses 
		for (Entry<String, List<Element>> entry : placeHouseNumbers.entrySet()){
			String placeName = entry.getKey();
			if (houseNumbers.get(placeName).isEmpty() == false){
				log.debug("implement handling of addr:place with equal names like addr:street for",placeName);
				continue;
			}
			HashSet<MapRoad> candidates = new HashSet<>();
			List<Element> elements = entry.getValue();
			List<HousenumberMatch> houses = convertElements(placeName, elements);
			for (HousenumberMatch house : houses){
				RoadPoint rp = (RoadPoint) roadSearchTree.findNextPoint(house);
				Coord c1 = rp.p;
				double d1 = house.getLocation().distance(c1);
				boolean useIt = false;
				if (d1 < MAX_DISTANCE_TO_ROAD){
					useIt = true;
				} else if (d1 < 200){
					if (!useIt && rp.segment > 0){
						Coord c0 = rp.r.getPoints().get(rp.segment - 1);
						double d0 = house.getLocation().shortestDistToLineSegment(c0, c1);
						if (d0 < MAX_DISTANCE_TO_ROAD){
							useIt = true;
						}
					}
					if (!useIt && rp.segment + 1 < rp.r.getPoints().size()){
						Coord c2 = rp.r.getPoints().get(rp.segment + 1);
						double d2 = house.getLocation().shortestDistToLineSegment(c1, c2);
						if (d2 < MAX_DISTANCE_TO_ROAD){
							useIt = true;
						}
					}
				}
				if (!useIt)
					continue;
				rp.addHouse(placeName, house);
				candidates.add(rp.r);
				usedSegments.add(rp.r, rp);
			}
			if (candidates.isEmpty()){
				log.debug("found no road near houses with addr:place="+placeName);
				continue;
			}
			for (MapRoad road : candidates){
				potentialRoadNames.add(road, placeName);
			}
		}
		HashSet<String> goodPlaceHouses = new HashSet<>();
		for (int roadPos = 0; roadPos < roads.size(); roadPos++){
			MapRoad road = roads.get(roadPos);
			if (road.isSkipHousenumberProcessing())
				continue;
			List<String> names = potentialRoadNames.get(road);
			if (names.isEmpty())
				continue;
			if (names.size() == 1) {
				boolean okay = true;
				if (road.getName() == null){
					// road has one or more labels
					// road.getStreet() returns the value from tag mkgmap:street
					List<Element> housesWithStreet = houseNumbers.get(road.getStreet());
					okay = housesWithStreet.isEmpty();
				}
				String placeName = names.get(0);
				if (!okay){
					log.debug("road",road,"is candidate for houses with addr:street and with addr:place, ignoring it for addr:place");
					continue;
				}
				boolean changed = road.addLabel(placeName);
				if (changed && log.isInfoEnabled()){
					if (placeName.equals(road.getName()))
						log.info("using place name",placeName,"for road",road);
					else 
						log.info("added place name",placeName,"as label for road",road);
				}

				List<Element> housesWithStreet = houseNumbers.get(placeName);
				if (housesWithStreet.isEmpty()){
					goodPlaceHouses.add(placeName);
					if (placeName.equals(road.getStreet()) == false){
						List<MapRoad> roadsWithName = roadsByName.get(placeName);
						if (roadsWithName.contains(road) == false)
							roadsByName.add(placeName, road);
					}
				}
			} else {
				Set<RoadPoint> segments = usedSegments.get(road);
				HashMap<String, BitSet> placeSegments = new HashMap<>();
				for (RoadPoint rp: segments){
//					log.debug(rp.r, rp.segment,rp.houses.size());
					for (String name : rp.houses.keySet()){
						BitSet bs = placeSegments.get(name);
						if (bs == null){
							bs = new BitSet();
							placeSegments.put(name, bs);
						}
						bs.set(rp.segment);
					}
				}
				for (Entry<String, BitSet> entry : placeSegments.entrySet()){
					BitSet bs = entry.getValue();
					String placeName = entry.getKey();
					List<Element> housesWithStreet = houseNumbers.get(placeName);
					if (housesWithStreet.isEmpty()){
						int first = Math.max(0, bs.nextSetBit(0) - 1);
						int last = Math.min(bs.length()+1, road.getPoints().size());
						List<Coord> points = new ArrayList<>(last-first);
						for (int k = first; k < last; k++){
							Coord co = road.getPoints().get(k);
							Coord c2;
							if (k == first || k + 1 == last ){
								c2 = new CoordNode(co, nextNodeId++, co.getOnBoundary());
							}
							else 						
								c2 = new Coord (co);
							points.add(c2);
						}
						MapLine ml = new MapLine(road);
						assert points.get(0).getId() != 0;
						assert points.get(points.size()-1).getId() != 0;
						ml.setPoints(points);
						ml.setName(placeName);
						MapRoad copy = new MapRoad(road.getRoadDef().getId(), ml);
						copy.setMinResolution(road.getMaxResolution());
						copy.setRoadClass(road.getRoadDef().getRoadClass());
						copy.setSpeed(road.getRoadDef().getRoadSpeed());
						if (road.getRoadDef().isOneway())
							copy.setOneway();
						copy.setAccess((byte) 0);
						copy.skipAddToNOD(true);
						goodPlaceHouses.add(placeName);
						roadsByName.add(placeName, copy);
						roads.add(roadPos+1,copy);
						log.debug("adding copy of road",road,"for place",placeName,"with segments",first,"to",last);
					} else {
						log.debug("???");
					}

				}
			}
		}
		for (String placeName : goodPlaceHouses){
			List<Element> elements = placeHouseNumbers.remove(placeName);
			for (Element el : elements){
				houseNumbers.add(placeName, el);
			}
		}
		long t2 = System.currentTimeMillis();
		log.debug("preperation for addr:place houses took",t2-t1,"ms");
		return;	
	}
	
}