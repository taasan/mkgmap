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
 * Create date: 22-Sep-2007
 */
package uk.me.parabola.mkgmap.reader.osm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.Version;
import uk.me.parabola.mkgmap.general.LevelInfo;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.osmstyle.StyleImpl;
import uk.me.parabola.mkgmap.osmstyle.StyledConverter;
import uk.me.parabola.mkgmap.reader.MapperBasedMapDataSource;
import uk.me.parabola.mkgmap.reader.osm.bin.OsmBinHandler;
import uk.me.parabola.mkgmap.reader.osm.o5m.O5mBinHandler;
import uk.me.parabola.mkgmap.reader.osm.xml.OsmXmlHandler;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Base class for OSM map sources.  It exists so that more than
 * one version of the api can be supported at a time.
 *
 * @author Steve Ratcliffe
 */
public class OsmMapDataSource extends MapperBasedMapDataSource implements LoadableMapDataSource {
	private static final Logger log = Logger.getLogger(OsmMapDataSource.class);

	private Style style;
	
	// attention, the order of the hooks is important!
	private final OsmReadingHooks[] POSSIBLE_HOOKS = {
			new SeaGenerator(),
			new MultiPolygonFinishHook(),
			new RelationStyleHook(), 
			new LinkDestinationHook(),
			new UnusedElementsRemoverHook(),
			new RoutingHook(),
			new HighwayHooks(),
			new LocationHook(),
			new POIGeneratorHook(),
			new LanduseHook(),
			new HousenumberHooks(),
	};
	protected OsmConverter converter;
	private final Set<String> usedTags = new HashSet<>();
	protected ElementSaver elementSaver;
	protected OsmReadingHooks osmReadingHooks;
	private static final LocalDateTime now = LocalDateTime.now();
	
	protected static final List<OsmHandler> handlers;

	public static final String ADD_MKGMAP_RESIDENTIAL = "style-uses-mkgmap:residential";
	static {
		handlers = new ArrayList<>();
		handlers.add(new OsmBinHandler());
		handlers.add(new O5mBinHandler());
		handlers.add(new OsmXmlHandler()); // must be last
	}


	/**
	 * Get the maps levels to be used for the current map.  This can be
	 * specified in a number of ways in order:
	 * <ol>
	 * <li>On the command line with the --levels flag.
	 * The format is a comma (or space) separated list of level/resolution
	 * pairs.  Eg --levels=0:24,1:22,2:20
	 * If the flag is given without an argument then the command line override
	 * is turned off for maps following that option.
	 *
	 * <li>In the style options file.  This works just like the command line
	 * option, but it applies whenever the given style is used and not overridden
	 * on the command line.
	 *
	 * <li>A default setting.
	 * </ol>
	 *
	 * <p>I'd advise that new styles specify their own set of levels.
	 *
	 * @return An array of level information, basically a [level,resolution]
	 * pair.
	 */
	public LevelInfo[] mapLevels() {
		String levelSpec = getLevelSpec("levels");
		if (levelSpec == null)
			levelSpec = LevelInfo.DEFAULT_LEVELS;

		return LevelInfo.createFromString(levelSpec);
	}

	@Override
	public LevelInfo[] overviewMapLevels() {
		String levelSpec = getLevelSpec("overview-levels");
		
		if (levelSpec == null)
			return null;
		LevelInfo[] levels = LevelInfo.createFromString(levelSpec); 
		for (int i = 0; i < levels.length; i++)
			levels[i] = new LevelInfo(levels.length-i-1,levels[i].getBits());
		return levels;
	}
		
	private String getLevelSpec (String optionName){
		// First try command line, then style, then our default.
		String levelSpec = getConfig().getProperty(optionName);
		log.debug(optionName, levelSpec, ", ", ((levelSpec!=null)?levelSpec.length():""));
		if ((levelSpec == null || levelSpec.length() < 2) && style != null) {
			levelSpec = style.getOption(optionName);
			log.debug("getting " + optionName + " from style:", levelSpec);
		}
		return levelSpec;
	}
	
	@Override
	public void load(String name, boolean addBackground) throws FileNotFoundException {
		InputStream is = Utils.openFile(name);
		parse(is, name);
		elementSaver.finishLoading();

		osmReadingHooks.end();
		osmReadingHooks = null;
		
		// now convert the saved elements
		elementSaver.convert(getConverter());
		
		if (addBackground)
			addBackground();
	}

	protected void parse(InputStream is, String name) {
		for (OsmHandler h : handlers) {
			if (h.isFileSupported(name)) {
				try {
					OsmHandler handler = h.getClass().newInstance();
					setupHandler(handler);
					handler.parse(is);
					break;
				} catch (InstantiationException | IllegalAccessException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
	}

	/**
	 * There are no copyright messages in the OSM files themselves.  So we
	 * include a fixed set of strings on the assumption that .osm files
	 * are probably going to have the OSM copyright statements.
	 *
	 * @return A list of copyright messages as a String array.
	 */
	public String[] copyrightMessages() {
		String copyrightFileName = getConfig().getProperty("copyright-file", null);
		if (copyrightFileName != null) {
			List<String> copyrightArray = new ArrayList<>();
			try {
				File file = new File(copyrightFileName);
				copyrightArray = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
			}
			catch (Exception e) {
				throw new ExitException("Error reading copyright file " + copyrightFileName, e);
			}
			if (!copyrightArray.isEmpty() && copyrightArray.get(0).startsWith("\ufeff"))
				copyrightArray.set(0, copyrightArray.get(0).substring(1));
			UnaryOperator<String> replaceVariables = s -> s.replace("$MKGMAP_VERSION$", Version.VERSION)
					.replace("$JAVA_VERSION$", System.getProperty("java.version"))
					.replace("$YEAR$", Integer.toString(now.getYear()))
					.replace("$LONGDATE$", now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)))
					.replace("$SHORTDATE$", now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)))
					.replace("$TIME$", now.toLocalTime().toString().substring(0, 5));
			copyrightArray.replaceAll(replaceVariables);
			String[] copyright = new String[copyrightArray.size()];
			copyrightArray.toArray(copyright);
			return copyright;
		}
		String note = getConfig().getProperty("copyright-message", 
				"OpenStreetMap.org contributors. See: http://wiki.openstreetmap.org/index.php/Attribution");
		return new String[] { note };
	}

	protected void setStyle(Style style) {
		this.style = style;
	}

	/**
	 * Common code to setup the file handler.
	 * @param handler The file handler.
	 */
	protected void setupHandler(OsmHandler handler) {
		createElementSaver();
		createConverter();
		
		handler.setIgnoreBounds(getConfig().getProperty("ignore-osm-bounds", false));
		
		osmReadingHooks = pluginChain(elementSaver, getConfig());

		handler.setElementSaver(elementSaver);
		handler.setHooks(osmReadingHooks);

		handler.setUsedTags(getUsedTags());

		String deleteTagsFileName = getConfig().getProperty("delete-tags-file");
		if(deleteTagsFileName != null) {
			Map<String, Set<String>> deltags = readDeleteTagsFile(deleteTagsFileName);
			handler.setTagsToDelete(deltags);
		}
		if (getConfig().getProperty("ignore-fixme-values", false)) {
			handler.setDeleteFixmeValues(true);
		}
	}
	
	protected void createElementSaver() {
		elementSaver = new ElementSaver(getConfig());
	}
	
	public ElementSaver getElementSaver() {
		return elementSaver;
	}

	protected OsmReadingHooks[] getPossibleHooks() {
		return this.POSSIBLE_HOOKS;
	}
	
	protected OsmReadingHooks pluginChain(ElementSaver saver, EnhancedProperties props) {
		List<OsmReadingHooks> plugins = new ArrayList<>();
		for (OsmReadingHooks p : getPossibleHooks()) {
			if (p.init(saver, props)){
				plugins.add(p);
				if (p instanceof RelationStyleHook)
					((RelationStyleHook) p).setStyle(style);
			}
		}

		OsmReadingHooks hooks;
		switch (plugins.size()) {
		case 0:
			hooks = new NullHook();
			break;
		case 1:
			hooks = plugins.get(0);
			break;
		default:
			OsmReadingHooksChain chain = new OsmReadingHooksChain();
			for (OsmReadingHooks p : plugins) {
				chain.add(p);
			}
			hooks = chain;
		}
		usedTags.addAll(hooks.getUsedTags());
		return hooks;
	}

	/** do nothing hook */
	private class NullHook implements OsmReadingHooks {}

	private static Map<String, Set<String>> readDeleteTagsFile(String fileName) {
		Map<String, Set<String>> deletedTags = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new FileReader(fileName))) { 
			String line;
			while((line = br.readLine()) != null) {
				line = line.trim();
				if(line.length() > 0 && !line.startsWith("#") && !line.startsWith(";")) {
					String[] parts = line.split("=");
					if (parts.length == 2) {
						parts[0] = parts[0].trim();
						parts[1] = parts[1].trim();
						if ("*".equals(parts[1])) {
							deletedTags.put(parts[0], new HashSet<String>());
						} else {
							Set<String> vals = deletedTags.get(parts[0]);
							if (vals == null)
								vals = new HashSet<>();
							vals.add(parts[1]);
							deletedTags.put(parts[0], vals);
						}
					} else {
						log.error("Ignoring bad line in deleted tags file: " + line);
					}
				}
			}
		}
		catch(FileNotFoundException e) {
			log.error("Could not open delete tags file " + fileName);
		}
		catch(IOException e) {
			log.error("Error reading delete tags file " + fileName);
		}

		if(deletedTags.isEmpty())
			deletedTags = null;

		return deletedTags;
	}

	/**
	 * Create the appropriate converter from osm to garmin styles.
	 *
	 */
	protected void createConverter() {
		EnhancedProperties props = getConfig();
		setStyle(StyleImpl.readStyle(props));

		if (style.getUsedTags().contains("mkgmap:residential")) {
			System.err.println("Found mkgmap:residential in style, please replace by mkgmap:lu:residential");
			props.put(ADD_MKGMAP_RESIDENTIAL, "yes");
		}
		usedTags.addAll(style.getUsedTags());
		usedTags.addAll(NameFinder.getNameTags(props));
		converter = new StyledConverter(style, mapper, props);
	}

	public OsmConverter getConverter() {
		return converter;
	}

	public Set<String> getUsedTags() {
		return usedTags;
	}
	
	@Override
	public Boolean getDriveOnLeft(){
		return converter.getDriveOnLeft();
	}

	@Override
	public boolean isFileSupported(String name) {
		return true; // we always try xml reader if nothing else matched
	}

	@Override
	public int getPoiDispFlag() {
		return 0; // no info in OSM data  
	}
}
