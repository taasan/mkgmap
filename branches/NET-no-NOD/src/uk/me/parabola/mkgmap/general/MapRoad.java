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
package uk.me.parabola.mkgmap.general;

import java.util.List;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.lbl.City;
import uk.me.parabola.imgfmt.app.lbl.Zip;
import uk.me.parabola.imgfmt.app.net.Numbers;
import uk.me.parabola.imgfmt.app.net.RoadDef;

/**
 * Used to represent a road.  A road is a special kind of line in that
 * it can be used to route down and can have addresses etc.
 *
 * A road has several coordinates, and some of those coordinates can be
 * routing nodes.
 *
 * A lot of the information is kept in a {@link RoadDef} this is done
 * because it needs to be shared between all sections and all levels
 * of the same road.
 *  
 * @author Steve Ratcliffe
 */
public class MapRoad extends MapLine {

	private final RoadDef roadDef;
	private boolean segmentsFollowing;
	private boolean skipHousenumberProcessing;
	private boolean namedByHousenumberProcessing;
	private final int roadId;
	
	public MapRoad(int roadId, long OSMid, MapLine line) {
		super(line);
		this.roadId = roadId;
		setPoints(line.getPoints());
		roadDef = new RoadDef(OSMid, getName());
	}

	private MapRoad(MapRoad r) {
		super(r);
		roadId = r.roadId;
		roadDef = r.roadDef;
		segmentsFollowing = r.segmentsFollowing;
	}

	/**
	 * @return value that can be used to sort MapRoad instances
	 */
	public int getRoadId(){
		return roadId;
	}
	public MapRoad copy() {
		return new MapRoad(this);
	}

	public boolean isRoad() {
		return true;
	}

	public void setRoadClass(int roadClass) {
		roadDef.setRoadClass(roadClass);
	}

	public void setSpeed(int speed) {
		roadDef.setSpeed(speed);
	}

	public void setOneway() {
		roadDef.setOneway();
	}

	public void setToll() {
		roadDef.setToll();
	}

	public void paved(boolean p) {
		roadDef.paved(p);
	}

	public void ferry(boolean f) {
		roadDef.ferry(f);
	}

	public void setSynthesised(boolean s) {
		roadDef.setSynthesised(s);
	}

	public void setAccess(byte access) {
		roadDef.setAccess(access);
	}

	public void setCarpoolLane() {
		roadDef.setCarpoolLane();
	}
	
	public void setNoThroughRouting() {
		roadDef.setNoThroughRouting();
	}

	public void setNumbers(List<Numbers> numbers) {
		roadDef.setNumbersList(numbers);
	}
	public List<Numbers> getNumbers() {
		return roadDef.getNumbersList();
	}

	public RoadDef getRoadDef() {
		return roadDef;
	}

	public void addRoadCity(City c) {
		roadDef.addCityIfNotPresent(c);
	}

	public void addRoadZip(Zip z) {
		roadDef.addZipIfNotPresent(z);
	}

	public void setRoundabout(boolean r) {
		roadDef.setRoundabout(r);
	}

	public void doFlareCheck(boolean fc) {
		roadDef.doFlareCheck(fc);
	}

	public boolean hasSegmentsFollowing() {
		return segmentsFollowing;
	}

	public void setSegmentsFollowing(boolean segmentsFollowing) {
		this.segmentsFollowing = segmentsFollowing;
	}

	public boolean isSkipHousenumberProcessing() {
		return skipHousenumberProcessing;
	}

	public void setSkipHousenumberProcessing(boolean skipHousenumberProcessing) {
		this.skipHousenumberProcessing = skipHousenumberProcessing;
	}

	public boolean isNamedByHousenumberProcessing() {
		return namedByHousenumberProcessing;
	}

	public void setNamedByHousenumberProcessing(boolean namedByHousenumberProcessing) {
		this.namedByHousenumberProcessing = namedByHousenumberProcessing;
	}

	public boolean skipAddToNOD() {
		return roadDef.skipAddToNOD();
	}

	public void skipAddToNOD(boolean skip) {
		roadDef.skipAddToNOD(skip);
	}

	public boolean addLabel(String label){
		if (label == null)
			return false;
		for (int i = 0; i < labels.length; i++){
			if (labels[i] == null){
				labels[i] = label;
				return true;
			}
			if (labels[i].equals(label))
				return false;
		}
		return false;
	}
	
	public int getLabelPos(String label){
		if (label == null)
			return -1;
		for (int i = 0; i < labels.length; i++){
			if (labels[i] == null){
				return -1;
			}
			if (labels[i].equals(label))
				return i;
		}
		return -1;
	}
	
	public String toString(){
		if ((getName() == null || getName().isEmpty()) && getStreet() != null)
			return "id="+this.getRoadDef().getId() + ", (" + this.getStreet() + ")";
		else 
			return "id="+this.getRoadDef().getId() + ", " + this.getName();
	}

	public void resetImgData() {
		roadDef.resetImgData();
		
	}
	
	public int countNodes() {
		int n = 0;
		for (Coord p : getPoints()) {
			if (p.isNumberNode()) 
				n++;
		}
		return n;
	}

	
}
