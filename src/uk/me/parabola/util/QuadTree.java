package uk.me.parabola.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.util.QuadTreeNode.QuadTreePolygon;

public class QuadTree {

	private final QuadTreeNode root;
	private long itemCount;

	public QuadTree(Area bbox) {
		this.root = new QuadTreeNode(bbox);
		this.itemCount = 0;
	}

	public boolean addAll(Collection<Coord> coordList) {
		long oldCount = itemCount;
		for (Coord c : coordList) {
			add(c);
		}
		return itemCount > oldCount;
	}

	public boolean add(Coord c) {

		boolean added = root.add(c);
		if (added) {
			itemCount++;
		}
		return added;
	}

	public List<Coord> get(Area bbox) {
		return root.get(bbox, new ArrayList<Coord>(2000));
	}

	public List<Coord> get(Collection<List<Coord>> polygons) {
		return root.get(new QuadTreePolygon(polygons), new ArrayList<Coord>(2000));
	}

	public List<Coord> get(List<Coord> polygon) {
		return get(polygon, 0);
	}

	public List<Coord> get(List<Coord> polygon, int offset) {
		if (polygon.size() < 3) {
			return Collections.emptyList();
		}
		if (!polygon.get(0).equals(polygon.get(polygon.size() - 1))) {
			throw new IllegalArgumentException("polygon is not closed");
		}
		List<Coord> points = root.get(new QuadTreePolygon(polygon), new ArrayList<Coord>(2000));
		if (offset > 0) {
			ListIterator<Coord> pointIter = points.listIterator();
			while (pointIter.hasNext()) {
				if (isCloseToPolygon(pointIter.next(), polygon, offset)) {
					pointIter.remove();
				}
			}
		}
		return points;
	}

	public void clear() {
		itemCount = 0;
		root.clear();
	}

	public long getSize() {
		return itemCount;
	}

	private static boolean isCloseToPolygon(Coord point, List<Coord> polygon, int gap) {
		Iterator<Coord> polyIter = polygon.iterator();
		Coord c2 = polyIter.next();
		while (polyIter.hasNext()) {
			Coord c1 = c2;
			c2 = polyIter.next();
			double dist = point.shortestDistToLineSegment(c1, c2);
			if (dist <= gap) {
				return true;
			}
		}
		return false;
	}
}
