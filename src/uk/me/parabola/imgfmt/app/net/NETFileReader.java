/*
 * Copyright (C) 2009.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.BufferedImgFileReader;
import uk.me.parabola.imgfmt.app.ImgFile;
import uk.me.parabola.imgfmt.app.ImgFileReader;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.lbl.City;
import uk.me.parabola.imgfmt.app.lbl.LBLFileReader;
import uk.me.parabola.imgfmt.app.lbl.Zip;
import uk.me.parabola.imgfmt.fs.ImgChannel;

/**
 * Read the NET file.
 */
public class NETFileReader extends ImgFile {
	private final NETHeader netHeader = new NETHeader();

	// To begin with we only need LBL offsets.
	private final Map<Integer, Integer> offsetLabelMap = new HashMap<>();
	private List<Integer> offsets;

	private List<City> cities;
	private int citySize;

	private List<Zip> zips;
	private int zipSize;
	private LBLFileReader labels;

	public NETFileReader(ImgChannel chan) {
		setHeader(netHeader);

		setReader(new BufferedImgFileReader(chan));
		netHeader.readHeader(getReader());

		readLabelOffsets();
	}

	/**
	 * Get the label offset, given the NET offset.
	 * @param netOffset An offset into NET 1, as found in the road entries in
	 * RGN for example.
	 * @return The offset into LBL as found in NET 1.
	 */
	public int getLabelOffset(int netOffset) {
		Integer off = offsetLabelMap.get(netOffset);
		if (off == null)
			return 0;
		else
			return off;
	}

	/**
	 * Get the list of roads from the net section.
	 *
	 * Saving the bare minimum that is needed, please improve.
	 * @return A list of RoadDefs. Note that currently not everything is
	 * populated in the road def so it can't be written out as is.
	 */
	public List<RoadDef> getRoads() {
		ImgFileReader reader = getReader();
		long start = netHeader.getRoadDefinitionsStart();

		List<RoadDef> roads = new ArrayList<>();
		int record = 0;
		for (int off : offsets) {
			reader.position(start + off);

			RoadDef road = new RoadDef(++record, off, null);
			readLabels(reader, road);
			byte netFlags = reader.get();
			/*int len =*/ reader.get3u();

			int[] counts = new int[24];
			int level = 0;
			while (level < 24) {
				int n = reader.get();
				counts[level++] = n & 0x7f;
				if ((n & 0x80) != 0)
					break;
			}

			for (int i = 0; i < level; i++) {
				int c = counts[i];
				for (int j = 0; j < c; j++) {
					/*byte b =*/ reader.get();
					/*char sub =*/ reader.get2u();
				}
			}

			if ((netFlags & RoadDef.NET_FLAG_ADDRINFO) != 0) {
				int flags2 = reader.get2u();

				int zipFlag = (flags2 >> 10) & 0x3;
				int cityFlag = (flags2 >> 12) & 0x3;
				int numberFlag = (flags2 >> 14) & 0x3;
				IntArrayList indexes = new IntArrayList();
				fetchZipCityIndexes(reader, zipFlag, zipSize, indexes);
				for (int index : indexes){
					road.addZipIfNotPresent(zips.get(index));
				}
				fetchZipCityIndexes(reader, cityFlag, citySize, indexes);
				for (int index : indexes){
					road.addCityIfNotPresent(cities.get(index));
				}

				fetchNumber(reader, numberFlag);
			}

			if ((netFlags & RoadDef.NET_FLAG_NODINFO) != 0) {
				int nodFlags = reader.get1u();
				int nbytes = nodFlags & 0x3;
				if (nbytes > 0) {
					/*int nod = */reader.getNu(nbytes+1);
				}
			}

			roads.add(road);
		}
		return roads;
	}

	/**
	 * Parse a list of zip/city indexes.
	 * @param reader
	 * @param flag
	 * @param size
	 * @param indexes
	 */
	private void fetchZipCityIndexes(ImgFileReader reader, int flag, int size, IntArrayList indexes) {
		indexes.clear();
		if (flag == 2) {
			// fetch city/zip index
			int ind = reader.getNu(size);
			if (ind != 0)
				indexes.add(ind-1);
		} else if (flag == 3) {
			// there is no item
		} else if (flag == 0) {
			int n = reader.get1u();
			parseList(reader, n, size, indexes);
		} else if (flag == 1) {
			int n = reader.get2u();
			parseList(reader, n, size, indexes);
		} else {
			assert false : "flag is " + flag;
		}
	}
	
	private void parseList(ImgFileReader reader, int n, int size,
			IntArrayList indexes) {
		long endPos = reader.position() + n;
		int node = 0; // not yet used
		while (reader.position() < endPos) {
			int initFlag = reader.get1u();
			int skip = initFlag & 0x1f;
			initFlag >>= 5;
			if (initFlag == 7) {
				// Need to read another byte
				initFlag = reader.get1u();
				skip |= (initFlag & 0x1f) << 5;
				initFlag >>= 5;
			}
			node += skip + 1;
			int right = 0;
			int left = 0;
			if (initFlag == 0) {
				right = left = getCityOrZip(reader, size, endPos);
			} else if ((initFlag & 0x4) != 0) {
				if ((initFlag & 1) == 0)
					right = 0;
				if ((initFlag & 2) == 0)
					left = 0;
			} else {
				if ((initFlag & 1) != 0)
					left = getCityOrZip(reader, size, endPos);
				if ((initFlag & 2) != 0)
					right = getCityOrZip(reader, size, endPos);
			}
			if (left > 0)
				indexes.add(left - 1);
			if (right > 0 && left != right)
				indexes.add(right - 1);
		}
	}

	private int getCityOrZip(ImgFileReader reader, int size, long endPos) {
		if (reader.position() > endPos - size) {
			assert false : "ERRROR overflow";
			return 0;
		}
		return reader.getNu(size);
	}

	/**
	 * Fetch a block of numbers.
	 * @param reader The reader.
	 * @param numberFlag The flag that says how the block is formatted.
	 */
	private void fetchNumber(ImgFileReader reader, int numberFlag) {
		int n = 0;
		if (numberFlag == 0) {
			n = reader.get1u();
		} else if (numberFlag == 1) {
			n = reader.get2u();
		} else if (numberFlag == 3) {
			// There is no block
			return;
		} else {
			// Possible but don't know what to do in this context
			assert false;
		}
		if (n > 0)
			reader.get(n);
	}

	private void readLabels(ImgFileReader reader, RoadDef road) {
		for (int i = 0; i < 4; i++) {
			int lab = reader.get3u();
			Label label = labels.fetchLabel(lab & 0x7fffff);
			road.addLabel(label);
			if ((lab & 0x800000) != 0)
				break;
		}
	}

	/**
	 * The first field in NET 1 is a label offset in LBL.  Currently we
	 * are only interested in that to convert between a NET 1 offset and
	 * a LBL offset.
	 */
	private  void readLabelOffsets() {
		ImgFileReader reader = getReader();
		offsets = readOffsets();
		long start = netHeader.getRoadDefinitionsStart();
		for (int off : offsets) {
			reader.position(start + off);
			int labelOffset = reader.get3u();
			offsetLabelMap.put(off, labelOffset & 0x7fffff);
		}
	}

	/**
	 * NET 3 contains a list of all the NET 1 record start positions.  They
	 * are in alphabetical order of name.  So read them in and sort into
	 * memory address order.
	 * @return A list of start offsets in NET 1, sorted by increasing offset.
	 */
	private List<Integer> readOffsets() {
		int start = netHeader.getSortedRoadsStart();
		int end = netHeader.getSortedRoadsEnd();
		ImgFileReader reader = getReader();
		reader.position(start);

		Set<Integer> allOffsets = new HashSet<>();
		while (reader.position() < end) {
			int net1 = reader.get3u();

			// The offset is stored in the bottom 22 bits. The top 2 bits are an index into the list
			// of lbl pointers in the net1 entry. 
			allOffsets.add((net1 & 0x3fffff) << netHeader.getRoadShift());
		}

		// Sort in address order in the hope of speeding up reading.
		List<Integer> sortedOffsets = new ArrayList<>(allOffsets);
		sortedOffsets.sort(null);
		return sortedOffsets;
	}

	public void setCities(List<City> cities) {
		this.cities = cities;
		this.citySize = Utils.numberToPointerSize(cities.size());
	}

	public void setZips(List<Zip> zips) {
		this.zips = zips;
		this.zipSize = Utils.numberToPointerSize(zips.size());
	}

	public void setLabels(LBLFileReader labels) {
		this.labels = labels;
	}
}
