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
 * Create date: 02-Dec-2008
 */
package uk.me.parabola.mkgmap.osmstyle.actions;

import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.TagDict;

/**
 * Set the name on the given element.  The tags of the element may be
 * used in setting the name.
 *
 * We have a list of possible substitutions.
 *
 * @author Steve Ratcliffe
 */
public class NameAction extends ValueBuildedAction {
	private static final short TKM_LABEL_1 = TagDict.getInstance().xlate("mkgmap:label:1"); 
	/**
	 * search for the first matching name pattern and set the element name
	 * to it.
	 *
	 * If the element name is already set, then nothing is done.
	 *
	 * @param el The element on which the name may be set.
	 * @return 
	 */
	public boolean perform(Element el) {
		if (el.getTag(TKM_LABEL_1) != null)
			return false;
		
		for (ValueBuilder vb : getValueBuilder()) {
			String s = vb.build(el, el);
			if (s != null) {
				el.addTag(TKM_LABEL_1, s);
				return true;
			}
		}
		return false;
	}

	public String toString() {
		return "name " + calcValueBuildersString();
	}
}
