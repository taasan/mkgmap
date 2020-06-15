/*
 * Copyright (C) 2013.
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

package uk.me.parabola.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;


public class MultiIdentityHashMap<K,V> extends IdentityHashMap<K,List<V>> {

	/**
	* the empty list to be returned when there is key without values.
	*/
	
	/**
	* Returns the list of values associated with the given key.
	*
	* @param key the key to get the values for.
	* @return a list of values for the given keys or the empty list of no such
	*         value exist.
	*/
	public List<V> get(Object key) {
		List<V> result = super.get(key);
		return result == null ? Collections.emptyList() : result;
	}


	public V add(K key, V value ) {
		List<V> values = super.get(key);
	    if (values == null ) {
	        values = new LinkedList<>();
	        super.put( key, values );
	    }
	    
	    boolean results = values.add(value);
	    
	    return ( results ? value : null );
	}

	public V removeMapping(K key, V value) {
		List<V> values = super.get(key);
	    if (values == null )
			return null;
	
	    values.remove(value);
		
		if (values.isEmpty())
			super.remove(key);

		return value;
	}
}

