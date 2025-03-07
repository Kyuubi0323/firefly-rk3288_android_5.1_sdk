/*******************************************************************************
 * Copyright (c) 2006, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Pawel Piech (Wind River) - added a breadcrumb mode to Debug view (Bug 252677)
 *******************************************************************************/
package org.eclipse.debug.internal.ui.viewers.model.provisional;

import org.eclipse.debug.internal.ui.viewers.model.InternalTreeModelViewer;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.ViewerLabel;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IMemento;

/**
 * A tree viewer for a model.
 * <p>
 * Style flags supported by this viewer are the same as for {@link TreeViewer}, 
 * except:
 * <ul>
 * <li>SWT.VIRTUAL - Indicates that the viewer should be in lazy mode.  This 
 * flag is required when creating this viewer.
 * <li>SWT.POP_UP - Indicates that the viewer is used in a popup window, and it
 * should ignore requests from the model to select, expand, or collapse tree 
 * elements.</li>
 * </ul>
 * </p>
 * @since 3.3 
 * @noextend Clients may instantiate this class. Clients may not subclass this class.
 */
public class TreeModelViewer extends InternalTreeModelViewer {

	public TreeModelViewer(Composite parent, int style, IPresentationContext context) {
		super(parent, style, context);
	}
	
	/**
	 * Returns this viewer's presentation context.
	 * 
	 * @return presentation context
	 */
	public IPresentationContext getPresentationContext() {
		return super.getPresentationContext();
	}	

	/**
	 * Registers the given listener for model delta notification.
	 * 
	 * @param listener model delta listener
	 */
	public void addModelChangedListener(IModelChangedListener listener) {
		super.addModelChangedListener(listener); 
	}
	
	/**
	 * Unregisters the given listener from model delta notification.
	 * 
	 * @param listener model delta listener
	 */
	public void removeModelChangedListener(IModelChangedListener listener) {
		super.removeModelChangedListener(listener);
	}	
	
	/**
	 * Registers the specified listener for view update notifications.
	 * 
	 * @param listener listener
	 */
	public void addViewerUpdateListener(IViewerUpdateListener listener) {
		super.addViewerUpdateListener(listener);
	}
	
	/**
	 * Removes the specified listener from update notifications.
	 * 
	 * @param listener listener
	 */
	public void removeViewerUpdateListener(IViewerUpdateListener listener) {
		super.removeViewerUpdateListener(listener);
	}
		
	/**
	 * Returns whether columns can be toggled on/off for this viewer's current
	 * input element.
	 * 
	 * @return whether columns can be toggled on/off
	 */
	public boolean canToggleColumns() {
		return super.canToggleColumns();
	}	
	
	/**
	 * Returns the current column presentation for this viewer, or <code>null</code>
	 * if none.
	 * 
	 * @return column presentation or <code>null</code>
	 */
	public IColumnPresentation getColumnPresentation() {
		return super.getColumnPresentation();
	}	
	
	/**
	 * Returns identifiers of the visible columns in this viewer, or <code>null</code>
	 * if there are currently no columns.
	 *  
	 * @return visible columns identifiers or <code>null</code>
	 */
	public String[] getVisibleColumns() {
		return super.getVisibleColumns();
	}  	
	
	/**
	 * Initializes viewer state from the memento
	 * 
	 * @param memento
	 */
	public void initState(IMemento memento) {
		super.initState(memento);
	}
	
	/**
	 * Save viewer state into the given memento.
	 * 
	 * @param memento
	 */
	public void saveState(IMemento memento) {
		super.saveState(memento);
	}
	
	/**
	 * Returns whether columns are being displayed currently.
	 * 
	 * @return
	 */
	public boolean isShowColumns() {
		return super.isShowColumns();
	}	
	
	/**
	 * Resets any persisted column size for the given columns
	 * 
	 * @param columnIds array of column identifiers
	 */
	public void resetColumnSizes(String[] columnIds) {
		super.resetColumnSizes(columnIds);
	}
	
	/**
	 * Toggles columns on/off for the current column presentation, if any.
	 * 
	 * @param show whether to show columns if the current input supports
	 * 	columns
	 */
	public void setShowColumns(boolean show) {
		super.setShowColumns(show);
	}	
	
	/**
	 * Sets the visible columns for this viewer. Id's correspond to 
	 * column identifiers from a column presentation. Use <code>null</code>
	 * or an empty collection to display default columns for the current
	 * column presentation. Only effects the current column presentation.
	 * 
	 * @param ids column identifiers or <code>null</code>
	 */
	public void setVisibleColumns(String[] ids) {
		super.setVisibleColumns(ids);
	}		
	
    public ViewerLabel getElementLabel(TreePath path, String columnId) {
        return super.getElementLabel(path, columnId);
    }
}
