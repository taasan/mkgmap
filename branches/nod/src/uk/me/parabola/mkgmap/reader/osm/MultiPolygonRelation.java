package uk.me.parabola.mkgmap.reader.osm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Coord;

/**
 * Representation of an OSM Multipolygon Relation.
 * This will combine the different roles into one area.
 * 
 * @author Rene_A
 */
public class MultiPolygonRelation extends Relation {
	private Way outer;
	private static final Collection<Way> inners = new ArrayList<Way>();

	/**
	 * Create an instance based on an exsiting relation.  We need to do
	 * this because the type of the relation is not known until after all
	 * its tags are read in.
	 * @param other The relation to base this one on.
	 */
	public MultiPolygonRelation(Relation other) {
		for (Map.Entry<Element, String> pairs: other.getRoles().entrySet()){
	        String value = pairs.getValue();

			if (pairs.getKey() instanceof Way) {
				Way way = (Way) pairs.getKey();
				if (value.equals("outer"))
					outer = way;
				else if (value.equals("inner")) {
					inners.add(way);
				}
			}
		}
	}

	/** Process the ways in this relation.
	 * Adds ways with the role "inner" to the way with the role "outer"
	 */
	public void processElements() {
		if (outer != null)
		{   
			for (Way w: inners) {	
				if (w != null) {
					List<Coord> pts = w.getPoints();
					int[] insert = findCpa(outer.getPoints(), pts);
					if (insert[0] > 0)
						insertPoints(pts, insert[0], insert[1]);				
					pts.clear();
				}
			}
		}
	}
	
	/**
	 * Insert Coordinates into the outer way.
	 * @param inList List of Coordinates to be inserted
	 * @param out Coordinates will be inserted after this point in the outer way.
	 * @param in Points will be inserted starting at this index, 
	 *    then from element 0 to (including) this element;
	 */
	private void insertPoints(List<Coord> inList, int out, int in){
		List<Coord> outList = outer.getPoints();
		int index = out+1;
		for (int i = in; i < inList.size(); i++)
			outList.add(index++, inList.get(i));
		for (int i = 0; i <= in; i++)
			outList.add(index++, inList.get(i));

		//with this line commented we get triangles, when uncommented some areas disappear
		// at least in mapsource, on device itself looks OK.
		outList.add(index,outList.get(out));  
	}
	
	/**
	 * find the Closest Point of Approach between two coordinate-lists	 
	 * This will probably be moved to a Utils class
	 * @param l1 First list of points.
	 * @param l2 Second list of points.
	 * @return The first element is the index in l1, the second in l2 which are the closest together.
	 */
	private static int[] findCpa(List<Coord> l1, List <Coord> l2){
		double oldDistance = Double.MAX_VALUE;
		Coord found1 = null;
		Coord found2 = null;

		for (Coord c1: l1){
			for(Coord c2: l2){
				double newDistance = distance(c1, c2);
				if (newDistance < oldDistance)
				{
					oldDistance = newDistance;
					found1 = c1;
					found2 = c2;				
				}				
			}
		}
		// FIXME: what if not found?
		return new int[]{l1.indexOf(found1), l2.indexOf(found2)};
	}

	/**
	 * Find the Distance between two coordinates. 
	 * @return distance between c1 and c2.
	 */
	private static double distance(Coord c1, Coord c2){
		double lat1 = Utils.toDegrees(c1.getLatitude());
		double lat2 = Utils.toDegrees(c2.getLatitude());
		double long1 = Utils.toDegrees(c1.getLongitude());
		double long2 = Utils.toDegrees(c2.getLongitude());
				
		double latDiff;
		if (lat1 < lat2)
			latDiff = lat2 - lat1;
		else
			latDiff = lat1 - lat2;	
		if (latDiff > 90)
			latDiff -= 180;

		double longDiff;
		if (long1 < long2)
			longDiff = long2 - long1;
		else
			longDiff = long1 - long2;
		if (longDiff > 180)
			longDiff -= 360;
		
		return Math.pow((latDiff *latDiff) + (longDiff * longDiff), 0.5);
	}
}
