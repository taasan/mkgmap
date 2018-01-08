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

package uk.me.parabola.mkgmap.sea.optional;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.SeaGenerator;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.Java2DConverter;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

/**
 * Converts a shapefile containing land polygons into mkgmap precompiled sea tiles.
 * @author WanMil
 */
public class PrecompSeaGenerator {

	private final ExecutorService service;

	/** the shapefile (.shp) that contains the land polygons */
	private final File shapeFile;
	/** the directory the precomp sea tiles are written to */
	private final File outputDir;

	private SimpleFeatureCollection shapeCollection;
	private SimpleFeatureIterator shapeIterator;

	/** transforms the projection of the shapefile to WGS84 ({@code null} if shape file uses WGS84) */
	private final MathTransform transformation;
	/** {@code true}: sea tiles are created with PBF format; {@code false}: sea tiles are created with .osm.gz format */
	private boolean usePbfFormat;
	/** Number of tiles generated by one full reading of the shapefile. Higher numbers require more memory. */
	private int tilesPerCycle;

	public PrecompSeaGenerator(File shapeFile, String shapeCRS, File outputDir)
			throws NoSuchAuthorityCodeException, FactoryException {
		this.shapeFile = shapeFile;
		this.outputDir = outputDir;
		this.transformation = createTransformation(shapeCRS);
		
		this.service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		this.usePbfFormat = true;
		this.tilesPerCycle = 10 * 512;
	}

	/**
	 * Sets the flag if pbf format or gzipped osm xml format (.osm.gz) should be used
	 * for the precompiled sea tiles.
	 * @param usePbf {@code true} use PBF format; {@code false} use .osm.gz format
	 */
	public void setUsePbfFormat(boolean usePbf) {
		this.usePbfFormat = usePbf;
	}

	/**
	 * Retrieves the transformation that is necessary to transform the 
	 * data from the shape file to WGS84. 
	 * @param shapeCRS the projection of the shape file
	 * @return the transformation ({@code null} if no transformation required)
	 * @throws NoSuchAuthorityCodeException if the given projection of the shape file is not supported
	 * @throws FactoryException if the given projection of the shape file is not supported
	 */
	private MathTransform createTransformation(String shapeCRS)
			throws NoSuchAuthorityCodeException, FactoryException {
		if ("WGS84".equals(shapeCRS)) {
			return null;
		}
		if ("Mercator".equals(shapeCRS)) {
			shapeCRS = "EPSG:3857";
		}
		CoordinateReferenceSystem crsInShapefile = CRS.decode(shapeCRS);
		CoordinateReferenceSystem targetCRS = DefaultGeographicCRS.WGS84;
		boolean lenient = true; // allow for some error due to different datums
		return CRS.findMathTransform(crsInShapefile, targetCRS, lenient);
	}

	/**
	 * Transforms a geometry from the shape file to a geometry with WGS84 projection.
	 * @param geometry a geometry from the shape file
	 * @return a geometry with WGS84 projection
	 * @throws MismatchedDimensionException if the transformation fails
	 * @throws TransformException if the geometry could not be transformed
	 */
	private Geometry transformToWGS84(Geometry geometry)
			throws MismatchedDimensionException, TransformException {
		if (transformation == null) {
			return geometry;
		} else {
			return JTS.transform(geometry, transformation);
		}
	}

	/**
	 * Retrieve the areas of all precompiled tiles that have to be worked out. 
	 * @return the areas of all tiles
	 */
	private List<uk.me.parabola.imgfmt.app.Area> getTiles() {
		return getTiles(uk.me.parabola.imgfmt.app.Area.PLANET);
	}

	private List<uk.me.parabola.imgfmt.app.Area> getTiles(
			uk.me.parabola.imgfmt.app.Area wholeArea) {
		int minLat = wholeArea.getMinLat();
		int maxLat = wholeArea.getMaxLat();
		int minLon = wholeArea.getMinLong();
		int maxLon = wholeArea.getMaxLong();

		List<uk.me.parabola.imgfmt.app.Area> tiles = new ArrayList<uk.me.parabola.imgfmt.app.Area>();
		for (int lon = SeaGenerator.getPrecompTileStart(minLon); lon < maxLon; lon += SeaGenerator.PRECOMP_RASTER) {
			for (int lat = SeaGenerator.getPrecompTileStart(minLat); lat < maxLat; lat += SeaGenerator.PRECOMP_RASTER) {
				uk.me.parabola.imgfmt.app.Area tile = new uk.me.parabola.imgfmt.app.Area(
						Math.max(lat, minLat), Math.max(lon, minLon), Math.min(
								lat + SeaGenerator.PRECOMP_RASTER, maxLat),
						Math.min(lon + SeaGenerator.PRECOMP_RASTER, maxLon));
				tiles.add(tile);
			}
		}
		return tiles;
	}


	/**
	 * Prints regularly how many tiles are not yet finished.
	 * @author WanMil
	 */
	private static class ProgressPrinter extends Thread {
		private final CountDownLatch countdown;

		public ProgressPrinter(CountDownLatch countdown) {
			super("ProgressPrinter");
			this.countdown = countdown;
			setDaemon(true);
		}

		public void run() {
			long count = 0;
			do {
				count = countdown.getCount();
				System.out.println(count + " tiles remaining");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException exp) {
				}
			} while (count > 0);
		}
	}

	/**
	 * Converts the given geometry to an {@link Area} object.
	 * @param geometry a polygon as {@link Geometry} object
	 * @return the polygon converted to an {@link Area} object.
	 */
	private Area convertToArea(Geometry geometry) {
		Coordinate[] c = geometry.getCoordinates();
		List<Coord> points = new ArrayList<>(c.length);
		for (int n = 0; n < c.length; n++) {
			points.add(new Coord(c[n].y, c[n].x));
		}
		return Java2DConverter.createArea(points);
	}

	
	/**
	 * Creates the merger threads for the given tiles.
	 * @param tiles the areas of the precompiled tiles
	 * @param tilesCountdown the countdown that should be decreased after a tile is finished
	 * @param saveQueue the queue the merged results should be added to
	 * @return the preinitialized but not started mergers
	 */
	private List<PrecompSeaMerger> createMergers(
			Collection<uk.me.parabola.imgfmt.app.Area> tiles,
			CountDownLatch tilesCountdown,
			BlockingQueue<Entry<String, List<Way>>> saveQueue) {
		List<PrecompSeaMerger> mergers = new ArrayList<PrecompSeaMerger>();

		for (uk.me.parabola.imgfmt.app.Area bounds : tiles) {

			Rectangle mergeBounds = new Rectangle(bounds.getMinLong(),
					bounds.getMinLat(), bounds.getWidth(), bounds.getHeight());
			String tileKey = bounds.getMinLat() + "_" + bounds.getMinLong();

			PrecompSeaMerger merger = new PrecompSeaMerger(mergeBounds,
					tileKey, tilesCountdown, saveQueue);
			merger.setExecutorService(service);
			mergers.add(merger);
		}
		return mergers;
	}

	private void createShapefileAccess() throws IOException {
		Map<String,URL> map = new HashMap<String, URL>();
		map.put("url", shapeFile.toURI().toURL());
		DataStore dataStore = DataStoreFinder.getDataStore(map);
		String typeName = dataStore.getTypeNames()[0];

		SimpleFeatureSource source = dataStore.getFeatureSource(typeName);
		shapeCollection = source.getFeatures();
	}

	private void openShapefile() {
		shapeIterator = shapeCollection.features();
	}

	private void closeShapefile() {
		shapeIterator.close();
		shapeIterator = null;
	}

	public void runSeaGeneration() throws MismatchedDimensionException,
			TransformException, IOException, InterruptedException {
		createShapefileAccess();

		// get all tiles that need to be processed
		List<uk.me.parabola.imgfmt.app.Area> remainingTiles = getTiles();

		// initialize the count down so that it is possible to get the 
		// information when all tiles are finished
		CountDownLatch tilesCountdown = new CountDownLatch(remainingTiles.size());
		
		// start a printer that outputs how many tiles still need to be
		// processed
		new ProgressPrinter(tilesCountdown).start();

		// start the saver thread that stores the tiles to disc and creates
		// the index file
		PrecompSeaSaver precompSaver = new PrecompSeaSaver(outputDir, usePbfFormat);
		new Thread(precompSaver, "SaveThread").start();

		// perform several cycles which is necessary to reduce memory
		// requirements
		while (remainingTiles.isEmpty() == false) {

			// create a list with all tiles that are processed within this cycle
			List<uk.me.parabola.imgfmt.app.Area> tiles = new ArrayList<uk.me.parabola.imgfmt.app.Area>();
			tiles.addAll(remainingTiles.subList(0,
					Math.min(tilesPerCycle, remainingTiles.size())));
			remainingTiles.subList(0,
					Math.min(tilesPerCycle, remainingTiles.size())).clear();


			// create the mergers that merge the data of one tile
			List<PrecompSeaMerger> mergers = createMergers(tiles, tilesCountdown, precompSaver.getQueue());

			// create an overall area for a simple check if a polygon read from the
			// shape file intersects one of the currently processed sea tiles
			Area tileArea = new Area();
			for (PrecompSeaMerger m : mergers) {
				tileArea.add(new Area(m.getTileBounds()));
				// start the mergers
				service.execute(m);
			}

			openShapefile();

			int numPolygon = 0;
			long lastInfo = System.currentTimeMillis();

			// read all polygons from the shape file and add them to the queues of the
			// merger threads
			Geometry wgs84Poly = null;
			while (shapeIterator.hasNext()) {
				Feature feature = shapeIterator.next();
				GeometryAttribute geom = feature.getDefaultGeometryProperty();
				Geometry poly = (Geometry) geom.getValue();
				if (poly == null){
					continue;
				}

				try {
					wgs84Poly = transformToWGS84(poly);
				} catch (Exception exp) {
					System.err.println(exp);
					continue;
				}

				if (wgs84Poly.getNumGeometries() != 1) {
					// only simple polygons are supported by now
					// maybe this could be changed in future?
					System.err.println("Polygon from shapefile has "
							+ wgs84Poly.getNumGeometries()
							+ " geometries. Only one geometry is supported.");
					System.err.println("Skip polygon.");
					continue;
				}

				Geometry bounds = wgs84Poly.getEnvelope();
				if (bounds.isEmpty()) {
					System.err.println("Empty or non polygon: " + bounds);
				} else {
					Area polyBounds = convertToArea(bounds);

					// easy check if the polygon is used by any tile that is
					// currently processed
					if (polyBounds.intersects(tileArea.getBounds2D())) {
						
						// yes it touches at least one tile => convert it to 
						// a java.awt.geom.Area object
						Area polyAsArea = convertToArea(wgs84Poly.getGeometryN(0));
						
						// go through all current merger threads and add the 
						// polygon to the queues of them
						for (PrecompSeaMerger mThread : mergers) {
							if (mThread.getTileBounds().intersects(polyAsArea.getBounds2D())) {
								try {
									mThread.getQueue().put(polyAsArea);
								} catch (InterruptedException exp) {
									exp.printStackTrace();
								}
							}
						}
					}

					numPolygon++;
					if ((numPolygon) % 50000 == 0
							|| System.currentTimeMillis() - lastInfo > 30000) {
						// print out the current number of polygons already processed
						System.out.println("Worked out " + (numPolygon) + " polygons");
						lastInfo = System.currentTimeMillis();
					}

				}
			}
			closeShapefile();

			System.out.println("Reading shapefile finished");

			// signal all mergers that all polygons have been read
			for (PrecompSeaMerger mThread : mergers) {
				mThread.signalInputComplete();
			}

			// Wait until not more than twice the number of tiles per cycle
			// are waiting for processing. Otherwise OutOfMemory problems
			// may occurr
			while (tilesCountdown.getCount() > remainingTiles.size()
					+ 2*tilesPerCycle) {
				Thread.sleep(50L);
			}
		}
		// wait until all tiles have been merged
		tilesCountdown.await();
		// wait until the saver for the tiles is finished
		precompSaver.waitForFinish();
		// shutdown the executor service
		service.shutdown();
	}

	public static void main(String[] args) throws MismatchedDimensionException,
			TransformException, IOException, FactoryException, CQLException,
			InterruptedException {
		long t1 = System.currentTimeMillis();
		
		File shapeFile = new File(args[0]);
		String shapeCRS = args[1];
		File outputDir = new File(args[2]);

		if (shapeFile.exists() == false) {
			throw new FileNotFoundException("File "+shapeFile+" does not exist.");
		}
		
		// use small fake ids so that the xml files become smaller
		FakeIdGenerator.setStartId(0);
		
		PrecompSeaGenerator seaGenerator = new PrecompSeaGenerator(shapeFile,
				shapeCRS, outputDir);
		seaGenerator.runSeaGeneration();

		System.out.println("Generation took "+(System.currentTimeMillis()-t1)+" ms");
		
	}
}
