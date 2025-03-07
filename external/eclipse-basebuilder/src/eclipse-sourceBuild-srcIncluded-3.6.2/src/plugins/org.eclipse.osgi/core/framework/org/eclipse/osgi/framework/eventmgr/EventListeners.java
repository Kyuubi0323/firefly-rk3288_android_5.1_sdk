/*******************************************************************************
 * Copyright (c) 2003, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.framework.eventmgr;

import java.util.Set;

/**
 * This class manages a list of listeners. 
 * 
 * Listeners may be added or removed as necessary.
 * 
 * This class uses identity for comparison, not equals.
 * 
 * @since 3.1
 * @deprecated As of 3.5. Replaced by CopyOnWriteIdentityMap.
 * @noextend This class is not intended to be subclassed by clients.
 */
public class EventListeners {
	private final CopyOnWriteIdentityMap list = new CopyOnWriteIdentityMap();

	/**
	 * Creates an empty listener list.
	 *
	 */
	public EventListeners() {
		super();
	}

	/**
	 * Creates an empty listener list.
	 *
	 * @param capacity This argument is ignored.
	 */
	public EventListeners(int capacity) {
		this();
	}

	/**
	 * Add a listener to the list.
	 * If a listener object is already in the list, then it is replaced.
	 * This method calls the put method.
	 *
	 * @param listener This is the listener object to be added to the list.
	 * @param listenerObject This is an optional listener-specific object.
	 * This object will be passed to the EventDispatcher along with the listener
	 * when the listener is to be called. This may be null
	 * @throws IllegalArgumentException If listener is null.
	 */
	public void addListener(Object listener, Object listenerObject) {
		list.put(listener, listenerObject);
	}

	/**
	 * Remove a listener from the list.
	 * This method calls the remove method.
	 *
	 * @param listener This is the listener object to be removed from the list.
	 * @throws IllegalArgumentException If listener is null.
	 */
	public void removeListener(Object listener) {
		list.remove(listener);
	}

	/**
	 * Remove all listeners from the list.
	 * 
	 * This method calls the clear method.
	 */
	public void removeAllListeners() {
		list.clear();
	}

	/**
	 * Get the entry Set from the internal CopyOnWriteIdentityMap.
	 * @return The entry Set.
	 */
	Set entrySet() {
		return list.entrySet();
	}
}
