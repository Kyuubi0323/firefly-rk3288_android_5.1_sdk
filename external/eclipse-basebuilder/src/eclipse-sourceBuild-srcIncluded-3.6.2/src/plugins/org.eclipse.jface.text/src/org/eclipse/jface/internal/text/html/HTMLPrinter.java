/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jface.internal.text.html;

import java.io.IOException;
import java.io.Reader;
import java.net.URL;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import org.eclipse.jface.util.Util;

/**
 * Provides a set of convenience methods for creating HTML pages.
 * <p>
 * Moved into this package from <code>org.eclipse.jface.internal.text.revisions</code>.</p>
 */
public class HTMLPrinter {

	private static RGB BG_COLOR_RGB= new RGB(255, 255, 225); // RGB value of info bg color on WindowsXP
	private static RGB FG_COLOR_RGB= new RGB(0, 0, 0); // RGB value of info fg color on WindowsXP
	
	private static final String UNIT; // See https://bugs.eclipse.org/bugs/show_bug.cgi?id=155993
	static {
		UNIT= Util.isMac() ? "px" : "pt";   //$NON-NLS-1$//$NON-NLS-2$
	}


	static {
		final Display display= Display.getDefault();
		if (display != null && !display.isDisposed()) {
			try {
				display.asyncExec(new Runnable() {
					/*
					 * @see java.lang.Runnable#run()
					 */
					public void run() {
						cacheColors(display);
						installColorUpdater(display);
					}
				});
			} catch (SWTError err) {
				// see https://bugs.eclipse.org/bugs/show_bug.cgi?id=45294
				if (err.code != SWT.ERROR_DEVICE_DISPOSED)
					throw err;
			}
		}
	}

	private HTMLPrinter() {
	}

	private static void cacheColors(Display display) {
		BG_COLOR_RGB= display.getSystemColor(SWT.COLOR_INFO_BACKGROUND).getRGB();
		FG_COLOR_RGB= display.getSystemColor(SWT.COLOR_INFO_FOREGROUND).getRGB();
	}
	
	private static void installColorUpdater(final Display display) {
		display.addListener(SWT.Settings, new Listener() {
			public void handleEvent(Event event) {
				cacheColors(display);
			}
		});
	}
	
	private static String replace(String text, char c, String s) {

		int previous= 0;
		int current= text.indexOf(c, previous);

		if (current == -1)
			return text;

		StringBuffer buffer= new StringBuffer();
		while (current > -1) {
			buffer.append(text.substring(previous, current));
			buffer.append(s);
			previous= current + 1;
			current= text.indexOf(c, previous);
		}
		buffer.append(text.substring(previous));

		return buffer.toString();
	}

	public static String convertToHTMLContent(String content) {
		content= replace(content, '&', "&amp;"); //$NON-NLS-1$
		content= replace(content, '"', "&quot;"); //$NON-NLS-1$
		content= replace(content, '<', "&lt;"); //$NON-NLS-1$
		return replace(content, '>', "&gt;"); //$NON-NLS-1$
	}

	public static String read(Reader rd) {

		StringBuffer buffer= new StringBuffer();
		char[] readBuffer= new char[2048];

		try {
			int n= rd.read(readBuffer);
			while (n > 0) {
				buffer.append(readBuffer, 0, n);
				n= rd.read(readBuffer);
			}
			return buffer.toString();
		} catch (IOException x) {
		}

		return null;
	}

	public static void insertPageProlog(StringBuffer buffer, int position, RGB fgRGB, RGB bgRGB, String styleSheet) {
		if (fgRGB == null)
			fgRGB= FG_COLOR_RGB;
		if (bgRGB == null)
			bgRGB= BG_COLOR_RGB;

		StringBuffer pageProlog= new StringBuffer(300);

		pageProlog.append("<html>"); //$NON-NLS-1$

		appendStyleSheet(pageProlog, styleSheet);

		appendColors(pageProlog, fgRGB, bgRGB);

		buffer.insert(position,  pageProlog.toString());
	}

	private static void appendColors(StringBuffer pageProlog, RGB fgRGB, RGB bgRGB) {
		pageProlog.append("<body text=\""); //$NON-NLS-1$
		appendColor(pageProlog, fgRGB);
		pageProlog.append("\" bgcolor=\""); //$NON-NLS-1$
		appendColor(pageProlog, bgRGB);
		pageProlog.append("\">"); //$NON-NLS-1$
	}

	private static void appendColor(StringBuffer buffer, RGB rgb) {
		buffer.append('#');
		appendAsHexString(buffer, rgb.red);
		appendAsHexString(buffer, rgb.green);
		appendAsHexString(buffer, rgb.blue);
	}

	private static void appendAsHexString(StringBuffer buffer, int intValue) {
		String hexValue= Integer.toHexString(intValue);
		if (hexValue.length() == 1)
			buffer.append('0');
		buffer.append(hexValue);
	}

	public static void insertStyles(StringBuffer buffer, String[] styles) {
		if (styles == null || styles.length == 0)
			return;

		StringBuffer styleBuf= new StringBuffer(10 * styles.length);
		for (int i= 0; i < styles.length; i++) {
			styleBuf.append(" style=\""); //$NON-NLS-1$
			styleBuf.append(styles[i]);
			styleBuf.append('"');
		}

		// Find insertion index
		// a) within existing body tag with trailing space
		int index= buffer.indexOf("<body "); //$NON-NLS-1$
		if (index != -1) {
			buffer.insert(index+5, styleBuf);
			return;
		}

		// b) within existing body tag without attributes
		index= buffer.indexOf("<body>"); //$NON-NLS-1$
		if (index != -1) {
			buffer.insert(index+5, ' ');
			buffer.insert(index+6, styleBuf);
			return;
		}
	}

	private static void appendStyleSheet(StringBuffer buffer, String styleSheet) {
		if (styleSheet == null)
			return;
		
		// workaround for https://bugs.eclipse.org/318243
		StringBuffer fg= new StringBuffer();
		appendColor(fg, FG_COLOR_RGB);
		styleSheet= styleSheet.replaceAll("InfoText", fg.toString()); //$NON-NLS-1$
		StringBuffer bg= new StringBuffer();
		appendColor(bg, BG_COLOR_RGB);
		styleSheet= styleSheet.replaceAll("InfoBackground", bg.toString()); //$NON-NLS-1$

		buffer.append("<head><style CHARSET=\"ISO-8859-1\" TYPE=\"text/css\">"); //$NON-NLS-1$
		buffer.append(styleSheet);
		buffer.append("</style></head>"); //$NON-NLS-1$
	}

	private static void appendStyleSheetURL(StringBuffer buffer, URL styleSheetURL) {
		if (styleSheetURL == null)
			return;

		buffer.append("<head>"); //$NON-NLS-1$

		buffer.append("<LINK REL=\"stylesheet\" HREF= \""); //$NON-NLS-1$
		buffer.append(styleSheetURL);
		buffer.append("\" CHARSET=\"ISO-8859-1\" TYPE=\"text/css\">"); //$NON-NLS-1$

		buffer.append("</head>"); //$NON-NLS-1$
	}

	public static void insertPageProlog(StringBuffer buffer, int position) {
		StringBuffer pageProlog= new StringBuffer(60);
		pageProlog.append("<html>"); //$NON-NLS-1$
		appendColors(pageProlog, FG_COLOR_RGB, BG_COLOR_RGB);
		buffer.insert(position,  pageProlog.toString());
	}

	public static void insertPageProlog(StringBuffer buffer, int position, URL styleSheetURL) {
		StringBuffer pageProlog= new StringBuffer(300);
		pageProlog.append("<html>"); //$NON-NLS-1$
		appendStyleSheetURL(pageProlog, styleSheetURL);
		appendColors(pageProlog, FG_COLOR_RGB, BG_COLOR_RGB);
		buffer.insert(position,  pageProlog.toString());
	}

	public static void insertPageProlog(StringBuffer buffer, int position, String styleSheet) {
		insertPageProlog(buffer, position, null, null, styleSheet);
	}

	public static void addPageProlog(StringBuffer buffer) {
		insertPageProlog(buffer, buffer.length());
	}

	public static void addPageEpilog(StringBuffer buffer) {
		buffer.append("</body></html>"); //$NON-NLS-1$
	}

	public static void startBulletList(StringBuffer buffer) {
		buffer.append("<ul>"); //$NON-NLS-1$
	}

	public static void endBulletList(StringBuffer buffer) {
		buffer.append("</ul>"); //$NON-NLS-1$
	}

	public static void addBullet(StringBuffer buffer, String bullet) {
		if (bullet != null) {
			buffer.append("<li>"); //$NON-NLS-1$
			buffer.append(bullet);
			buffer.append("</li>"); //$NON-NLS-1$
		}
	}

	public static void addSmallHeader(StringBuffer buffer, String header) {
		if (header != null) {
			buffer.append("<h5>"); //$NON-NLS-1$
			buffer.append(header);
			buffer.append("</h5>"); //$NON-NLS-1$
		}
	}

	public static void addParagraph(StringBuffer buffer, String paragraph) {
		if (paragraph != null) {
			buffer.append("<p>"); //$NON-NLS-1$
			buffer.append(paragraph);
		}
	}

	public static void addParagraph(StringBuffer buffer, Reader paragraphReader) {
		if (paragraphReader != null)
			addParagraph(buffer, read(paragraphReader));
	}

	/**
	 * Replaces the following style attributes of the font definition of the <code>html</code>
	 * element:
	 * <ul>
	 * <li>font-size</li>
	 * <li>font-weight</li>
	 * <li>font-style</li>
	 * <li>font-family</li>
	 * </ul>
	 * The font's name is used as font family, a <code>sans-serif</code> default font family is
	 * appended for the case that the given font name is not available.
	 * <p>
	 * If the listed font attributes are not contained in the passed style list, nothing happens.
	 * </p>
	 *
	 * @param styles CSS style definitions
	 * @param fontData the font information to use
	 * @return the modified style definitions
	 * @since 3.3
	 */
	public static String convertTopLevelFont(String styles, FontData fontData) {
		boolean bold= (fontData.getStyle() & SWT.BOLD) != 0;
		boolean italic= (fontData.getStyle() & SWT.ITALIC) != 0;
		String size= Integer.toString(fontData.getHeight()) + UNIT;
		String family= "'" + fontData.getName() + "',sans-serif"; //$NON-NLS-1$ //$NON-NLS-2$
		
		styles= styles.replaceFirst("(html\\s*\\{.*(?:\\s|;)font-size:\\s*)\\d+pt(\\;?.*\\})", "$1" + size + "$2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		styles= styles.replaceFirst("(html\\s*\\{.*(?:\\s|;)font-weight:\\s*)\\w+(\\;?.*\\})", "$1" + (bold ? "bold" : "normal") + "$2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		styles= styles.replaceFirst("(html\\s*\\{.*(?:\\s|;)font-style:\\s*)\\w+(\\;?.*\\})", "$1" + (italic ? "italic" : "normal") + "$2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		styles= styles.replaceFirst("(html\\s*\\{.*(?:\\s|;)font-family:\\s*).+?(;.*\\})", "$1" + family + "$2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return styles;
	}
}
