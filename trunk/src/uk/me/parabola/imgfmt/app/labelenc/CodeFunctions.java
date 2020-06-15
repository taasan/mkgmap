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
 * Create date: Jan 1, 2008
 */
package uk.me.parabola.imgfmt.app.labelenc;

import uk.me.parabola.imgfmt.ExitException;

/**
 * @author Steve Ratcliffe
 */
public class CodeFunctions {
	// Label encoding length
	public static final int ENCODING_FORMAT6 = 6;
	private static final int ENCODING_FORMAT9 = 9;
	private static final int ENCODING_FORMAT10 = 10;

	private int codepage;
	private int encodingType;
	private CharacterEncoder encoder;
	private CharacterDecoder decoder;

	protected void setEncoder(CharacterEncoder encoder) {
		this.encoder = encoder;
	}

	public CharacterEncoder getEncoder() {
		return encoder;
	}

	private void setDecoder(CharacterDecoder decoder) {
		this.decoder = decoder;
	}

	public CharacterDecoder getDecoder() {
		return decoder;
	}

	public int getEncodingType() {
		return encodingType;
	}

	private void setEncodingType(int encodingType) {
		this.encodingType = encodingType;
	}

	public int getCodepage() {
		return codepage;
	}

	protected void setCodepage(int codepage) {
		this.codepage = codepage;
	}

	/**
	 * Create a CharacterEncoder for the given charset option.  Note that this
	 * routine also writes to the lblHeader parameter to set the encoding type.
	 * @param charset The mkgmap command line option to be interpreted.
	 * @return The various character set parameters that will be needed.
	 */
	public static CodeFunctions createEncoderForLBL(String charset) {
		CodeFunctions funcs = new CodeFunctions();
		switch (charset) {
		case "ascii":
			funcs.setEncodingType(ENCODING_FORMAT6);
			funcs.setEncoder(new Format6Encoder());
			funcs.setDecoder(new Format6Decoder());
			break;
		case "cp0":  // This is used for ascii but with the single byte format
			funcs.setEncodingType(ENCODING_FORMAT9);
			funcs.setEncoder(new AnyCharsetEncoder("ascii", new TableTransliterator("ascii")));
			funcs.setDecoder(new AnyCharsetDecoder("ascii"));
			funcs.setCodepage(0);
			break;
		case "cp1252":
		case "latin1":
			funcs.setEncodingType(ENCODING_FORMAT9);
			funcs.setEncoder(new AnyCharsetEncoder("cp1252", new TableTransliterator("latin1")));
			funcs.setDecoder(new AnyCharsetDecoder("cp1252"));
			funcs.setCodepage(1252);
			break;
		case "cp65001":
		case "unicode":
			funcs.setEncodingType(ENCODING_FORMAT10);
			funcs.setEncoder(new Utf8Encoder());
			funcs.setDecoder(new Utf8Decoder());
			funcs.setCodepage(65001);
			break;
		case "cp932":
		case "ms932":
			funcs.setEncodingType(ENCODING_FORMAT10);
			funcs.setEncoder(new AnyCharsetEncoder("ms932", new SparseTransliterator("nomacron")));
			funcs.setDecoder(new AnyCharsetDecoder("ms932"));
			funcs.setCodepage(932);
			break;
		default:
			funcs.setEncodingType(ENCODING_FORMAT9);
			funcs.setDecoder(new AnyCharsetDecoder(charset));
			funcs.setEncoder(new AnyCharsetEncoder(charset, new TableTransliterator("ascii")));
			funcs.setCodepage(guessCodepage(charset));
			break;
		}

		return funcs;
	}

	/**
	 * Sets encoding functions for a given format and code page.  This is used
	 * when reading from an existing file.
	 *
	 * @param format The format from the lbl header.
	 * @param codePage The codepage found in the header.
	 * @return The various character set parameters that will be needed.
	 */
	public static CodeFunctions createEncoderForLBL(int format, int codePage) {
		CodeFunctions funcs;

		if (format == ENCODING_FORMAT6) {
			funcs = createEncoderForLBL("ascii");
		} else {
			funcs = createEncoderForLBL("cp" + codePage);
		}

		return funcs;
	}

	/**
	 * Guess the code page from the given charset.  Only works with things
	 * like cp1252, windows-1252 and some well known ones.
	 * @param charset The charset that was given.
	 */
	private static int guessCodepage(String charset) {
		String cs = charset.toLowerCase();
		if (cs.startsWith("cp")) {
			try {
				return Integer.parseInt(charset.substring(2));
			} catch (NumberFormatException e) {
				// wasn't in the right form
				throw new ExitException("Invalid character set: " + cs);
			}
		} else if (cs.startsWith("windows-")) {
			try {
				return Integer.parseInt(charset.substring(8));
			} catch (NumberFormatException e) {
				// wasn't in the right form to guess
				throw new ExitException("Invalid character set: " + cs);
			}
		} else if ("latin1".equals(cs)) {
			return 1252;
		}
		return 0;
	}

	public static CharacterEncoder getDefaultEncoder() {
		return new Format6Encoder();
	}

	public static CharacterDecoder getDefaultDecoder() {
		return new Format6Decoder();
	}
}
