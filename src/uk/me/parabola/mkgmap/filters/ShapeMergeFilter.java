/*
 * Copyright (C) 2014.
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
package uk.me.parabola.mkgmap.filters;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.osmstyle.WrongAngleFixer;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.util.Java2DConverter;
import uk.me.parabola.util.MultiHashMap;


/**
 * Merge shapes with same Garmin type and similar attributes if they have common 
 * points. This reduces the number of shapes as well as the number of points.
 * @author GerdP
 *
 */
public class ShapeMergeFilter{
	private static final Logger log = Logger.getLogger(ShapeMergeFilter.class);
	private final int resolution;
	private final ShapeHelper dupShape = new ShapeHelper(new ArrayList<Coord>(0)); 

	public ShapeMergeFilter(int resolution) {
		this.resolution = resolution;
	}

	public List<MapShape> merge(List<MapShape> shapes, int subdivId) {
		if (shapes.size() <= 1)
			return shapes;
		int count = 0;
		MultiHashMap<Integer, Map<MapShape, List<ShapeHelper>>> topMap = new MultiHashMap<Integer, Map<MapShape,List<ShapeHelper>>>();
		List<MapShape> mergedShapes = new ArrayList<MapShape>();
		for (MapShape shape: shapes) {
			if (shape.getMinResolution() > resolution || shape.getMaxResolution() < resolution)
				continue;
			count++;
			if (shape.getPoints().size() > PolygonSplitterFilter.MAX_POINT_IN_ELEMENT){
				mergedShapes.add(shape);
				continue;
			}
			
			if (shape.getPoints().get(0) != shape.getPoints().get(shape.getPoints().size()-1)){
				// should not happen here
				log.error("shape is not closed with identical points" + shape.getOsmid());
				mergedShapes.add(shape);
				continue;
			}
			List<Map<MapShape, List<ShapeHelper>>> sameTypeList = topMap.get(shape.getType());
			ShapeHelper sh = new ShapeHelper(shape.getPoints());
			sh.id = shape.getOsmid();
			if (sameTypeList.isEmpty()){
				Map<MapShape, List<ShapeHelper>> lowMap = new LinkedHashMap<MapShape, List<ShapeHelper>>();
				ArrayList<ShapeHelper> list = new ArrayList<ShapeHelper>(4);
				list.add(sh);
				lowMap.put(shape, list);
				topMap.add(shape.getType(),lowMap);
				continue;
			}
			for (Map<MapShape, List<ShapeHelper>> lowMap : sameTypeList){
				boolean added = false;
				for (MapShape ms: lowMap.keySet()){
					if (ms.isSimilar(shape)){
						List<ShapeHelper> list = lowMap.get(ms);
						int oldSize = list.size();
						list = addWithConnectedHoles(list, sh, ms.getType());
						lowMap.put(ms, list);
						if (list.size() < oldSize+1)
							log.debug("shape with id " + sh.id + " was merged " + (oldSize+1 - list.size()) + "  time(s) at resolution " + resolution);
						added = true;
						break;
					}
				}
				if (!added){
					ArrayList<ShapeHelper> list = new ArrayList<ShapeHelper>();
					list.add(sh);
					lowMap.put(shape, list);
				}
			}
		}
		
		for (List<Map<MapShape, List<ShapeHelper>>> sameTypeList : topMap.values()){
			for (Map<MapShape, List<ShapeHelper>> lowMap : sameTypeList){
				Iterator<Entry<MapShape, List<ShapeHelper>>> iter = lowMap.entrySet().iterator();
				while (iter.hasNext()){
					Entry<MapShape, List<ShapeHelper>> item = iter.next();
					MapShape ms = item.getKey();
					List<ShapeHelper> shapeHelpers = item.getValue();
					for (ShapeHelper sh:shapeHelpers){
						MapShape newShape = ms.copy();
						assert sh.getPoints().get(0) == sh.getPoints().get(sh.getPoints().size()-1);
						if (sh.id == 0){
							List<Coord> optimizedPoints = WrongAngleFixer.fixAnglesInShape(sh.getPoints());
							newShape.setPoints(optimizedPoints);
						} else
							newShape.setPoints(sh.getPoints());
						mergedShapes.add(newShape);
					}
				}
			}
		}
		log.info("merged shapes " + count + "->" + mergedShapes.size() + " at resolution " + resolution);
		return mergedShapes;
	}

	/**
	 * Try to merge a shape with one or more of the shapes in the list.
	 *  If it cannot be merged, it is added to the list.
	 *  Holes in shapes are connected with the outer lines,
	 *  so no following routine must use {@link Java2DConverter}
	 *  to process these shapes.   
	 * @param list list of shapes with equal type
	 * @param toAdd new shape
	 * @return new list of shapes, this might contain fewer (merged) elements
	 */
	private List<ShapeHelper> addWithConnectedHoles(List<ShapeHelper> list,
			final ShapeHelper toAdd, final int type) {
		assert toAdd.getPoints().size() > 3;
		List<ShapeHelper> result = new ArrayList<ShapeHelper>(list.size()+1);
		ShapeHelper shNew = new ShapeHelper(toAdd);
		for (ShapeHelper shOld:list){
			if (shOld.getBounds().intersects(shNew.getBounds()) == false){
				result.add(shOld);
				continue;
			}
			int shSize = shOld.getPoints().size();
			int toMergeSize = shNew.getPoints().size();
			if (shSize + toMergeSize - 3 >= PolygonSplitterFilter.MAX_POINT_IN_ELEMENT){
				// don't merge because merged polygon would be split again
				result.add(shOld);
				continue;
			}
			ShapeHelper mergeRes = tryMerge(shOld, shNew, type);
			if (mergeRes == shOld){
				result.add(shOld);
				continue;
			} else if (mergeRes != null){
				shNew = mergeRes;
			}
			if (shNew == dupShape){
				log.warn("ignoring duplicate shape with id " + toAdd.id + " at " +  toAdd.getPoints().get(0).toOSMURL() + " with type " + GType.formatType(type) + " for resolution " + resolution);
				return list; // nothing to do
			}
		}
		if (shNew != null && shNew != dupShape)
			result.add(shNew);
		if (result.size() > list.size()+1 )
			log.error("result list size is wrong " + list.size() + " -> " + result.size());
		return result;
	}

	/**
	 * Find out if two shapes have common points. If yes, merge them.
	 * @param sh1 1st shape1
	 * @param sh2 2st shape2
	 * @param type Garmin type (used for log messages)
	 * @return merged shape or 1st shape if no common point found or {@code dupShape} 
	 * if both shapes describe the same area. 
	 */
	private ShapeHelper tryMerge(ShapeHelper sh1, ShapeHelper sh2, int type) {
		
		// both clockwise or both ccw ?
		boolean sameDir = sh1.areaTestVal > 0 && sh2.areaTestVal > 0 || sh1.areaTestVal < 0 && sh2.areaTestVal < 0;
		
		List<Coord> points1, points2;
		if (sh2.getPoints().size()> sh1.getPoints().size()){
			points1 = sh2.getPoints();
			points2 = sh1.getPoints();
		} else {
			points1 = sh1.getPoints();
			points2 = sh2.getPoints();
		}
		List<Coord> merged = null; 
		// find all coords that are common in the two shapes 
		IntArrayList sh1PositionsToCheck = new IntArrayList();
		IntArrayList sh2PositionsToCheck = new IntArrayList();

		findCommonCoords(points1, points2, sh1PositionsToCheck, sh2PositionsToCheck); 		
		if (sh1PositionsToCheck.isEmpty()){
			return sh1;
		}
		if (sh2PositionsToCheck.size() + 1 >= points2.size()){
			// all points are identical, might be a duplicate
			// or a piece that fills a hole 
			if (points1.size() == points2.size() && Math.abs(sh1.areaTestVal) == Math.abs(sh2.areaTestVal)){ 
				// it is a duplicate, we can ignore it
				// XXX this might fail if one of the shapes is self intersecting
				return dupShape;
			}
		}
		
		merged = mergeLongestSequence(points1, points2, sh1PositionsToCheck, sh2PositionsToCheck, sameDir);
		if (merged.get(0) != merged.get(merged.size()-1))
			merged = null;
		ShapeHelper shm = null;
		if (merged != null){
			shm = new ShapeHelper(merged);
			if (Math.abs(shm.areaTestVal) != Math.abs(sh1.areaTestVal) + Math.abs(sh2.areaTestVal)){
				log.warn("merging shapes skipped for shapes near " + points1.get(sh1PositionsToCheck.getInt(0)).toOSMURL() + " (maybe overlapping shapes?)");
				merged = null;
				shm = null;
			} 
		}
		if (shm != null)
			return shm;
		if (merged == null)
			return sh1;
		return null;
	}

	/**
	 * Find the common Coord instances and save their positions for both shapes.
	 * @param s1 shape 1
	 * @param s2 shape 2
	 * @param s1PositionsToCheck will contain common positions in shape 1   
	 * @param s2PositionsToCheck will contain common positions in shape 2
	 */
	private void findCommonCoords(List<Coord> s1, List<Coord> s2,
			IntArrayList s1PositionsToCheck,
			IntArrayList s2PositionsToCheck) {
		Map<Coord, Integer> s2PosMap = new IdentityHashMap<>(s2.size() - 1);
		
		for (int i = 0; i+1 < s1.size(); i++){
		    Coord co = s1.get(i);
		    co.resetShapeCount();
		}
		for (int i = 0; i+1 < s2.size(); i++){
		    Coord co = s2.get(i);
			co.resetShapeCount();
		    s2PosMap.put(co, i); 
		}
		
		// increment the shape counter for all points, but
		// only once for each distinct point
		for (Coord co : s2) {
			if (co.getShapeCount() == 0){
				co.incShapeCount();
			}
		}
		
		int start = 0;
		while(start < s1.size()){
			Coord co = s1.get(start);
			int usage = co.getShapeCount();			
			if (usage == 0)
				break;
			start++;
		}
		int pos = start+1;
		int tested = 0;
		while(true){
			if (pos+1 >= s1.size())
				pos = 0;
			Coord co = s1.get(pos);
			int usage = co.getShapeCount();
			if (++tested >= s1.size())
				break;
			if (usage > 0){
				s1PositionsToCheck.add(pos);
				Integer posInSh2 = s2PosMap.get(co);
				assert posInSh2 != null;
				s2PositionsToCheck.add(posInSh2);
			}
			pos++;
		}
		return;
	} 	
	
	/**
	 * Finds the longest sequence of common points in two shapes.
	 * @param points1 list of Coord instances that describes the 1st shape 
	 * @param points2 list of Coord instances that describes the 2nd shape
	 * @param sh1PositionsToCheck positions in the 1st shape that are common
	 * @param sh2PositionsToCheck positions in the 2nd shape that are common
	 * @param sameDir true if both shapes are clockwise or both are ccw
	 * @return the merged shape or null if no points are common.
	 */
	private List<Coord> mergeLongestSequence(List<Coord> points1, List<Coord> points2, IntArrayList sh1PositionsToCheck,
			IntArrayList sh2PositionsToCheck, boolean sameDir) {
		if (sh1PositionsToCheck.isEmpty())
			return null;
		int s1Size = points1.size(); 
		int s2Size = points2.size();
		int bestLength = 0;
		int bestStart = 0;
		int length = 0;
		int start = -1;
		int n1 = sh1PositionsToCheck.size();
		assert sh2PositionsToCheck.size() == n1;
		for (int i = 0; i+1 < n1; i++){
			int p0 = sh1PositionsToCheck.getInt(i);
			int p1 = sh1PositionsToCheck.getInt(i+1);
			if (Math.abs(p1-p0) == 1 || p0+2 == s1Size && p1 == 0 || p1+2 == s1Size && p0 == 0 ){
				// found sequence in old
				p0 = sh2PositionsToCheck.getInt(i);
				p1 = sh2PositionsToCheck.getInt(i+1);
				if (Math.abs(p1-p0) == 1 || p0+2 == s2Size && p1 == 0 || p1+2 == s2Size && p0 == 0 ){
					// found common seqence
					if (start < 0)
						start = i;
					length++; 
				} else {
					if (length > bestLength){
						bestLength = length;
						bestStart = start;
					}
					length = 0;
					start = -1;
				}
			} else {
				if (length > bestLength){
					bestLength = length;
					bestStart = start;
				}
				length = 0;
				start = -1;
			}
		}
		if (length > bestLength){
			bestLength = length;
			bestStart = start;
		}
		return combineShapes(points1, points2, sh1PositionsToCheck, sh2PositionsToCheck, bestStart, bestLength, sameDir);
	}
 	
	/**
	 * Combine two shapes. The longest sequence of common points is removed.
	 * The remaining points are connected in the direction of the 1st shape. 
	 * @param points1 list of Coord instances that describes the 1st shape 
	 * @param points2 list of Coord instances that describes the 2nd shape
	 * @param sh1PositionsToCheck positions in the 1st shape that are common
	 * @param sh2PositionsToCheck positions in the 2nd shape that are common
	 * @param startOfLongestSequence index of sh1PositionsToCheck/sh2PositionsToCheck 
	 *   that contains the start of the longest common sequence
	 * @param seqLength length of the longest common sequence
	 * @param sameDir true if both shapes are clockwise or both are ccw
	 * @return list of Coord instances that describes the merged shape
	 */
	private List<Coord> combineShapes(List<Coord> points1, List<Coord> points2,
			IntArrayList sh1PositionsToCheck, IntArrayList sh2PositionsToCheck,
			int startOfLongestSequence, int seqLength, boolean sameDir) {
		int n1 = points1.size();
		int n2 = points2.size();
		
		List<Coord> merged = new ArrayList<Coord>(n1 + n2 - 2*seqLength -1);
		int s1Pos = sh1PositionsToCheck.getInt(startOfLongestSequence+seqLength);
		for (int i = 0; i < n1 - seqLength - 1; i++){
			merged.add(points1.get(s1Pos++));
			if (s1Pos+1 >= n1)
				s1Pos = 0;
		}
		int s2Pos = sh2PositionsToCheck.getInt(startOfLongestSequence);
		int s2Step = sameDir ? 1:-1;
		for (int i = 0; i < n2 - seqLength; i++){
			merged.add(points2.get(s2Pos));
			s2Pos += s2Step;
			if (s2Pos < 0) 
				s2Pos = n2-2;
			else if (s2Pos+1 >= n2)
				s2Pos = 0;
		}
		return merged;
	}

	private class ShapeHelper{
		final private List<Coord> points;
		long id; // TODO: remove debugging aid
		long areaTestVal;
		private final Area bounds;

		public ShapeHelper(List<Coord> merged) {
			this.points = merged;
			areaTestVal = calcAreaSizeTestVal(points);
			bounds = prep();
		}

		public ShapeHelper(ShapeHelper other) {
			this.points = new ArrayList<>(other.getPoints());
			this.areaTestVal = other.areaTestVal;
			this.id = other.id;
			this.bounds = new Area(other.getBounds().getMinLat(), 
					other.getBounds().getMinLong(), 
					other.getBounds().getMaxLat(), 
					other.getBounds().getMaxLong());
		}

		public List<Coord> getPoints() {
//			return Collections.unmodifiableList(points); // too slow, use only while testing
			return points;
		}
		
		public Area getBounds(){
			return bounds;
		}
		/**
		 * Calculates a unitless number that gives a value for the size
		 * of the area and the direction (clockwise/ccw)
		 * 
		 */
		Area prep() {
			int minLat = Integer.MAX_VALUE;
			int maxLat = Integer.MIN_VALUE;
			int minLon = Integer.MAX_VALUE;
			int maxLon = Integer.MIN_VALUE;
			for (Coord co: points) {
				if (co.getLatitude() > maxLat)
					maxLat = co.getLatitude();
				if (co.getLatitude() < minLat)
					minLat = co.getLatitude();
				if (co.getLongitude() > maxLon)
					maxLon = co.getLongitude();
				if (co.getLongitude() < minLon)
					minLon = co.getLongitude();
			}
			return new Area(minLat, minLon, maxLat, maxLon);
		}
	}
	
	/**
	 * Calculate the high precision area size test value.  
	 * @param points
	 * @return area size in high precision map units * 2.
	 * The value is >= 0 if the shape is clockwise, else < 0   
	 */
	public static long calcAreaSizeTestVal(List<Coord> points){
		if (points.isEmpty())
			return 0;
		assert points.size() >= 4;
		if (points.get(0) != points.get(points.size()-1)){
			long dd  = 4;
		}
		assert points.get(0) == points.get(points.size()-1) : "shape is not closed with identical points";
		Iterator<Coord> polyIter = points.iterator();
		Coord c2 = polyIter.next();
		long signedAreaSize = 0;
		while (polyIter.hasNext()) {
			Coord c1 = c2;
			c2 = polyIter.next();
			signedAreaSize += (long) (c2.getHighPrecLon() + c1.getHighPrecLon())
					* (c1.getHighPrecLat() - c2.getHighPrecLat());
		}
		return signedAreaSize;
	}
}

