/*
 * Copyright (C) 2014.
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


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ibm.icu.text.CollationElementIterator;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;

//import java.text.CollationElementIterator;
//import java.text.Collator;
//import java.text.RuleBasedCollator;

/**
 * Create a set of rules for a given code page.
 *
 * Should be usable, perhaps with a few tweaks.
 * Works with unicode too, need to choose which blocks to take for unicode.
 *
 * @author Steve Ratcliffe
 */
public class CollationRules {

	private CharsetDecoder decoder;
	private final NavigableSet<CharPosition> positionMap = new TreeSet<>();
	private final NavigableSet<CharPosition> basePositionMap = new TreeSet<>();
	private final Map<Character, CharPosition> charMap = new HashMap<>();
	private boolean isUnicode;
	private Charset charset;

	public static void main(String[] args) {
		String charsetName = args[0];
		CollationRules main = new CollationRules();
		main.go(charsetName);
	}

	private void go(String charsetName) {
		RuleBasedCollator col = (RuleBasedCollator) Collator.getInstance();

		charset = Charset.forName(charsetName);
		if ("utf-8".equalsIgnoreCase(charsetName))
			isUnicode = true;
		decoder = charset.newDecoder();

		if (isUnicode)
			addUnicode();
		else
			addBlock(col, 0);

		printCharMap();
		printExpansions();
	}

	private void addBlock(RuleBasedCollator col, int block) {
		for (int i = 0; i < 0x100; i++) {
			int ch = (block << 8) + i;
			String testString = getString(ch);
			char conv = testString.charAt(0);
			if (Character.getType(conv) == Character.UNASSIGNED || conv == 0xfffd)
				continue;
			CollationElementIterator it = col.getCollationElementIterator(testString);

			System.out.printf("# %s ", fmtChar(testString.charAt(0)));
			int next;
			int index = 0;
			CharPosition cp = new CharPosition(0);
			while ((next = it.next()) != CollationElementIterator.NULLORDER) {
				if (index == 0) {
					cp = new CharPosition(ch);
					cp.setOrder(next);
				} else {
					assert index < 3;
					if ((next & 0xffff0000) == 0) {
						cp.addOrder(next, index);
					} else {
						cp.addChar(new CharPosition(ch));
						cp.setOrder(next);
					}
				}

				index++;
			}
			System.out.printf(" %s %d", cp, Character.getType(cp.getUnicode()));
			System.out.println();

			tweak(cp);
			if (ch > 0)
				positionMap.add(cp);
			if (cp.nextChar == null) {
				basePositionMap.add(cp);
				charMap.put(conv, cp);
			}
		}
	}

	private void addUnicode() {
		Pattern pat = Pattern.compile("([0-9A-F]{4,5}) ? ; \\[[.*](.*)\\] #.*");
		try (FileReader r = new FileReader("allkeys.txt")) {
			try (BufferedReader br = new BufferedReader(r)) {
				String line;
				while ((line = br.readLine()) != null) {
					Matcher matcher = pat.matcher(line);
					if (matcher.matches()) {
						String weights = matcher.group(2);
						int ch = Integer.parseInt(matcher.group(1), 16);
						if (ch > 0xffff)
							continue;

						System.out.printf("# %04x %s ", ch, fmtChar(ch));

						String[] split = weights.split("]\\[[.*]");

						int index = 0;
						CharPosition cp = new CharPosition(0);

						for (String s : split) {
							String[] ws = s.split("\\.");
							int next = Integer.parseInt(ws[0], 16) << 16
									| ((Integer.parseInt(ws[1], 16) << 8) & 0xff00)
									| ((Integer.parseInt(ws[2], 16)) & 0xff);

							if (index == 0) {
								cp = new CharPosition(ch);
								cp.setOrder(next);
							} else {
								if ((next & 0xffff0000) == 0) {
									cp.addOrder(next, index);
								} else {
									cp.addChar(new CharPosition(ch));
									cp.setOrder(next);
								}
							}
							index++;
						}

						System.out.printf(" %s %d\n", cp, Character.getType(cp.getUnicode()));

						tweak(cp);
						if (ch > 0)
							positionMap.add(cp);
						if (cp.nextChar == null) {
							basePositionMap.add(cp);
							charMap.put((char) ch, cp);
						}
					} else {
						System.out.println("# NOMATCH: " + line);
					}
				}

			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Fix up a few characters that we always want to be in well known places.
	 *
	 * @param cp The position to change.
	 */
	private void tweak(CharPosition cp) {
		if (cp.val < 8)
			cp.third = cp.val + 7;

		if (!isUnicode) {
			switch (cp.getUnicode()) {
			case '¼':
				cp.nextChar = charMap.get('/').copy();
				cp.nextChar.nextChar = charMap.get('4');
				break;
			case '½':
				cp.nextChar = charMap.get('/').copy();
				cp.nextChar.nextChar = charMap.get('2');
				break;
			case '¾':
				cp.nextChar = charMap.get('/').copy();
				cp.nextChar.nextChar = charMap.get('4');
				break;
			}
		}

		switch (cp.getUnicode()) {
		case '˜':
			CharPosition tilde = charMap.get('~');
			cp.first = tilde.first;
			cp.second = tilde.second + 1;
			cp.third = tilde.third + 1;
			cp.nextChar = null;
			break;
		}
	}

	private String getString(int i) {
		if (isUnicode)
			return new String(new char[]{(char) i});
		else {
			byte[] b = {(byte) i};
			return new String(b, 0, 1, charset);
		}
	}

	private void printCharMap() {

		Formatter chars = new Formatter();
		chars.format("\n");

		CharPosition last = new CharPosition(0);
		last.first = 0;
		for (CharPosition cp : positionMap) {
			if (cp.isExpansion())
				continue;

			if (cp.first != last.first) {
				chars.format("\n < ");
			} else if (cp.second != last.second) {
				chars.format(" ; ");
			} else if (cp.third != last.third) {
				chars.format(",");
			} else {
				chars.format("=");
			}
			last = cp;
			int uni = toUnicode(cp.val);
			chars.format("%s", fmtChar(uni));
		}

		System.out.println(chars);
	}

	private void printExpansions() {
		for (CharPosition cp : positionMap) {
			if (!cp.isExpansion())
				continue;

			Formatter fmt = new Formatter();

			//noinspection MalformedFormatString
			fmt.format("expand %c to", cp.getUnicode());

			boolean ok = true;
			for (CharPosition cp2 = cp; cp2 != null; cp2 = cp2.nextChar) {
				cp2.second = 0x50000;
				int top = (cp2.third >> 16) & 0xff;
				cp2.third = (top == 0x9e || top == 0xa2 || top == 0x2b) ? 0x9b0000 : 0;

				CharPosition floor = basePositionMap.ceiling(cp2);
				if (floor == null || floor.getUnicode() == 0xfffd) {
					fmt.format(" NF");
					ok = false;
				} else {
					fmt.format(" %s", fmtChar(floor.getUnicode()));
				}
			}

			System.out.println((ok ? "" : "# ") + fmt.toString());

			// Print comments to help find problems.
			for (CharPosition cp2 = cp; cp2 != null; cp2 = cp2.nextChar) {
				CharPosition floor = basePositionMap.ceiling(cp2);
				if (floor == null) {
					System.out.println("#FIX: NF ref=" + cp2);
				} else {
					System.out.println("#floor is " + fmtChar(toUnicode(floor.val)) + ", " + floor + ", ref is " + cp2);
				}
			}
		}
	}

	private String fmtChar(int val) {
		boolean asChar = true;
		switch (val) {
		case '<':
		case ';':
		case ',':
		case '=':
		case '#':
			asChar = false;
			break;
		default:

			switch (Character.getType(val)) {
			case Character.UNASSIGNED:
			case Character.NON_SPACING_MARK:
			case Character.FORMAT:
			case Character.CONTROL:
			case Character.SPACE_SEPARATOR:
			case Character.LINE_SEPARATOR:
			case Character.PARAGRAPH_SEPARATOR:
				asChar = false;
			}
		}

		if (asChar) {
			//noinspection MalformedFormatString
			return String.format("%c", val);
		} else {
			return String.format("%04x", val);
		}
	}

	private int toUnicode(int c) {
		if (isUnicode)
			return c;
		ByteBuffer b = ByteBuffer.allocate(1);
		b.put((byte) c);
		b.flip();
		try {
			CharBuffer chars = decoder.decode(b);
			return chars.charAt(0);
		} catch (CharacterCodingException e) {
			return '?';
		}
	}


	class CharPosition implements Comparable<CharPosition> {
		private final int val;
		private int first;
		private int second;
		private int third;
		private CharPosition nextChar;

		public CharPosition(int charValue) {
			this.val = charValue;
		}

		public int compareTo(CharPosition other) {
			if (other.first == first)
				return compareSecond(other);
			else if (first < other.first)
				return -1;
			else
				return 1;
		}

		private int compareSecond(CharPosition c2) {
			if (c2.second == second)
				return compareThird(c2);
			else if (second < c2.second)
				return -1;
			else
				return 1;
		}

		private int compareThird(CharPosition c2) {
			if (third == c2.third)
				return new Integer(val).compareTo(c2.val);
			else if (third < c2.third)
				return -1;
			else
				return 1;
		}

		public String toString() {
			Formatter fmt = new Formatter();
			toString(fmt);

			return fmt.toString();
		}

		private void toString(Formatter fmt) {
			fmt.format("[%04x %02x %02x]", first, second, third);
			if (nextChar != null)
				nextChar.toString(fmt);
		}

		public void setOrder(int next) {
			if (nextChar != null) {
				nextChar.setOrder(next);
				return;
			}
			first = (next >> 16) & 0xffff;
			second = (next << 8) & 0xff0000;
			third = (next << 16) & 0xff0000;
		}

		public void addOrder(int next, int count) {
			assert ((next >>> 16) & 0xffff) == 0;
			if (this.nextChar != null) {
				this.nextChar.addOrder(next, count);
				return;
			}
			second += ((next >> 8) & 0xff) << (2-count)*8;
			third += ((next) & 0xff) << (2-count)*8;
		}

		public boolean isExpansion() {
			return nextChar != null;
		}

		public void addChar(CharPosition pos) {
			if (nextChar != null) {
				nextChar.addChar(pos);
				return;
			}
			nextChar = pos;
		}

		public int getUnicode() {
			return toUnicode(val);
		}

		public CharPosition copy() {
			CharPosition cp = new CharPosition(this.val);
			cp.first = this.first;
			cp.second = this.second;
			cp.third = this.third;
			return cp;
		}
	}
}
