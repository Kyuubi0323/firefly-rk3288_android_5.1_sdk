/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.synchronize;

import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.ui.views.navigator.ResourceSorter;

/**
 * This class sorts <code>SyncInfoModelElement</code> instances.
 * It is not thread safe so it should not be reused between views.
 */
public class SynchronizeModelElementSorter extends ResourceSorter {
			
	public SynchronizeModelElementSorter() {
		super(ResourceSorter.NAME);
	}

	/* (non-Javadoc)
	 * Method declared on ViewerSorter.
	 */
	public int compare(Viewer viewer, Object o1, Object o2) {
		IResource resource1 = getResource(o1);
		IResource resource2 = getResource(o2);
		int result;
		if (resource1 != null && resource2 != null) {
			result = super.compare(viewer, resource1, resource2);
		} else {
			result = super.compare(viewer, o1, o2);
		}
		return result;
	}

	protected IResource getResource(Object obj) {
		IResource[] resources = Utils.getResources(new Object[] {obj});
		return resources.length == 1 ? resources[0] : null;
	}
}
