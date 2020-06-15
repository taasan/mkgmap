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
 * Create date: 06-Dec-2008
 */
package uk.me.parabola.mkgmap.osmstyle.actions;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Relation;

/**
 * This is an action that contains sub-actions.  It is used for Relations
 * where you want to apply the commands to the elements that are contained
 * in the relation and not on the relation itself.
 *
 * @author Steve Ratcliffe
 */
public class SubAction implements Action {
	private final List<Action> actionList = new ArrayList<>();
	private final String role;
	private final String selector;

	public SubAction(String role, String selector) {
		this.role = role;
		this.selector = selector;
	}

	public boolean perform(Element el) {
		if (el instanceof Relation)
			performOnSubElements((Relation) el);
		return true; // probably false, but relation may contain itself in complex recursive structures 
	}

	private void performOnSubElements(Relation rel) {
		List<Map.Entry<String,Element>> elements = rel.getElements();

		for (Action a : actionList)
			if (a instanceof AddTagAction)
				((AddTagAction) a).setValueTags(rel);
			else if (a instanceof AddAccessAction)
				((AddAccessAction) a).setValueTags(rel);

		boolean once = "once".equals(selector);
		boolean first_only = "first".equals(selector);
		HashSet<Element> elems = once ? new HashSet<>() : null;

		for (Map.Entry<String,Element> r_el : elements) {
			if ((role == null || role.equals(r_el.getKey())) &&
				(!once || elems.add(r_el.getValue()))) {

				for (Action a : actionList)
					a.perform(r_el.getValue());
			}
			if (first_only)
				break;
		}
	}

	public void add(Action act) {
		actionList.add(act);
	}

	public String toString() {
		Formatter fmt = new Formatter();
		fmt.format("apply");
		if (selector != null) {
			fmt.format("_%s",selector);
		}
		if (role != null)
			fmt.format(" role=%s ", role);
		
		fmt.format(" {");

		for (Iterator<Action> it = actionList.iterator(); it.hasNext();) {
			Action a = it.next();
			fmt.format(a.toString());
			if (it.hasNext())
				fmt.format(" ");
		}
		
		fmt.format("}");
		return fmt.toString();
	}
}
