/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.wizards;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.operation.IRunnableContext;
import org.eclipse.team.internal.ui.*;
import org.eclipse.team.ui.TeamOperation;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.IWorkingSetManager;

public class ImportProjectSetOperation extends TeamOperation {

	private String psfFile;
	private IWorkingSet[] workingSets;

	/**
	 * Operation for importing a Team Project Set file
	 * 
	 * @param context
	 *            a runnable context
	 * @param psfFile
	 *            a psf file name
	 * @param workingSets
	 *            an array of working sets where imported project should be
	 *            added. If a working set doesn't exist it will be created. The
	 *            array cannot be <code>null</code>, pass an empty array if you
	 *            don't want to add projects to any working set.
	 */
	public ImportProjectSetOperation(IRunnableContext context, String psfFile,
			IWorkingSet[] workingSets) {
		super(context);
		this.psfFile = psfFile;
		this.workingSets = workingSets;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.operation.IRunnableWithProgress#run(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void run(IProgressMonitor monitor) throws InvocationTargetException,
			InterruptedException {
		PsfFilenameStore.remember(psfFile);
		IProject[] newProjects = ProjectSetImporter.importProjectSet(psfFile,
				getShell(), monitor);
		createWorkingSet(workingSets, newProjects);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.team.ui.TeamOperation#canRunAsJob()
	 */
	protected boolean canRunAsJob() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.team.ui.TeamOperation#getJobName()
	 */
	protected String getJobName() {
		return TeamUIMessages.ImportProjectSetMainPage_jobName;
	}
	
	private void createWorkingSet(IWorkingSet[] workingSets, IProject[] projects) {
		IWorkingSetManager manager = TeamUIPlugin.getPlugin().getWorkbench().getWorkingSetManager();
		String workingSetName;
		for (int i = 0; i < workingSets.length; i++) {
			workingSetName = workingSets[i].getName();
			IWorkingSet oldSet = manager.getWorkingSet(workingSetName);
			if (oldSet == null) {
				IWorkingSet newSet = manager.createWorkingSet(workingSetName, projects);
				manager.addWorkingSet(newSet);
			} else {
				//don't overwrite the old elements
				IAdaptable[] tempElements = oldSet.getElements();
				IAdaptable[] adaptedProjects = oldSet.adaptElements(projects);
				IAdaptable[] finalElementList = new IAdaptable[tempElements.length + adaptedProjects.length];
				System.arraycopy(tempElements, 0, finalElementList, 0, tempElements.length);
				System.arraycopy(adaptedProjects, 0,finalElementList, tempElements.length, adaptedProjects.length);
				oldSet.setElements(finalElementList);
			}	
		}
	}
}
