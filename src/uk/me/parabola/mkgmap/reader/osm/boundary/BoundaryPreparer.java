/*
 * Copyright (C) 2006, 2011.
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

import java.awt.geom.Area;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryUtil.BoundaryFileFilter;
import uk.me.parabola.util.EnhancedProperties;

public class BoundaryPreparer extends Thread {
	private static final Logger log = Logger.getLogger(BoundaryPreparer.class);

	private static final List<Class<? extends LoadableBoundaryDataSource>> loaders;

	static {
		String[] sources = {
				"uk.me.parabola.mkgmap.reader.osm.boundary.OsmBinBoundaryDataSource",
				// must be last as it is the default
				"uk.me.parabola.mkgmap.reader.osm.boundary.Osm5BoundaryDataSource", };

		loaders = new ArrayList<Class<? extends LoadableBoundaryDataSource>>();

		for (String source : sources) {
			try {
				@SuppressWarnings({ "unchecked" })
				Class<? extends LoadableBoundaryDataSource> c = (Class<? extends LoadableBoundaryDataSource>) Class
						.forName(source);
				loaders.add(c);
			} catch (ClassNotFoundException e) {
				// not available, try the rest
			} catch (NoClassDefFoundError e) {
				// not available, try the rest
			}
		}
	}

	/**
	 * Return a suitable boundary map reader. The name of the resource to be
	 * read is passed in. This is usually a file name, but could be something
	 * else.
	 * 
	 * @param name
	 *            The resource name to be read.
	 * @return A LoadableBoundaryDataSource that is capable of reading the
	 *         resource.
	 */
	private static LoadableBoundaryDataSource createMapReader(String name) {
		for (Class<? extends LoadableBoundaryDataSource> loader : loaders) {
			try {
				LoadableBoundaryDataSource src = loader.newInstance();
				if (name != null && src.isFileSupported(name))
					return src;
			} catch (InstantiationException e) {
				// try the next one.
			} catch (IllegalAccessException e) {
				// try the next one.
			} catch (NoClassDefFoundError e) {
				// try the next one
			}
		}

		// Give up and assume it is in the XML format. If it isn't we will get
		// an error soon enough anyway.
		return new Osm5BoundaryDataSource();
	}

	private final String boundaryFilename;
	private final String boundsDir;

	public BoundaryPreparer(EnhancedProperties properties) {
		this.boundaryFilename = properties
				.getProperty("createboundsfile", null);
		this.boundsDir = properties.getProperty("bounds", "bounds");
	}

	public void run() {
		if (boundaryFilename == null) {
			return;
		}
		try {
			File boundsDirectory = new File(boundsDir);
			BoundarySaver saver = new BoundarySaver(boundsDirectory);
			LoadableBoundaryDataSource dataSource = createMapReader(boundaryFilename);
			dataSource.setBoundarySaver(saver);
			log.info("Started loading", boundaryFilename);
			dataSource.load(boundaryFilename);
			saver.setBbox(dataSource.getBounds());
			log.info("Finished loading", boundaryFilename);
			saver.end();

			workoutBoundaryRelations(boundsDirectory);
		} catch (FileNotFoundException exp) {
			log.error("Boundary file " + boundaryFilename + " not found.");
		}
	}

	public static void main(String[] args) {
		BoundaryPreparer p = new BoundaryPreparer(new EnhancedProperties());
		p.workoutBoundaryRelations(new File(p.boundsDir));
	}

	/**
	 * Reworks all bounds files of the given directory so that all boundaries
	 * are applied with the information in which boundary they are contained.<br/>
	 * This information is added as tag "mkgmap:lies_in" and contains a semicolon 
	 * separated list of boundary ids.<br/>
	 * Example:<br/>
	 * <code>
	 *   mkgmap:lies_in=2:r51477;4:r87782
	 * </code><br/>
	 * The boundary tagged in such a way lies within relation 51477 (admin level 2) 
	 * and within relation 87782 (admin level 4).
	 * 
	 * @param boundsDirectory
	 *            the directory of the bounds files
	 */
	public void workoutBoundaryRelations(File boundsDirectory) {
		File[] boundsFiles = boundsDirectory
				.listFiles(new BoundaryFileFilter());
		BoundarySaver saver = new BoundarySaver(boundsDirectory);
		saver.setCreateEmptyFiles(false);
		for (File boundsFile : boundsFiles) {
			log.info("Workout boundary relations in",boundsFile);
			try {
				List<Boundary> boundaries = BoundaryUtil.loadBoundaryFile(
						boundsFile, null);
				BoundaryCollator bColl = new BoundaryCollator();
				Collections.sort(boundaries, bColl);

				List<Boundary> reworked = new ArrayList<Boundary>(
						boundaries.size());

				while (boundaries.isEmpty() == false) {
					// get the first boundary from the list
					// all other boundaries have a lower or no admin_level
					Boundary bOuter = boundaries.remove(0);
					String outerLevel = bOuter.getTags().get("admin_level");
					String outerId = bOuter.getTags().get("mkgmap:boundaryid");

					// check which other boundaries lie in bNext
					for (Boundary b : boundaries) {
						if (bColl.compareAdminLevels(outerLevel, b.getTags()
								.get("admin_level")) == 0) {
							continue;
						}

						String inStr = b.getTags().get("mkgmap:lies_in");
						if (inStr != null) {
							String[] inLevels = inStr.split(Pattern.quote(";"));
							boolean levelFound = false;
							for (String levelRef : inLevels) {
								String[] levelParts = levelRef.split(Pattern
										.quote(":"));
								if (outerLevel.equals(levelParts[0])) {
									levelFound = true;
									break;
								}
							}
							if (levelFound) {
								continue;
							}
						}

						if (b.getArea()
								.intersects(bOuter.getArea().getBounds()) == false) {
							continue;
						}

						Area bCopy = new Area(b.getArea());
						bCopy.subtract(bOuter.getArea());
						if (bCopy.isEmpty() == false) {
							// this area does not completely lie in bOuter
							continue;
						}

						if (inStr == null || inStr.length() == 0) {
							inStr = outerLevel + ":" + outerId;
						} else {
							inStr += ";" + outerLevel + ":" + outerId;
						}
						b.getTags().put("mkgmap:lies_in", inStr);
						if (log.isDebugEnabled()) {
							log.debug(b.getTags().toString(), "lies in", bOuter
									.getTags().toString());
						}
					}

					reworked.add(bOuter);
				}

				Collections.reverse(reworked);
				saver.saveBoundaries(reworked, boundsFile);
			} catch (IOException exp) {
				exp.printStackTrace();
			}
		}
	
		saver.end();
	}

	public static class BoundaryCollator implements Comparator<Boundary> {

		public int compare(Boundary o1, Boundary o2) {
			if (o1 == o2) {
				return 0;
			}

			String adminLevel1 = o1.getTags().get("admin_level");
			String adminLevel2 = o2.getTags().get("admin_level");

			return compareAdminLevels(adminLevel1, adminLevel2);
		}

		public int compareAdminLevels(String level1, String level2) {
			if (level1 == null) {
				level1 = "100";
			}
			if (level2 == null) {
				level2 = "100";
			}

			int l1 = 100;
			try {
				l1 = Integer.valueOf(level1);
			} catch (NumberFormatException nfe) {
			}

			int l2 = 100;
			try {
				l2 = Integer.valueOf(level2);
			} catch (NumberFormatException nfe) {
			}

			if (l1 == l2) {
				return 0;
			} else if (l1 > l2) {
				return 1;
			} else {
				return -1;
			}
		}
	}

}
/*
 * Copyright (C) 2006, 2011.
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

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uk.me.parabola.imgfmt.FormatException;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.main.Preparer;
import uk.me.parabola.util.EnhancedProperties;

public class BoundaryPreparer extends Preparer {
	private static final Logger log = Logger.getLogger(BoundaryPreparer.class);
	private static final List<Class<? extends LoadableBoundaryDataSource>> loaders;
	static {
		String[] sources = {
				"uk.me.parabola.mkgmap.reader.osm.boundary.OsmBinBoundaryDataSource",
				// must be last as it is the default
				"uk.me.parabola.mkgmap.reader.osm.boundary.Osm5BoundaryDataSource", };

		loaders = new ArrayList<Class<? extends LoadableBoundaryDataSource>>();

		for (String source : sources) {
			try {
				@SuppressWarnings({ "unchecked" })
				Class<? extends LoadableBoundaryDataSource> c = (Class<? extends LoadableBoundaryDataSource>) Class
						.forName(source);
				loaders.add(c);
			} catch (ClassNotFoundException e) {
				// not available, try the rest
			} catch (NoClassDefFoundError e) {
				// not available, try the rest
			}
		}
	}

	/**
	 * Return a suitable boundary map reader. The name of the resource to be
	 * read is passed in. This is usually a file name, but could be something
	 * else.
	 * 
	 * @param name
	 *            The resource name to be read.
	 * @return A LoadableBoundaryDataSource that is capable of reading the
	 *         resource.
	 */
	private static LoadableBoundaryDataSource createMapReader(String name) {
		for (Class<? extends LoadableBoundaryDataSource> loader : loaders) {
			try {
				LoadableBoundaryDataSource src = loader.newInstance();
				if (name != null && src.isFileSupported(name))
					return src;
			} catch (InstantiationException e) {
				// try the next one.
			} catch (IllegalAccessException e) {
				// try the next one.
			} catch (NoClassDefFoundError e) {
				// try the next one
			}
		}

		// Give up and assume it is in the XML format. If it isn't we will get
		// an error soon enough anyway.
		return new Osm5BoundaryDataSource();
	}

	
	private boolean workoutOnly = false;
	private String boundaryFilename;
	private String inDir;
	private String outDir;

	public BoundaryPreparer() {
		
	}

	/**
	 * constructor for stand-alone usage (workout only)
	 * @param in source directory or zip file 
	 * @param out target directory
	 */
	private BoundaryPreparer(String in, String out){
		this.inDir = in;
		this.outDir = out;
		this.workoutOnly = true;
	}
	
	public boolean init(EnhancedProperties props,
			ExecutorService additionalThreadPool) {
		super.init(props, additionalThreadPool);
		
		if (workoutOnly)
			return true;
		this.boundaryFilename = props.getProperty("createboundsfile", null);
		this.inDir = props.getProperty("bounds", "bounds");
		this.outDir = props.getProperty("bounds", "bounds");
		return boundaryFilename != null;
	}

	public void run() {
		if (workoutOnly == false && boundaryFilename != null) {
			long t1 = System.currentTimeMillis();
			boolean prepOK = createRawData();
			long t2 = System.currentTimeMillis();
			log.info("BoundaryPreparer pass 1 took", (t2-t1), "ms");

			if (!prepOK){
				return;
			}
		}
		workoutBoundaryRelations(inDir, outDir);
	}

	/**
	 * Parse OSM data and create boundaries. Distribute the boundaries on a grid
	 * with a fixed raster. 
	 * @return true if data was successfully written, else false
	 */
	private boolean createRawData(){
		File boundsDirectory = new File(outDir);
		BoundarySaver saver = new BoundarySaver(boundsDirectory, BoundarySaver.RAW_DATA_FORMAT);
		LoadableBoundaryDataSource dataSource = createMapReader(boundaryFilename);
		dataSource.setBoundarySaver(saver);
		log.info("Started loading", boundaryFilename);
		try {
			dataSource.load(boundaryFilename);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return false;
		} catch (FormatException e) {
			e.printStackTrace();
			return false;
		}
		saver.setBbox(dataSource.getBounds());
		log.info("Finished loading", boundaryFilename);
		saver.end();
		return true;
	}
	
	
	public static void main(String[] args) {
		if (args[0].equals("--help")) {
			System.err.println("Usage:");
			System.err
					.println("java -cp mkgmap.jar uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryPreparer [<boundsdir1>] [<boundsdir2>]");
			System.err.println(" <boundsdir1>: optional directory name or zip file with *.bnd files, default is bounds");
			System.err
			.println(" <boundsdir2>: optional output directory, if not specified, files in input are overwritten.");
			System.err
			.println("               If input is a zip file, output directory must be specified");

			System.exit(-1);
		} 
		String in = "bounds";
		String out = "bounds";
		if (args.length >= 1)
			in = args[0];
		if (args.length >= 2)
			out = args[1];
		else
			out = in;
		long t1 = System.currentTimeMillis();
		
		int maxJobs = Runtime.getRuntime().availableProcessors();
		ExecutorService threadPool = Executors.newFixedThreadPool(maxJobs);

		EnhancedProperties props = new EnhancedProperties();
		
		BoundaryPreparer p = new BoundaryPreparer(in, out);
		p.init(props, threadPool);
		try {
			p.runPreparer();
		} catch (InterruptedException exp) {
			exp.printStackTrace();
		} catch (ExecutionException exp) {
			System.err.println(exp);
			exp.printStackTrace();
		}
		
		threadPool.shutdown();
		
		log.info("Bnd files converted in", (System.currentTimeMillis()-t1), "ms");
	}

	/**
	 * Reworks all bounds files of the given directory so that all boundaries
	 * are applied with the information with which boundary they intersect.<br/>
	 * The files are rewritten in the QUADTREE_DATA_FORMAT which is used in the 
	 * LocationHook.
	 * 
	 * @param inputDirName the directory or zip file name that identifies the input
	 * @param outputDirName a directory name for the rewritten bnd files
	 */
	public void workoutBoundaryRelations(String inputDirName, String outputDirName) {
		List<String> boundsFileNames = BoundaryUtil.getBoundaryDirContent(inputDirName);
				
		for (String boundsFileName : boundsFileNames) {
			// start workers that rework the boundary files and add the 
			// quadtree information
			addWorker(new QuadTreeWorker(inputDirName, outputDirName, boundsFileName));
		}
	}

	class QuadTreeWorker implements Callable<String> {
		private final String inputDirName;
		private final String outputDirName;
		private final String boundsFileName;
		
		public QuadTreeWorker(String inputDirName, String outputDirName, String boundsFileName) {
			this.inputDirName = inputDirName;
			this.outputDirName = outputDirName;
			this.boundsFileName = boundsFileName;
		}
		
		@Override
		public String call() throws Exception {
			log.info("Workout boundary relations in", inputDirName, boundsFileName);
			long t1 = System.currentTimeMillis();
			BoundaryQuadTree bqt = BoundaryUtil.loadQuadTree(inputDirName, boundsFileName);
			long dt = System.currentTimeMillis() - t1;
			log.info("splitting", boundsFileName, "took", dt, "ms");
			if (bqt != null){
				File outDir = new File(outputDirName);
				BoundarySaver saver = new BoundarySaver(outDir, BoundarySaver.QUADTREE_DATA_FORMAT);
				saver.setCreateEmptyFiles(false);

				saver.saveQuadTree(bqt, boundsFileName); 		
				saver.end();
			}
			return boundsFileName;
		}

	}

}

