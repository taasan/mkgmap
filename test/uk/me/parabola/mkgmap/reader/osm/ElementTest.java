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
 * Create date: 30-Nov-2008
 */
package uk.me.parabola.mkgmap.reader.osm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;


public class ElementTest {
	@Test
	public void testEntryIterator() {
		Element el = new Way(1);

		el.addTag("a", "1");
		el.addTag("b", "2");
		el.addTag("c", "3");

		List<String> keys = new ArrayList<>();
		List<String> values = new ArrayList<>();

		for (Map.Entry<String, String> ent : el.getTagEntryIterator()) {
			keys.add(ent.getKey());
			values.add(ent.getValue());
		}

		keys.sort(null);
		values.sort(null);

		assertArrayEquals("list of keys",
				new String[] {"a", "b", "c"},
				keys.toArray());

		assertArrayEquals("list of values",
				new String[] {"1", "2", "3"},
				values.toArray());
	}

	@Test
	public void testaddTagFromRawOSM() {
		Element el = new Way(1);

		el.addTagFromRawOSM("a", "1");
		el.addTagFromRawOSM("b", "1 ");
		el.addTagFromRawOSM("c", " 1");
		el.addTagFromRawOSM("d", "1  2");
		el.addTagFromRawOSM("e", "1  2  3");
		el.addTagFromRawOSM("f", "   1  2  3 4  ");
		el.addTagFromRawOSM("g", " ");
		el.addTagFromRawOSM("h", "   ");

		assertEquals("1", el.getTag("a"));
		assertEquals("1", el.getTag("b"));
		assertEquals("1", el.getTag("c"));
		assertEquals("1 2", el.getTag("d"));
		assertEquals("1 2 3", el.getTag("e"));
		assertEquals("1 2 3 4", el.getTag("f"));
		assertEquals("", el.getTag("g"));
		assertEquals("", el.getTag("h"));
	}
}
