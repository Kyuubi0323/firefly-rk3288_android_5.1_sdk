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
package org.eclipse.jdt.internal.junit.ui;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.swt.widgets.Shell;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.texteditor.ITextEditor;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import org.eclipse.jdt.ui.JavaUI;

/**
 * Abstract Action for opening a Java editor.
 */
public abstract class OpenEditorAction extends Action {
	protected String fClassName;
	protected TestRunnerViewPart fTestRunner;
	private final boolean fActivate;

	protected OpenEditorAction(TestRunnerViewPart testRunner, String testClassName) {
		this(testRunner, testClassName, true);
	}

	public OpenEditorAction(TestRunnerViewPart testRunner, String className, boolean activate) {
		super(JUnitMessages.OpenEditorAction_action_label);
		fClassName= className;
		fTestRunner= testRunner;
		fActivate= activate;
	}

	/*
	 * @see IAction#run()
	 */
	public void run() {
		IEditorPart editor= null;
		try {
			IJavaElement element= findElement(getLaunchedProject(), fClassName);
			if (element == null) {
				MessageDialog.openError(getShell(),
					JUnitMessages.OpenEditorAction_error_cannotopen_title, JUnitMessages.OpenEditorAction_error_cannotopen_message);
				return;
			}
			editor= JavaUI.openInEditor(element, fActivate, false);
		} catch (CoreException e) {
			ErrorDialog.openError(getShell(), JUnitMessages.OpenEditorAction_error_dialog_title, JUnitMessages.OpenEditorAction_error_dialog_message, e.getStatus());
			return;
		}
		if (!(editor instanceof ITextEditor)) {
			fTestRunner.registerInfoMessage(JUnitMessages.OpenEditorAction_message_cannotopen);
			return;
		}
		reveal((ITextEditor)editor);
	}

	protected Shell getShell() {
		return fTestRunner.getSite().getShell();
	}

	/**
	 * @return the Java project, or <code>null</code>
	 */
	protected IJavaProject getLaunchedProject() {
		return fTestRunner.getLaunchedProject();
	}

	protected String getClassName() {
		return fClassName;
	}

	protected abstract IJavaElement findElement(IJavaProject project, String className) throws CoreException;

	protected abstract void reveal(ITextEditor editor);

	protected final IType findType(final IJavaProject project, String className) {
		final IType[] result= { null };
		final String dottedName= className.replace('$', '.'); // for nested classes...
		try {
			PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
					try {
						if (project == null) {
							int lastDot= dottedName.lastIndexOf('.');
							TypeNameMatchRequestor nameMatchRequestor= new TypeNameMatchRequestor() {
								public void acceptTypeNameMatch(TypeNameMatch match) {
									result[0]= match.getType();
								}
							};
							new SearchEngine().searchAllTypeNames(
									lastDot >= 0 ? dottedName.substring(0, lastDot).toCharArray() : null,
									SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
									(lastDot >= 0 ? dottedName.substring(lastDot + 1) : dottedName).toCharArray(),
									SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
									IJavaSearchConstants.TYPE,
									SearchEngine.createWorkspaceScope(),
									nameMatchRequestor,
									IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
									monitor);
						} else {
							result[0]= internalFindType(project, dottedName, new HashSet(), monitor);
						}
					} catch (JavaModelException e) {
						throw new InvocationTargetException(e);
					}
				}
			});
		} catch (InvocationTargetException e) {
			JUnitPlugin.log(e);
		} catch (InterruptedException e) {
			// user cancelled
		}
		return result[0];
	}

	private IType internalFindType(IJavaProject project, String className, Set/*<IJavaProject>*/ visitedProjects, IProgressMonitor monitor) throws JavaModelException {
		try {
			if (visitedProjects.contains(project))
				return null;
			monitor.beginTask("", 2); //$NON-NLS-1$
			IType type= project.findType(className, new SubProgressMonitor(monitor, 1));
			if (type != null)
				return type;
			//fix for bug 87492: visit required projects explicitly to also find not exported types
			visitedProjects.add(project);
			IJavaModel javaModel= project.getJavaModel();
			String[] requiredProjectNames= project.getRequiredProjectNames();
			IProgressMonitor reqMonitor= new SubProgressMonitor(monitor, 1);
			reqMonitor.beginTask("", requiredProjectNames.length); //$NON-NLS-1$
			for (int i= 0; i < requiredProjectNames.length; i++) {
				IJavaProject requiredProject= javaModel.getJavaProject(requiredProjectNames[i]);
				if (requiredProject.exists()) {
					type= internalFindType(requiredProject, className, visitedProjects, new SubProgressMonitor(reqMonitor, 1));
					if (type != null)
						return type;
				}
			}
			return null;
		} finally {
			monitor.done();
		}
	}

}
