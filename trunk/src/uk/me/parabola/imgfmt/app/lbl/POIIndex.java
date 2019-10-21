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
 * Create date: Jan 1, 2008
 */
package uk.me.parabola.imgfmt.app.lbl;

import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.trergn.Subdivision;

/**
 * Represent a POI index entry
 *
 * @author Mark Burton
 */
public class POIIndex {

	private final String name;
	private final int poiIndex;
	private final Subdivision group;
	private final int subType;

	public POIIndex(String name, int poiIndex, Subdivision group, int subType) {
		this.name = name;
		this.poiIndex = poiIndex;
		this.group = group;
		this.subType = subType & 0xff;
	}

	void write(ImgFileWriter writer) {
		writer.put1u(poiIndex);
		writer.put2u(group.getNumber());
		writer.put1u(subType);
	}

	public String getName() {
		return name;
	}
}
