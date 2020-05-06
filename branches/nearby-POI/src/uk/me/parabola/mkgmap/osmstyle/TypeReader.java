package uk.me.parabola.mkgmap.osmstyle;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LevelInfo;
import uk.me.parabola.mkgmap.reader.osm.FeatureKind;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.scan.SyntaxException;
import uk.me.parabola.mkgmap.scan.TokType;
import uk.me.parabola.mkgmap.scan.Token;
import uk.me.parabola.mkgmap.scan.TokenScanner;

/**
 * Read a type description from a style file.
 */
public class TypeReader {
	private static final Logger log = Logger.getLogger(TypeReader.class);

	private final FeatureKind kind;
	private final LevelInfo[] levels;
	private static final Pattern HYPHEN_PATTERN = Pattern.compile("-");

	public TypeReader(FeatureKind kind, LevelInfo[] levels) {
		this.kind = kind;
		this.levels = levels;
	}

	public GType readType(TokenScanner ts){
		return readType(ts, false, false, null);
	}
	
	public GType readType(TokenScanner ts, boolean performChecks, boolean forRoutableMap, Map<Integer, List<Integer>> overlays) {
		// We should have a '[' to start with
		Token t = ts.nextToken();
		if (t == null || t.getType() == TokType.EOF)
			throw new SyntaxException(ts, "No garmin type information given");

		if (!t.getValue().equals("[")) {
			throw new SyntaxException(ts, "No type definition");
		}

		ts.skipSpace();
		String type = ts.nextValue();
		if (!Character.isDigit(type.charAt(0)))
			throw new SyntaxException(ts, "Garmin type number must be first.  Saw '" + type + '\'');

		log.debug("gtype", type);
		

		GType gt = new GType(kind, type);
		if (!GType.checkType(gt.getFeatureKind(), gt.getType())) {
			if (!performChecks && (kind != FeatureKind.POLYLINE || overlays == null || overlays.get(gt.getType()) == null))
				throw new SyntaxException("invalid type " + type + " for " + kind + " in style file " + ts.getFileName() + ", line " + ts.getLinenumber());
		}
			
		while (!ts.isEndOfFile()) {
			ts.skipSpace();
			String w = ts.nextValue();
			if (w.equals("]"))
				break;

			if (w.equals("level")) {
				setLevel(ts, gt);
			} else if (w.equals("resolution")) {
				setResolution(ts, gt);
			} else if (w.equals("default_name")) {
				gt.setDefaultName(nextValue(ts));
			} else if (w.equals("road_class")) {
				gt.setRoadClass(nextIntValue(ts));
			} else if (w.equals("road_speed")) {
				gt.setRoadSpeed(nextIntValue(ts));
			} else if (w.equals("copy")) {
				// Reserved
			} else if (w.equals("continue")) {
				gt.setContinueSearch(true);
				// By default no propagate of actions on continue 
				gt.propagateActions(false);
			} else if (w.equals("propagate") || w.equals("with_actions") || w.equals("withactions")) {
				gt.propagateActions(true);
			} else if (w.equals("no_propagate")) {
				gt.propagateActions(false);
			} else if (w.equals("oneway")) {
				// reserved
			} else if (w.equals("access")) {
				// reserved
			} else {
				throw new SyntaxException(ts, "Unrecognised type command '" + w + '\'');
			}
		}
		
		gt.fixLevels(levels);
		int maxResLevel0 = toResolution(0);
		if (gt.getMaxResolution() > maxResLevel0 && gt.getMinResolution() > maxResLevel0) {
			String msg = "Type " + GType.formatType(gt.getType()) +  " min-res:" + gt.getMinResolution() + " will not be written with level 0 at resolution " + maxResLevel0
					+ " in style file " + ts.getFileName() + ", line " + ts.getLinenumber();
			if (performChecks) {
				log.error(msg);
			} else if (kind == FeatureKind.POLYLINE && gt.isRoad() && forRoutableMap) {
				log.error(msg , "-> routing may not work");
			}
		}
		if ("lines".equals(ts.getFileName())){
			if(gt.getRoadClass() < 0 || gt.getRoadClass() > 4)
				log.error("road class value", gt.getRoadClass(), "not in the range 0-4 in style file lines, line " + ts.getLinenumber());
			if(gt.getRoadSpeed() < 0 || gt.getRoadSpeed() > 7)
				log.error("road speed value ", gt.getRoadSpeed(), "not in the range 0-7 in style file lines, line " + ts.getLinenumber());
		}
		if (performChecks){
			boolean fromOverlays = false;
			List<Integer> usedTypes = null;
			if (gt.getMaxResolution() < levels[0].getBits() || gt.getMaxResolution() > 24){
				System.out.println("Warning: Object with max resolution of " + gt.getMaxResolution() + " is ignored. Check levels option and style file "+ ts.getFileName() + ", line " + ts.getLinenumber());
			} else if (gt.getMinResolution() > 24) {
				System.out.println("Warning: Object with min resolution of " + gt.getMinResolution() + " is ignored. Check levels option and style file "+ ts.getFileName() + ", line " + ts.getLinenumber());
			}
			if (overlays != null && kind == FeatureKind.POLYLINE){
				usedTypes = overlays.get(gt.getType());
				if (usedTypes != null)
					fromOverlays = true;
			}
			if (usedTypes == null)
				usedTypes = Arrays.asList(gt.getType());
			boolean foundRoutableType = false;
			for (int i = 0; i < usedTypes.size(); i++){
				int usedType = usedTypes.get(i);
				String typeOverlaidMsg = ". Type is overlaid with " + GType.formatType(usedType);
				if (GType.checkType(kind, usedType) == false){
					String msg = "Warning: invalid type " + type + " for " + kind + " in style file " + ts.getFileName() + ", line " + ts.getLinenumber();
					if (fromOverlays)
						msg += typeOverlaidMsg;
					System.out.println(msg);
				}
				if (kind == FeatureKind.POLYLINE && gt.getMinLevel() == 0 && gt.getMaxLevel() >= 0){ 
					if (GType.isSpecialRoutableLineType(usedType)){
						if (gt.hasRoadAttribute() == false){
							String msg = "Warning: routable type " + type  + " is used for non-routable line with level 0. This may break routing. Style file "+ ts.getFileName() + ", line " + ts.getLinenumber();
							if (fromOverlays)
								msg += typeOverlaidMsg;
							System.out.println(msg);
						}
						else if (i > 0){
							System.out.println("Warning: routable type " + type + " is used for non-routable line with level 0. " +
									"This may break routing. Style file " + ts.getFileName() + ", line " + ts.getLinenumber() + 
									typeOverlaidMsg +
									" which is used for adding the non-routable copy of the way.");
						}
					}
				}
				if (kind == FeatureKind.POLYLINE && GType.isRoutableLineType(usedType)){
						foundRoutableType = true;
				}
			}
			if (gt.hasRoadAttribute() && foundRoutableType == false && gt.getMinLevel() == 0 && gt.getMaxLevel() >= 0){
				String msg = "Warning: non-routable type " + type  + " is used in combination with road_class/road_speed. Line will not be routable. Style file "+ ts.getFileName() + ", line " + ts.getLinenumber();
				if (fromOverlays)
					msg += ". Type is overlaid, but not with a routable type";
				System.out.println(msg);
			}

		}
		
		return gt;
	}

	private static int nextIntValue(TokenScanner ts) {
		if (ts.checkToken("="))
			ts.nextToken();
		try {
			return ts.nextInt();
		} catch (NumberFormatException e) {
			throw new SyntaxException(ts, "Expecting numeric value");
		}
	}

	/**
	 * Get the value in a 'name=value' pair.
	 */
	private static String nextValue(TokenScanner ts) {
		if (ts.checkToken("="))
			ts.nextToken();
		return ts.nextWord();
	}

	/**
	 * A resolution can be just a single number, in which case that is the
	 * min resolution and the max defaults to 24.  Or a min to max range.
	 */
	private static void setResolution(TokenScanner ts, GType gt) {
		String str = ts.nextWord();
		log.debug("res word value", str);
		try {
			if (str.indexOf('-') >= 0) {
				String[] minmax = HYPHEN_PATTERN.split(str, 2);
				int val1 = Integer.parseInt(minmax[0]);
				int val2 = Integer.parseInt(minmax[1]);
				if (val1 > val2) {
					// Previously there was a bug where the order was reversed, so we swap the numbers if they are
					// the wrong way round.
					int h = val1;
					val1 = val2;
					val2 = h;
				}
				gt.setMinResolution(val1);
				gt.setMaxResolution(val2);
			} else {
				gt.setMinResolution(Integer.parseInt(str));
			}
		} catch (NumberFormatException e) {
			throw new SyntaxException(ts, "Invalid value for resolution: '" + str + '\'');
		}
	}

	/**
	 * Read a level spec, which is either the max level or a min to max range.
	 * This is immediately converted to resolution(s).
	 */
	private void setLevel(TokenScanner ts, GType gt) {
		String str = ts.nextWord();
		try {
			if (str.indexOf('-') >= 0) {
				String[] minmax = HYPHEN_PATTERN.split(str, 2);
				int val1 = toResolution(Integer.parseInt(minmax[0]));
				int val2 = toResolution(Integer.parseInt(minmax[1]));
				if (val1 > val2) {
					// Previously there was a bug where the order was reversed, so we swap the numbers if they are
					// the wrong way round.
					int h = val1;
					val1 = val2;
					val2 = h;
				}
				gt.setMinResolution(val1);
				gt.setMaxResolution(val2);
			} else {
				gt.setMinResolution(toResolution(Integer.parseInt(str)));
			}
		} catch (NumberFormatException e) {
			throw new SyntaxException(ts, "Invalid value for level: '" + str + '\'');
		}
	}

	private int toResolution(int level) {
		int max = levels.length - 1;
		if (level > max)
			throw new SyntaxException("Level number too large, max=" + max);

		return levels[max - level].getBits();
	}
}
