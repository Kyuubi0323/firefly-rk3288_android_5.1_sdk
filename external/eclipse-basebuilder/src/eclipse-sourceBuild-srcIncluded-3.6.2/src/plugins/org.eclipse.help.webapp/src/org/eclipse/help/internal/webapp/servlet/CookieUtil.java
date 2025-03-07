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
package org.eclipse.help.internal.webapp.servlet;

import java.io.*;
import java.util.HashSet;
import java.util.Iterator;

import javax.servlet.http.*;

import org.eclipse.help.internal.webapp.*;
import org.eclipse.help.internal.webapp.data.*;

/**
 * Utilities for working with cookies
 * 
 * @since 3.0
 */
public class CookieUtil {
	private static final int COOKIE_LIFE = 5 * 365 * 24 * 60 * 60;
	private static final int MAX_COOKIE_PAYLOAD = 4096
			- "wset_contents01=".length() - "81920<".length() - 1; //$NON-NLS-1$ //$NON-NLS-2$

	/**
	 * @return null or String
	 */
	public static String getCookieValue(String name, HttpServletRequest request) {
		String ret = null;
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (int i = 0; i < cookies.length; i++) {
				if (name.equals(cookies[i].getName())) {
					ret = cookies[i].getValue();
					break;
				}
			}
		}
		if (HelpWebappPlugin.DEBUG_WORKINGSETS) {
			System.out.println("CookieUtil.getCookieValue(" //$NON-NLS-1$
					+ name + ", " //$NON-NLS-1$
					+ request.getRequestURI() + ") returning " //$NON-NLS-1$
					+ ret);
		}
		return ret;
	}
	public static void setCookieValue(String name, String value,
			HttpServletRequest request, HttpServletResponse response) {
		Cookie cookie = new Cookie(name, value);
		cookie.setMaxAge(COOKIE_LIFE);
		cookie.setPath(getCookiePath(request)); 
		response.addCookie(cookie);
		if (HelpWebappPlugin.DEBUG_WORKINGSETS) {
			System.out
					.println("CookieUtil.setCookieValue(" + name + ", " + value + ",...)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}
	protected static String getCookiePath(HttpServletRequest request) {
		String path = request.getContextPath() + '/';
		return path;
	}

	public static void deleteCookie(String name, HttpServletRequest request, HttpServletResponse response) {
		deleteCookieUsingPath(name, response, getCookiePath(request));
	}
	
	protected static void deleteCookieUsingPath(String name,
			HttpServletResponse response, String cookiePath) {
		Cookie cookie = new Cookie(name, ""); //$NON-NLS-1$
		cookie.setPath(cookiePath); 
		cookie.setMaxAge(0);
		response.addCookie(cookie);
	}
	
	public static void deleteObsoleteCookies(HttpServletRequest request, HttpServletResponse response) {
		Cookie[] cookies = request.getCookies();
		HashSet cookiesToKeep= new HashSet();
		HashSet cookiesToDelete = new HashSet();
		if (cookies != null) {
			for (int i = 0; i < cookies.length; i++) {
				    String name = cookies[i].getName();
				    if (isObsoleteCookie(name) || cookiesToKeep.contains(name)) {
				    	cookiesToDelete.add(name);
				    }
					cookiesToKeep.add(name); {				
				}
			}

			for (Iterator iter = cookiesToDelete.iterator(); iter.hasNext();) {
				String name = (String) iter.next();
				deleteCookieUsingPath(name, response, request.getContextPath() + "/advanced/"); //$NON-NLS-1$
				deleteCookieUsingPath(name, response, "/"); //$NON-NLS-1$
			}
		}
	}

	/*
	 * Is this one of the cookies that was used before Eclipse 3.6 of the 
	 * form wset_nn
	 */
	private static boolean isObsoleteCookie(String name) {
		final String WSET_PREFIX = "wset"; //$NON-NLS-1$
		if (!name.startsWith(WSET_PREFIX)) {
			return false;
		}
		for (int i = WSET_PREFIX.length(); i < name.length(); i++) {
			char suffixChar = name.charAt(i);
			if (suffixChar < '0' || suffixChar > '9') {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Saves string in multiple browser cookies. Cookies can store limited
	 * length string. This method will attemt to split string among multiple
	 * cookies. The following cookies will be set name1=length <substing1
	 * name2=substring2 ... namen=substringn
	 * 
	 * @param data
	 *            a string containing legal characters for cookie value
	 * @throws IOException
	 *             when data is too long.
	 */
	public static void saveString(String name, String data, int maxCookies,
			HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		int len = data.length();
		int n = len / MAX_COOKIE_PAYLOAD;
		if (n > maxCookies) {
			throw new IOException(WebappResources.getString(
					"CookieUtil.tooManyCookiesNeeded", UrlUtil.getLocaleObj( //$NON-NLS-1$
							request, response)));
		}
		for (int i = 1; i <= n; i++) {
			if (i == 1) {
				setCookieValue(name + "1", //$NON-NLS-1$
						len + "<" + data.substring(0, MAX_COOKIE_PAYLOAD), //$NON-NLS-1$
						request, response);
			} else {
				setCookieValue(name + i, data.substring(MAX_COOKIE_PAYLOAD
						* (i - 1), MAX_COOKIE_PAYLOAD * i), request, response);
			}
		}
		if (len % MAX_COOKIE_PAYLOAD > 0) {
			if (n == 0) {
				setCookieValue(name + "1", //$NON-NLS-1$
						len + "<" + data.substring(0, len), //$NON-NLS-1$
						request, response);
			} else {
				setCookieValue(name + (n + 1), data.substring(
						MAX_COOKIE_PAYLOAD * n, len), request, response);
			}
		}

		// if using less cookies than maximum, delete not needed cookies from
		// last time
		for (int i = n + 1; i <= maxCookies; i++) {
			if (i == n + 1 && len % MAX_COOKIE_PAYLOAD > 0) {
				continue;
			}
			if (getCookieValue(name + i, request) != null) {
				deleteCookie(name + i, request, response);
			} else {
				break;
			}
		}
	}
	/**
	 * @return null or String
	 */
	public static String restoreString(String name, HttpServletRequest request) {
		String value1 = CookieUtil.getCookieValue(name + "1", request); //$NON-NLS-1$
		if (value1 == null) {
			// no cookie
			return null;
		}
		String lengthAndSubstring1[] = value1.split("<"); //$NON-NLS-1$
		if (lengthAndSubstring1.length < 2) {
			return null;
		}
		int len = 0;
		try {
			len = Integer.parseInt(lengthAndSubstring1[0]);
		} catch (NumberFormatException nfe) {
			return null;
		}
		if (len <= 0) {
			return null;
		}
		StringBuffer data = new StringBuffer(len);
		data.append(lengthAndSubstring1[1]);
		int n = len / MAX_COOKIE_PAYLOAD;
		for (int i = 2; i <= n; i++) {
			String substring = CookieUtil.getCookieValue(name + i, request);
			if (substring == null) {
				return null;
			}
			data.append(substring);
		}
		if (len % MAX_COOKIE_PAYLOAD > 0 && n > 0) {
			String substring = CookieUtil.getCookieValue(name + (n + 1),
					request);
			if (substring == null) {
				return null;
			}
			data.append(substring);
		}

		if (data.length() != len) {
			return null;
		}

		return data.toString();
	}
}
