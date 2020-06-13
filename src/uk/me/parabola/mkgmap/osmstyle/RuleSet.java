/*
 * Copyright (C) 2008 Steve Ratcliffe
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
 * Create date: 08-Nov-2008
 */
package uk.me.parabola.mkgmap.osmstyle;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.eval.AbstractBinaryOp;
import uk.me.parabola.mkgmap.osmstyle.eval.AbstractOp;
import uk.me.parabola.mkgmap.osmstyle.eval.LinkedBinaryOp;
import uk.me.parabola.mkgmap.osmstyle.eval.LinkedOp;
import uk.me.parabola.mkgmap.osmstyle.eval.Op;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Rule;
import uk.me.parabola.mkgmap.reader.osm.TagDict;
import uk.me.parabola.mkgmap.reader.osm.TypeResult;
import uk.me.parabola.mkgmap.reader.osm.WatchableTypeResult;

/**
 * A list of rules and the logic to select the correct one.
 *
 * A separate {@link RuleIndex} class is used to speed access to the rule list.
 *
 * @author Steve Ratcliffe
 */
public class RuleSet implements Rule, Iterable<Rule> {
	private static final Logger log = Logger.getLogger(RuleSet.class);
	private Rule[] rules;
	private Rule finalizeRule;

	// identifies cached values 
	int cacheId;
	boolean compiled = false;

	private static final short TKM_EXECUTE_FINALIZE_RULES = TagDict.getInstance().xlate("mkgmap:execute_finalize_rules");

	private RuleIndex index = new RuleIndex();
	private final Set<String> usedTags = new HashSet<>();
	
	@Override
	public void resolveType(Element el, TypeResult result) {
		cacheId = resolveType(cacheId, el, result);
	}
	
	
	/**
	 * Resolve the type for this element by running the rules in order.
	 *
	 * This is a very performance critical part of the style system as parts
	 * of the code are run for every tag in the input file.
	 *
	 * @param el The element as read from an OSM xml file in 'tag' format.
	 * @param result A GType describing the Garmin type of the first rule that
	 * matches is returned here.  If continue types are used then more than
	 * one type may be saved here.  If there are no matches then nothing will
	 * be saved.
	 */
	public int resolveType(int cacheId, Element el, TypeResult result) {
		WatchableTypeResult a = new WatchableTypeResult(result);
		if (!compiled || cacheId == Integer.MAX_VALUE)
			compile();
		// new element, invalidate all caches
		cacheId++;
		
		// Get all the rules that could match from the index.  
		BitSet candidates = new BitSet();
		for (Entry<Short, String> tagEntry : el.getFastTagEntryIterator()) {
			BitSet bsRules = index.getRulesForTag(tagEntry.getKey(), tagEntry.getValue());
			if (bsRules != null && !bsRules.isEmpty() )
				candidates.or(bsRules);
		}
		Rule lastRule = null;
		for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
			a.reset();
			lastRule = rules[i];
			cacheId = lastRule.resolveType(cacheId, el, a);
			if (a.isResolved())
				return cacheId;
		}
		if (lastRule != null && lastRule.getFinalizeRule() != null
				&& "true".equals(el.getTag(TKM_EXECUTE_FINALIZE_RULES))) {
			cacheId = lastRule.getFinalizeRule().resolveType(cacheId, el, a);
		}
		return cacheId;
	}

	public Iterator<Rule> iterator() {
		if (rules == null)
			prepare();
		return Arrays.asList(rules).iterator();
	}

	/**
	 * Add a rule to this rule set.
	 * @param keystring The string form of the first term of the rule.  It will
	 * be A=B or A=*.  (In the future we may allow other forms).
	 * @param rule The actual rule.
	 * @param changeableTags The tags that may be changed by the rule.  This
	 * will be either a plain tag name A, or with a value A=B.
	 */
	public void add(String keystring, Rule rule, Set<String> changeableTags) {
		compiled = false;
		index.addRuleToIndex(new RuleDetails(keystring, rule, changeableTags));
	}

	/**
	 * Add all rules from the given rule set to this one.
	 * @param rs The other rule set.
	 */
	public void addAll(RuleSet rs) {
		for (RuleDetails rd : rs.index.getRuleDetails())
			add(rd.getKeystring(), rd.getRule(), rd.getChangingTags());
	}

	/**
	 * Format the rule set.  Warning: this doesn't produce a valid input
	 * rule file.
	 */
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Rule rule : rules) {
			sb.append(rule.toString());
		}
		return sb.toString();
	}

	/**
	 * Merge the two rulesets together so that they appear to be one.
	 * @param rs The other rule set, it will have lower priority, that is the
	 * rules will be tried after the rules of this ruleset.
	 */
	public void merge(RuleSet rs) {
		// We have to basically rebuild the index and reset the rule list.
		RuleIndex newIndex = new RuleIndex();

		for (RuleDetails rd : index.getRuleDetails())
			newIndex.addRuleToIndex(rd);

		for (RuleDetails rd : rs.index.getRuleDetails())
			newIndex.addRuleToIndex(rd);

		index = newIndex;
		rules = newIndex.getRules();
		addUsedTags(rs.usedTags);
		compiled = false;
	}

	/**
	 * Prepare this rule set for use.  The index is built and and the rules
	 * are saved to an array for fast access.
	 */
	public void prepare() {
		index.prepare();
		rules = index.getRules();
		compile();
	}

	public Set<String> getUsedTags() {
		return usedTags;
	}

	public void addUsedTags(Collection<String> usedTags) {
		this.usedTags.addAll(usedTags);
	}

	/**
	 * Compile the rules and reset caches. Detect common sub-expressions and
	 * make sure that all rules use the same instance of these common
	 * sub-expressions.
	 */
	private void compile() {
		HashMap<String, Op> tests = new HashMap<>();

		for (Rule rule : rules) {
			Op op;
			if (rule instanceof ExpressionRule)
				op = ((ExpressionRule) rule).getOp();
			else if (rule instanceof ActionRule)
				op = ((ActionRule) rule).getOp();
			else {
				log.error("unexpected rule instance");
				continue;
			}
			if (op instanceof AbstractBinaryOp) {
				AbstractBinaryOp binOp = (AbstractBinaryOp) op;
				binOp.setFirst(compileOp(tests, binOp.getFirst()));
				binOp.setSecond(compileOp(tests, binOp.getSecond()));
				op = compileOp(tests, binOp);
			} else if (op instanceof AbstractOp) {
				op = compileOp(tests, op);
			} else if (op instanceof LinkedBinaryOp) {
				((LinkedBinaryOp) op).setFirst(compileOp(tests, ((LinkedBinaryOp) op).getFirst()));
				((LinkedBinaryOp) op).setSecond(compileOp(tests, ((LinkedBinaryOp) op).getSecond()));
			} else if (op instanceof LinkedOp) {
				Op wrappedOp = compileOp(tests, ((LinkedOp) op).getFirst());
				op.setFirst(wrappedOp);
			} else {
				log.error("unexpected op instance");
				continue;
			}
			if (rule instanceof ExpressionRule)
				((ExpressionRule) rule).setOp(op);
			else if (rule instanceof ActionRule)
				((ActionRule) rule).setOp(op);
			else {
				log.error("unexpected rule instance");
			}
		}
		cacheId = 0;
		compiled = true;
	}
	
	private Op compileOp(HashMap<String, Op> tests, Op op){
		if (op instanceof AbstractBinaryOp){
			AbstractBinaryOp binOp = (AbstractBinaryOp) op;
			binOp.setFirst(compileOp(tests, binOp.getFirst()));
			binOp.setSecond(compileOp(tests, binOp.getSecond()));
		}
		if (op instanceof LinkedOp){
			// LinkedOp is referenced by other OPs, don't replace it 
			return op;
		}
		String test = op.toString();
		Op commonOp = tests.get(test);
		if (commonOp == null){
			if (op instanceof AbstractOp)
				((AbstractOp)op).resetCache();
			tests.put(test, op);
			commonOp = op;
		}
		
		return commonOp;
	}
	
	public void setFinalizeRule(Rule finalizeRule) {
		if (rules == null) {
			// this method must be called after prepare() is called so
			// that we have rules to which the finalize rules can be applied
			throw new IllegalStateException("First call prepare() before setting the finalize rules");
		}
		for (Rule rule : rules) 
			rule.setFinalizeRule(finalizeRule);
		
		compiled = false;
		this.finalizeRule = finalizeRule;  
	}

	@Override
	public Rule getFinalizeRule() {
		return finalizeRule;
	}

	@Override
	public void printStats(String header) {
		if (rules == null)
		  return;
		for (Rule rule : rules){ 
			rule.printStats(header);
		}
		if (finalizeRule != null)
			finalizeRule.printStats(header);
	}
	
	@Override
	public boolean containsExpression(String exp) {
		if (rules == null) {
			// this method must be called after prepare() is called so
			// that we have rules to which the finalize rules can be applied
			throw new IllegalStateException("First call prepare() before setting the finalize rules");
		}
		for (Rule rule : rules) {
			if (rule.containsExpression(exp))
				return true;
		}
		return finalizeRule != null && finalizeRule.containsExpression(exp);
	}

	public BitSet getRules(Element el) {
		if (!compiled || cacheId == Integer.MAX_VALUE)
			compile();
		// new element, invalidate all caches
		cacheId++;

		// Get all the rules that could match from the index.
		BitSet candidates = new BitSet();
		for (Entry<Short, String> tagEntry : el.getFastTagEntryIterator()) {
			BitSet bsRules = index.getRulesForTag(tagEntry.getKey(), tagEntry.getValue());
			if (bsRules != null && !bsRules.isEmpty())
				candidates.or(bsRules);
		}
		return candidates;
	}
	
	@Override
	public void augmentWith(uk.me.parabola.mkgmap.reader.osm.ElementSaver elementSaver) {
		if (rules == null)
			return;
		for (Rule rule: rules)
			rule.augmentWith(elementSaver);
	}
} 
