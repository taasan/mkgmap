/*
 * Copyright (C) 2015 Gerd Petermann
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
package uk.me.parabola.mkgmap.general;

import java.util.Objects;

import uk.me.parabola.imgfmt.app.lbl.City;

public class CityInfo implements Comparable<CityInfo> {
	private static final String UNKNOWN = "?";
	private final String city,region,country;
	private City imgCity;

	public CityInfo (String city, String region, String country){
		this.city = (city != null) ? city: UNKNOWN;
		this.region = (region != null) ?  region : UNKNOWN;
		this.country = (country != null) ? country : UNKNOWN;
	}
	

	public String getCity() {
		if (city == UNKNOWN)
			return null;
		return city;
	}

	public String getRegion() {
		if (region == UNKNOWN)
			return null;
		return region;
	}

	public String getCountry() {
		if (country == UNKNOWN)
			return null;
		return country;
	}
	
	
	public City getImgCity() {
		return imgCity;
	}


	public void setImgCity(City imgCity) {
		this.imgCity = imgCity;
	}

	public boolean isEmpty(){
		return city == UNKNOWN && region == UNKNOWN && country == UNKNOWN;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((city == null) ? 0 : city.hashCode());
		result = prime * result + ((country == null) ? 0 : country.hashCode());
		result = prime * result + ((region == null) ? 0 : region.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof CityInfo))
			return false;
		CityInfo other = (CityInfo) obj;
		if (!Objects.equals(city, other.city))
			return false;
		if (!Objects.equals(country, other.country))
			return false;
		return Objects.equals(region, other.region);
	}

	@Override
	public int compareTo(CityInfo o) {
		if (this == o)
			return 0;
		int d = city.compareTo(o.city);
		if (d != 0)
			return d;
		d = region.compareTo(o.region);
		if (d != 0)
			return d;
		return country.compareTo(o.country);
	}

	@Override
	public String toString() {
		return city + "/" + region + " in " + country; 
	}
}
