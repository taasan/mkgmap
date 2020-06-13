/*
 * Copyright (C) 2010, 2012.
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
package uk.me.parabola.mkgmap.reader.osm;

import java.util.Collections;
import java.util.Set;

public class OsmCoastDataSource extends OsmMapDataSource {

	@Override
	protected OsmReadingHooks[] getPossibleHooks() {
		// no hooks
		return new OsmReadingHooks[] {};
	}

	@Override
	protected void createElementSaver() {
		elementSaver = new CoastlineElementSaver(getConfig());
	}

	@Override
	public Set<String> getUsedTags() {
		return Collections.singleton("natural");
	}
}
