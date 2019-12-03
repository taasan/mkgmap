/*
 * Copyright (C) 2008
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
 * Create date: 07-Jul-2008
 */
package uk.me.parabola.imgfmt.app.net;

import java.util.ArrayList;

import java.util.List;

import uk.me.parabola.imgfmt.app.ImgFileWriter;
import static uk.me.parabola.imgfmt.app.net.AccessTagsAndBits.*;


/**
 * A restriction in the routing graph.
 *
 * A routing restriction has two or more arcs.
 * The first arc is the "from" arc, the last is the "to" arc,
 *  and other arc is a "via" arc.
 *
 * A from-to-via restriction says you can't go along arc "to"
 * if you came to node to.getSource() == from.getSource()
 * via the inverse arc of "from". We're using the inverse of
 * "from" since that has the information we need for writing
 * the Table C entry.
 *
 * @author Robert Vollmert
 */
public class RouteRestriction {
	// first three bytes of the header -- might specify the type of restriction
	// and when it is active
	private static final byte RESTRICTION_TYPE = 0x05; // 0x07 spotted, meaning?

	// To specify that a node is given by a relative offset instead
	// of an entry to Table B.
	private static final int F_INTERNAL = 0x8000;

	// the arcs
	private final List<RouteArc> arcs;

	private final RouteNode viaNode; 
	// offset in Table C
	private int offsetSize;
	private int offsetC;

	// last restriction in a node
	private boolean last;

	// mask that specifies which vehicle types the restriction doesn't apply to
	private final byte exceptMask;
	private final byte flags; // meaning of bits 0x01 and 0x10 are not clear 

	private static final byte F_EXCEPT_FOOT      = 0x02;
	private static final byte F_EXCEPT_EMERGENCY = 0x04;
	private static final byte F_MORE_EXCEPTIONS  = 0x08;
	
	private static final byte EXCEPT_CAR      = 0x01;
	private static final byte EXCEPT_BUS      = 0x02;
	private static final byte EXCEPT_TAXI     = 0x04;
	private static final byte EXCEPT_DELIVERY = 0x10;
	private static final byte EXCEPT_BICYCLE  = 0x20;
	private static final byte EXCEPT_TRUCK    = 0x40;
	
	/**
	 * 
	 * @param viaNode the node to which this restriction is related
	 * @param traffArcs the arcs that describe the "forbidden" path
	 * @param mkgmapExceptMask the exception mask in the mkgmap format
	 */
	public RouteRestriction(RouteNode viaNode, List<RouteArc> traffArcs, byte mkgmapExceptMask) {
		this.viaNode = viaNode;
		this.arcs = new ArrayList<>(traffArcs);
		for (int i = 0; i < arcs.size(); i++){
			RouteArc arc = arcs.get(i);
			assert arc.getDest() != viaNode;
		}
		byte tmpFlags = 0;
		
		if ((mkgmapExceptMask & FOOT) != 0)
			tmpFlags |= F_EXCEPT_FOOT;
		if ((mkgmapExceptMask & EMERGENCY) != 0)
			tmpFlags |= F_EXCEPT_EMERGENCY;
		
		exceptMask = translateExceptMask(mkgmapExceptMask); 
		if(exceptMask != 0)
			tmpFlags |= F_MORE_EXCEPTIONS;

		int numArcs = arcs.size();
		assert numArcs < 8;
		tmpFlags |= ((numArcs) << 5);
		this.flags = tmpFlags;
	}

	
	/**
	 * Translate the mkgmap internal representation of vehicles to the one used in the img format
	 * @param mkgmapExceptMask
	 * @return
	 */
	private byte translateExceptMask(byte mkgmapExceptMask) {
		byte mask = 0;
		if ((mkgmapExceptMask & CAR) != 0)
			mask |= EXCEPT_CAR;
		if ((mkgmapExceptMask & BUS) != 0)
			mask |= EXCEPT_BUS;
		if ((mkgmapExceptMask & TAXI) != 0)
			mask |= EXCEPT_TAXI;
		if ((mkgmapExceptMask & DELIVERY) != 0)
			mask |= EXCEPT_DELIVERY;
		if ((mkgmapExceptMask & BIKE) != 0)
			mask |= EXCEPT_BICYCLE;
		if ((mkgmapExceptMask & TRUCK) != 0)
			mask |= EXCEPT_TRUCK;
		return mask;
	}


	private int calcOffset(RouteNode node, int tableOffset) {
		int offset = tableOffset - node.getOffsetNod1();
		assert offset >= 0 : "node behind start of tables";
		assert offset < 0x8000 : "node offset too large";
		return offset | F_INTERNAL;
	}

	public List<RouteArc> getArcs(){
		return arcs;
	}
	
	/**
	 * Writes a Table C entry with 3 or more nodes.
	 *
	 * @param writer The writer.
	 * @param tableOffset The offset in NOD 1 of the tables area.
	 * 
	 */
	public void write(ImgFileWriter writer, int tableOffset) {
		writer.put1u(RESTRICTION_TYPE);

		writer.put(flags);
		writer.put1u(0); // meaning ?

		if(exceptMask != 0)
			writer.put1u(exceptMask);

		int numArcs = arcs.size();
		int[] offsets = new int[numArcs+1];
		int pos = 0;
		boolean viaWritten = false;
		for (int i = 0; i < numArcs; i++){
			RouteArc arc = arcs.get(i);
			// the arcs must have a specific order and direction
			// first arc: dest is from node , last arc: dest is to node
			// if there only two arcs, both will have the via node as source node.
			// For more n via nodes, the order is like this: 
			// from <- via(1) <- via(2) <- ... <- this via node -> via( n-1) -> via(n) -> to
			if (arc.isInternal())
				offsets[pos++] = calcOffset(arc.getDest(), tableOffset);
			else 
				offsets[pos++] = arc.getIndexB();
			if (!viaWritten && arc.getSource() == viaNode) {
				// there will be two nodes with source node = viaNode, but we write the source only once
				offsets[pos++] = calcOffset(viaNode, tableOffset);
				viaWritten = true;
			}
		}

		for (int offset : offsets)
			writer.put2u(offset);

		for (RouteArc arc: arcs)
			writer.put1u(arc.getIndexA());
	}

	/**
	 * Write this restriction's offset within Table C into a node record.
	 */
	public void writeOffset(ImgFileWriter writer) {
		assert 0 < offsetSize && offsetSize <= 2 : "illegal offset size";
		int offset = offsetC;
		if (offsetSize == 1) {
			assert offset < 0x80;
			if (last)
				offset |= 0x80;
			writer.put1u(offset);
		} else {
			assert offset < 0x8000;
			if (last)
				offset |= 0x8000;
			writer.put2u(offset);
		}
	}

	/**
	 * Size in bytes of the Table C entry.
	 */
	public int getSize() {
		int size = 3; // header length
		if(exceptMask != 0)
			++size;
		size += arcs.size() + (arcs.size()+1) * 2; 
		return size;
	}

	public void setOffsetC(int offsetC) {
		this.offsetC = offsetC;
	}

	public int getOffsetC() {
		return offsetC;
	}

	public void setOffsetSize(int size) {
		offsetSize = size;
	}

	public void setLast() {
		last = true;
	}
}
