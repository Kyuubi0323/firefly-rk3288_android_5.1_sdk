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
package org.eclipse.team.internal.ccvs.ui.actions;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.mapping.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.mapping.ISynchronizationScopeManager;
import org.eclipse.team.core.mapping.provider.SynchronizationScopeManager;
import org.eclipse.team.core.subscribers.Subscriber;
import org.eclipse.team.core.subscribers.SubscriberResourceMappingContext;
import org.eclipse.team.internal.ccvs.core.CVSProviderPlugin;
import org.eclipse.team.internal.ccvs.ui.CVSUIMessages;
import org.eclipse.team.internal.ccvs.ui.Policy;
import org.eclipse.team.internal.ccvs.ui.operations.RepositoryProviderOperation;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.internal.ui.mapping.BuildScopeOperation;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;

/**
 * A specialized workspace actions that operates on resource traversals
 * instead of resources/
 */
public abstract class WorkspaceTraversalAction extends WorkspaceAction {

    /**
     * Return the selected mappings that contain resources 
     * within a CVS managed project.
     * @return the selected mappings that contain resources 
     * within a CVS managed project
     */
    protected ResourceMapping[] getCVSResourceMappings() {
        return getSelectedResourceMappings(CVSProviderPlugin.getTypeId());
    }

    private static ResourceTraversal[] getTraversals(IWorkbenchPart part, ISynchronizationScopeManager manager, IProgressMonitor monitor) throws CoreException {
    	try {
    		BuildScopeOperation op = new BuildScopeOperation(part, manager);
    		op.run(monitor);
    		return manager.getScope().getTraversals();
    	} catch (InvocationTargetException e) {
			throw TeamException.asTeamException(e);
		} catch (InterruptedException e) {
			throw new OperationCanceledException();
		}
    }
    
    private static IResource[] getRootTraversalResources(ISynchronizationScopeManager manager, IProgressMonitor monitor) throws CoreException {
    	Set result = new HashSet();
    	ResourceTraversal[] traversals = getTraversals(null, manager, monitor);
    	for (int i = 0; i < traversals.length; i++) {
			ResourceTraversal traversal = traversals[i];
            IResource[] resources = traversal.getResources();
            for (int k = 0; k < resources.length; k++) {
                IResource resource = resources[k];
                if (RepositoryProvider.getProvider(resource.getProject(), CVSProviderPlugin.getTypeId()) != null) {
                    result.add(resource);
                }
            }
        }
        return (IResource[]) result.toArray(new IResource[result.size()]);
    }

    protected Subscriber getWorkspaceSubscriber() {
        return CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber();
    }
    
	public static IResource[] getResourcesToCompare(ResourceMapping[] mappings, Subscriber subscriber) throws InvocationTargetException {
    	ISynchronizationScopeManager manager = new SynchronizationScopeManager("",  //$NON-NLS-1$
    			mappings, SubscriberResourceMappingContext.createContext(subscriber), true);
    	try {
    		return getResourcesToCompare(manager);
    	} finally {
    		manager.dispose();
    	}
	}
	
    protected IResource[] getResourcesToCompare(final Subscriber subscriber) throws InvocationTargetException {
    	ISynchronizationScopeManager manager = new SynchronizationScopeManager("",  //$NON-NLS-1$
    			getCVSResourceMappings(), SubscriberResourceMappingContext.createContext(subscriber), true);
    	try {
    		return getResourcesToCompare(manager);
    	} finally {
    		manager.dispose();
    	}
    }
    
    protected ResourceMappingContext getResourceMappingContext() {
		return SubscriberResourceMappingContext.createContext(CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber());
	}

	public static IResource[] getResourcesToCompare(final ISynchronizationScopeManager manager) throws InvocationTargetException {
        // Determine what resources need to be synchronized.
        // Use a resource mapping context to include any relevant remote resources
        final IResource[][] resources = new IResource[][] { null };
        try {
            PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        resources[0] = getRootTraversalResources(
                                manager, 
                                monitor);
                    } catch (CoreException e) {
                        throw new InvocationTargetException(e);
                    }
                }
            
            });
        } catch (InterruptedException e) {
            // Canceled
            return null;
        }
        return resources[0];
    }
    
    public static IResource[] getProjects(IResource[] resources) {
        Set projects = new HashSet();
        for (int i = 0; i < resources.length; i++) {
            IResource resource = resources[i];
            projects.add(resource.getProject());
        }
        return (IResource[]) projects.toArray(new IResource[projects.size()]);
    }
    
    /**
     * 
     * @param mappings
     * @return
     * 
     * @deprecated need to find a better way to do this
     */
    public static boolean isLogicalModel(ResourceMapping[] mappings) {
        for (int i = 0; i < mappings.length; i++) {
            ResourceMapping mapping = mappings[i];
            if (! (mapping.getModelObject() instanceof IResource) ) {
                return true;
            }
        }
        return false;
    }
    
    protected IFile getSelectedFile() {
    	ResourceMapping[] mappings = getCVSResourceMappings();
    	if (mappings.length == 1) {
    		IResource resource = Utils.getResource(mappings[0].getModelObject());
    		if (resource != null && resource.getType() == IResource.FILE)
    			return (IFile)resource;
    	}
    	return null;
    }
    
	protected boolean hasOutgoingChanges(final RepositoryProviderOperation operation) throws InvocationTargetException, InterruptedException {
		final boolean[] hasChange = new boolean[] { false };
		PlatformUI.getWorkbench().getProgressService().run(true, true, new IRunnableWithProgress() {
			public void run(IProgressMonitor monitor) throws InvocationTargetException,
					InterruptedException {
				try {
					monitor.beginTask(CVSUIMessages.WorkspaceTraversalAction_0, 100);
					operation.buildScope(Policy.subMonitorFor(monitor, 50));
					hasChange[0] = CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber().hasLocalChanges(
							operation.getScope().getTraversals(), 
							Policy.subMonitorFor(monitor, 50));
				} catch (CoreException e) {
					throw new InvocationTargetException(e);
				} finally {
					monitor.done();
				}
			}
		});
		return hasChange[0];
	}
	
	/**
	 * Return the complete set of traversals to be targeted by the action
	 * including those that are included by consulting the models.
	 * 
	 * @param monitor
	 *            a progress monitor
	 * @return the complete set of traversals to be targeted by the action
	 * @throws CoreException
	 */
	protected ResourceTraversal[] getTraversals(IProgressMonitor monitor) throws CoreException {
		SynchronizationScopeManager scopeManager = getScopeManager();
		try {
			return getTraversals(getTargetPart(), scopeManager, monitor);
		} finally {
			scopeManager.dispose();
		}
	}
	
	/**
	 * Return a scope manager that provides the scope for the action.
	 * @return a scope manager that provides the scope for the action
	 */
	protected SynchronizationScopeManager getScopeManager() {
		return new SynchronizationScopeManager(
    			"",  //$NON-NLS-1$
    			getCVSResourceMappings(), 
    			getResourceMappingContext(), true);
	}
}
