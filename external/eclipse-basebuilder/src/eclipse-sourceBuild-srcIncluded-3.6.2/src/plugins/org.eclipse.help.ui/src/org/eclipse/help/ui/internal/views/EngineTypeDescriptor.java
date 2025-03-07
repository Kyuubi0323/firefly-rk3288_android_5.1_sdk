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
package org.eclipse.help.ui.internal.views;

import java.util.Dictionary;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.help.search.ISearchEngine;
import org.eclipse.help.search.ISearchScope;
import org.eclipse.help.ui.ISearchScopeFactory;
import org.eclipse.help.ui.RootScopePage;
import org.eclipse.help.ui.internal.HelpUIPlugin;
import org.eclipse.help.ui.internal.HelpUIResources;
import org.eclipse.help.ui.internal.IHelpUIConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;

/**
 * Descriptor for a federated search engine participant.
 */
public class EngineTypeDescriptor {
	private IConfigurationElement config;
	private Image image;
	private ISearchScopeFactory factory;
	/**
	 * 
	 */
	public EngineTypeDescriptor(IConfigurationElement config) {
		this.config = config;
	}
	public IConfigurationElement getConfig() {
		return config;
	}
	public IConfigurationElement [] getPages() {
		return config.getChildren("subpage"); //$NON-NLS-1$
	}
	
	public String getLabel() {
		return config.getAttribute(IHelpUIConstants.ATT_LABEL);		
	}
	public String getId() {
		return config.getAttribute(IHelpUIConstants.ATT_ID);
	}

	public Image getIconImage() {
		if (image!=null)
			return image;
		String icon = config.getAttribute(IHelpUIConstants.ATT_ICON);
		if (icon!=null) {
			String bundleId = config.getContributor().getName();
			HelpUIResources.getImageDescriptor(bundleId, icon);
			return HelpUIResources.getImage(icon);
		}
		image = HelpUIResources.getImage(IHelpUIConstants.IMAGE_HELP_SEARCH);
		return image;
	}
	public String getDescription() {
		String desc = null;
		IConfigurationElement [] children = config.getChildren(IHelpUIConstants.TAG_DESC);
		if (children.length==1) 
			desc = children[0].getValue();
		return desc;
	}
	public ImageDescriptor getImageDescriptor() {
		ImageDescriptor desc=null;
		String icon = config.getAttribute(IHelpUIConstants.ATT_ICON);
		if (icon!=null)
			desc = HelpUIResources.getImageDescriptor(icon);
		else
			desc = HelpUIResources.getImageDescriptor(IHelpUIConstants.IMAGE_HELP_SEARCH);
		return desc;
	}
	public RootScopePage createRootPage(String scopeSetName) {
		try {
			Object obj = config.createExecutableExtension(IHelpUIConstants.ATT_PAGE_CLASS);
			if (obj instanceof RootScopePage) {
				RootScopePage page = (RootScopePage)obj;
				return page;
			}
			return null;
		}
		catch (CoreException e) {
			return null;
		}
	}
	public ISearchEngine createEngine() {
		String eclass = config.getAttribute(IHelpUIConstants.ATT_CLASS);
		if (eclass!=null) {
			try {
				Object obj = config.createExecutableExtension(IHelpUIConstants.ATT_CLASS);
				if (obj instanceof ISearchEngine)
					return (ISearchEngine)obj;
			}
			catch (CoreException e) {
				HelpUIPlugin.logError("Engine " + eclass + " cannot be instantiated", null); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return null;
	}
	
	public ISearchScope createSearchScope(IPreferenceStore store, String engineId, Dictionary parameters) {
		if (factory==null) {
			String fclass = config.getAttribute(IHelpUIConstants.ATT_SCOPE_FACTORY);
			if (fclass!=null) {
				try {
					Object obj = config.createExecutableExtension(IHelpUIConstants.ATT_SCOPE_FACTORY);
					if (obj instanceof ISearchScopeFactory) {
						factory = (ISearchScopeFactory)obj;
					}
				}
				catch (CoreException e) {
                    HelpUIPlugin.logError("Scope factory " + fclass + " cannot be instantiated", null); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
		if (factory!=null)
			return factory.createSearchScope(store, engineId, parameters);
		return null;
	}
}
