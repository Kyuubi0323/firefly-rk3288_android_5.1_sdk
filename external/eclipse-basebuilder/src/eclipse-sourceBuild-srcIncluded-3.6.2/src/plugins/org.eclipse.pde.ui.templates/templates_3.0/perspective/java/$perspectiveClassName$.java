/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package $packageName$;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.jdt.ui.JavaUI;


/**
 *  This class is meant to serve as an example for how various contributions 
 *  are made to a perspective. Note that some of the extension point id's are
 *  referred to as API constants while others are hardcoded and may be subject 
 *  to change. 
 */
public class $perspectiveClassName$ implements IPerspectiveFactory {

	private IPageLayout factory;

	public $perspectiveClassName$() {
		super();
	}

	public void createInitialLayout(IPageLayout factory) {
		this.factory = factory;
		addViews();
% if(actionSets)	
		addActionSets();
% endif
% if(newWizardShortcuts)
		addNewWizardShortcuts();
% endif
% if(perspectiveShortcuts)	
		addPerspectiveShortcuts();
% endif
% if(showViewShortcuts)	
		addViewShortcuts();
% endif
	}

	private void addViews() {
		// Creates the overall folder layout. 
		// Note that each new Folder uses a percentage of the remaining EditorArea.
		
		IFolderLayout bottom =
			factory.createFolder(
				"bottomRight", //$NON-NLS-1$
				IPageLayout.BOTTOM,
				0.75f,
				factory.getEditorArea());
		bottom.addView(IPageLayout.ID_PROBLEM_VIEW);
		bottom.addView("org.eclipse.team.ui.GenericHistoryView"); //$NON-NLS-1$
		bottom.addPlaceholder(IConsoleConstants.ID_CONSOLE_VIEW);

		IFolderLayout topLeft =
			factory.createFolder(
				"topLeft", //$NON-NLS-1$
				IPageLayout.LEFT,
				0.25f,
				factory.getEditorArea());
		topLeft.addView(IPageLayout.ID_RES_NAV);
		topLeft.addView("org.eclipse.jdt.junit.ResultView"); //$NON-NLS-1$
		
		factory.addFastView("org.eclipse.team.ccvs.ui.RepositoriesView",0.50f); //$NON-NLS-1$
		factory.addFastView("org.eclipse.team.sync.views.SynchronizeView", 0.50f); //$NON-NLS-1$
	}

% if(actionSets)	
	private void addActionSets() {
		factory.addActionSet("org.eclipse.debug.ui.launchActionSet"); //$NON-NLS-1$
		factory.addActionSet("org.eclipse.debug.ui.debugActionSet"); //$NON-NLS-1$
		factory.addActionSet("org.eclipse.debug.ui.profileActionSet"); //$NON-NLS-1$
		factory.addActionSet("org.eclipse.jdt.debug.ui.JDTDebugActionSet"); //$NON-NLS-1$
		factory.addActionSet("org.eclipse.jdt.junit.JUnitActionSet"); //$NON-NLS-1$
		factory.addActionSet("org.eclipse.team.ui.actionSet"); //$NON-NLS-1$
		factory.addActionSet("org.eclipse.team.cvs.ui.CVSActionSet"); //$NON-NLS-1$
		factory.addActionSet("org.eclipse.ant.ui.actionSet.presentation"); //$NON-NLS-1$
		factory.addActionSet(JavaUI.ID_ACTION_SET);
		factory.addActionSet(JavaUI.ID_ELEMENT_CREATION_ACTION_SET);
		factory.addActionSet(IPageLayout.ID_NAVIGATE_ACTION_SET); //$NON-NLS-1$
	}

% endif	
% if(perspectiveShortcuts)	
	private void addPerspectiveShortcuts() {
		factory.addPerspectiveShortcut("org.eclipse.team.ui.TeamSynchronizingPerspective"); //$NON-NLS-1$
		factory.addPerspectiveShortcut("org.eclipse.team.cvs.ui.cvsPerspective"); //$NON-NLS-1$
		factory.addPerspectiveShortcut("org.eclipse.ui.resourcePerspective"); //$NON-NLS-1$
	}

% endif
% if(newWizardShortcuts)
	private void addNewWizardShortcuts() {
		factory.addNewWizardShortcut("org.eclipse.team.cvs.ui.newProjectCheckout");//$NON-NLS-1$
		factory.addNewWizardShortcut("org.eclipse.ui.wizards.new.folder");//$NON-NLS-1$
		factory.addNewWizardShortcut("org.eclipse.ui.wizards.new.file");//$NON-NLS-1$
	}

% endif	
% if(showViewShortcuts)	
	private void addViewShortcuts() {
		factory.addShowViewShortcut("org.eclipse.ant.ui.views.AntView"); //$NON-NLS-1$
		factory.addShowViewShortcut("org.eclipse.team.ccvs.ui.AnnotateView"); //$NON-NLS-1$
		factory.addShowViewShortcut("org.eclipse.pde.ui.DependenciesView"); //$NON-NLS-1$
		factory.addShowViewShortcut("org.eclipse.jdt.junit.ResultView"); //$NON-NLS-1$
		factory.addShowViewShortcut("org.eclipse.team.ui.GenericHistoryView"); //$NON-NLS-1$
		factory.addShowViewShortcut(IConsoleConstants.ID_CONSOLE_VIEW);
		factory.addShowViewShortcut(JavaUI.ID_PACKAGES);
		factory.addShowViewShortcut(IPageLayout.ID_RES_NAV);
		factory.addShowViewShortcut(IPageLayout.ID_PROBLEM_VIEW);
		factory.addShowViewShortcut(IPageLayout.ID_OUTLINE);
	}

% endif
}
