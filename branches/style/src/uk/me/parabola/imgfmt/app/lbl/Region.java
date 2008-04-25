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

import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.WriteStrategy;

/**
 * A region is in a country and contains one or more cities.
 * 
 * @author Steve Ratcliffe
 */
public class Region {
	private final char index;

	private final Country country;
	private Label label;

	public Region(Country country, int index) {
		this.country = country;
		this.index = (char) index;
	}

	public void write(WriteStrategy writer) {
		writer.putChar(country.getIndex());
		writer.put3(label.getOffset());
	}

	public char getIndex() {
		return index;
	}

	public void setLabel(Label label) {
		this.label = label;
	}
}
