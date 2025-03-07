/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.actions;

import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.team.internal.ui.*;
import org.eclipse.team.internal.ui.wizards.ImportProjectSetOperation;
import org.eclipse.ui.*;
import org.eclipse.ui.actions.ActionDelegate;

public class ImportProjectSetAction extends ActionDelegate implements IObjectActionDelegate {
	
	private IStructuredSelection fSelection;
	
	public void run(IAction action) {
		final Shell shell= Display.getDefault().getActiveShell();
		try {
			new ProgressMonitorDialog(shell).run(true, true, new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
					Iterator iterator= fSelection.iterator();
					while (iterator.hasNext()) {
						IFile file = (IFile) iterator.next();
						if (isRunInBackgroundPreferenceOn()) {
							ImportProjectSetOperation op = new ImportProjectSetOperation(null, file.getLocation().toString(), new IWorkingSet[0]);
							op.run();
						} else { 
							ProjectSetImporter.importProjectSet(file.getLocation().toString(), shell, monitor);
						}
					}
				}
			});
		} catch (InvocationTargetException exception) {
			ErrorDialog.openError(shell, null, null, new Status(IStatus.ERROR, TeamUIPlugin.PLUGIN_ID, IStatus.ERROR, TeamUIMessages.ImportProjectSetAction_0, exception.getTargetException()));
		} catch (InterruptedException exception) {
		}
	}
	
	public void selectionChanged(IAction action, ISelection sel) {
		if (sel instanceof IStructuredSelection) {
			fSelection= (IStructuredSelection) sel;
		}
	}

	public void setActivePart(IAction action, IWorkbenchPart targetPart) {
	}

	private static boolean isRunInBackgroundPreferenceOn() {
		return TeamUIPlugin.getPlugin().getPreferenceStore().getBoolean(
				IPreferenceIds.RUN_IMPORT_IN_BACKGROUND);
	}
	
}
