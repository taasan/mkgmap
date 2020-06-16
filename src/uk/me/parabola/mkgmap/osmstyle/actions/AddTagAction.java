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
 * Create date: 15-Nov-2008
 */
package uk.me.parabola.mkgmap.osmstyle.actions;

import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.TagDict;

/**
 * Add a tag, optionally changing it if it already exists.  The value that
 * the tag is set to can have replacements from the current tags.
 *
 * @author Steve Ratcliffe
 */
public class AddTagAction extends ValueBuildedAction {
	private final boolean modify;
	private final String tag;
	private final short tagKey;

	// The tags used to build the value.
	private Element valueTags;

	/**
	 * Create an action to add the given tag with a value.
	 * If the modify flag is true, then we change the tag if it
	 * already exists.
	 */
	public AddTagAction(String tag, String value, boolean modify) {
		this.modify = modify;
		this.tag = tag;
		this.tagKey = TagDict.getInstance().xlate(tag);
		add(value);
	}

	public boolean perform(Element el) {
		if (!modify && el.getTag(tagKey) != null)
			return false;
		
		Element tags = valueTags != null ? valueTags : el;

		for (ValueBuilder value : getValueBuilder()) {
			String newval = value.build(tags, el);
			if (newval != null) {
				el.addTag(tagKey, newval);
				return true;
			}
		}
		return false;
	}


	public void setValueTags(Element valueTags) {
		this.valueTags = valueTags;
	}

	public String toString() {
		return modify ? "set " : "add " + tag + "=" + calcValueBuildersString() + ";";
	}
}
