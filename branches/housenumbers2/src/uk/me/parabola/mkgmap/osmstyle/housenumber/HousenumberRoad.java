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

package uk.me.parabola.mkgmap.osmstyle.housenumber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.osmstyle.housenumber.HousenumberGenerator.HousenumberMatchByNumComparator;
import uk.me.parabola.mkgmap.osmstyle.housenumber.HousenumberGenerator.HousenumberMatchByPosComparator;

/**
 * Helper class to combine house numbers with MapRoad instances
 * @author Gerd Petermann
 *
 */
public class HousenumberRoad {
	private static final Logger log = Logger.getLogger(HousenumberRoad.class);
	private final String streetName;
	private final MapRoad road;
	private ExtNumbers extNumbersHead;
	private final List<HousenumberMatch> houseNumbers;
	private boolean changed;
	private boolean isRandom;

	public HousenumberRoad(String streetName, MapRoad r, List<HousenumberMatch> potentialNumbersThisRoad) {
		this.streetName = streetName;
		this.road = r;
		this.houseNumbers = new ArrayList<>(potentialNumbersThisRoad);
		
	}
	public void buildIntervals() {
		Collections.sort(houseNumbers, new HousenumberMatchByNumComparator());
		if (log.isInfoEnabled())
			log.info("Initial housenumbers for",road,"in",road.getCity(),houseNumbers);
		
		filterRealDuplicates();
		filterGroups();
		if (houseNumbers.isEmpty())
			return;
		List<HousenumberMatch> leftNumbers = new ArrayList<HousenumberMatch>();
		List<HousenumberMatch> rightNumbers = new ArrayList<HousenumberMatch>();
		
		for (HousenumberMatch hr : houseNumbers) {
			if (hr.getRoad() == null || hr.isIgnored()){
				continue;
			}
			if (hr.isLeft()) {
				leftNumbers.add(hr);
			} else {
				rightNumbers.add(hr);
			}
		}
		detectGroups(leftNumbers, rightNumbers);
		Collections.sort(leftNumbers, new HousenumberMatchByPosComparator());
		Collections.sort(rightNumbers, new HousenumberMatchByPosComparator());
		
		
		int currNodePos = 0;
		int nodeIndex = 0;
		int prevNumberNodeIndex = 0;
		int prevNodePos = 0;
		extNumbersHead = null;
		ExtNumbers currNumbers = null;
		for (Coord p : road.getPoints()) {
			if (currNodePos == 0) {
				if (road.skipAddToNOD() == false)
					assert p instanceof CoordNode; 
			}

			// An ordinary point in the road.
			if (p.isNumberNode() == false) {
				currNodePos++;
				continue;
			}

			// The first time round, this is guaranteed to be a CoordNode
			if (currNodePos == 0) {
				nodeIndex++;
				currNodePos++;
				continue;
			}

			// Now we have a CoordNode and it is not the first one.
			ExtNumbers numbers = new ExtNumbers(this);
			numbers.setRnodNumber(prevNumberNodeIndex);
			int leftUsed = numbers.setNumbers(leftNumbers, prevNodePos, currNodePos, true);
			int rightUsed = numbers.setNumbers(rightNumbers, prevNodePos, currNodePos, false);
			prevNodePos = currNodePos;
			// maintain chain
			numbers.prev = currNumbers;
			if (currNumbers != null)
				currNumbers.next = numbers;
			else {
				extNumbersHead = numbers;
			}
			currNumbers = numbers;
			leftNumbers.subList(0, leftUsed).clear();
			rightNumbers.subList(0, rightUsed).clear(); 				

			prevNumberNodeIndex = nodeIndex;
			nodeIndex++;
			currNodePos++;
		}
	}

	/**
	 * Try to detect groups of houses with continues numbers 
	 * which should be attached to a zero-length segment.
	 * Very useful when a service road connects eg. 
	 * numbers 7..15 to the named road, but also for just two numbers.
	 * @param depth
	 * @param leftNumbers
	 * @param rightNumbers
	 */
	private void detectGroups(List<HousenumberMatch> leftNumbers, List<HousenumberMatch> rightNumbers) {
		List<HousenumberGroup> groups = new ArrayList<>();

		for (int side = 0; side < 2; side++){
			boolean left = side == 0;
			List<HousenumberMatch> houses = left ? leftNumbers : rightNumbers;
			HousenumberGroup group = null;
			for (int j = 1; j < houses.size(); j++){
				HousenumberMatch house = houses.get(j);
				if (group == null){
					if (house.isInterpolated())
						continue;
					HousenumberMatch predHouse = houses.get(j-1);
					int deltaNum = predHouse.getHousenumber() - house.getHousenumber();
					if (Math.abs(deltaNum) > 2)
						continue;
					if (HousenumberGroup.housesFormAGroup(predHouse, house))
						group = new HousenumberGroup(this, houses.subList(j-1, j+1));
				} else {
					if (group.tryAddHouse(house) == false){
						if(group.verify())
							groups.add(group);
						group = null;
					}
				}
			}
			if (group != null && group.verify()){
				groups.add(group);
			}
		}
		if (groups.isEmpty())
			return;
		boolean nodesAdded = false;
		for (HousenumberGroup group : groups){
			int oldNumPoints = getRoad().getPoints().size(); 
			if (nodesAdded){
				if (group.recalcPositions() == false)
					continue;
			}
			if (group.findSegment(streetName, groups)){
				nodesAdded = true;
				log.debug("added",getRoad().getPoints().size() - oldNumPoints,"number node(s) at",group.linkNode.toDegreeString(),"for group",group,"in road",getRoad());
				oldNumPoints = getRoad().getPoints().size();
				int minSeg = group.minSeg;
				for (HousenumberMatch house : this.houseNumbers){
					if (house.getSegment() >= minSeg)
						HousenumberGenerator.findClosestRoadSegment(house, getRoad());
				}
				group.recalcPositions();
			} else {
				if(group.linkNode != null)
					log.debug("used existing zero-length-segment at",group.linkNode.toDegreeString(),"for group",group,"in road",getRoad());
			}
		}
		return;
	}

	/**
	 */
	public void checkIntervals(){
		if (extNumbersHead == null)
			return;
		boolean anyChanges = false;
		
		extNumbersHead.detectRandom();
		for (int loop = 0; loop < 10; loop++){
			if (loop > 4){
				// TODO: 3,4,5 ? 
				setRandom(true);
			}
			setChanged(false);
			extNumbersHead = extNumbersHead.checkSingleChainSegments(streetName);
			extNumbersHead = extNumbersHead.checkChainPlausibility(streetName, houseNumbers);
			if (isChanged())
				anyChanges = true;
			else 
				break;
		}
		setChanged(anyChanges);
	}
	
	
	/**
	 * Identify duplicate numbers and ignore those which are close together
	 * and those which are probably wrong. 
	 */
	private void filterRealDuplicates() {
		List<HousenumberMatch> toIgnore = new ArrayList<>();
		final int TO_SEARCH = 6;
		int oddLeft = 0, oddRight = 0, evenLeft = 0, evenRight = 0;
		for (HousenumberMatch  house: houseNumbers){
			if (house.isIgnored())
				continue;
			if (house.isLeft()){
				if (house.getHousenumber() % 2 == 0) 
					evenLeft++;
				else 
					oddLeft++;
			} else {
				if (house.getHousenumber() % 2 == 0)
					evenRight++;
				else
					oddRight++;
			}
		}
		HousenumberMatch usedForCalc = null;
		for (int i = 1; i < houseNumbers.size(); i++){
			HousenumberMatch house1 = houseNumbers.get(i - 1);
			HousenumberMatch house2 = houseNumbers.get(i);
			if (house1.getSign().equals(house2.getSign()) == false){
				usedForCalc = null;
			} else {
				// found a duplicate house number (e.g. 2 and 2 or 1b and 1b)
				double distBetweenHouses = house2.getLocation().distance(house1.getLocation());
				double distToUsed = (usedForCalc == null) ? distBetweenHouses : house2.getLocation().distance(usedForCalc.getLocation()); 
				if (usedForCalc == null)
					usedForCalc = (house1.getDistance() < house2.getDistance()) ? house1 : house2;
				else {
					house1 = usedForCalc;
				}
				boolean sameSide = (house2.isLeft() == house1.isLeft());
				if (log.isDebugEnabled())
					log.debug("analysing duplicate address",streetName,house1.getSign(),"for road with id",getRoad().getRoadDef().getId());
				if (sameSide && (distBetweenHouses < 100 || distToUsed < 100)){
					HousenumberMatch obsolete = house1 == usedForCalc ? house2 : house1;
					if (log.isDebugEnabled())
						log.debug("house",obsolete,obsolete.getElement().toBrowseURL(),"is close to other element and on the same road side, is ignored");
					toIgnore.add(obsolete);
					continue;
				}
				
				if (!sameSide){
					if (log.isDebugEnabled())
						log.debug("oddLeft, oddRight, evenLeft, evenRight:",oddLeft, oddRight, evenLeft, evenRight);
					HousenumberMatch wrongSide = null;
					if (house2.getHousenumber() % 2 == 0){
						if (evenLeft == 1 && (oddLeft > 1 || evenRight > 0 && oddRight == 0)){
							wrongSide = house2.isLeft() ? house2: house1;
						}
						if (evenRight == 1 && (oddRight > 1 || evenLeft > 0 && oddLeft == 0)){
							wrongSide = !house2.isLeft() ? house2: house1;
						}
					} else {
						if (oddLeft == 1 && (evenLeft > 1 || oddRight > 0 && evenRight == 0)){
							wrongSide = house2.isLeft() ? house2: house1;
						}
						if (oddRight == 1 && (evenRight > 1 || oddLeft > 0 && evenLeft == 0)){
							wrongSide = !house2.isLeft() ? house2: house1;
						}
					}
					if (wrongSide != null){
						if (log.isDebugEnabled())
							log.debug("house",streetName,wrongSide.getSign(),"from",wrongSide.getElement().toBrowseURL(),"seems to be wrong, is ignored");
						toIgnore.add(wrongSide);
						continue;
					}
				}
				
				double[] sumDist = new double[2];
				double[] sumDistSameSide = new double[2];
				int[] confirmed = new int[2];
				int[] falsified = new int[2];
				int[] found = new int[2];
				List<HousenumberMatch> dups = Arrays.asList(house2, house1);
				for (int k = 0; k < dups.size(); k++){
					HousenumberMatch other, curr;
					if (k == 0){
						curr = dups.get(0);
						other = dups.get(1);
					} else {
						curr = dups.get(1);
						other = dups.get(0);
					}
					int pos = houseNumbers.indexOf(curr);

					int left = pos - 1;
					int right = pos + 1;
					HousenumberMatch nearHouse;
					int stillToFind = TO_SEARCH;
					while (stillToFind > 0){
						int oldDone = stillToFind;
						if (left >= 0){
							nearHouse = houseNumbers.get(left);
							if (nearHouse != other){
								double dist = curr.getLocation().distance(nearHouse.getLocation());
								sumDist[k] += dist;
								if (nearHouse.isLeft() == curr.isLeft()){
									sumDistSameSide[k] += dist;
								}
								if (curr.getHousenumber() == nearHouse.getHousenumber()){
									if (dist < 20)
										confirmed[k]++;
								} else {
									if (dist < 10 )
										falsified[k]++;
								}
							}
							--left;
							stillToFind--;
							if (stillToFind == 0)
								break;
						}
						if (right < houseNumbers.size()){
							nearHouse = houseNumbers.get(right);
							if (nearHouse != other){
								double dist = curr.getLocation().distance(nearHouse.getLocation());
								sumDist[k] += dist;
								if (nearHouse.isLeft() == curr.isLeft()){
									sumDistSameSide[k] += dist;
								}
								if (curr.getHousenumber() == nearHouse.getHousenumber()){
									if (dist < 40)
										confirmed[k]++;
								} else {
									if (dist < 10 )
										falsified[k]++;
								}
							}
							stillToFind--;
							right++;
						}
						if (oldDone == stillToFind)
							break;
					}
					found[k] = TO_SEARCH - 1 - stillToFind; 
				}
				if (log.isDebugEnabled()){
					log.debug("dup check 1:", streetName, house1, house1.getElement().toBrowseURL());
					log.debug("dup check 2:", streetName, house2, house2.getElement().toBrowseURL());
					log.debug("confirmed",Arrays.toString(confirmed),"falsified",Arrays.toString(falsified),"sum-dist",Arrays.toString(sumDist),"sum-dist-same-side",Arrays.toString(sumDistSameSide));
				}
				HousenumberMatch bad = null;
				if (confirmed[1] > 0 && confirmed[0] == 0  && falsified[1] == 0)
					bad = dups.get(0);
				else if (confirmed[0] > 0 && confirmed[1] == 0  && falsified[0] == 0)
					bad = dups.get(1);
				else if (found[0] > 3 && sumDist[0] > sumDist[1] && sumDistSameSide[0] > sumDistSameSide[1])
					bad = dups.get(0);
				else if (found[1] > 3 && sumDist[1] > sumDist[0] && sumDistSameSide[1] > sumDistSameSide[0])
					bad = dups.get(1);
				if (bad != null){
					toIgnore.add(bad);
				} else {
					if (log.isDebugEnabled())
						log.debug("duplicate house number, don't know which one to use, ignoring both");
					toIgnore.add(house1);
					toIgnore.add(house2);
					house2.setIgnored(true);
					house1.setIgnored(true);
				}
			}
		} 
		for (HousenumberMatch house : toIgnore){
			if (log.isInfoEnabled())
				log.info("duplicate housenumber",streetName,house.getSign(),"is ignored for road with id",house.getRoad().getRoadDef().getId(),",house:",house.getElement().toBrowseURL());
			houseNumbers.remove(house);
		}
	}

	/**
	 * Identify groups of buildings with numbers like 1a,1b,1c.
	 * The list in housenumbers is sorted so that 2 appears before 2a and
	 * 2b appears before 2c. 
	 * XXX This is quite aggressive, maybe we have to add more logic here.  
	 */
	private void filterGroups() {
		if (houseNumbers.size() <= 1)
			return;
		HousenumberMatch prev = houseNumbers.get(0);
		HousenumberMatch used = null;
		for (int i = 1; i < houseNumbers.size(); i++){
			HousenumberMatch house = houseNumbers.get(i);
			if (house.getHousenumber() != prev.getHousenumber())
				used = null;
			else {
				if (used == null)
					used = prev;
				house.setIgnored(true);
				if (log.isInfoEnabled())
					log.info("using",streetName,used.getSign(), "in favor of",house.getSign(),"as target for address search");
			}
			prev = house;
		}
	}
	

	public void checkWrongRoadAssignmments(HousenumberRoad other) {
		if (this.extNumbersHead == null || other.extNumbersHead == null)
			return;
		
		for (int loop = 0; loop < 10; loop++){
			boolean changed = false;
			ExtNumbers head1 = this.extNumbersHead;
			for (ExtNumbers en1 = head1; en1 != null; en1 = en1.next){
				if (changed)
					break;
				if (en1.hasNumbers() == false)
					continue;
				ExtNumbers head2 = other.extNumbersHead;
				for (ExtNumbers en2 = head2; en2 != null; en2 = en2.next){
					if (changed)
						break;
					if (en2.hasNumbers() == false)
						continue;
					int res = ExtNumbers.checkIntervals(streetName, en1, en2);
					switch (res) {
					case ExtNumbers.OK_NO_CHANGES:
					case ExtNumbers.NOT_OK_KEEP:
						break;
					case ExtNumbers.OK_AFTER_CHANGES:
						changed = true;
						this.setChanged(true);
						other.setChanged(true);
						break;
					case ExtNumbers.NOT_OK_TRY_SPLIT:
						if (en1.needsSplit()){
							ExtNumbers test = en1.tryChange(ExtNumbers.SR_FIX_ERROR);
							if (test != en1){
								changed = true;
								if (test.prev == null){
									this.extNumbersHead = test;
								}
							}
						}
						if (en2.needsSplit()){
							ExtNumbers test = en2.tryChange(ExtNumbers.SR_FIX_ERROR);
							if (test != en2){
								changed = true;
								if (test.prev == null){
									other.extNumbersHead = test;
								}
							}
						}
						break;
					case ExtNumbers.NOT_OK_STOP:
						return;
					default:
						log.error("can't fix",en1,en2);
					}
				}
			}
			if (!changed)
				break;
		}
	}
	
	public void setNumbers() {
		if (extNumbersHead == null)
			return;
		// make sure that the name we used for the cluster is also attached to the road
		String[] labels = road.getLabels();
		boolean found = false;
		for (String label : labels){
			if (streetName.equals(label))
				found = true;
		}
		if (!found){
			for (int i = 0; i < labels.length; i++){
				if (labels[i] == null){
					labels[i] = streetName;
					log.info("added label",streetName,"for",road,"Labels are now:",Arrays.toString(labels));
					found = true;
					break;
				}
			}
		}
		if (!found){
			int last = labels.length-1;
			String droppedLabel = labels[last];
			labels[last] = streetName;
			if (droppedLabel != null){
				if (log.isInfoEnabled())
					log.info("dropped label",droppedLabel,"for",road,"in preference to correct address search. Labels are now:",Arrays.toString(labels));
			}
		}
		
		road.setNumbers(extNumbersHead.getNumberList());
	}
	
	public MapRoad getRoad(){
		return road;
	}

	public boolean isChanged() {
		return changed;
	}

	public void setChanged(boolean changed) {
		this.changed = changed;
	}

	public boolean isRandom() {
		return isRandom;
	}

	public void setRandom(boolean isRandom) {
		if (this.isRandom == false)
			log.debug("detected random case",this);
		this.isRandom = isRandom;
	}

	/**
	 * 
	 */
	public void improveSearchResults() {
		ExtNumbers curr = extNumbersHead;
		while (curr != null) {
			ExtNumbers en = curr.splitLargeGaps();
			if (en != curr) {
				if (en.hasNumbers() && en.next != null && en.next.hasNumbers())
					setChanged(true);
				else {
					ExtNumbers test = en.hasNumbers() ?  en : en.next;
					if (test.getNumbers().isSimilar(curr.getNumbers()) == false)
						setChanged(true);
				}
				if (curr.prev == null)
					extNumbersHead = en;
				curr = en;
				continue;
			}
			curr = curr.next;
		}
	}

	public String toString(){
		return getRoad().toString() + " " + houseNumbers;
	}
}

