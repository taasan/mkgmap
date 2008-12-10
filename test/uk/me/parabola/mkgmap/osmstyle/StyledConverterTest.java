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
package uk.me.parabola.mkgmap.osmstyle;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.general.MapCollector;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.reader.osm.OsmConverter;
import uk.me.parabola.mkgmap.reader.osm.Style;
import uk.me.parabola.mkgmap.reader.osm.Way;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;


public class StyledConverterTest {
	private static final String LOC = "classpath:teststyles";
	private OsmConverter converter;
	private final List<MapLine> lines = new ArrayList<MapLine>();

	@Test
	public void testConvertWay() {
		Way way = makeWay();
		way.addTag("highway", "primary");
		way.addTag("x", "y");
		converter.convertWay(way);

		assertEquals("line converted", 1, lines.size());
		assertEquals("line from highway", 0x2, lines.get(0).getType());
	}

	@Test
	public void testNullPointerFromSecondMatch() throws FileNotFoundException {
		Way way = makeWay();
		way.addTag("highway", "primary");
		way.addTag("x", "z");
		converter.convertWay(way);

		assertEquals("line converted", 1, lines.size());
		assertEquals("line from x=y", 0x3, lines.get(0).getType());
	}

	@Test
	public void testModifyingTagsInUse() throws FileNotFoundException {
		Way way = makeWay();
		way.addTag("name", "bar");
		way.addTag("highway", "other");
		way.addTag("a", "z");
		way.addTag("z", "z");
		converter.convertWay(way);

		assertEquals("line converted", 1, lines.size());
		assertEquals("line", 0x12, lines.get(0).getType());
	}

	/**
	 * Test the overlay feature, when one line is duplicated with different
	 * types.
	 */
	@Test
	public void testOverlay() {
		Way way = makeWay();
		way.addTag("highway", "overlay");
		converter.convertWay(way);

		assertEquals("lines produced", 3, lines.size());
		assertEquals("first line is 1", 1, lines.get(0).getType());
		assertEquals("second line is 2", 2, lines.get(1).getType());
		assertEquals("third line is 3", 3, lines.get(2).getType());
	}

	private Way makeWay() {
		Way way = new Way();
		way.addPoint(new Coord(100, 100));
		way.addPoint(new Coord(100, 102));
		way.addPoint(new Coord(100, 103));
		return way;
	}

	@Before
	public void setUp() throws FileNotFoundException {
		Style style = new StyleImpl(LOC, "simple");
		MapCollector coll = new MapCollector() {
			public void addToBounds(Coord p) {
			}

			public void addPoint(MapPoint point) {
			}

			public void addLine(MapLine line) {
				lines.add(line);
			}

			public void addShape(MapShape shape) {
			}

			public void finish() {
			}
		};

		converter = new StyledConverter(style, coll);
	}
}
