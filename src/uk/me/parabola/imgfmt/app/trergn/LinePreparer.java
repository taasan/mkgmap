/**
 * Copyright (C) 2006 Steve Ratcliffe
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
 * Author: steve
 * Date: 24-Dec-2006
 */
package uk.me.parabola.imgfmt.app.trergn;

import java.util.List;

import uk.me.parabola.imgfmt.app.BitWriter;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;

/**
 * This class holds all of the calculations needed to encode a line into
 * the garmin format.
 */
public class LinePreparer {
	private static final Logger log = Logger.getLogger(LinePreparer.class);

	// These are our inputs.
	private final Polyline polyline;

	/** if true, we must write the extraBits which allow to find out which points are nodes */ 
	private final boolean extraBit;
	private final boolean extTypeLine;
	private boolean xSameSign;
	private boolean xSignNegative;     // Set if all negative

	private boolean ySameSign;
	private boolean ySignNegative;     // Set if all negative

	// The base number of bits
	private int xBase;
	private int yBase;

	// The delta changes between the points.
	private int[] deltas;
	private boolean[] nodes;

	private final boolean ignoreNumberOnlyNodes;

	LinePreparer(Polyline line) {
		extraBit = line.isRoad() && line.getSubdiv().getZoom().getLevel() == 0
				&& (line.hasInternalNodes() || !line.isLastSegment());
		ignoreNumberOnlyNodes = !line.hasHouseNumbers();
		extTypeLine = line.hasExtendedType();

		polyline = line;
		calcLatLong();
		calcDeltas();
	}

	/**
	 * Write the bit stream to a BitWriter and return it.
	 * Try different values for xBase and yBase to find the one
	 * that results in the shortest bit stream.
	 * 
	 * @return A class containing the written byte stream.
	 */
	public BitWriter makeShortestBitStream(int minPointsRequired) {
		BitWriter bsSimple = makeBitStream(minPointsRequired, xBase, yBase);
		if (bsSimple == null)
			return bsSimple;
		BitWriter bsBest = bsSimple; 
		int xBestBase = xBase;
		int yBestBase = yBase;
		if (xBase > 0 ||  yBase > 0 && log.isDebugEnabled()) {
			log.debug("start opt:", xBase, yBase, xSameSign, xSignNegative, ySameSign, ySignNegative);
		}
		if (xBase > 0){
			int notBetter = 0;
			boolean xSameSignBak = xSameSign;
			xSameSign = false;
			for (int xTestBase = xBase-1; xTestBase >= 0; xTestBase--){
				BitWriter bstest = makeBitStream(minPointsRequired, xTestBase, yBase);
				if (bstest.getBitPosition() >= bsBest.getBitPosition() ){
					if (++notBetter >= 2)
						break; // give up
				} else {
					xBestBase = xTestBase;
					bsBest = bstest;
					xSameSignBak = false;
				}
			}
			xSameSign = xSameSignBak;
		}
		if (yBase > 0){
			int notBetter = 0;
			boolean ySameSignBak = ySameSign;
			ySameSign = false;
			for (int yTestBase = yBase-1; yTestBase >= 0; yTestBase--){
				BitWriter bstest = makeBitStream(minPointsRequired, xBestBase, yTestBase);
				if (bstest.getBitPosition() >= bsBest.getBitPosition()){
					if (++notBetter >= 2)
						break; // give up
				} else {
					yBestBase = yTestBase;
					bsBest = bstest;
					ySameSignBak = false;
				}
			}
			ySameSign = ySameSignBak;
		}
		if (xBase != xBestBase || yBestBase != yBase && log.isInfoEnabled()) {
			if (bsSimple.getLength() > bsBest.getLength())
				log.info("optimizer reduced bit stream byte length from",bsSimple.getLength(),"->",bsBest.getLength(),"(" + (bsSimple.getLength()-bsBest.getLength()), " byte(s)) for",polyline.getClass().getSimpleName(),"with",polyline.getPoints().size(),"points");
			else 
				log.info("optimizer only reduced bit stream bit length from",bsSimple.getBitPosition(),"->",bsBest.getBitPosition(),"bits for",polyline.getClass().getSimpleName(),"with",polyline.getPoints().size(),"points, using original bit stream");
		}
		if (bsSimple.getLength() == bsBest.getLength()){
			// if the (byte) length was not improved, 
			// prefer the bit stream that doesn't need the special "trick"
			// to encode large values, it is assumed that this can safe a  
			// few CPU cycles when reading the map
			return bsSimple;
		}
		return bsBest;
	}
	/**
	 * Write the bit stream to a BitWriter and return it.
	 *
	 * @return A class containing the written byte stream.
	 */
	public BitWriter makeBitStream(int minPointsRequired, int xb, int yb) {
		assert xb >= 0 && yb >= 0;
		
		int xbits = base2Bits(xb);
		if (!xSameSign)
			xbits++;
		int ybits = base2Bits(yb);
		if (!ySameSign)
			ybits++;

			
		// Note no sign included.
		if (log.isDebugEnabled())
			log.debug("xbits", xbits, ", y=", ybits);

		// Write the bitstream
		BitWriter bw = new BitWriter();

		// Pre bit stream info
		bw.putn(xb, 4);
		bw.putn(yb, 4);

		bw.put1(xSameSign);
		if (xSameSign)
			bw.put1(xSignNegative);

		bw.put1(ySameSign);
		if (ySameSign)
			bw.put1(ySignNegative);

		if (log.isDebugEnabled()) {
			log.debug("x same is", xSameSign, "sign is", xSignNegative);
			log.debug("y same is", ySameSign, "sign is", ySignNegative);
		}

		if(extTypeLine) {
			bw.put1(false);		// no extra bits required
		}

		// first extra bit always appears to be false
		// refers to the start point?
		if (extraBit)
			bw.put1(false);

		int numPointsEncoded = 1;
		for (int i = 0; i < deltas.length; i+=2) {
			int dx = deltas[i];
			int dy = deltas[i + 1];
			if (dx == 0 && dy == 0) {
				if (extraBit && !nodes[i/2+1] && i+2 != deltas.length) // don't skip CoordNode
					continue;
			}
			++numPointsEncoded;

			if (log.isDebugEnabled())
				log.debug("x delta", dx, "~", xbits);
			if (xSameSign) {
				bw.putn(Math.abs(dx), xbits);
			} else {
				bw.sputn(dx, xbits);
			}

			if (log.isDebugEnabled())
				log.debug("y delta", dy, ybits);
			if (ySameSign) {
				bw.putn(Math.abs(dy), ybits);
			} else {
				bw.sputn(dy, ybits);
			}
			if (extraBit)
				bw.put1(nodes[i/2+1]);
		}

		if (log.isDebugEnabled())
			log.debug(bw);

		if(numPointsEncoded < minPointsRequired)
			return null;

		return bw;
	}

	/**
	 * Calculate the correct lat and long points.  They must be shifted if
	 * required by the zoom level.  The point that is taken to be the
	 * location is just the first point in the line.
	 */
	private void calcLatLong() {
		Coord co = polyline.getPoints().get(0);

		polyline.setLatitude(co.getLatitude());
		polyline.setLongitude(co.getLongitude());
	}

	/**
	 * Calculate the deltas of one point to the other.  While we are doing
	 * this we must save more information about the maximum sizes, if they
	 * are all the same sign etc.  This must be done separately for both
	 * the lat and long values.
	 */
	private void calcDeltas() {
		Subdivision subdiv = polyline.getSubdiv();
		if(log.isDebugEnabled())
			log.debug("label offset", polyline.getLabel().getOffset());
		List<Coord> points = polyline.getPoints();

		// Space to hold the deltas
		int numPointsToUse = points.size();
		if (polyline instanceof Polygon && points.get(0).equals(points.get(points.size()-1))) {
			--numPointsToUse; // no need to write the closing point 
		}
		deltas = new int[2 * (numPointsToUse - 1)];

		if (extraBit)
			nodes = new boolean[numPointsToUse];
		boolean first = true;

		// OK go through the points
		int lastLat = 0;
		int lastLong = 0;
		int minDx = Integer.MAX_VALUE, maxDx = 0;
		int minDy = Integer.MAX_VALUE, maxDy = 0;
		// index of first point in a series of identical coords (after shift)
		int firstsame = 0;
		for (int i = 0; i < numPointsToUse; i++) {
			Coord co = points.get(i);

			int lat = subdiv.roundLatToLocalShifted(co.getLatitude());
			int lon = subdiv.roundLonToLocalShifted(co.getLongitude());
			if (log.isDebugEnabled())
				log.debug("shifted pos", lat, lon);

			int dx = lon - lastLong;
			int dy = lat - lastLat;
			lastLong = lon;
			lastLat = lat;
			if (first) {
				first = false;
				continue;
			}

			/*
			 * Current thought is that the node indicator is set when
			 * the point is a routing node or a house number node. 
			 * There's a separate first extra bit
			 * that always appears to be false. The last points' extra bit
			 * is set if the point is a node and this is not the last
			 * polyline making up the road.
			 */
			if (extraBit) {
				boolean isSpecialNode = co.getId() > 0 || (!ignoreNumberOnlyNodes && co.isNumberNode());
				if (dx != 0 || dy != 0 || isSpecialNode)
					firstsame = i;
				boolean extra = false;
				if (isSpecialNode) {
					if (i < nodes.length - 1)
						// inner node of polyline
						extra = true;
					else
						// end node of polyline: set if inner
						// node of road
						extra = !polyline.isLastSegment();
				}

				/*
				 * Only the first among a range of equal points
				 * is written, so set the bit if any of the points
				 * is a node.
				 * Since we only write extra bits at level 0 now,
				 * this can only happen when points in the input
				 * data round to the same point in map units, so
				 * it may be better to handle this in the
				 * reader.
				 */ 
				nodes[firstsame] = nodes[firstsame] || extra;
			}

			// find largest delta values
			if (dx < minDx)
				minDx = dx;
			if (dx > maxDx)
				maxDx = dx;
			if (dy < minDy)
				minDy = dy;
			if (dy > maxDy)
				maxDy = dy;
			
			// Save the deltas
			deltas[2*(i-1)] = dx;
			deltas[2*(i-1) + 1] = dy;
		}
		// Find the maximum number of bits required to hold the delta values.
		int xBits = Math.max(bitsNeeded(minDx), bitsNeeded(maxDx)); 
		int yBits = Math.max(bitsNeeded(minDy), bitsNeeded(maxDy));

		// Now we need to know the 'base' number of bits used to represent
		// the value.  In decoding you start with that number and add various
		// adjustments to get the final value.  We need to try and work
		// backwards from this.
		//
		// Note that the sign bit is already not included so there is
		// no adjustment needed for it.

		if (log.isDebugEnabled())
			log.debug("initial xBits, yBits", xBits, yBits);

		this.xBase = bits2Base(xBits);
		this.yBase = bits2Base(yBits);

		if (log.isDebugEnabled())
			log.debug("initial xBase, yBase", xBase, yBase);

		// Set flags for same sign etc.
		this.xSameSign = !(minDx < 0 && maxDx > 0);
		this.ySameSign = !(minDy < 0 && maxDy > 0);
		if (this.xSameSign)
			this.xSignNegative = minDx < 0;
		if (this.ySameSign)
			this.ySignNegative = minDy < 0;
	}

	/**
	 * The bits needed to hold a number without truncating it.
	 *
	 * @param val The number for bit counting.
	 * @return The number of bits required.
	 */
	public static int bitsNeeded(int val) {
		int n = Math.abs(val);

		int count = 0;
		while (n != 0) {
			n >>>= 1;
			count++;
		}
		return count;
//		count should be equal to Integer.SIZE - Integer.numberOfLeadingZeros(Math.abs(val));

	}

	public boolean isExtraBit() {
		return extraBit;
	}

	private static int base2Bits(int base){
		int bits = 2;
		if (base < 10)
			return bits + base;
		else
			return bits + (2 * base) - 9;
	}
	
	private static int bits2Base(int bits){
		int base = Math.max(0, bits - 2);
		if (base > 10) {
			if ((base & 0x1) == 0)
				base++;
			base = 9 + (base - 9) / 2;
		}
		return base;
	}
}
