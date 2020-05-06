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
package uk.me.parabola.imgfmt.app.net;

import java.util.LinkedHashMap;
import java.util.Map;

import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.TagDict;

/**
 * mkgmap internal representation of (vehicle) access.
 * @author GerdP
 *
 */
public final class AccessTagsAndBits {
	
	private AccessTagsAndBits() {
		//  private constructor to hide the implicit public one
	}

	// constants for vehicle class
	public static final byte FOOT 	   = 0x01;
	public static final byte BIKE      = 0x02;
	public static final byte CAR       = 0x04;
	public static final byte DELIVERY  = 0x08;

	public static final byte TRUCK     = 0x10;
	public static final byte BUS       = 0x20;
	public static final byte TAXI      = 0x40;
	public static final byte EMERGENCY = (byte) 0x80;

	// other routing attributes
	public static final byte R_THROUGHROUTE	= 0x001; // note: 1 means throughroute is allowed
	public static final byte R_CARPOOL      = 0x002; 	
	public static final byte R_ONEWAY       = 0x004;
	public static final byte R_TOLL		    = 0x008;
	public static final byte R_UNPAVED      = 0x010;
	public static final byte R_FERRY        = 0x020;
	public static final byte R_ROUNDABOUT   = 0x040;

	public static final Map<String, Byte> ACCESS_TAGS;
	public static final Map<Short, Byte> ACCESS_TAGS_COMPILED;
	static {
		ACCESS_TAGS = new LinkedHashMap<>();
		ACCESS_TAGS.put("mkgmap:foot", FOOT);
		ACCESS_TAGS.put("mkgmap:bicycle", BIKE);
		ACCESS_TAGS.put("mkgmap:car", CAR);
		ACCESS_TAGS.put("mkgmap:delivery", DELIVERY);
		ACCESS_TAGS.put("mkgmap:truck", TRUCK);
		ACCESS_TAGS.put("mkgmap:bus", BUS);
		ACCESS_TAGS.put("mkgmap:taxi", TAXI);
		ACCESS_TAGS.put("mkgmap:emergency", EMERGENCY);

		ACCESS_TAGS_COMPILED = new LinkedHashMap<>();
		for (Map.Entry<String, Byte> entry : ACCESS_TAGS.entrySet()) {
			ACCESS_TAGS_COMPILED.put(TagDict.getInstance().xlate(entry.getKey()), entry.getValue());
		}
	}

	
	public static final Map<String, Byte> ROUTE_TAGS;
	static {
		ROUTE_TAGS = new LinkedHashMap<>();
		ROUTE_TAGS.put("mkgmap:throughroute", R_THROUGHROUTE);
		ROUTE_TAGS.put("mkgmap:carpool", R_CARPOOL);
		ROUTE_TAGS.put("oneway", R_ONEWAY);
		ROUTE_TAGS.put("mkgmap:toll", R_TOLL);
		ROUTE_TAGS.put("mkgmap:unpaved", R_UNPAVED);
		ROUTE_TAGS.put("mkgmap:ferry", R_FERRY);
		ROUTE_TAGS.put("junction", R_ROUNDABOUT);
	}

	public static byte evalAccessTags(Element el) {
		byte noAccess = 0;
		for (Map.Entry<Short, Byte> entry : ACCESS_TAGS_COMPILED.entrySet()) {
			if (el.tagIsLikeNo(entry.getKey()))
				noAccess |= entry.getValue();
		}
		return  (byte) ~noAccess;
	}

	private static final short carpoolTagKey = TagDict.getInstance().xlate("mkgmap:carpool");
	private static final short tollTagKey = TagDict.getInstance().xlate("mkgmap:toll");
	private static final short unpavedTagKey = TagDict.getInstance().xlate("mkgmap:unpaved");
	private static final short ferryTagKey = TagDict.getInstance().xlate("mkgmap:ferry");
	private static final short throughrouteTagKey = TagDict.getInstance().xlate("mkgmap:throughroute");
	private static final short junctionTagKey = TagDict.getInstance().xlate("junction");
	private static final short onewayTagKey = TagDict.getInstance().xlate("oneway");
	
	public static byte evalRouteTags(Element el) {
		byte routeFlags = 0;

		// Style has to set "yes"
		if (el.tagIsLikeYes(carpoolTagKey))
			routeFlags |= R_CARPOOL;
		if (el.tagIsLikeYes(tollTagKey))
			routeFlags |= R_TOLL;
		if (el.tagIsLikeYes(unpavedTagKey))
			routeFlags |= R_UNPAVED;
		if (el.tagIsLikeYes(ferryTagKey))
			routeFlags |= R_FERRY;

		// Style has to set "no" 
		if (el.tagIsLikeNo(throughrouteTagKey))
			routeFlags &= ~R_THROUGHROUTE;
		else 
			routeFlags |= R_THROUGHROUTE;

		// tags without the mkgmap: prefix
		if ("roundabout".equals(el.getTag(junctionTagKey))) 
			routeFlags |= R_ROUNDABOUT;
		if (el.tagIsLikeYes(onewayTagKey))
			routeFlags |= R_ONEWAY;

		return routeFlags;
	}

}
