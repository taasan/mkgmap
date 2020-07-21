/*
 * Copyright (c) 2009.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Created: 13 Sep 2009
 * By: steve
 */

package uk.me.parabola.mkgmap.reader.osm;

import java.util.Formatter;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LevelInfo;
import uk.me.parabola.mkgmap.general.MapPoint;

/**
 * Holds the garmin type of an element and all the information that
 * will be needed to represent it on the map.  So we have a range of
 * resolutions at which it will be present.
 */
public class GType {
	private static final Logger log = Logger.getLogger(GType.class);

	private final FeatureKind featureKind;
	private final int type;

	private int minResolution = 24;
	private int maxResolution = 24;

	private int maxLevel = -1;
	private int minLevel;

	private String defaultName;

	// road class and speed will be set on roads.
	private int roadClass;
	private int roadSpeed;

	private boolean hasRoadAttribute;
	private boolean levelsWereFixed = false;

	/** If this is set, then we look for further types after this one is matched */
	private boolean continueSearch;

	// by default, a rule's actions are skipped when searching for
	// further rules to match - by setting this true, the rule's
	// actions will always be executed
	private boolean propogateActionsOnContinue;

	@SuppressWarnings("incomplete-switch")
	public static boolean checkType(FeatureKind featureKind, int type) {
		if (type >= 0x010000) {
			if ((type & 0xff) > 0x1f)
				return false;
		} else {
			switch (featureKind) {
			case POLYLINE:
				if (type > 0x3f)
					return false;
				break;
			case POLYGON:
				if (type > 0x7f || type == 0x4a)
					return false;
				break;
			case POINT:
				if (type < 0x0100)
					return false;
				int subtype = type & 0xff;
				if (MapPoint.isCityType(type)) {
					if (subtype != 0)
						return false;
				} else if (type >= 0x1600 && type < 0x1e00 || // Andrzej Popowski says.
						   type >= 0x2a00 && type < 0x3100 || // These may be indexed and this
						   type >= 0x6400 && type < 0x6700) { // confines subtype to 5 bits
					if (subtype > 0x1f)
						return false;
				} else {
					if (subtype > 0x3f)
						return false;
				}
				break;
			}
		}
		return true;
	}
	
	public GType(FeatureKind featureKind, String type) {
		this.featureKind = featureKind;
		try {
			int t = Integer.decode(type);
			if (featureKind == FeatureKind.POLYGON && t >= 0x100 && t < 0x10000 && (t & 0xff) == 0) {
				// allow 0xYY00 instead of 0xYY
				t >>= 8;
			}
			this.type = t;
		} catch (NumberFormatException e) {
			log.error("not numeric " + type);
			throw new ExitException("non-numeric type in style file");
		}
	}

	public FeatureKind getFeatureKind() {
		return featureKind;
	}

	public int getType() {
		return type;
	}

	public int getMinResolution() {
		return minResolution;
	}

	public void setMinResolution(int minResolution) {
		this.minResolution = minResolution;
	}

	public int getMaxResolution() {
		return maxResolution;
	}

	public void setMaxResolution(int maxResolution) {
		this.maxResolution = maxResolution;
	}

	public String getDefaultName() {
		return defaultName;
	}

	public void setDefaultName(String defaultName) {
		this.defaultName = defaultName;
	}

	/**
	 * Set minLevel and maxLevel based on the resolution values set and
	 * the given levels info.  We do this because we used to work only
	 * on resolution, but we want to move more towards working with
	 * levels.
	 */
	public void fixLevels(LevelInfo[] levels) {
		for (LevelInfo info : levels) {
			if (info.getBits() <= minResolution)
				maxLevel = info.getLevel();
			if (info.getBits() <= maxResolution)
				minLevel = info.getLevel();
		}
		levelsWereFixed = true;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();
		Formatter fmt = new Formatter(sb);
		sb.append('[');
		fmt.format("%#x", type);
		if (maxLevel == -1) {
			if (maxResolution == 24)
				fmt.format(" resolution %d", minResolution);
			else
				fmt.format(" resolution %d-%d", maxResolution, minResolution);
		} else {
			if (minLevel == 0)
				fmt.format(" level %d", maxLevel);
			else
				fmt.format(" level %d-%d", minLevel, maxLevel);
		}
		if (hasRoadAttribute)
			fmt.format(" road_class=%d road_speed=%d", roadClass, roadSpeed);
		
		if (continueSearch)
			sb.append(" continue");
		if (propogateActionsOnContinue)
			sb.append(" propagate");
		sb.append(']');
		String res = sb.toString();
		fmt.close();
		return res;
	}

	public int getMinLevel() {
		return minLevel;
	}

	public int getMaxLevel() {
		return maxLevel;
	}

	public int getRoadClass() {
		return roadClass;
	}

	public void setRoadClass(int roadClass) {
		// road class might also be set for nodes used by the link-pois-to-ways option
		if (getFeatureKind() == FeatureKind.POLYLINE)
			hasRoadAttribute = true;
		this.roadClass = roadClass;
	}

	public int getRoadSpeed() {
		return roadSpeed;
	}

	public void setRoadSpeed(int roadSpeed) {
		// road speed might also be set for nodes used by the link-pois-to-ways option
		if (getFeatureKind() == FeatureKind.POLYLINE)
			hasRoadAttribute = true;
		this.roadSpeed = roadSpeed;
	}

	public boolean hasRoadAttribute() {
		return hasRoadAttribute;
	}

	/**
	 * @return true if the object has valid attributes to be used as a routable way 
	 */
	public boolean isRoad() {
		if (!levelsWereFixed)
			log.error("internal: isRoad() called before fixLevels()");
		return hasRoadAttribute && minLevel == 0;
	}

	public boolean isContinueSearch() {
		return continueSearch;
	}

	public void propagateActions(boolean propagate) {
		propogateActionsOnContinue = propagate;
	}

	public boolean isPropogateActions() {
		return !continueSearch || propogateActionsOnContinue;
	}

	public void setContinueSearch(boolean continueSearch) {
		this.continueSearch = continueSearch;
	}
	
	/**
	 * 
	 * @param type the type value
	 * @return true if the type can be used for routable lines
	 */
	public static boolean isRoutableLineType(int type){
		return type >= 0x01 && type <= 0x3f;
	}
	/**
	 *  
	 * @param type the type value
	 * @return true if the type is known as routable in Garmin maps. These are 
	 * known to cause routing errors if used for non-routable lines. 
	 */
	public static boolean isSpecialRoutableLineType(int type){
		return type >= 0x01 && type <= 0x13 || type == 0x16 || type == 0x1a || type == 0x1b || type >= 0x2c && type <= 0x2f;
	}
	
	/**
	 * Return a type value in the commonly used hex format 
	 * @param type the integer value
	 * @return a hex string with even number of digits 
	 */
	public static String formatType(int type){
		String s = String.format("%x", type);
		return (s.length() % 2 != 0 ? "0x0":"0x") + s;
	}
	
}
