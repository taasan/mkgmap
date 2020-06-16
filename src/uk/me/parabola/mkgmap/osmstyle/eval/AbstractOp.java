/*
 * Copyright (C) 2008-2012 Steve Ratcliffe
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
 * Create date: 03-Nov-2008
 */
package uk.me.parabola.mkgmap.osmstyle.eval;

import java.util.HashSet;
import java.util.Set;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.mkgmap.osmstyle.function.GetTagFunction;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.scan.SyntaxException;

/**
 * A base class that can be used as the superclass of an operation.
 *
 * @author Steve Ratcliffe
 */
public abstract class AbstractOp implements Op {
	
	protected Op first;
	private NodeType type;
	protected boolean lastRes;
	protected int lastCachedId = -1;

	public static Op createOp(String value) {
		char c = value.charAt(0);
		Op op;
		switch (c) {
		case '=': op = new EqualsOp(); break;
		case '&':
			if (value.length() > 1)
				throw new SyntaxException(String.format("Use '&' instead of '%s'", value));
			op = new AndOp();
			break;
		case '|':
			if (value.length() > 1)
				throw new SyntaxException(String.format("Use '|' instead of '%s'", value));
			op = new OrOp();
			break;
		case '~': op = new RegexOp(); break;
		case '(': op = new OpenOp(); break;
		case ')': op = new CloseOp(); break;
		case '>':
			if (">=".equals(value))
				op = new GTEOp();
			else
				op = new GTOp();
			break;
		case '<':
			if ("<=".equals(value))
				op = new LTEOp();
			else
				op = new LTOp();
			break;
		case '!':
			if ("!=".equals(value))
				op = new NotEqualOp();
			else
				op = new NotOp();
			break;
		default:
			throw new SyntaxException("Unrecognised operation " + c);
		}
		return op;
	}

	public static Op createOp(NodeType kind) {
		switch (kind) {
		case EQUALS:
			return new EqualsOp();
		case GT:
			return new GTOp();
		case GTE:
			return new GTEOp();
		case LT:
			return new LTOp();
		case LTE:
			return new LTEOp();
		case NOT_EQUALS:
			return new NotEqualOp();
		case EXISTS:
			return new ExistsOp();
		case NOT_EXISTS:
			return new NotExistsOp();
		case AND:
			return new AndOp();
		case OR:
			return new OrOp();
		//case VALUE:
		//	return new ValueOp();
		//case FUNCTION:
		//	break;
		case NOT:
			return new NotOp();
		case REGEX:
			return new RegexOp();
		case NOT_REGEX:
			return new NotRegexOp();
		default:
			throw new UnsupportedOperationException("Please implement if you want it");
		}
	}

	public boolean eval(int cacheId, Element el){
		if (lastCachedId != cacheId){
			if (lastCachedId > cacheId){
				throw new ExitException("fatal error: cache id invalid");
			}
			lastRes = eval(el);
			lastCachedId = cacheId;
		}
		return lastRes;
			
	}

	/**
	 * Does this operation have a higher priority that the other one?
	 * @param other The other operation.
	 */
	public boolean hasHigherPriority(Op other) {
		return priority() > other.priority();
	}

	public Op getFirst() {
		return first;
	}

	@SuppressWarnings("unchecked")
	public <T extends Op> T setFirst(Op first) {
		this.first = first;
		lastCachedId = -1;
		return (T) this;
	}

	/**
	 * Only supported on Binary operations, but useful to return null to make code simpler, rather than
	 * defaulting to UnsupportedOperation.
	 */
	public Op getSecond() {
		return null;
	}

	@SuppressWarnings("unchecked")
	public <T extends Op> T set(Op a, Op b) {
		this.setFirst(a);
		if (b != null)
			this.setSecond(b);
		return (T) this;
	}

	public NodeType getType() {
		return type;
	}

	protected void setType(NodeType type) {
		this.type = type;
	}

	/**
	 * Only supported on value nodes.
	 */
	public String value(Element el) {
		throw new UnsupportedOperationException();
	}

	/**
	 * This is only supported on value nodes.
	 */
	public String getKeyValue() {
		throw new UnsupportedOperationException();
	}

	public boolean isType(NodeType value) {
		return type == value;
	}

	public void resetCache(){
		lastCachedId = -1;
	}

	@Override
	public Set<String> getEvaluatedTagKeys() {
		HashSet<String> set = new HashSet<>();
		collectEvaluatedTags(set);
		return set;
	}

	private void collectEvaluatedTags(HashSet<String> set) {
		if (this instanceof GetTagFunction) {
			set.add(getKeyValue());
		} else if (this instanceof BinaryOp) {
			set.addAll(getFirst().getEvaluatedTagKeys());
			set.addAll(getSecond().getEvaluatedTagKeys());
		} else if (this instanceof NumericOp) {
			set.addAll(getFirst().getEvaluatedTagKeys());
		} else if (this.isType(NodeType.EXISTS) || this.isType(NodeType.NOT_EXISTS) || this.isType(NodeType.NOT)) {
			set.addAll(getFirst().getEvaluatedTagKeys());
		} else if (this.getFirst() != null) {
			System.err.println("Unhandled type of Op");
		}
			
	}

	@Override
	public void augmentWith(uk.me.parabola.mkgmap.reader.osm.ElementSaver elementSaver) {
		if (first != null)
			first.augmentWith(elementSaver);
	}

}
