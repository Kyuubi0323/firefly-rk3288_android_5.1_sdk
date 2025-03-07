/*******************************************************************************
 * Copyright (c) 2007, 2009 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Patrick Chuong (Texas Instruments) - Improve usability of the breakpoint view (Bug 238956)
 *******************************************************************************/
package org.eclipse.debug.internal.ui.elements.adapters;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.internal.ui.model.elements.ViewerInputProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;
import org.eclipse.debug.ui.IDebugUIConstants;

/**
 * Default input provider supplies the expression manager as input to the 
 * expression view.
 * 
 * @since 3.4
 */
public class DefaultViewerInputProvider extends ViewerInputProvider {

	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.model.elements.ViewerInputProvider#getViewerInput(java.lang.Object, org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext, org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate)
	 */
	protected Object getViewerInput(Object source, IPresentationContext context, IViewerUpdate update) throws CoreException {	    
		if (IDebugUIConstants.ID_BREAKPOINT_VIEW.equals(context.getId())) {	  			
			DefaultBreakpointsViewInput input = new DefaultBreakpointsViewInput(context);
			return input;
		}	    	
		
		return DebugPlugin.getDefault().getExpressionManager();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.model.elements.ViewerInputProvider#supportsContextId(java.lang.String)
	 */
	protected boolean supportsContextId(String id) {
		return IDebugUIConstants.ID_EXPRESSION_VIEW.equals(id) ||
			IDebugUIConstants.ID_BREAKPOINT_VIEW.equals(id);
	}

}
