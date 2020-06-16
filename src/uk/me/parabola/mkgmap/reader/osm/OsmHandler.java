/*
 * Copyright (C) 2006 - 2012.
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

package uk.me.parabola.mkgmap.reader.osm;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import uk.me.parabola.imgfmt.FormatException;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;

/**
 * Base class for OSM file handlers.
 * 
 * @author Steve Ratcliffe
 */
public abstract class OsmHandler {
	// Elements that are read are saved/further processed by these two classes.
	protected ElementSaver saver;
	protected OsmReadingHooks hooks;

	private Map<String,Set<String>> deletedTags;
	private Map<String, String> usedTags;

	/** Pattern for values containing fixme, fix_me etc. */
	private static final Pattern FIXME_PATTERN = Pattern.compile("(?i)fix[ _]?+me");
	private boolean removeFixme;

	// Options
	private boolean ignoreBounds;
	
	// Node references within a way
	private long firstNodeRef;
	private long lastNodeRef;
	private boolean missingNodeRef;

	/**
	 * Set a set of tags with values that are to be deleted on input.
	 * For each key there is a set of values.  If the value set is empty then
	 * all tags with the given key are deleted.  If the value set is not empty
	 * then only tags with the given key that has one of the given values are
	 * deleted.
	 *
	 * @param deletedTags A map of tag key, to a set of values to be deleted.
	 */
	public void setTagsToDelete(Map<String, Set<String>> deletedTags) {
		this.deletedTags = deletedTags;
	}

	/**
	 * This sets a list of all the tags that are used in the system.
	 *
	 * Assuming this list is complete, no other tag can have an effect on the output
	 * and can therefore be dropped on input. This reduces memory usage, sometimes
	 * dramatically if there are many useless tags in the input.
	 *
	 * We keep a map of tag-name to tag-name.  This allows us to keep only a single
	 * copy of each string.  This also results in a reasonable reduction in memory usage.
	 *
	 * @param used The complete set of tags that are used to form the output.
	 */
	public void setUsedTags(Set<String> used) {
		if (used == null || used.isEmpty()) {
			usedTags = null;
			return;
		}
		usedTags = new HashMap<>();
		for (String s : used) {
			if (s == null) {
				continue;
			}
			// intern the keys
			s = s.intern();
			usedTags.put(s, s);
		}
	}

	/**
	 * Some tags are dropped at the input stage.  We drop tags that are not going
	 * to be used and there is also an option to provide a file containing tags to
	 * be dropped.
	 *
	 * @param key The tag key.
	 * @param val The tag value.
	 * @return Returns the tag key if this tag should be kept.  Returns null if the tag
	 * should be discarded.
	 */
	protected String keepTag(String key, String val) {
		if (val.isEmpty())
			return null;
		if(deletedTags != null) {
			Set<String> vals = deletedTags.get(key);
			if(vals != null && (vals.isEmpty() || vals.contains(val))) {
				return null;
			}
		}

		// By returning the value stored in usedTags, instead of the key, we ensure
		// that the same string is always used so saving some memory.
		if (usedTags != null)
			key = usedTags.get(key);
		
		if (key != null && removeFixme && val.length() >= 5) {
			// remove tags with value fixme if the key is NOT fixme
			if ("fixme".equals(key) || "FIXME".equals(key)) {
				// keep fixme no matter what value it has
			} else if (FIXME_PATTERN.matcher(val).matches()) {
				return null;
			}
		}
		return key;
	}

	/**
	 * Actually set the bounding box.  The boundary values are given.
	 */
	protected void setBBox(double minlat, double minlong, double maxlat, double maxlong) {
		if (minlat == maxlat || minlong == maxlong) {
			return; // silently ignore bounds with dim 0
		}
		Area bbox = new Area(minlat, minlong, maxlat, maxlong);
		saver.setBoundingBox(bbox);
	}

	public void setElementSaver(ElementSaver elementSaver) {
		this.saver = elementSaver;
	}

	public void setHooks(OsmReadingHooks plugin) {
		this.hooks = plugin;
	}

	/**
	 * Common actions to take when creating a new way.
	 * Reset some state and create the Way object.
	 * @param id The osm id of the new way.
	 * @return The new Way itself.
	 */
	protected Way startWay(long id) {
		firstNodeRef = 0;
		lastNodeRef = 0;
		missingNodeRef = false;
		return new Way(id);
	}

	/**
	 * Common actions to take when a way has been completely read by the parser.
	 * It is saved
	 * @param way The way that was read.
	 */
	protected void endWay(Way way) {
		way.setClosedInOSM(firstNodeRef == lastNodeRef);
		way.setComplete(!missingNodeRef);

		saver.addWay(way);
		hooks.onAddWay(way);
	}

	/**
	 * Add a coordinate point to the way.
	 * @param way The Way.
	 * @param id The coordinate id.
	 */
	protected void addCoordToWay(Way way, long id) {
		lastNodeRef = id;
		if (firstNodeRef == 0) firstNodeRef = id;

		Coord co = saver.getCoord(id);

		if (co != null) {
			hooks.onCoordAddedToWay(way, id, co);
			co = saver.getCoord(id);
			way.addPoint(co);
		} else {
			missingNodeRef = true;
		}
	}

	/**
	 * Enable removal of tags / value pairs where value matches the fixme pattern.
	 * @param b true: enable the filter
	 */
	public void setDeleteFixmeValues(boolean b) {
		this.removeFixme = b;
	}

	public boolean isIgnoreBounds() {
		return ignoreBounds;
	}

	public void setIgnoreBounds(boolean ignoreBounds) {
		this.ignoreBounds = ignoreBounds;
	}
	
	/**
	 * Determines if the file (or other resource) is supported by this map
	 * data source.  The implementation may do this however it likes, eg
	 * by extension or by opening up the file and reading part of it.
	 *
	 * @param name The file (or other resource) to check.
	 * @return True if the OSM handler supports that file.
	 */
	public abstract boolean isFileSupported(String name);

	/**
	 * Load osm data from open stream.  
	 * You would implement this interface to allow reading data from
	 * zipped files.
	 *
	 * @param is the already opened stream.
	 * @throws FormatException For any kind of malformed input.
	 */
	
	public abstract void parse(InputStream is) throws FormatException; 
}
