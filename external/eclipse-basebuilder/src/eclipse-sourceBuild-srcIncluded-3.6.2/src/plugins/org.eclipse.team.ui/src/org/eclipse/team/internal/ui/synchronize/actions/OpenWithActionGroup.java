/*******************************************************************************
 * Copyright (c) 2000, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.synchronize.actions;

import org.eclipse.core.resources.IResource;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.team.internal.ui.TeamUIMessages;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.ui.synchronize.*;
import org.eclipse.ui.IWorkbenchSite;
import org.eclipse.ui.actions.ActionGroup;
import org.eclipse.ui.actions.OpenWithMenu;

/**
 * This is the action group for the open actions. It contains open
 * actions for 
 */
public class OpenWithActionGroup extends ActionGroup {

	private OpenFileInSystemEditorAction openFileAction;
	private OpenInCompareAction openInCompareAction;
	private final boolean includeOpenInCompare;
	private final ISynchronizePageConfiguration configuration;

	public OpenWithActionGroup(ISynchronizePageConfiguration configuration, boolean includeOpenInCompare) {
		this.configuration = configuration;
		this.includeOpenInCompare = includeOpenInCompare;
		makeActions();
	}

	protected void makeActions() {
		IWorkbenchSite ws = getSite().getWorkbenchSite();
		if (ws != null) {
			openFileAction = new OpenFileInSystemEditorAction(ws.getPage());
			if (includeOpenInCompare)
				openInCompareAction = new OpenInCompareAction(configuration);
		}
	}

	private ISynchronizeParticipant getParticipant() {
		return configuration.getParticipant();
	}

	private ISynchronizePageSite getSite() {
		return configuration.getSite();
	}

	public void fillContextMenu(IMenuManager menu, String groupId) {
		ISelection selection = getSite().getSelectionProvider().getSelection();
		if (selection instanceof IStructuredSelection && !hasFileMenu(menu)) {
			fillOpenWithMenu(menu, groupId, (IStructuredSelection)selection);
		}
	}

	private boolean hasFileMenu(IMenuManager menu) {
		return menu.find(openFileAction.getId()) != null;
	}

	/**
	 * Adds the OpenWith submenu to the context menu.
	 * 
	 * @param menu the context menu
	 * @param selection the current selection
	 */
	private void fillOpenWithMenu(IMenuManager menu, String groupId, IStructuredSelection selection) {

        // Only supported if at least one file is selected.
        if (selection == null || selection.size() < 1)
            return;
        Object[] elements = selection.toArray();
        IResource resources[] = Utils.getResources(elements);       
		if (resources.length == 0) {
			if (openInCompareAction != null) {
				// We can still show the compare editor open if the element has
				// a compare input
				if (elements.length > 0) {
					ISynchronizeParticipant participant = getParticipant();
					if (participant instanceof ModelSynchronizeParticipant) {
						ModelSynchronizeParticipant msp = (ModelSynchronizeParticipant) participant;
						boolean allElementsHaveCompareInput = true;
						for (int i = 0; i < elements.length; i++) {
							if (!msp.hasCompareInputFor(elements[i])) {
								allElementsHaveCompareInput = false;
								break;
							}
						}
						if (allElementsHaveCompareInput) {
							menu.appendToGroup(groupId, openInCompareAction);
						}
					}
				}
			}
			return;
		}
        
        if (elements.length != resources.length){
        	// Only supported if all the items are resources.
        	return;
        }
        
		for (int i = 0; i < resources.length; i++) {
			if (resources[i].getType() != IResource.FILE) {
				// Only supported if all the items are files.
				return;
			}
		}
        
        if (openInCompareAction != null) {
            menu.appendToGroup(groupId, openInCompareAction);
        }
        
        for (int i = 0; i < resources.length; i++) {
            if (!resources[i].exists()) {
                // Only support non-compare actions if all files exist.
                return;
            }
        }
        
		if (openFileAction != null) {
			openFileAction.selectionChanged(selection);
			menu.appendToGroup(groupId, openFileAction);
		}
        
        if (resources.length == 1) {
            // Only support the "Open With..." submenu if exactly one file is selected.
            IWorkbenchSite ws = getSite().getWorkbenchSite();
            if (ws != null) {
                MenuManager submenu =
                    new MenuManager(TeamUIMessages.OpenWithActionGroup_0); 
                submenu.add(new OpenWithMenu(ws.getPage(), resources[0]));
                menu.appendToGroup(groupId, submenu);
            }
        }
    }

	public void openInCompareEditor() {
		if (openInCompareAction != null)
			openInCompareAction.run();		
	}
}
