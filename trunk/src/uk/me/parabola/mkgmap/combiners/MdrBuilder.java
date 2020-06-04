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
package uk.me.parabola.mkgmap.combiners;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.lbl.City;
import uk.me.parabola.imgfmt.app.lbl.Country;
import uk.me.parabola.imgfmt.app.lbl.POIRecord;
import uk.me.parabola.imgfmt.app.lbl.Region;
import uk.me.parabola.imgfmt.app.lbl.Zip;
import uk.me.parabola.imgfmt.app.map.MapReader;
import uk.me.parabola.imgfmt.app.mdr.MDRFile;
import uk.me.parabola.imgfmt.app.mdr.Mdr13Record;
import uk.me.parabola.imgfmt.app.mdr.Mdr14Record;
import uk.me.parabola.imgfmt.app.mdr.Mdr5Record;
import uk.me.parabola.imgfmt.app.mdr.MdrConfig;
import uk.me.parabola.imgfmt.app.net.RoadDef;
import uk.me.parabola.imgfmt.app.srt.SRTFile;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.trergn.Point;
import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.imgfmt.sys.ImgFS;
import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.srt.SrtTextReader;

/**
 * Create the global index file.  This consists of an img file containing
 * an MDR file and optionally an SRT file.
 *
 * @author Steve Ratcliffe
 */
public class MdrBuilder implements Combiner {
	private MDRFile mdrFile;

	// The mdr.img file
	private FileSystem imgfs;

	// We write to a temporary file name, and then rename once all is OK.
	private File tmpName;
	private String outputName;

	/**
	 * Create the mdr file and initialise.
	 * It has a name that is based on the overview-mapname option, as does
	 * the associated MDX file.
	 *
	 * @param args The command line arguments.
	 */
	public void init(CommandArgs args) {
		String name = args.get("overview-mapname", "osmmap");
		String outputDir = args.getOutputDir();

		outputName = Utils.joinPath(outputDir, name + "_mdr.img");

		ImgChannel mdrChan;

		try {
			// Create the .img file system/archive
			FileSystemParam params = new FileSystemParam();

			tmpName = File.createTempFile("mdr", null, new File(outputDir));
			tmpName.deleteOnExit();

			imgfs = ImgFS.createFs(tmpName.getPath(), params);

			// Create the MDR file within the .img
			mdrChan = imgfs.create(name.toUpperCase(Locale.ENGLISH) + ".MDR");
		} catch (IOException e) {
			throw new ExitException("Could not create global index file");
		}

		// Create the sort description
		Sort sort = SrtTextReader.sortForCodepage(args.getCodePage());

		// Set the options that we are using for the mdr.
		MdrConfig config = new MdrConfig();
		config.setHeaderLen(568);
		config.setWritable(true);
		config.setForDevice(false);
		config.setOutputDir(outputDir);
		config.setSort(sort);
		config.setIndexOptions(args);

		// Wrap the MDR channel with the MDRFile object
		mdrFile = new MDRFile(mdrChan, config);

		try {
			ImgChannel srtChan = imgfs.create(name.toUpperCase(Locale.ENGLISH) + ".SRT");
			SRTFile srtFile = new SRTFile(srtChan);
			srtFile.setSort(sort);
			srtFile.write();
			// Do not close srtFile here
		} catch (FileExistsException e) {
			throw new ExitException("Could not create SRT file within index file");
		}
	}

	/**
	 * Create an mdr file, in the format used in a gmapsupp.
	 *
	 * @param chan Reference to an open file within the gmapsupp file.
	 */
	void initForDevice(ImgChannel chan, Sort sort, MdrConfig baseConfig) {
		// Set the options that we are using for the mdr.
		MdrConfig config = new MdrConfig(baseConfig);
		config.setHeaderLen(568);
		config.setWritable(true);
		config.setForDevice(true);
		config.setSort(sort);

		// Wrap the MDR channel with the MDRFile object
		mdrFile = new MDRFile(chan, config);
	}

	/**
	 * Adds a new map to the file.  We need to read in the img file and
	 * extract all the information that can be indexed from it.
	 *
	 * @param info An interface to read the map.
	 */
	public void onMapEnd(FileInfo info) {
		if (!info.isImg())
			return;
		
		// Add the map name
		mdrFile.addMap(info.getHexname(), info.getCodePage());

		String filename = info.getFilename();
		MapReader mr = null;
		try {
			mr = new MapReader(filename);

			AreaMaps maps = new AreaMaps();

			maps.countries = addCountries(mr);
			maps.regions = addRegions(mr, maps);
			List<Mdr5Record> mdrCityList = fetchCities(mr, maps);
			maps.cityList = mdrCityList;

			addPoints(mr, maps);
			addCities(mdrCityList);
			addStreets(mr, mdrCityList);
			addZips(mr);
		} catch (FileNotFoundException e) {
			throw new ExitException("Could not open " + filename + " when creating mdr file");
		} finally {
			Utils.closeFile(mr);
		}
	}

	private Map<Integer, Mdr14Record> addCountries(MapReader mr) {
		Map<Integer, Mdr14Record> countryMap = new HashMap<>();
		List<Country> countries = mr.getCountries();
		for (Country c : countries) {
			if (c != null) {
				Mdr14Record record = mdrFile.addCountry(c);
				countryMap.put((int) c.getIndex(), record);
			}
		}
		return countryMap;
	}

	private Map<Integer, Mdr13Record> addRegions(MapReader mr, AreaMaps maps) {
		Map<Integer, Mdr13Record> regionMap = new HashMap<>();

		List<Region> regions = mr.getRegions();
		for (Region region : regions) {
			if (region != null) {
				Mdr14Record mdr14 = maps.countries.get((int) region.getCountry().getIndex());
				Mdr13Record record = mdrFile.addRegion(region, mdr14);
				regionMap.put((int) region.getIndex(), record);
			}
		}
		return regionMap;
	}

	/**
	 * There is not complete information that we need about a city in the city
	 * section, it has to be completed from the points section. So we fetch
	 * and create the mdr5s first before points.
	 */
	private List<Mdr5Record> fetchCities(MapReader mr, AreaMaps maps) {
		Map<Integer, Mdr5Record> cityMap = maps.cities;

		List<Mdr5Record> cityList = new ArrayList<>();
		List<City> cities = mr.getCities();
		for (City c : cities) {
			int regionCountryNumber = c.getRegionCountryNumber();
			Mdr13Record mdrRegion = null;
			Mdr14Record mdrCountry;
			if ((regionCountryNumber & 0x4000) == 0) {
				mdrRegion = maps.regions.get(regionCountryNumber);
				mdrCountry = mdrRegion.getMdr14();
			} else {
				mdrCountry = maps.countries.get(regionCountryNumber & 0x3fff);
			}
			Mdr5Record mdrCity = new Mdr5Record();
			mdrCity.setCityIndex(c.getIndex());
			mdrCity.setRegionIndex(c.getRegionCountryNumber());
			mdrCity.setMdrRegion(mdrRegion);
			mdrCity.setMdrCountry(mdrCountry);
			mdrCity.setLblOffset(c.getLblOffset());
			mdrCity.setName(c.getName());

			int key = (c.getSubdivNumber() << 8) + (c.getPointIndex() & 0xff);
			assert key < 0xffffff;
			cityMap.put(key, mdrCity);
			cityList.add(mdrCity);
		}

		return cityList;
	}

	/**
	 * Now really add the cities.
	 * @param cityList The previously saved cities.
	 */
	private void addCities(List<Mdr5Record> cityList) {
		for (Mdr5Record c : cityList) {
			mdrFile.addCity(c);
		}
	}
	private void addZips(MapReader mr) {
		List<Zip> zips = mr.getZips();
		for (Zip zip : zips)
			mdrFile.addZip(zip);
	}

	/**
	 * Read points from this map and add them to the index.
	 * @param mr The currently open map.
	 * @param maps Maps of regions, cities countries etc.
	 */
	private void addPoints(MapReader mr, AreaMaps maps) {
		List<Point> list = mr.pointsForLevel(0, MapReader.WITHOUT_EXT_TYPE_DATA);
		for (Point p : list) {
			Label label = p.getLabel();
			if (p.getNumber() > 256) {
				continue;
			}

			Mdr5Record mdrCity = null;
			boolean isCity;
			if (MapPoint.isCityType(p.getType())) {
				// This is itself a city, it gets a reference to its own MDR 5 record.
				// and we also use it to set the name of the city.
				mdrCity = maps.cities.get((p.getSubdiv().getNumber() << 8) + p.getNumber());
				if (mdrCity != null) {
					mdrCity.setLblOffset(label.getOffset());
					mdrCity.setName(label.getText());
				}
				isCity = true;
			} else {
				// This is not a city, but we have information about which city
				// it is in.  If so then add the mdr5 record number of the city.
				POIRecord poi = p.getPOIRecord();
				City c = poi.getCity();
				if (c != null)
					mdrCity = getMdr5FromCity(maps, c);
				isCity = false;
			}

			if (label != null && !label.getText().trim().isEmpty())
				mdrFile.addPoint(p, mdrCity, isCity);
		}
	}

	private void addStreets(MapReader mr, List<Mdr5Record> cityList) {
		List<RoadDef> roads = mr.getRoads();

		for (RoadDef road : roads) {
			List<City> cities = road.getCities();
			if (cities.isEmpty())
				mdrFile.addStreet(road, null);
			else {
				for (City city : cities){
					Mdr5Record mdrCity = cityList.get(city.getIndex() - 1);
					if (mdrCity.getMapIndex() == 0)
						mdrCity = null;

					mdrFile.addStreet(road, mdrCity);
				}
			}
		}
	}

	private Mdr5Record getMdr5FromCity(AreaMaps cityMap, City c) {
		if (c == null)
			return null;

		if (c.getPointIndex() > 0) {
			return cityMap.cities.get((c.getSubdivNumber() << 8) + (c.getPointIndex() & 0xff));
		} else {
			return cityMap.cityList.get(c.getIndex() - 1);
		}
	}

	/**
	 * Called after all maps are processed.
	 *
	 * We are building a standalone mdr file, so that is
	 */
	public void onFinish() {
		// Write out the mdr file
		mdrFile.write();

		// Close the mdr.img file, thus causing it to be written out fully.
		imgfs.close();

		// Rename from the temporary file to the proper name. On windows the target file must
		// not exist for rename to work, so we are forced to remove it first.
		try {
			Files.move(tmpName.toPath(), Paths.get(outputName), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new MapFailedException("Could not create mdr.img file");
		}
	}

	/**
	 * Called after all maps processed when making a gmapsupp.img
	 *
	 * Here by 'for device' we mean in the format required for uploading to a device, so that is
	 * the gmapsupp.img file.
	 */
	void onFinishForDevice() {
		// Write out the mdr file
		mdrFile.write();
	}

	@Override
	public String getFilename() {
		return outputName;
	}

	public int getSize() {
		return (int) tmpName.length();
	}

	/**
	 * Holds lookup maps for cities, regions and countries.  Used to
	 * link streets, pois to cities, regions and countries.
	 *
	 * These are only held for a single map at a time, which is
	 * sufficient to link them all up.
	 */
	private class AreaMaps {
		private final Map<Integer, Mdr5Record> cities = new HashMap<>();
		private Map<Integer, Mdr13Record> regions;
		private Map<Integer, Mdr14Record> countries;
		private List<Mdr5Record> cityList;
	}
}
