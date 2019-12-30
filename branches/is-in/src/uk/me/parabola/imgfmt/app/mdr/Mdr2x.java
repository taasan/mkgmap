/*
 * Copyright (C) 2011.
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
package uk.me.parabola.imgfmt.app.mdr;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.app.ImgFileWriter;

/**
 * Common code for 20, 21, 22 which are all lists of streets ordered in
 * different ways.
 * 
 * @author Steve Ratcliffe
 */
public abstract class Mdr2x extends MdrMapSection implements HasHeaderFlags {
	protected List<Mdr7Record> streets = new ArrayList<>();
	protected static final int HAS_LABEL = 0x02;
	protected static final int HAS_NAME_OFFSET = 0x04;
	
	/**
	 * Write out the contents of this section.
	 *
	 * @param writer Where to write it.
	 */
	public void writeSectData(ImgFileWriter writer) {
		Mdr7Record prev = null;
		Collator collator = getConfig().getSort().getCollator();
		collator.setStrength(Collator.SECONDARY);

		int size = getSizes().getStreetSizeFlagged();
		int magic = getExtraValue();
		boolean writeLabel = (magic & HAS_LABEL) != 0;  // A guess
		boolean writeNameOffset = (magic & HAS_NAME_OFFSET) != 0;  // A guess, but less so
		int partialInfoSize = ((magic >> 3) & 0x7);
		
		int recordNumber = 0;
		for (Mdr7Record street : streets) {
			assert street.getMapIndex() == street.getCity().getMapIndex() : street.getMapIndex() + "/" + street.getCity().getMapIndex();
			addIndexPointer(street.getMapIndex(), ++recordNumber);

			int repeat = 1;
			if (writeLabel) {
				int rr = street.checkRepeat(prev, collator);
				putMapIndex(writer, street.getMapIndex());
				int offset = street.getLabelOffset();
				if (rr == 3 && sameGroup(street, prev))
					repeat = 0;
				if (repeat != 0)
					offset |= 0x800000;

				writer.put3u(offset);
				if (writeNameOffset)
					writer.put1u(street.getOutNameOffset());

				if (partialInfoSize > 0) {
					int trailingFlags = ((rr & 1) == 0) ? 1 : 0;
					writer.putNu(partialInfoSize, trailingFlags);
				}
			} else {
				int rr = street.checkFullRepeat(prev, collator);
				if (rr == 2 && sameGroup(street, prev))
					repeat = 0;

				int index = street.getIndex();
				writer.putNu(size, (index << 1) | repeat);
			}

			prev = street;
		}
	}

	/**
	 * The size of a record in the section.
	 * 
	 * For these sections there is one field that is an index into the
	 * streets with an extra bit for a flag.
	 * 
	 * In the device configuration, then there is a label and a flag, just like
	 * for mdr7.
	 */
	public int getItemSize() {
		int size;
		int magic = getExtraValue();
		int partialInfoSize = ((magic >> 3) & 0x7);
		
		if (isForDevice()) {
			// map-index, label, name-offset, 1byte flag
			size = getSizes().getMapSize() + 3 + 1 + partialInfoSize;

		} else {
			size = getSizes().getStreetSizeFlagged();
		}

		return size;
	}

	/**
	 * The number of records in this section.
	 *
	 * @return The number of items in the section.
	 */
	protected int numberOfItems() {
		return streets.size();
	}

	@Override
	protected void releaseMemory() {
		streets = null;
	}

	/**
	 * These sections are divided into groups based on city, region or country. This routine is
	 * implemented to return true if the two streets are in the same group.
	 *
	 * It is not clear if this is needed for region or country.
	 * @param street1 The first street.
	 * @param street2 The street to compare against.
	 * @return True if the streets are in the same group (city, region etc).
	 */
	protected abstract boolean sameGroup(Mdr7Record street1, Mdr7Record street2);
}
