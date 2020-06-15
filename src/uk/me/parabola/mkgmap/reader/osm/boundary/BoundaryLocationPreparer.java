/*
 * Copyright (C) 2012.
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
package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.build.Locator;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.TagDict;
import uk.me.parabola.mkgmap.reader.osm.Tags;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Allows to extract boundary tags into BoundaryLocationInfo.  
 * Uses a locator if possible, else defaults. 
 * The locator is only needed when used with the LocationHook, utilities like the 
 * BoundaryPreparer will work without it.
 * @author GerdP
 *
 */
public class BoundaryLocationPreparer {
	private static final Logger log = Logger.getLogger(BoundaryLocationPreparer.class);

	private final Locator locator;
	private static final Pattern COMMA_OR_SEMICOLON_PATTERN = Pattern.compile("[,;]+");
	private final NameFinder nameFinder;

	/**
	 * Create a preparer. 
	 * @param props The program properties or null. 
	 */
	public BoundaryLocationPreparer(EnhancedProperties props) {
		locator = (props != null) ? new Locator(props) : null;
		nameFinder = new NameFinder(props);
	}

	/**
	 * Extract location relevant information from tags
	 * @param tags the Tags of a boundary 
	 * @return a new BoundaryLocationInfo instance 
	 */
	public BoundaryLocationInfo parseTags(Tags tags){
		String zip = getZip(tags);
		int admLevel = getAdminLevel(tags);
		String name = getName(tags);
		if (admLevel == 2 && locator != null) {
			String isoCode = locator.addCountry(tags);
			if (isoCode != null) {
				name = isoCode;
			} else {
				log.warn("Country name",name,"not in locator config. Country may not be assigned correctly.");
			}
			log.debug("Coded:",name);
		}
		return new BoundaryLocationInfo(admLevel, name, zip);
	}

	/**
	 * Extract location relevant information from tags of the element
	 * @param el the element
	 * @return a new BoundaryLocationInfo instance 
	 */
	public BoundaryLocationInfo parseTags(Element el){
		return parseTags(el.getCopyOfTags());
	}

	/** 
	 * Extract and prepare tag infos from BoundaryList 
	 * @param boundaries list of boundaries
	 * @return A Map that maps boundary Ids to the location relevant tags
	 */
	public Map<String, BoundaryLocationInfo> getPreparedLocationInfo(
			List<Boundary> boundaries) {
		HashMap<String, BoundaryLocationInfo> preparedLocationInfo = new HashMap<> ();
		if (boundaries != null){
			for (Boundary b :boundaries){
				preparedLocationInfo.put(b.getId(), parseTags(b.getTags())); 
			}
		}
		return preparedLocationInfo;
	}
	
	/**
	 * Try to extract the name of the boundary. 
	 * @param tags the boundary tags
	 * @return a name or null if no usable name tag was found
	 */
	private String getName(Tags tags) {
		if ("2".equals(tags.get(TK_ADMIN_LEVEL))) {
			// admin_level=2 boundaries. They need to be handled special because their name is changed 
			// to the 3 letter ISO code using the Locator class and the LocatorConfig.xml file. 
			for (short nameTagKey : Locator.PREFERRED_NAME_TAG_KEYS) {
				String nameTagValue = tags.get(nameTagKey);
				if (nameTagValue != null) {
					return getFirstPart(nameTagValue);
				}
			}
		}
		String name = nameFinder.getName(tags);
		if (name != null)
			return getFirstPart(name);
		return null;
	}

	private static String getFirstPart(String name) {
		String[] nameParts = COMMA_OR_SEMICOLON_PATTERN.split(name);
		if (nameParts.length > 0)
			return nameParts[0].trim().intern();
		return null;
	}
	
	private static final short TK_POSTAL_CODE = TagDict.getInstance().xlate("postal_code");
	private static final short TK_BOUNDARY = TagDict.getInstance().xlate("boundary");
	/**
	 * Try to extract a zip code from the the tags of a boundary. 
	 * @param tags the boundary tags
	 * @return null if no zip code was found, else a String that should be a zip code. 
	 */
	private String getZip(Tags tags) {
		String zip = tags.get(TK_POSTAL_CODE);
		if (zip == null && "postal_code".equals(tags.get(TK_BOUNDARY))){
			// unlikely
			String name = tags.get("name"); 
			if (name == null) {
				name = getName(tags);
			}
			if (name != null) {
				String[] nameParts = name.split(Pattern.quote(" "));
				if (nameParts.length > 0) {
					zip = nameParts[0].trim();
				}
			}
		}
		return zip;
	}

	public static final int UNSET_ADMIN_LEVEL = 100; // must be higher than real levels
	private static final short TK_ADMIN_LEVEL = TagDict.getInstance().xlate("admin_level");
	/**
	 * translate the admin_level tag to an integer. 
	 * @param tags the boundary tags
	 * @return the admin_level value. The value is UNSET_ADMIN_LEVEL if 
	 * the conversion failed. 
	 */
	private static int getAdminLevel(Tags tags) {
		if ("administrative".equals(tags.get(TK_BOUNDARY))) {
			String level = tags.get(TK_ADMIN_LEVEL);
			if (level != null) {
				try {
					int res = Integer.parseInt(level);
					if (res >= 2 && res <= 11)
						return res;
				} catch (NumberFormatException nfe) {
				}
			}
		}
		return UNSET_ADMIN_LEVEL;
	}
} 

