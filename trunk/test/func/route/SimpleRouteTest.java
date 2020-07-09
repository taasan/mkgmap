/*
 * Copyright (C) 2008 Steve Ratcliffe
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
/* Create date: 16-Feb-2009 */
package func.route;

import java.io.FileNotFoundException;
import java.util.List;

import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.mkgmap.main.Main;

import func.Base;
import func.lib.Args;
import func.lib.RangeMatcher;
import org.junit.Test;

import static org.junit.Assert.*;

public class SimpleRouteTest extends Base {

	/**
	 * Simple test to ensure that nothing has changed.  Of course
	 * if the output should have changed, then this will have to be altered
	 * to match.
	 */
	@Test
	public void testSize() throws FileNotFoundException {
		Main.mainNoSystemExit(Args.TEST_STYLE_ARG, "--preserve-element-order",
				"--route", Args.TEST_RESOURCE_OSM + "uk-test-1.osm.gz", Args.TEST_RESOURCE_MP + "test1.mp");

		FileSystem fs = openFs(Args.DEF_MAP_ID + ".img");
		assertNotNull("file exists", fs);

		List<DirectoryEntry> entries = fs.list();
		int count = 0;
		for (DirectoryEntry ent : entries) {
			String ext = ent.getExt();

			int size = ent.getSize();
			switch (ext) {
			case "RGN":
				count++;
				System.out.println("RGN size " + size);
				assertThat("RGN size", size, new RangeMatcher(127579));
				break;
			case "TRE":
				count++;
				System.out.println("TRE size " + size);
				// Size varies depending on svn modified status
				assertThat("TRE size", size, new RangeMatcher(1427, 2));
				break;
			case "LBL":
				count++;
				assertEquals("LBL size", 28742, size);
				break;
			case "NET":
				count++;
				assertEquals("NET size", 66859, size);
				break;
			case "NOD":
				count++;
				System.out.println("NOD size " + size);
				assertEquals("NOD size", 170201, size);
				break;
			}
		}
		assertTrue("enough checks run", count == 5);

		fs = openFs(Args.DEF_MAP_FILENAME2);
		assertNotNull("file exists", fs);

		entries = fs.list();
		count = 0;
		for (DirectoryEntry ent : entries) {
			String ext = ent.getExt();

			int size = ent.getSize();
			switch (ext) {
			case "RGN":
				count++;
				System.out.println("RGN size " + size);
				assertThat("RGN size", size, new RangeMatcher(2764,3));
				break;
			case "TRE":
				count++;
				System.out.println("TRE size " + size);
				// Size varies depending on svn modified status
				assertThat("TRE size", size, new RangeMatcher(770, 2));
				break;
			case "LBL":
				count++;
				assertEquals("LBL size", 999, size);
				break;
			case "NET":
				count++;
				assertEquals("NET size", 1301, size);
				break;
			case "NOD":
				count++;
				System.out.println("NOD size " + size);
				assertEquals("NOD size", 3584, size);
				break;
			}
		}
		assertTrue("enough checks run", count == 5);
	}
}
