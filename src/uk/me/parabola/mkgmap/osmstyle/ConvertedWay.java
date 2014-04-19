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

package uk.me.parabola.mkgmap.osmstyle;

import java.util.List;
import java.util.Map.Entry;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.reader.osm.Way;
import static uk.me.parabola.imgfmt.app.net.AccessTagsAndBits.*;

/**
 * Class that is used to connect an OSM way with the attributes of GType
 * after Style processing.
 * 
 * @author GerdP
 *
 */
public class ConvertedWay {
	private static final Logger log = Logger.getLogger(ConvertedWay.class);
	private int id;
	private byte roadClass;
	private byte roadSpeed;
	private byte mkgmapAccess; 		// bit mask 
	private GType gt;								
	private Way way;				// with tags after Style processing

	public ConvertedWay(int id, Way way, GType type) {
		this.id = id;
		this.way = way;
		this.gt = type;
		this.roadClass = (byte) gt.getRoadClass();
		this.roadSpeed = (byte) gt.getRoadSpeed();
		recalcRoadClass(way);
		recalcRoadSpeed(way);
		byte noAccess = 0;
		for (Entry<String, Byte> entry : ACCESS_TAGS.entrySet()){
			String access = way.getTag(entry.getKey());
			if (access == null)
				continue;
			if ("no".equals(access))
				noAccess |= entry.getValue();
		}
		mkgmapAccess = (byte) ~noAccess;
	}
	
	public GType getType(){
		return gt;
	}

	public Way getWay() {
		return way;
	}
	
	public byte getAccess(){
		return mkgmapAccess;
	}
	/**
	 * Recalculates the road class based on the tags
	 * <ul>
	 * <li>{@code mkgmap:road-class}</li>
	 * <li>{@code mkgmap:road-class-min}</li>
	 * <li>{@code mkgmap:road-class-max}</li>
	 * </ul>
	 * The road class is changed if the tags modify its road class. 
	 * 
	 * @param el an element 
	 * @return {@code true} the road class has been changed, else {@code false} 
	 */
	
	public boolean recalcRoadClass(Element el) {
		// save the original road class value
		byte oldRoadClass = roadClass;
		
		String val = el.getTag("mkgmap:road-class");
		if (val != null) {
			if (val.startsWith("-")) {
				roadClass -= Byte.decode(val.substring(1));
			} else if (val.startsWith("+")) {
				roadClass += Byte.decode(val.substring(1));
			} else {
				roadClass = Byte.decode(val);
			}
			val = el.getTag("mkgmap:road-class-max");
			byte roadClassMax = 4;
			if (val != null)
				roadClassMax = Byte.decode(val);
			val = el.getTag("mkgmap:road-class-min");

			byte roadClassMin = 0;
			if (val != null)
				roadClassMin = Byte.decode(val);
			if (roadClass > roadClassMax)
				roadClass = roadClassMax;
			else if (roadClass < roadClassMin)
				roadClass = roadClassMin;

		}
		return (roadClass != oldRoadClass);
	}
	
	/**
	 * Recalculates the road speed 
	 * <ul>
	 * <li>{@code mkgmap:road-speed-class}</li>
	 * <li>{@code mkgmap:road-speed}</li>
	 * <li>{@code mkgmap:road-speed-min}</li>
	 * <li>{@code mkgmap:road-speed-max}</li>
	 * </ul>
	 * 
	 * @param el an element 
	 * @return {@code true} the road speed has been changed, else {@code false} 
	 */
	private boolean recalcRoadSpeed(Element el) {
		// save the original road speed value
		byte oldRoadSpeed = roadSpeed;
		
		// check if the road speed is modified
		String roadSpeedOverride = el.getTag("mkgmap:road-speed-class");
		if (roadSpeedOverride != null) {
			try {
				byte rs = Byte.decode(roadSpeedOverride);
				if (rs >= 0 && rs <= 7) {
					// override the road speed class
					roadSpeed = rs;
				} else {
					log.error(el.getDebugName()
							+ " road classification mkgmap:road-speed-class="
							+ roadSpeedOverride + " must be in [0;7]");
				}
			} catch (Exception exp) {
				log.error(el.getDebugName()
						+ " road classification mkgmap:road-speed-class="
						+ roadSpeedOverride + " must be in [0;7]");
			}
		}
		
		// check if the road speed should be modified more
		String val = el.getTag("mkgmap:road-speed");
		if(val != null) {
			if(val.startsWith("-")) {
				roadSpeed -= Byte.decode(val.substring(1));
			}
			else if(val.startsWith("+")) {
				roadSpeed += Byte.decode(val.substring(1));
			}
			else {
				roadSpeed = Byte.decode(val);
			}
			val = el.getTag("mkgmap:road-speed-max");
			byte roadSpeedMax = 7;
			if(val != null)
				roadSpeedMax = Byte.decode(val);
			val = el.getTag("mkgmap:road-speed-min");

			byte roadSpeedMin = 0;
			if(val != null)
				roadSpeedMin = Byte.decode(val);
			if(roadSpeed > roadSpeedMax)
				roadSpeed = roadSpeedMax;
			else if(roadSpeed < roadSpeedMin)
				roadSpeed = roadSpeedMin;
		}
		return (oldRoadSpeed != roadSpeed);
	}

	public List<Coord> getPoints(){
		return way.getPoints();
	}

	public boolean isValid() {
		if (way == null)
			return false;
		if (way.getPoints() == null || way.getPoints().size()<2)
			return false;
		return true;
	}
	
	public byte getRoadClass(){
		return roadClass;
	}

	public byte getRoadSpeed(){
		return roadSpeed;
	}
}
