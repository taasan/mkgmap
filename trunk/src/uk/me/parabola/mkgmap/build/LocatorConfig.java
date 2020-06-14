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
package uk.me.parabola.mkgmap.build;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import uk.me.parabola.imgfmt.app.trergn.TREHeader;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.reader.osm.Tags;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

public class LocatorConfig {
	private static final Logger log = Logger.getLogger(LocatorConfig.class);

	/** maps country name (in all variants) to the 3 letter ISO code */
	private final Map<String,String>  isoMap = new HashMap<>();
	/** maps the ISO code to the offset of the region in the is_in tag */
	private final Map<String,Integer>  regOffsetMap = new HashMap<>();
	/** maps the ISO code to the POI display flag */
	private final Map<String,Integer>  poiDispFlagMap = new HashMap<>();
	/** maps the ISO code to the drive-on-left flag */
	private final Map<String,Boolean>  driveOnLeftFlagMap = new HashMap<>();
	/** contains the names of all continents */
	private final Set<String> continents = new HashSet<>();

	/** maps ISO => default country name */
	private final Map<String, String> defaultCountryNames = new HashMap<>();
	
	/** Maps 3 letter ISO code to all tags of a country */
	private final Map<String, Tags> countryTagMap = new HashMap<>();
	
	private static final LocatorConfig instance = new LocatorConfig();
	
	public static LocatorConfig get() {
		return instance;
	}

	private LocatorConfig()
	{
		loadConfig("/LocatorConfig.xml");
	}

 	private void loadConfig(String fileName)
 	{
		try 
		{
			DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

			InputStream inStream;

			try
			{
				inStream = new FileInputStream("resources/" + fileName);
			}
			catch (Exception ex)
			{
				inStream = null;
			}

			if(inStream == null)	// If not loaded from disk use from jar file
				inStream = this.getClass().getResourceAsStream(fileName);

			Document document = builder.parse(inStream);
  		
			Node rootNode = document.getDocumentElement();
			
			if("locator".equals(rootNode.getNodeName()))
			{
				  Node cNode = rootNode.getFirstChild();

					while(cNode != null)
					{
						if("continent".equals(cNode.getNodeName()))
						{
							NamedNodeMap attr = cNode.getAttributes();
	
							if(attr != null)
							{
								Node nameTag = attr.getNamedItem("name");
								if(nameTag != null)
									addContinent(nameTag.getNodeValue());
							}

						}

						if ("country".equals(cNode.getNodeName())) {
							NamedNodeMap attr = cNode.getAttributes();
							String iso = null;
							if (attr != null) {
								Node nameTag = null;
								Node abrTag = attr.getNamedItem("abr");
								if (abrTag != null) {
									iso = abrTag.getNodeValue().toUpperCase().trim().intern();
									if (iso.length() != 3) {
										log.error("ISO code (abr) must have three characters: "
											+ iso);
									}
								}	

								nameTag = attr.getNamedItem("name");

								if (iso != null && nameTag != null) {
									addISO(nameTag.getNodeValue(), iso);
									defaultCountryNames.put(iso, nameTag
										.getNodeValue().trim());
								}
								
								if (iso != null)
									addISO(iso, iso);
								
								if(iso == null && nameTag != null)					
									addISO(nameTag.getNodeValue(),"");

								
								Node regionOffsetTag = attr.getNamedItem("regionOffset");

								if(regionOffsetTag != null && iso != null)
								{
									addRegionOffset(iso,Integer.parseInt(regionOffsetTag.getNodeValue()));
								}

								int poiDispTag = 0x0;
								Node streetBeforeHousenumber = attr.getNamedItem("streetBeforeHousenumber");
								if (streetBeforeHousenumber != null && "true".equals(streetBeforeHousenumber.getNodeValue())) {
									poiDispTag |= TREHeader.POI_FLAG_STREET_BEFORE_HOUSENUMBER;
								}

								Node postalcodeBeforeCity = attr.getNamedItem("postalcodeBeforeCity");
								if (postalcodeBeforeCity != null && "true".equals(postalcodeBeforeCity.getNodeValue())) {
									poiDispTag |= TREHeader.POI_FLAG_POSTALCODE_BEFORE_CITY;
								}
								
								if (poiDispTag != 0x0 && iso != null) {
									setPoiDispTag(iso, poiDispTag);
								}
								Node driveOnLeft = attr.getNamedItem("driveOnLeft");
								driveOnLeftFlagMap.put(iso, driveOnLeft != null && "true".equals(driveOnLeft.getNodeValue()));
							}

							if (iso != null) {
								Node cEntryNode = cNode.getFirstChild();
								while(cEntryNode != null)
								{
									if("variant".equals(cEntryNode.getNodeName()))
									{
										Node nodeText = cEntryNode.getFirstChild();
									
										if (nodeText != null)
											addISO(nodeText.getNodeValue(), iso);
									}
									cEntryNode = cEntryNode.getNextSibling();
								}
							}
						}

						cNode = cNode.getNextSibling();
					}
			}
			else
			{
				System.out.println(fileName + "contains invalid root tag " + rootNode.getNodeName());
			}
   	}
		catch (Exception ex)
		{
			ex.printStackTrace();
			//System.out.println("Something is wrong here");
		}
  	}

	private void addISO(String country, String iso)
	{
		String cStr = country.toUpperCase().trim();

		isoMap.put(cStr,iso);
	}

	private void addRegionOffset(String iso, Integer offset)
	{
		regOffsetMap.put(iso,offset);
	}

	private void setPoiDispTag(String iso, int flag)
	{
		// only two flags are allowed to be configured
		poiDispFlagMap.put(iso, flag & (TREHeader.POI_FLAG_STREET_BEFORE_HOUSENUMBER | TREHeader.POI_FLAG_POSTALCODE_BEFORE_CITY));
	}

	private void addContinent(String continent)
	{
		String cStr = continent.toUpperCase().trim();
		
		continents.add(cStr);
	}


	public synchronized void setDefaultCountry(String country, String abbr)
	{
		addISO(country, abbr);
	}

	public synchronized boolean isCountry(String country)
	{
		return isoMap.containsKey(country.toUpperCase().trim());
	}
	
	public synchronized boolean countryHasTags(String isoCode) {
		return countryTagMap.containsKey(isoCode);
	}
	
	public synchronized String addCountryWithTags(String isoCode, Tags countryTags) {
		
		if (isoCode == null) {
			// cannot find three letter iso code for this countries
			// do not use it
			log.warn("Cannot find country with tags", countryTags);
			return null;
		}
		
		if (countryHasTags(isoCode)) {
			// country is already known
			return isoCode;
		}
		
		// add it as new country to the tag map
		countryTagMap.put(isoCode, countryTags.copy());
		
		String name = countryTags.get("name");
		if (name != null) {
			addISO(name, isoCode);
		}
		String int_name = countryTags.get("int_name");
		if (int_name != null) {
			addISO(int_name, isoCode);
		}
		// add all variants to the abbreviation map
		for (String countryName : countryTags.getTagsWithPrefix("name:", false).values()) {
			addISO(countryName, isoCode);
		}
		return isoCode;
	}

	/**
	 * Retrieves the three letter ISO code which is used by the Garmins.
	 * @param country a country name 
	 * @return the three letter ISO code (<code>null</code> = unknown)
	 */
	public synchronized String getCountryISOCode(String country)
	{
		if (country == null) {
			return null;
		}
		String res = isoMap.get(country);
		if (res == null){
			res = isoMap.get(country.toUpperCase().trim());
			if (res != null) {
				isoMap.put(country, res);
			}
		}
		return res;
	}
	
	/**
	 * Retrieves the name of a country by its three letter iso code and the list of 
	 * name tags. The first available value of the tags in the nameTags list is returned.
	 * 
	 * @param isoCode the three letter ISO code
	 * @param nameFinder the list of name tags 
	 * @return the full country name (<code>null</code> if unknown)
	 */
	public synchronized String getCountryName(String isoCode, NameFinder nameFinder) {
		Tags countryTags = countryTagMap.get(isoCode);
		if (countryTags==null) {
			// no tags for this country available
			// return the default country name from the LocatorConfig.xml
			return defaultCountryNames.get(isoCode);
		}
		
		return nameFinder.getName(countryTags);
	}

	public synchronized int getRegionOffset(String iso)
	{
		if (iso == null) {
			return 1;
		}
		
		Integer regOffset = regOffsetMap.get(iso);

		if(regOffset != null)
			return regOffset;
		else
			return 1; // Default is 1 the next string after before country
	}

	public synchronized int getPoiDispFlag(String iso)
	{
		if (iso == null) {
			return 0;
		}
		
		Integer flag = poiDispFlagMap.get(iso);

		if(flag != null)
			return flag;
		else
			return 0; // Default is 0 
	}

	public synchronized boolean isContinent(String continent)
	{
		String s = continent.toUpperCase().trim();
		return continents.contains(s);
	}		

	public synchronized boolean getDriveOnLeftFlag(String iso)
	{
		if (iso == null)
			return false;
		Boolean driveOnLeft = driveOnLeftFlagMap.get(iso);
		if (driveOnLeft == null){
			log.warn("Did not find iso code",iso,"in LocatorConfig.xml, assuming drive-on-right for it");
			driveOnLeftFlagMap.put(iso,false);
			return false;
		}
		else 
			return driveOnLeft;
	}
}

