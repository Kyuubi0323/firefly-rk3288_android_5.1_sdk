/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Patrick Chuong (Texas Instruments) - Improve usability of the breakpoint view (Bug 238956)
 *******************************************************************************/
package org.eclipse.debug.internal.ui.actions.breakpoints;


import java.util.ArrayList;
import java.util.Iterator;

import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.actions.AbstractRemoveActionDelegate;
import org.eclipse.debug.internal.ui.actions.ActionMessages;
import org.eclipse.debug.internal.ui.breakpoints.provisional.IBreakpointContainer;
import org.eclipse.debug.internal.ui.preferences.IDebugPreferenceConstants;
import org.eclipse.debug.internal.ui.views.breakpoints.BreakpointsView;
import org.eclipse.debug.internal.ui.views.breakpoints.WorkingSetCategory;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.PlatformUI;

public class RemoveBreakpointAction extends AbstractRemoveActionDelegate {
	
	/* (non-Javadoc)
	 * @see org.eclipse.ui.IActionDelegate#run(org.eclipse.jface.action.IAction)
	 */
	public void run(IAction action) {
		IStructuredSelection selection = getSelection();
		if (selection.isEmpty()) {
			return;
		}
		final Iterator itr= selection.iterator();
		final CoreException[] exception= new CoreException[1];
		IWorkspaceRunnable runnable= new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) {
				ArrayList breakpointsToDelete = new ArrayList();
				ArrayList groupsToDelete = new ArrayList();
				boolean deleteAll = false;
				boolean deleteContainer = false;
				boolean prompted = false;
				while (itr.hasNext()) {		
					Object next= itr.next();
					IBreakpoint breakpoint = (IBreakpoint)DebugPlugin.getAdapter(next, IBreakpoint.class);
					if (breakpoint != null) {
						breakpointsToDelete.add(breakpoint);
					} else if (next instanceof IBreakpointContainer) {
						//the the container is a workingset, ask if they want to delete it as well
						IBreakpointContainer bpc = (IBreakpointContainer) next;
						if(bpc.getCategory() instanceof WorkingSetCategory) {
							IWorkingSet set = ((WorkingSetCategory)bpc.getCategory()).getWorkingSet();
							if(!prompted) {
								prompted = true;
								DeleteWorkingsetsMessageDialog dialog = new DeleteWorkingsetsMessageDialog(getView().getSite().getShell(),
										ActionMessages.RemoveBreakpointAction_3,
										null,
										ActionMessages.RemoveBreakpointAction_4,
										MessageDialog.QUESTION,
										new String[] {ActionMessages.RemoveBreakpointAction_5, ActionMessages.RemoveBreakpointAction_6},
										0);
								if (dialog.open() == Window.CANCEL) {
									return;
								}
								deleteAll = dialog.deleteAllBreakpoints();
								deleteContainer = dialog.deleteWorkingset();
							}
							if(deleteContainer) {
								groupsToDelete.add(set);
							}
						}
						else {
							if(!prompted) {
								IPreferenceStore store = DebugUIPlugin.getDefault().getPreferenceStore();
								prompted = store.getBoolean(IDebugPreferenceConstants.PREF_PROMPT_REMOVE_BREAKPOINTS_FROM_CONTAINER);
								if(prompted) {
									MessageDialogWithToggle mdwt = MessageDialogWithToggle.openYesNoQuestion(getView().getSite().getShell(), ActionMessages.RemoveBreakpointAction_0, 
											ActionMessages.RemoveBreakpointAction_1, ActionMessages.RemoveAllBreakpointsAction_3, !prompted, null, null);
									if(mdwt.getReturnCode() == IDialogConstants.NO_ID) {
										deleteAll = false;
									}
									else {
										store.setValue(IDebugPreferenceConstants.PREF_PROMPT_REMOVE_BREAKPOINTS_FROM_CONTAINER, !mdwt.getToggleState());
										deleteAll = true;
									}
								}
								else {
									deleteAll = !prompted;
								}
							}
						}
						if(deleteAll) {
						    IBreakpoint[] breakpoints = bpc.getBreakpoints();
						    for (int i = 0; i < breakpoints.length; i++) {
                                breakpointsToDelete.add(breakpoints[i]);
                            }
						}
					}
				}
				final IBreakpoint[] breakpoints = (IBreakpoint[]) breakpointsToDelete.toArray(new IBreakpoint[0]);
				final IWorkingSet[] sets = (IWorkingSet[])groupsToDelete.toArray(new IWorkingSet[groupsToDelete.size()]);
				if(breakpoints.length > 0) {
					((BreakpointsView)getView()).preserveSelection(getSelection());
				}
				new Job(ActionMessages.RemoveBreakpointAction_2) { 
                    protected IStatus run(IProgressMonitor pmonitor) {
                        try {
                            DebugPlugin.getDefault().getBreakpointManager().removeBreakpoints(breakpoints, true);
                            for (int i = 0; i < sets.length; i++) {
                            	PlatformUI.getWorkbench().getWorkingSetManager().removeWorkingSet(sets[i]);
							}
                            return Status.OK_STATUS;
                        } catch (CoreException e) {
                            DebugUIPlugin.log(e);
                        }
                        return Status.CANCEL_STATUS;
                    }   
                }.schedule();
			}
		};
		try {
			ResourcesPlugin.getWorkspace().run(runnable, null, 0, null);
		} catch (CoreException ce) {
			exception[0]= ce;
		}
		if (exception[0] != null) {
			IWorkbenchWindow window= DebugUIPlugin.getActiveWorkbenchWindow();
			if (window != null) {
				DebugUIPlugin.errorDialog(window.getShell(), ActionMessages.RemoveBreakpointAction_Removing_a_breakpoint_4,ActionMessages.RemoveBreakpointAction_Exceptions_occurred_attempting_to_remove_a_breakpoint__5 , exception[0]);  
			} else {
				DebugUIPlugin.log(exception[0]);
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.actions.AbstractSelectionActionDelegate#isEnabledFor(java.lang.Object)
	 */
	protected boolean isEnabledFor(Object element) {
		if (element instanceof IBreakpointContainer) {
			if(((IBreakpointContainer)element).getCategory() instanceof WorkingSetCategory) {
				return true;
			}
			return ((IBreakpointContainer)element).getBreakpoints().length > 0;
		}
		return DebugPlugin.getAdapter(element, IBreakpoint.class) != null;
	}
}
