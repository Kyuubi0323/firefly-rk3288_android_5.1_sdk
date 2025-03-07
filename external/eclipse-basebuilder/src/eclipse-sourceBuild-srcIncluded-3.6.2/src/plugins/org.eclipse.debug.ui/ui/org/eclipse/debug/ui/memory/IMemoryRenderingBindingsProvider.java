/*******************************************************************************
 * Copyright (c) 2004, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.ui.memory;

import org.eclipse.debug.core.model.IMemoryBlock;


/**
 * A rendering bindings provider provides rendering type bindings for a memory block.
 * <p>
 * By default, bindings for a memory block are provided by the memory rendering
 * manager. However, a client can provide dynamic renderings for a memory block
 * by contributing a dynamic rendering binding in the <code>renderingBindings</code>
 * element of a <code>memoryRenderings</code> extension.
 * </p>
 * <p>
 * Clients contributing dynamic rendering bindings are intended to implement this
 * interface.
 * </p>
 * @since 3.1
 */
public interface IMemoryRenderingBindingsProvider {
	
	/**
     * Returns all rendering types bound to the given memory block.
     * This includes default and primary rendering types.
     * 
     * @param block memory block
	 * @return all rendering types bound to the given memory block
	 */
	public IMemoryRenderingType[] getRenderingTypes(IMemoryBlock block);
    
    /**
     * Returns default rendering types bound to the given memory block, 
     * possibly empty.
     * 
     * @param block memory block
     * @return default rendering types bound to the given memory block, 
     * possibly empty
     */
    public IMemoryRenderingType[] getDefaultRenderingTypes(IMemoryBlock block);
    
    /**
     * Returns the primary rendering type bound to the given memory block,
     * or <code>null</code> if none.
     * 
     * @param block memory block
     * @return the primary rendering type bound to the given memory block,
     * or <code>null</code> if none
     */
    public IMemoryRenderingType getPrimaryRenderingType(IMemoryBlock block);
		
    /**
     * Adds a listener to this binding provider.  The listener will be notified
     * when rendering bindings change.
     * <p>
     * Has no affect if an identical listener is already registered.
     * </p>
     * @param listener listener to add
     */
	public void addListener(IMemoryRenderingBindingsListener listener);
	
    /**
     * Removes a listener from this binding provider.  
     * <p>
     * Has no affect if an identical listener is not already registered.
     * </p>
     * @param listener listener to remove
     */
	public void removeListener(IMemoryRenderingBindingsListener listener);
}
