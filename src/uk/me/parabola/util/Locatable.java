/*
 * Copyright (C) 2015 Gerd Petermann
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
package uk.me.parabola.util;

import uk.me.parabola.imgfmt.app.Coord;

/**
 * For objects that have a location which can be represented as a Coord instance.
 * 
 * @author Gerd Petermann
 */
public interface Locatable {

	/**
	 * get the location of the object
	 */
	public Coord getLocation();
}
