/*******************************************************************************
 * Copyright (c) 2000, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Pawel Piech - Wind River - adapted to use in Debug view
 *******************************************************************************/
package org.eclipse.debug.internal.ui.views.launch;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;

/**
 * Fake part to used to create the breadcrumb page.
 * 
 * @since 3.5
 * @see LaunchView#fDefaultPageRec
 */
class BreadcrumbWorkbenchPart implements IWorkbenchPart {

    private IWorkbenchPartSite fSite = null;
    
    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    public boolean equals(Object obj) {
        return (obj instanceof BreadcrumbWorkbenchPart);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    public int hashCode() {
        return getClass().hashCode();
    }

    /**
     * Constructs a part for the given console that binds to the given
     * site
     */
    public BreadcrumbWorkbenchPart(IWorkbenchPartSite site) {
        fSite = site;
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#addPropertyListener(org.eclipse.ui.IPropertyListener)
     */
    public void addPropertyListener(IPropertyListener listener) {
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#createPartControl(org.eclipse.swt.widgets.Composite)
     */
    public void createPartControl(Composite parent) {
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#dispose()
     */
    public void dispose() {
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#getSite()
     */
    public IWorkbenchPartSite getSite() {
        return fSite;
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#getTitle()
     */
    public String getTitle() {
        return ""; //$NON-NLS-1$
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#getTitleImage()
     */
    public Image getTitleImage() {
        return null;
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#getTitleToolTip()
     */
    public String getTitleToolTip() {
        return ""; //$NON-NLS-1$
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#removePropertyListener(org.eclipse.ui.IPropertyListener)
     */
    public void removePropertyListener(IPropertyListener listener) {
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPart#setFocus()
     */
    public void setFocus() {
    }

    /* (non-Javadoc)
     * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
     */
    public Object getAdapter(Class adapter) {
        return null;
    }
}

