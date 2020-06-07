/*
 * Copyright (C) 2013.
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

package uk.me.parabola.mkgmap.osmstyle.actions;

import uk.me.parabola.mkgmap.reader.osm.Element;

/**
 * Sends a message including the tags of an element to System.err.
 * 
 * @author WanMil
 */
public class EchoTagsAction implements Action {
	private final ValueBuilder value;

	public EchoTagsAction(String str) {
		this.value = new ValueBuilder(str, false);
	}

	public boolean perform(Element el) {
		System.err.println(el.getBasicLogInformation() + " " + el.toTagString() + " " + value.build(el, el));
		return false;
	}
	
	public String toString() {
		return "echotags " + value + ";";
	}
}
