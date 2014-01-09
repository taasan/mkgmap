/*
 * Copyright (C) 2014.
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

package uk.me.parabola.mkgmap.filters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.general.MapShape;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ShapeMergeFilterTest {
	private static final HashMap<Integer,Coord> map = new HashMap<Integer,Coord>(){
		{
			for (int lat = 0; lat < 100; lat +=5){
				for (int lon = 0; lon < 100; lon += 5){
					Coord co = new Coord(lat,lon);
					put(lat*1000 + lon,co);
				}
			}
		}
	};
	/**
	 * two simple shapes, sharing two points
	 */
	@Test
	public void testSimpleNonOverlapping(){
		List<Coord> points1 = new ArrayList<Coord>(){{
			add(getPoint(15,10));
			add(getPoint(30,25));
			add(getPoint(25,30));
			add(getPoint(15,35));
			add(getPoint(5,20));
			add(getPoint(15,10)); // close
		}};
		
		List<Coord> points2 = new ArrayList<Coord>(){{
			add(getPoint(25,30));
			add(getPoint(30,35));
			add(getPoint(20,40));
			add(getPoint(15,35));
			add(getPoint(25,30));
		}};
		testVariants("simple shapes", points1, points2,1,8);
	}

	/**
	 * two simple shapes, sharing three consecutive points 
	 */

	@Test
	public void test3SharedPointsNonOverlapping(){
		List<Coord> points1 = new ArrayList<Coord>(){{
			add(getPoint(15,10));
			add(getPoint(30,25));
			add(getPoint(25,30));
			add(getPoint(20,35)); 
			add(getPoint(15,35));
			add(getPoint(5,20));
			add(getPoint(15,10));// close
		}};
		
		List<Coord> points2 = new ArrayList<Coord>(){{
			add(getPoint(25,30));
			add(getPoint(30,35));
			add(getPoint(20,40));
			add(getPoint(15,35));
			add(getPoint(20,35));
			add(getPoint(25,30));// close
		}};
		testVariants("test 3 consecutive shared points", points1, points2, 1, 8);
	}
	
	/**
	 * one u-formed shape, the other closes it to a rectangular shape with a hole
	 * They are sharing 4 points. 
	 */

	@Test
	public void testCloseUFormed(){
		List<Coord> points1 = new ArrayList<Coord>(){{
			// u-formed shaped (open at top)
			add(getPoint(15,50));
			add(getPoint(30,50));
			add(getPoint(30,55));
			add(getPoint(20,55)); 
			add(getPoint(20,65));
			add(getPoint(30,65));
			add(getPoint(30,70));
			add(getPoint(15,70));
			add(getPoint(15,50));// close
		}};


		List<Coord> points2 = new ArrayList<Coord>(){{
			add(getPoint(35,50));
			add(getPoint(35,70));
			add(getPoint(30,70));
			add(getPoint(30,65));
			add(getPoint(30,55));
			add(getPoint(30,50));
			add(getPoint(35,50)); // close
		}};
		
		testVariants("test close U formed shape", points1, points2, 1, 11);
	}
	
	/**
	 * one u-formed shape, the fits into the u and shares all points
	 */

	@Test
	public void testFillUFormed(){
		List<Coord> points1 = new ArrayList<Coord>(){{
			// u-formed shaped (open at top)
			add(getPoint(15,50));
			add(getPoint(30,50));
			add(getPoint(30,55));
			add(getPoint(20,55)); 
			add(getPoint(20,65));
			add(getPoint(30,65));
			add(getPoint(30,70));
			add(getPoint(15,70));
			add(getPoint(15,50)); // close
		}};

		
		List<Coord> points2 = new ArrayList<Coord>(){{
			add(getPoint(30,55));
			add(getPoint(30,65));
			add(getPoint(20,65));
			add(getPoint(20,55));
			add(getPoint(30,55)); // close
		}};
		testVariants("test fill U-formed shape", points1, points2, 1, 5);
	}
	
	/**
	 * one u-formed shape, the fits into the u and shares all points
	 */

	@Test
	public void testFillHole(){
		List<Coord> points1 = new ArrayList<Coord>(){{
			// a rectangle with a hole 
			add(getPoint(35,50));
			add(getPoint(35,70));
			add(getPoint(15,70));
			add(getPoint(15,50));
			add(getPoint(30,50));
			add(getPoint(30,55));
			add(getPoint(20,55)); 
			add(getPoint(20,65));
			add(getPoint(30,65));
			add(getPoint(30,70));
			add(getPoint(30,50));
			add(getPoint(35,50));// close
		}};

		List<Coord> points2 = new ArrayList<Coord>(){{
			add(getPoint(30,55));
			add(getPoint(30,65));
			add(getPoint(20,65));
			add(getPoint(20,55));
			add(getPoint(30,55)); // close
		}};
		testVariants("test-fill-hole", points1, points2, 1, 5);
	}

	@Test
	public void testDuplicate(){
		List<Coord> points1 = new ArrayList<Coord>(){{
			add(getPoint(30,55));
			add(getPoint(30,65));
			add(getPoint(20,65));
			add(getPoint(20,55));
			add(getPoint(30,55)); // close
		}};
		List<Coord> points2 = new ArrayList<Coord>(points1);
		
		testVariants("test duplicate", points1, points2, 1, 5);
	}

	@Test
	public void testFullyContains(){
		List<Coord> points1 = new ArrayList<Coord>(){{
			add(getPoint(30,55));
			add(getPoint(30,65));
			add(getPoint(20,65));
			add(getPoint(20,55));
			add(getPoint(30,55)); // close
		}};

		List<Coord> points2 = new ArrayList<Coord>(){{
			add(getPoint(30,55));
			add(getPoint(30,65));
			add(getPoint(25,65));
			add(getPoint(25,55));
			add(getPoint(30,55)); // close
		}};
		
		testVariants("test overlap", points1, points2, 2, 5);
	}
	
	/**
	 * Test all variants regarding clockwise/ccw direction and positions of the points 
	 * in the list and the order of shapes. 
	 * @param list1
	 * @param list2
	 */
	void testVariants(String msg, List<Coord> list1, List<Coord> list2, int expectedNumShapes, int expectedNumPoints){
		MapShape s1 = new MapShape(1);
		MapShape s2 = new MapShape(2);
		s1.setMinResolution(22);
		s2.setMinResolution(22);
		for (int i = 0; i < 4; i++){
			for (int j = 0; j < list1.size(); j++){
				List<Coord> points1 = new ArrayList<>(list1);
				if (i == 1 || i == 3)
					Collections.reverse(points1);
				points1.remove(0);
				Collections.rotate(points1, j);
				points1.add(points1.get(0));
				s1.setPoints(points1);
				for (int k = 0; k < list2.size(); k++){
					List<Coord> points2 = new ArrayList<>(list2);
					if (i >= 2)
						Collections.reverse(points2);
					points2.remove(0);
					Collections.rotate(points2, k);
					points2.add(points2.get(0));
					s2.setPoints(points2);
					ShapeMergeFilter smf = new ShapeMergeFilter(24);
					for (int l = 0; l < 2; l++){
						String testId = msg+" i="+i+",j="+j+",k="+k+",l="+l;
						if (i == 0 && j == 0 && k == 2 && l == 1){
							long dd = 4;
						}
						List<MapShape> res;
						if (l == 0)
							res = smf.merge(Arrays.asList(s1,s2), 0);
						else 
							res = smf.merge(Arrays.asList(s2,s1), 0);
						assertTrue(testId, res != null);
						assertEquals(testId,expectedNumShapes, res.size() );
						assertEquals(testId,expectedNumPoints, res.get(0).getPoints().size());
					}
				}
			}
		}
	}
	Coord getPoint(int lat, int lon){
		Coord co = map.get(lat*1000+lon);
		assert co != null;
		return co;
	}
}
