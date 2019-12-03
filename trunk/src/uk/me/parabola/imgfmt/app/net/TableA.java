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
 * Create date: 18-Jul-2008
 */
package uk.me.parabola.imgfmt.app.net;

import java.util.HashMap;
import java.util.LinkedHashMap;

import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.log.Logger;

/**
 * Table A that contains road information for segments in one RouteCenter.
 *
 * Each arc starting from a node in the RouteCenter has an associated
 * entry in Table A, shared by the inverse arc for internal arcs. This
 * entry consists of some routing parameters and a link to the road in
 * NET.
 */
public class TableA {
	private static final Logger log = Logger.getLogger(TableA.class);

	private static final int ITEM_SIZE = 5;

	// This table's start position relative to the start of NOD 1
	private int offset;

	// arcs for roundabouts
	private final HashMap<RoadDef,Integer> roundaboutArcs = new LinkedHashMap<>();
	// arcs for unpaved ways
	private final HashMap<RoadDef,Integer> unpavedArcs = new LinkedHashMap<>();
	// arcs for ferry ways
	private final HashMap<RoadDef,Integer> ferryArcs = new LinkedHashMap<>();
	// arcs for paved ways
	private final HashMap<RoadDef,Integer> pavedArcs = new LinkedHashMap<>();

	private static int count;

	private boolean frozen ; // true when no more arcs should be added

	public TableA() {
		log.debug("creating TableA", count);
		count++;
	}

	/**
	 * Add an arc to the table if not present and set its index.
	 *
	 * The value may overflow while it isn't certain that
	 * the table fulfils the size constraint.
	 */
	public void addArc(RouteArc arc) {
		assert !frozen : "trying to add arc to Table A after it has been frozen";
		int i;
		RoadDef rd = arc.getRoadDef();
		if(rd.isRoundabout()) {
			if (!roundaboutArcs.containsKey(rd)) {
				i = roundaboutArcs.size();
				roundaboutArcs.put(rd, i);
				log.debug("added roundabout arc", count, rd, i);
			}
		}
		else if(rd.ferry()) {
			if (!ferryArcs.containsKey(rd)) {
				i = ferryArcs.size();
				ferryArcs.put(rd, i);
				log.debug("added ferry arc", count, rd, i);
			}
		}
		else if(rd.paved()) {
			if (!pavedArcs.containsKey(rd)) {
				i = pavedArcs.size();
				pavedArcs.put(rd, i);
				log.debug("added paved arc", count, rd, i);
			}
		}
		else {
			if (!unpavedArcs.containsKey(rd)) {
				i = unpavedArcs.size();
				unpavedArcs.put(rd, i);
				log.debug("added unpaved arc", count, rd, i);
			}
		}
	}

	/**
	 * Retrieve an arc's index.
	 * Order in table A: roundabouts, unpaved, ferry, paved 
	 */
	public int getIndex(RouteArc arc) {
		frozen = true;			// don't allow any more arcs to be added
		int i;
		RoadDef rd = arc.getRoadDef();
		if(rd.isRoundabout()) {
			assert roundaboutArcs.containsKey(rd):
			"Trying to read Table A index for non-registered arc: " + count + " " + rd;
			i = roundaboutArcs.get(rd);
		}
		else if(rd.ferry()) {
			assert ferryArcs.containsKey(rd):
			"Trying to read Table A index for non-registered arc: " + count + " " + rd;
			i = roundaboutArcs.size() + unpavedArcs.size() + ferryArcs.get(rd);
		}
		else if(rd.paved()) {
			assert pavedArcs.containsKey(rd):
			"Trying to read Table A index for non-registered arc: " + count + " " + rd;
			i = roundaboutArcs.size() + unpavedArcs.size() + ferryArcs.size() + pavedArcs.get(rd);
		}
		else {
			assert unpavedArcs.containsKey(rd):
			"Trying to read Table A index for non-registered arc: " + count + " " + rd;
			i = roundaboutArcs.size() + unpavedArcs.get(rd);
		}
		assert i < 0x100 : "Table A index too large: " + rd;
		return i;
	}

	/**
	 * Retrieve the size of the Table as an int.
	 *
	 * While Table A is limited to byte size (0x100 entries),
	 * we temporarily build larger tables while subdividing
	 * the network.
	 */
	public int size() {
		return roundaboutArcs.size() + unpavedArcs.size() + ferryArcs.size() + pavedArcs.size();
	}

	public int numRoundaboutArcs() {
		return roundaboutArcs.size();
	}

	public int numUnpavedArcs() {
		return unpavedArcs.size();
	}

	public int numFerryArcs() {
		return ferryArcs.size();
	}

	/**
	 * Retrieve the size of the table as byte.
	 *
	 * This value is what should be written to the table
	 * header. When this is read, the table is assumed to
	 * be fit for writing, so at this point we check
	 * it isn't too large.
	 */
	public int getNumberOfItems() {
		assert size() < 0x100 : "Table A too large";
		return size();
	}

	/**
	 * This is called first to reserve enough space.  It will be rewritten
	 * later.
	 */
	public void write(ImgFileWriter writer) {
		offset = writer.position();
		int size = size() * ITEM_SIZE;
		log.debug("tab a offset", offset, "tab a size", size);
	
		for (int i = 0; i < size; i++)
			writer.put1u(0);
	}

	/**
	 * Fill in the table once the NET offsets of the roads are known.
	 */
	public void writePost(ImgFileWriter writer) {
		writer.position(offset);
		// unpaved arcs first
		for (RoadDef rd: roundaboutArcs.keySet()) {
			writePost(writer, rd);
		}
		for (RoadDef rd: unpavedArcs.keySet()) {
			writePost(writer, rd);
		}
		// followed by the ferry arcs
		for (RoadDef rd : ferryArcs.keySet()) {
			writePost(writer, rd);
		}
		// followed by the paved arcs
		for (RoadDef rd : pavedArcs.keySet()) {
			writePost(writer, rd);
		}
	}

	public void writePost(ImgFileWriter writer, RoadDef rd) {
		// write the table A entries.  Consists of a pointer to net
		// followed by 2 bytes of class and speed flags and road restrictions.
		int pos = rd.getOffsetNet1();
		int access = rd.getTabAAccess();
		// top bits of access go into net1 offset
		final int ACCESS_TOP_BITS = 0xc000;
		pos |= (access & ACCESS_TOP_BITS) << 8;
		access &= ~ACCESS_TOP_BITS;
		writer.put3u(pos);
		writer.put1u(rd.getTabAInfo());
		writer.put1u(access);
	}
}
