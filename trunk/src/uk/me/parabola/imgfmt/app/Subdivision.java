/*
 * Copyright (C) 2006 Steve Ratcliffe
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
 * Create date: 07-Dec-2006
 */
package uk.me.parabola.imgfmt.app;

import org.apache.log4j.Logger;

import java.util.List;
import java.util.ArrayList;

/**
 * The map is divided into areas, depending on the zoom level.  These are
 * known as subdivisions.
 *
 * @author Steve Ratcliffe
 */
public class Subdivision {
	static private Logger log = Logger.getLogger(Subdivision.class);

	private int rgnPointer;
	
	private boolean hasPoints;
	private boolean hasIndPoints;
	private boolean hasPolylines;
	private boolean hasPolygons;

	// The location of the central point
	private int longitude;
	private int latitude;

	private int width;
	private int height;

	private int number;

	// Set if this is the last one.
	private boolean last;

	private List<Subdivision> divisions = new ArrayList<Subdivision>();

	public Subdivision(int latitude, int longitude, int width, int height) {
		this.latitude = latitude;
		this.longitude = longitude;
		this.width = width;
		this.height = height;
	}

	public Subdivision(Bounds area) {
		this.latitude = (area.getMaxLat()+area.getMinLat())/2;
		this.longitude = (area.getMaxLong()+area.getMinLong())/2;
		this.width = area.getMaxLat()-area.getMinLat();
		this.height = area.getMaxLong()-area.getMinLong();
	}

	public void write(ImgFile file) {
		file.put3(rgnPointer);
		file.put(getType());
		file.put3(longitude);
		file.put3(latitude);
		log.debug("last is " + last);
		file.putChar((char) (width | ((last)? 0x8000: 0)));
		file.putChar((char) height);

		if (!divisions.isEmpty()) {
			file.putChar((char) getNextLevel());
		}
	}

	private byte getType() {
		byte b = 0;
		if (hasPoints)
			b |= 0x10;
		if (hasIndPoints)
			b |= 0x20;
		if (hasPolylines)
			b |= 0x40;
		if (hasPolygons)
			b |= 0x80;

		return b;
	}

	public int getNextLevel() {
		return divisions.get(0).getNumber();
	}

	public void addSubdivision(Subdivision sd) {
		divisions.add(sd);
	}

	public int getNumber() {
		return number;
	}

	public void setNumber(int n) {
		number = n;
	}

	public boolean isLast() {
		return last;
	}

	public void setLast(boolean last) {
		this.last = last;
	}

	public void setHasPoints(boolean hasPoints) {
		this.hasPoints = hasPoints;
	}

	public void setHasIndPoints(boolean hasIndPoints) {
		this.hasIndPoints = hasIndPoints;
	}

	public void setHasPolylines(boolean hasPolylines) {
		this.hasPolylines = hasPolylines;
	}

	public void setHasPolygons(boolean hasPolygons) {
		this.hasPolygons = hasPolygons;
	}
}
