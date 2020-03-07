package uk.me.parabola.mkgmap.osmstyle.function;

import java.util.Locale;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Way;

/**
 * Calculates the area size of a polygon in garmin units ^ 2.
 *
 * if orderByDecreasingArea then the area of the polygon has already been
 * calculated and we use it here.
 * To be totally consistent, ie no possible difference to mkgmap behaviour when
 * --order-by-decreasing-area is not set, this flag should be set from the option;
 * However it is now considered that 'fullArea' is what the user might expect, rather
 * than various different values for the same original because of clipping and cutting to
 * expose holes.
 *
 * @author WanMil
 */
public class AreaSizeFunction extends CachedFunction {

	public AreaSizeFunction() {
		super(null);
	}

	protected String calcImpl(Element el) {
		if (el instanceof Way) {
			Way w = (Way)el;
			// a non closed way has size 0
			if (!w.hasEqualEndPoints()) {
				return "0";
			}
			long fullArea = w.getFullArea();
			if (fullArea == Long.MAX_VALUE)
				return "0";
			//  convert from high prec to value in map units
			double areaSize = (double) fullArea / (2 * (1<<Coord.DELTA_SHIFT) * (1<<Coord.DELTA_SHIFT));
			return String.format(Locale.US, "%.3f", Math.abs(areaSize));
		}
		return null;
	}

	@Override
	public String getName() {
		return "area_size";
	}
	
	@Override
	public boolean supportsWay() {
		return true;
	}

	@Override
	public int getComplexity() {
		return 2;
	}
}
