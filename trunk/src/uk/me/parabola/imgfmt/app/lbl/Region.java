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
import uk.me.parabola.imgfmt.app.ImgFileWriter;

/**
 * A region is in a country and contains one or more cities.
 * 
 * @author Steve Ratcliffe
 */
public class Region {
	private int index;

	private final Country country;
	private final Label label;

	public Region(Country country, Label label) {
		this.country = country;
		this.label = label;
	}

	public void write(ImgFileWriter writer) {
		writer.put2u(country.getIndex());
		writer.put3u(label.getOffset());
	}

	public int getIndex() {
		assert index > 0 : "Index not yet set";
		return index;
	}

	public Country getCountry() {
		return country;
	}

	public void setIndex(int index) {
		this.index = index;
	}

	public Label getLabel() {
		return label;
	}
}
