/*
 * Copyright (C) 2007 Steve Ratcliffe
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
 * Create date: Nov 15, 2007
 */
package uk.me.parabola.mkgmap.combiners;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileNotWritableException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.srt.SRTFile;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.imgfmt.mps.MapBlock;
import uk.me.parabola.imgfmt.mps.MpsFile;
import uk.me.parabola.imgfmt.mps.MpsFileReader;
import uk.me.parabola.imgfmt.mps.ProductBlock;
import uk.me.parabola.imgfmt.sys.FileImgChannel;
import uk.me.parabola.imgfmt.sys.ImgFS;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.CommandArgs;

/**
 * Create the gmapsupp file.  There is nothing much special about this file
 * (as far as I know - there's not a public official spec or anything) it is
 * just a regular .img file which is why it works to rename a single .img file
 * and send it to the device.
 * <p/>
 * Effectively we just 'unzip' the constituent .img files and then 'zip' them
 * back into the gmapsupp.img file.
 * <p/>
 * In addition we need to create and add the TDB file, if we don't already
 * have one.
 *
 * @author Steve Ratcliffe
 */
public class GmapsuppBuilder implements Combiner {
	private static final Logger log = Logger.getLogger(GmapsuppBuilder.class);

	private static final String GMAPSUPP = "gmapsupp.img";

	/**
	 * The number of block numbers that will fit into one entry block
	 */
	private static final int ENTRY_SIZE = 240;
	private static final int DIRECTORY_OFFSET_BLOCK = 2;

	private final Map<String, FileInfo> files = new LinkedHashMap<String, FileInfo>();

	// all these need to be set in the init routine from arguments.
	private String areaName;
	private String mapsetName;

	private String overallDescription = "Combined map";
	private String outputDir;
	private MpsFile mpsFile;
	private Sort sort;

	public void init(CommandArgs args) {
		areaName = args.get("area-name", null);
		mapsetName = args.get("mapset-name", "OSM map set");
		overallDescription = args.getDescription();
		outputDir = args.getOutputDir();
		sort = args.getSort();
	}

	/**
	 * This is called when the map is complete.
	 * We collect information about the map to be used in the TDB file and
	 * for preparing the gmapsupp file.
	 *
	 * @param info Information about the img file.
	 */
	public void onMapEnd(FileInfo info) {
		String mapname = info.getFilename();

		files.put(mapname, info);
	}

	/**
	 * The complete map set has been processed.
	 * Creates the gmapsupp file.  This is done by stepping through each img
	 * file, reading all the sub files and copying them into the gmapsupp file.
	 */
	public void onFinish() {
		FileSystem imgFs = null;

		try {
			imgFs = createGmapsupp();

			addAllFiles(imgFs);

			writeSrtFile(imgFs);
			writeMpsFile();

		} catch (FileNotWritableException e) {
			log.warn("Could not create gmapsupp file");
			System.err.println("Could not create gmapsupp file");
		} finally {
			if (imgFs != null)
				imgFs.close();
		}
	}

	/**
	 * Write the SRT file.
	 * @param imgFs The filesystem to create the SRT file in.
	 * @throws FileNotWritableException If it cannot be created.
	 */
	private void writeSrtFile(FileSystem imgFs) throws FileNotWritableException {
		if (sort.getId1() == 0 && sort.getId2() == 0)
			return;
		
		SRTFile srtFile;
		ImgChannel channel;
		try {
			channel = imgFs.create("MAKEGMAP.SRT");
			srtFile = new SRTFile(channel);
		} catch (FileExistsException e) {
			// well it shouldn't exist!
			log.error("could not create SRT file as it exists already");
			throw new FileNotWritableException("already existed", e);
		}

		srtFile.setSort(sort);
		srtFile.write();
		srtFile.close();

		Utils.closeFile(channel);
	}

	/**
	 * Write the MPS file.  The gmapsupp file will work without this, but it
	 * important if you want to include more than one map family and be able
	 * to turn them on and off separately.
	 */
	private void writeMpsFile() throws FileNotWritableException {
		try {
			mpsFile.sync();
			mpsFile.close();
		} catch (IOException e) {
			throw new FileNotWritableException("Could not finish write to MPS file", e);
		}
	}

	private MapBlock makeMapBlock(FileInfo info) {
		MapBlock mb = new MapBlock();
		mb.setMapNumber(info.getMapnameAsInt());
		mb.setHexNumber(info.getHexname());
		mb.setMapDescription(info.getDescription());
		mb.setAreaName(areaName != null ? areaName : "Area " + info.getMapname());

		mb.setSeriesName(info.getSeriesName());
		mb.setIds(info.getFamilyId(), info.getProductId());
		return mb;
	}

	private ProductBlock makeProductBlock(FileInfo info) {
		ProductBlock pb = new ProductBlock();
		pb.setFamilyId(info.getFamilyId());
		pb.setProductId(info.getProductId());
		pb.setDescription(info.getFamilyName());
		return pb;
	}

	private void addAllFiles(FileSystem outfs) {
		for (FileInfo info : files.values()) {
			String filename = info.getFilename();
			switch (info.getKind()) {
			case IMG_KIND:
				addImg(outfs, filename);
				addMpsEntry(info);
				break;
			case GMAPSUPP_KIND:
				addImg(outfs, filename);
				addMpsFile(info);
				break;
			case APP_KIND:
			case TYP_KIND:
				addFile(outfs, filename);
				break;
			case MDR_KIND:
				break;
			}
		}
	}

	/**
	 * Add a complete pre-existing mps file to the mps file we are currently
	 * building for this gmapsupp.
	 * @param info The details of the gmapsupp file that we need to extract the
	 */
	private void addMpsFile(FileInfo info) {
		String name = info.getFilename();
		try {
			FileSystem fs = ImgFS.openFs(name);
			MpsFileReader mr = new MpsFileReader(fs.open(info.getMpsName(), "r"));
			for (MapBlock block : mr.getMaps())
				mpsFile.addMap(block);

			for (ProductBlock b : mr.getProducts())
				mpsFile.addProduct(b);
			mr.close();
		} catch (IOException e) {
			log.error("Could not read MPS file from gmapsupp", e);
		}
	}

	/**
	 * Add a single entry to the mps file.
	 * @param info The img file information.
	 */
	private void addMpsEntry(FileInfo info) {
		mpsFile.addMap(makeMapBlock(info));

		// Add a new product block if we have found a new product
		mpsFile.addProduct(makeProductBlock(info));
	}

	private MpsFile createMpsFile(FileSystem outfs) throws FileNotWritableException {
		try {
			ImgChannel channel = outfs.create("MAKEGMAP.MPS");
			return new MpsFile(channel);
		} catch (FileExistsException e) {
			// well it shouldn't exist!
			log.error("could not create MPS file as it already exists");
			throw new FileNotWritableException("already existed", e);
		}
	}

	/**
	 * Add a single file to the output.
	 *
	 * @param outfs The output gmapsupp file.
	 * @param filename The input filename.
	 */
	private void addFile(FileSystem outfs, String filename) {
		ImgChannel chan = new FileImgChannel(filename, "r");
		try {
			String imgname = createImgFilename(filename);
			copyFile(chan, outfs, imgname);
		} catch (IOException e) {
			log.error("Could not open file " + filename);
		}
	}

	/**
	 * Create a suitable filename for use in the .img file from the external
	 * file name.
	 *
	 * The external file name might look something like /home/steve/foo.typ
	 * or c:\maps\foo.typ and we need to take the filename part and make
	 * sure that it is no more than 8+3 characters.
	 *
	 * @param pathname The external filesystem path name.
	 * @return The filename part, will be restricted to 8+3 characters and all
	 * in upper case.
	 */
	private String createImgFilename(String pathname) {
		File f = new File(pathname);
		String name = f.getName().toUpperCase(Locale.ENGLISH);
		int dot = name.lastIndexOf('.');

		String base = name.substring(0, dot);
		String ext = name.substring(dot+1);
		if (base.length() > 8)
			base = base.substring(0, 8);
		if (ext.length() > 3)
			ext = ext.substring(0, 3);

		return base + '.' + ext;
	}

	/**
	 * Add a complete .img file, that is all the constituent files from it.
	 *
	 * @param outfs The gmapsupp file to write to.
	 * @param filename The input filename.
	 */
	private void addImg(FileSystem outfs, String filename) {
		try {
			FileSystem infs = ImgFS.openFs(filename);

			try {
				copyAllFiles(infs, outfs);
			} finally {
				infs.close();
			}
		} catch (FileNotFoundException e) {
			log.error("Could not open file " + filename);
		}
	}

	/**
	 * Copy all files from the input filesystem to the output filesystem.
	 *
	 * @param infs The input filesystem.
	 * @param outfs The output filesystem.
	 */
	private void copyAllFiles(FileSystem infs, FileSystem outfs) {
		List<DirectoryEntry> entries = infs.list();
		for (DirectoryEntry ent : entries) {
			String ext = ent.getExt();
			if (ext.equals("   ") || ext.equals("MPS"))
				continue;

			String inname = ent.getFullName();

			try {
				copyFile(inname, infs, outfs);
			} catch (IOException e) {
				log.warn("Could not copy " + inname, e);
			}
		}
	}

	/**
	 * Create the output file.
	 *
	 * @return The gmapsupp file.
	 * @throws FileNotWritableException If it cannot be created for any reason.
	 */
	private FileSystem createGmapsupp() throws FileNotWritableException {
		BlockInfo bi = calcBlockSize();
		int blockSize = bi.blockSize;
		// Create this file, containing all the sub files
		FileSystemParam params = new FileSystemParam();
		params.setBlockSize(blockSize);
		params.setMapDescription(overallDescription);
		params.setDirectoryStartBlock(DIRECTORY_OFFSET_BLOCK);

		int reserved = DIRECTORY_OFFSET_BLOCK + bi.reserveBlocks + bi.headerSlots;
		log.info("bs of", blockSize, "reserving", reserved);

		int reserve = (int) Math.ceil(reserved * 512.0 / blockSize);
		params.setReservedDirectoryBlocks(reserve);
		log.info("reserved", reserve);

		FileSystem outfs = ImgFS.createFs(Utils.joinPath(outputDir, GMAPSUPP), params);
		mpsFile = createMpsFile(outfs);
		mpsFile.setMapsetName(mapsetName);

		return outfs;
	}

	/**
	 * Copy an individual file with the given name from the first archive/filesystem
	 * to the second.
	 *
	 * @param inname The name of the file.
	 * @param infs The filesystem to copy from.
	 * @param outfs The filesystem to copy to.
	 * @throws IOException If the copy fails.
	 */
	private void copyFile(String inname, FileSystem infs, FileSystem outfs) throws IOException {
		ImgChannel fin = infs.open(inname, "r");
		copyFile(fin, outfs, inname);
	}

	/**
	 * Copy a given open file to the a new file in outfs with the name inname.
	 * @param fin The file to copy from.
	 * @param outfs The file system to copy to.
	 * @param inname The name of the file to create on the destination file system.
	 * @throws IOException If a file cannot be read or written.
	 */
	private void copyFile(ImgChannel fin, FileSystem outfs, String inname) throws IOException {
		ImgChannel fout = outfs.create(inname);

		copyFile(fin, fout);
	}

	/**
	 * Copy an individual file with the given name from the first archive/filesystem
	 * to the second.
	 *
	 * @param fin The file to copy from.
	 * @param fout The file to copy to.
	 * @throws IOException If the copy fails.
	 */
	private void copyFile(ImgChannel fin, ImgChannel fout) throws IOException {
		try {
			ByteBuffer buf = ByteBuffer.allocate(1024);
			while (fin.read(buf) > 0) {
				buf.flip();
				fout.write(buf);
				buf.compact();
			}
		} finally {
			fin.close();
			fout.close();
		}
	}

	/**
	 * Calculate the block size that we need to use.  The block size must be such that
	 * the total number of blocks is less than 0xffff.
	 *
	 * I am making sure that the that the root directory entry doesn't require
	 * more than one block to hold its own block list.
	 *
	 * @return A suitable block size to use for the gmapsupp.img file.
	 */
	private BlockInfo calcBlockSize() {
		int[] ints = {1 << 9, 1 << 10, 1 << 11, 1 << 12, 1 << 13,
				1 << 14, 1 << 15, 1 << 16, 1 << 17, 1 << 18, 1 << 19,
				1 << 20, 1 << 21, 1 << 22, 1 << 23, 1 << 24,
		};

		for (int bs : ints) {
			int totBlocks = 0;
			int totHeaderSlots = 0;
			for (FileInfo info : files.values()) {
				totBlocks += info.getNumBlocks(bs);
				// Each file will take up at least one directory block.
				// Each directory block can hold 480 block-references
				int slots = info.getNumHeaderSlots(bs);
				log.info("adding", slots, "slots for", info.getFilename());
				totHeaderSlots += slots;
			}

			totHeaderSlots += 2;
			int totHeaderBlocks = totHeaderSlots * 512 / bs;

			log.info("total blocks for", bs, "is", totHeaderBlocks, "based on slots=", totHeaderSlots);

			if (totBlocks < 0xfffe && totHeaderBlocks <= ENTRY_SIZE) {
				// Add one for the MPS file
				totHeaderSlots += 1;
				return new BlockInfo(bs, totHeaderSlots, totHeaderSlots / bs + 1);
			}
		}

		throw new IllegalArgumentException("hmm");
	}

	/**
	 * Just a data value object for various bits of block size info.
	 */
	private static class BlockInfo {
		private final int blockSize;
		private final int headerSlots;
		private final int reserveBlocks;

		private BlockInfo(int blockSize, int headerSlots, int reserveBlocks) {
			this.blockSize = blockSize;
			this.headerSlots = headerSlots;
			this.reserveBlocks = reserveBlocks;
		}
	}
}
