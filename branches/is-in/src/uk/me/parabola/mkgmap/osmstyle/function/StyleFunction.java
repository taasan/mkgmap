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

package uk.me.parabola.mkgmap.osmstyle.function;

import static uk.me.parabola.mkgmap.osmstyle.eval.NodeType.FUNCTION;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import uk.me.parabola.mkgmap.osmstyle.eval.ValueOp;
import uk.me.parabola.mkgmap.reader.osm.FeatureKind;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.mkgmap.scan.SyntaxException;

/**
 * The interface for all functions that can be used within a style file.<br>
 * The input parameter of a function is one element. The resulting value is a
 * string which can carry number values.
 * @author WanMil
 */
public abstract class StyleFunction extends ValueOp {

	protected int reqdNumParams = 0;
	protected List<String> params;
	protected FeatureKind kind;

	public StyleFunction(String value) {
		super(value);
		setType(FUNCTION);
	}

	public void setParams(List<String> params, FeatureKind kind) {
		if (params.size() != reqdNumParams)
			throw new SyntaxException(String.format("Function %s takes %d parameters, %d given", getName(), reqdNumParams, params.size()));
		this.params = new ArrayList<>(params);
		this.kind = kind;
	}

	/**
	 * Retrieves if the function accepts {@link Node} objects as input parameter.
	 *
	 * @return {@code true} {@link Node} objects are supported; {@code false} .. are not supported
	 */
	public boolean supportsNode() {
		return false;
	}

	/**
	 * Retrieves if the function accepts {@link Way} objects as input parameter.
	 *
	 * @return {@code true} {@link Way} objects are supported; {@code false} .. are not supported
	 */
	public boolean supportsWay() {
		return false;
	}

	/**
	 * Retrieves if the function accepts {@link Relation} objects as input parameter.
	 *
	 * @return {@code true} {@link Relation} objects are supported; {@code false} .. are not supported
	 */
	public boolean supportsRelation() {
		return false;
	}

	/**
	 * Retrieves the function name. This is the part without function brackets (). It is case sensitive but should be lower
	 * case.
	 *
	 * @return the function name (e.g. length for length())
	 */
	public String getName() {
		return getKeyValue();
	}

	public String toString() {
		return getName() + "()";
	}

	/**
	 * @return the tag keys evaluated in this function.
	 */
	public Set<String> getUsedTags() {
		return Collections.emptySet();
	}
}
