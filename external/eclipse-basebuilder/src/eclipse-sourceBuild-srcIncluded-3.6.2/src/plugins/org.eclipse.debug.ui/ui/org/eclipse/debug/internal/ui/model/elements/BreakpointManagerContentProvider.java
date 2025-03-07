/*****************************************************************
 * Copyright (c) 2009, 2010 Texas Instruments and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Patrick Chuong (Texas Instruments) - Initial API and implementation (Bug 238956)
 *     IBM Corporation - ongoing enhancements and bug fixing
 *     Wind River Systems - ongoing enhancements and bug fixing
 *****************************************************************/
package org.eclipse.debug.internal.ui.model.elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.IBreakpointsListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.breakpoints.provisional.IBreakpointOrganizer;
import org.eclipse.debug.internal.ui.breakpoints.provisional.IBreakpointUIConstants;
import org.eclipse.debug.internal.ui.elements.adapters.DefaultBreakpointsViewInput;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ModelDelta;
import org.eclipse.debug.internal.ui.viewers.update.BreakpointManagerProxy;
import org.eclipse.debug.internal.ui.views.breakpoints.BreakpointContainer;
import org.eclipse.debug.internal.ui.views.breakpoints.ElementComparator;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.contexts.DebugContextEvent;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.debug.ui.contexts.IDebugContextService;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * This class provides breakpoint content for the breakpoint manager.
 * 
 * @since 3.6
 */
public class BreakpointManagerContentProvider extends ElementContentProvider 
        implements IBreakpointsListener {
    
    /**
     * Breakpoint input data. Contains all input specific data.
     * 
     * @since 3.6
     */
    private class InputData {
        /**
         * Breakpoint manager input
         */
        final private DefaultBreakpointsViewInput fInput;
        
        /**
         * Model proxy of the input
         */
        final private List/*<AbstractModelProxy>*/ fProxies = new ArrayList(1);
        
        /**
         * Element comparator, use to compare the ordering of elements for the model
         * <br/> Note: We assume that the comparator does not change.  
         */
        private ElementComparator fComparator;
        
        /**
         * The breakpoint root container.<br/>
         * Note: The final qualifier guarantees that fContainer will be 
         * initialized before the class is accessed on other threads.
         */
        final private BreakpointContainer fContainer;
        
        /**
         * Known current breakpoint organizers.
         */
        private IBreakpointOrganizer[] fOrganizers;
        
        private IStructuredSelection fDebugContext = StructuredSelection.EMPTY; 
        
        private IPropertyChangeListener fOrganizersListener = new IPropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent event) {
                // For any property changes in breakpiont organizers, refresh the containers.
                updateContainers();
            }
        };

        private IPropertyChangeListener fPresentationContextListener = new IPropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent event) {
                presentationPropertyChanged(event);
            }
        };
        
        private IDebugContextListener fDebugContextListener = new IDebugContextListener() {
            public void debugContextChanged(DebugContextEvent event) {
                InputData.this.debugContextChanged(event);
            }
        };

        /**
         * Constructor
         *  
         * @param input the breakpoint manager input
         * @param proxy the model proxy 
         * @param filter the debug context selection 
         * @param comparator the element comparator 
         */
        InputData(DefaultBreakpointsViewInput input) {
            fInput = input;
            fComparator = (ElementComparator)
                input.getContext().getProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_ELEMENT_COMPARATOR);
            
            fOrganizers = (IBreakpointOrganizer[])
                input.getContext().getProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_ORGANIZERS);

            // Create the initial container.
            ModelDelta initialDelta = new ModelDelta(fInput, 0, IModelDelta.NO_CHANGE, -1);
            IBreakpoint[] breakpoints = filterBreakpoints(
                fInput, getSelectionFilter(fInput, getDebugContext()), fBpManager.getBreakpoints());
            fContainer = createRootContainer(initialDelta, fInput, fOrganizers, breakpoints);
            
            registerOrganizersListener(null, fOrganizers);
            input.getContext().addPropertyChangeListener(fPresentationContextListener);
            
            IWorkbenchWindow window = fInput.getContext().getWindow();
            if (window != null) {
                IDebugContextService debugContextService = DebugUITools.getDebugContextManager().getContextService(window);
                ISelection debugContext = debugContextService.getActiveContext();
                if (debugContext instanceof IStructuredSelection) {
                    synchronized(this) {
                        fDebugContext = (IStructuredSelection)debugContext;
                    }
                }
                debugContextService.addDebugContextListener(fDebugContextListener);
            }
        }
        
        void dispose() {
            // Unregister listener to breakpoint organizers.
            IBreakpointOrganizer[] organizers;
            synchronized(this) {
                organizers = fOrganizers;
                fOrganizers = null;
            }
            registerOrganizersListener(organizers, null);
            
            // Unregister listener to presentation context.
            fInput.getContext().removePropertyChangeListener(fPresentationContextListener);
            
            // Unregister listener to debug context in window.
            IWorkbenchWindow window = fInput.getContext().getWindow();
            if (window != null) {
                IDebugContextService debugContextService = DebugUITools.getDebugContextManager().getContextService(window);
                debugContextService.removeDebugContextListener(fDebugContextListener);
            }

        }
        
        void proxyInstalled(BreakpointManagerProxy proxy) {
            ModelDelta rootDelta = null;
            synchronized(this) {
                fProxies.add(proxy);
                
                // Generate an install delta
                
                rootDelta = new ModelDelta(fInput, 0, IModelDelta.NO_CHANGE, -1);
                buildInstallDelta(rootDelta, fContainer);

                if (DEBUG_BREAKPOINT_DELTAS) {
                    System.out.println("PROXY INSTALLED (" + proxy + ")\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }

                proxy.postModelChanged(rootDelta, false);
            }
        }

        synchronized void proxyDisposed(BreakpointManagerProxy proxy) {
            fProxies.remove(proxy);
            if (DEBUG_BREAKPOINT_DELTAS) {
                System.out.println("PROXY DISPOSED (" + proxy + ")\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        synchronized BreakpointManagerProxy[] getProxies() {
            return (BreakpointManagerProxy[])fProxies.toArray(new BreakpointManagerProxy[fProxies.size()]);
        }
        
        /**
         * Change the breakpoint organizers for the root container.
         * 
         * @param organizers the new organizers.
         */
        void setOrganizers(IBreakpointOrganizer[] organizers) {
            IBreakpointOrganizer[] oldOrganizers = null;
            synchronized(this) {
                oldOrganizers = fOrganizers;
                fOrganizers = organizers;
            }
            registerOrganizersListener(oldOrganizers, organizers);
            updateContainers();
        }

        private void registerOrganizersListener(IBreakpointOrganizer[] oldOrganizers, IBreakpointOrganizer[] newOrganizers) {
            if (oldOrganizers != null) {
                for (int i = 0; i < oldOrganizers.length; i++) {
                    oldOrganizers[i].removePropertyChangeListener(fOrganizersListener);
                }
            }
            if (newOrganizers != null) {
                for (int i = 0; i < newOrganizers.length; i++) {
                    newOrganizers[i].addPropertyChangeListener(fOrganizersListener);
                }
            }
        }
        
        void updateContainers() {
            IBreakpoint[] breakpoints = filterBreakpoints(
                fInput, getSelectionFilter(fInput, getDebugContext()), fBpManager.getBreakpoints());
    
            synchronized(this) {
                ModelDelta delta = new ModelDelta(fInput, IModelDelta.NO_CHANGE);
                // create a reference container, use for deleting elements and adding elements
                ModelDelta dummyDelta = new ModelDelta(null, IModelDelta.NO_CHANGE);                
                BreakpointContainer refContainer = createRootContainer(dummyDelta, fInput, fOrganizers, breakpoints);
    
                // delete the removed elements
                deleteRemovedElements(fContainer, refContainer, delta);
                
                // adjust the old organizer with the reference organizer
                BreakpointContainer.copyOrganizers(fContainer, refContainer);
                
                // insert the added elements
                IBreakpoint newBreakpoint = insertAddedElements(fContainer, refContainer, delta);
                delta.setChildCount(fContainer.getChildren().length);
                
                // select the new breakpoint
                if (newBreakpoint != null) {
                    appendModelDeltaToElement(delta, newBreakpoint, IModelDelta.SELECT);
                }
                if (DEBUG_BREAKPOINT_DELTAS) {
                    System.out.println("POST BREAKPOINT DELTA (setOrganizers)\n"); //$NON-NLS-1$
                }
                postModelChanged(delta, false); 
            }            
        }

        private synchronized IStructuredSelection getDebugContext() {
            return fDebugContext;
        }
        
        /**
         * Handles the property changed events in presentation contexts.
         * Sub-classes may override to perform additional handling.
         * 
         * @param presentation Presentation context that changed.
         */
        private void presentationPropertyChanged(PropertyChangeEvent event) {
            if (IPresentationContext.PROPERTY_DISPOSED.equals(event.getProperty())) {
                contextDisposed(fInput.getContext());
            }
            if (IBreakpointUIConstants.PROP_BREAKPOINTS_ORGANIZERS.equals(event.getProperty())) {
                IBreakpointOrganizer[] organizers = (IBreakpointOrganizer[])event.getNewValue();
                setOrganizers(organizers);
            }
            else if ( IBreakpointUIConstants.PROP_BREAKPOINTS_FILTER_SELECTION.equals(event.getProperty()) ) 
            {
                IStructuredSelection selection = null;
                
                if (Boolean.TRUE.equals(event.getNewValue()) ) {
                    selection = getDebugContext();
                }
                setFilterSelection(selection);
            } 
            else if ( IBreakpointUIConstants.PROP_BREAKPOINTS_TRACK_SELECTION.equals(event.getProperty()) ) 
            {
                IStructuredSelection selection = null;
                
                if (Boolean.TRUE.equals(event.getNewValue()) ) {
                    selection = getDebugContext();
                }
                trackSelection(selection);
            } 
        }

        private void debugContextChanged(DebugContextEvent event) {
            IStructuredSelection newContext;
            if (event.getContext() instanceof IStructuredSelection) {
                newContext = (IStructuredSelection)event.getContext();
            } else {
                newContext = StructuredSelection.EMPTY;
            }

            synchronized(this) {
                fDebugContext = newContext;
            }

            if (Boolean.TRUE.equals(fInput.getContext().getProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_FILTER_SELECTION)) ) {
                setFilterSelection(newContext);
            }
            
            if (Boolean.TRUE.equals(fInput.getContext().getProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_TRACK_SELECTION)) ) {
                trackSelection(newContext);
            }            
        }
        
        
        private void setFilterSelection(IStructuredSelection ss) {
            ModelDelta delta = new ModelDelta(fInput, IModelDelta.NO_CHANGE);
            boolean changed = false;
        
            // calculate supported breakpoints outside of the synchronized section.
            IBreakpoint[] allBreakpoints = fBpManager.getBreakpoints();
            boolean[] supportedBreakpoints = new boolean[allBreakpoints.length];
            for (int i = 0; i < allBreakpoints.length; ++i) {
                supportedBreakpoints[i] = supportsBreakpoint(ss, allBreakpoints[i]);
            }
            
            synchronized(this) {
                Set existingBreakpoints = new HashSet(Arrays.asList(fContainer.getBreakpoints()));

                // Bug 310879
                // Process breakpoints in two passes: first remove breakpoints, then add new ones.
                // This way the breakpoint counts and indexes will be consistent in the delta.
                for (int i = 0; i < allBreakpoints.length; ++i) {
                    if (!supportedBreakpoints[i] && existingBreakpoints.contains(allBreakpoints[i])) {
                        fContainer.removeBreakpoint(allBreakpoints[i], delta);
	                    changed = true;
                    }
                }
                for (int i = 0; i < allBreakpoints.length; ++i) {
                    if (supportedBreakpoints[i] && !existingBreakpoints.contains(allBreakpoints[i])) {
	                    fContainer.addBreakpoint(allBreakpoints[i], delta);
	                    changed = true;
                    }
                }

                if (changed) {
                    if (DEBUG_BREAKPOINT_DELTAS) {
                        System.out.println("POST BREAKPOINT DELTA (setFilterSelection)\n"); //$NON-NLS-1$
                    }
                    postModelChanged(delta, false); 
                }
            }
        }

        
        private void trackSelection(IStructuredSelection selection) {
            if (selection == null || selection.size() != 1) {
                return;
            }

            Iterator iter = selection.iterator();
            Object firstElement = iter.next();
            if (firstElement == null || iter.hasNext()) {
                return;
            }
            IThread thread = null;
            if (firstElement instanceof IStackFrame) {
                thread = ((IStackFrame) firstElement).getThread();
            } else if (firstElement instanceof IThread) {
                thread = (IThread) firstElement;
            } else {
                return;
            }

            IBreakpoint[] breakpoints = thread.getBreakpoints();
            Set bpsSet = new HashSet(breakpoints.length * 4/3);
            for (int i = 0; i< breakpoints.length; i++) {
                bpsSet.add(breakpoints[i]);
            }

            ModelDelta delta = new ModelDelta(fInput, IModelDelta.NO_CHANGE);
            synchronized (this) {
                if (buildTrackSelectionDelta(delta, fContainer, bpsSet)) {
                    if (DEBUG_BREAKPOINT_DELTAS) {
                        System.out.println("POST BREAKPOINT DELTA (trackSelection)\n"); //$NON-NLS-1$
                    }
                    BreakpointManagerProxy[] proxies = getProxies();
                    for (int i = 0; i < proxies.length; i++) {
                        proxies[i].postModelChanged(delta, true);
                    }       
                }
            }

        }

        /**
         * Recursive function to build the model delta to select a breakpoint
         * corresponding to the active debug context selection.
         * 
         * @param delta Delta node to build on
         * @param container Container element to build delta for.
         * @param breakpoints Breakpoint set to be selected.
         * @return whether we found a breakpoint to select
         */
        private boolean buildTrackSelectionDelta(ModelDelta delta, BreakpointContainer container, Set breakpoints) {
            Object[] children = container.getChildren();
            delta.setChildCount(children.length);
            for (int i = 0; i < children.length; i++) {
                ModelDelta childDelta = delta.addNode(children[i], i, IModelDelta.NO_CHANGE);
                if (children[i] instanceof BreakpointContainer) {
                    BreakpointContainer childContainer = (BreakpointContainer)children[i];
                    boolean containsBP = false;
                    IBreakpoint[] containerBPs = childContainer.getBreakpoints();
                    for (int j = 0; j < containerBPs.length; j++) {
                        if (breakpoints.contains(containerBPs[j])) {
                            containsBP = true; 
                            break;
                        }
                    }
                    if (containsBP && buildTrackSelectionDelta(childDelta, childContainer, breakpoints) ) { 
                        return true; 
                    }
                } else if (children[i] instanceof IBreakpoint &&
                    breakpoints.contains(children[i])) 
                {
                    childDelta.setFlags(IModelDelta.SELECT | IModelDelta.EXPAND);
                    return true;
                }
            }
            return false;
        }

        /**
         * Helper method to add breakpoints to the given input.
         * 
         * @param data the input to add the breakpoints
         * @param breakpoints the breakpoints
         */
        void breakpointsAdded(IBreakpoint[] breakpoints) {
            IBreakpoint[] filteredBreakpoints = filterBreakpoints(
                fInput, getSelectionFilter(fInput, getDebugContext()), breakpoints);
            
            if (filteredBreakpoints.length > 0) {
                synchronized (this) {
                    ModelDelta delta = new ModelDelta(fInput, 0, IModelDelta.NO_CHANGE, -1);
                    for (int i = 0; i < filteredBreakpoints.length; ++i) {
                        // Avoid adding breakpoints which were already removed.  If breakpoints 
                        // are added and removed very fast, the Breakpoint manager can issue 
                        // breakpoint added events after breakpoint removed events!  This means 
                        // that such breakpoints would never be removed from the view.
                        // (Bug 289526)
                        if (DebugPlugin.getDefault().getBreakpointManager().getBreakpoint(filteredBreakpoints[i].getMarker()) != null) {
                            fContainer.addBreakpoint(filteredBreakpoints[i], delta);
                        }
                    }
                    delta.setChildCount(fContainer.getChildren().length);
                    
                    // select the breakpoint
                    if (filteredBreakpoints.length > 0) {
                        appendModelDeltaToElement(delta, filteredBreakpoints[0], IModelDelta.SELECT);
                    }
                
                    if (DEBUG_BREAKPOINT_DELTAS) {
                        System.out.println("POST BREAKPOINT DELTA (breakpointsAddedInput)\n"); //$NON-NLS-1$
                    }
                    postModelChanged(delta, false); 
                }
            }
        }

        /**
         * Helper method to remove breakpoints from a given input.
         * 
         * @param data the input to add the breakpoints
         * @param breakpoints the breakpoints
         */
        void breakpointsRemoved(IBreakpoint[] breakpoints) {
            synchronized (this) {
                boolean removed = false;
                ModelDelta delta = new ModelDelta(fInput, IModelDelta.NO_CHANGE);
                for (int i = 0; i < breakpoints.length; ++i) {
                    removed = fContainer.removeBreakpoint(breakpoints[i], delta) || removed;
                }
                
                if (removed) {
                    if (DEBUG_BREAKPOINT_DELTAS) {
                        System.out.println("POST BREAKPOINT DELTA (breakpointsRemovedInput)\n"); //$NON-NLS-1$
                    }
                    postModelChanged(delta, false); 
                }
            }
        }
        
        void breakpointsChanged(IBreakpoint[] breakpoints) {
            
            
            IBreakpoint[] filteredBreakpoints = filterBreakpoints(
                fInput, getSelectionFilter(fInput, getDebugContext()), breakpoints);

            synchronized (this) {
                ModelDelta delta = new ModelDelta(fInput, IModelDelta.NO_CHANGE);

                // If the change caused a breakpoint to be added (installed) or remove (un-installed) update accordingly.
                List removed = new ArrayList();
                List added = new ArrayList();
                List filteredAsList = Arrays.asList(filteredBreakpoints);
                for (int i = 0; i < breakpoints.length; i++) {
                    IBreakpoint bp = breakpoints[i];
                    boolean oldContainedBp = fContainer.contains(bp);
                    boolean newContained = filteredAsList.contains(bp);
                    if (oldContainedBp && !newContained) {
                        removed.add(bp);
                    } else if (!oldContainedBp && newContained) {
                        added.add(bp);
                    }                   
                }
                if (!added.isEmpty()) {
                    breakpointsAdded((IBreakpoint[]) added.toArray(new IBreakpoint[added.size()]));
                }
                if (!removed.isEmpty()) {
                    breakpointsRemoved((IBreakpoint[]) removed.toArray(new IBreakpoint[removed.size()]));
                }                       
                for (int i = 0; i < filteredBreakpoints.length; ++i)
                    appendModelDelta(fContainer, delta, IModelDelta.STATE | IModelDelta.CONTENT, filteredBreakpoints[i]); // content flag triggers detail refresh

                if (DEBUG_BREAKPOINT_DELTAS) {
                    System.out.println("POST BREAKPOINT DELTA (breakpointsChanged)\n"); //$NON-NLS-1$
                }
                postModelChanged(delta, false); 
            }            
        }

        
        /**
         * Recursive function to build the model delta to install breakpoint
         * model proxies for all breakpoints and breakpoint containers.
         * 
         * @param delta Delta node to build on
         * @param container Container element to build delta for.
         */
        private void buildInstallDelta(ModelDelta delta, BreakpointContainer container) {
            Object[] children = container.getChildren();
            delta.setChildCount(children.length);
            for (int i = 0; i < children.length; i++) {
                ModelDelta childDelta = delta.addNode(children[i], i, IModelDelta.NO_CHANGE);
                if (children[i] instanceof BreakpointContainer) {
                    childDelta.setFlags(IModelDelta.INSTALL);
                    buildInstallDelta(childDelta, (BreakpointContainer)children[i]);
                } else if (children[i] instanceof IBreakpoint) {
                    childDelta.setFlags(IModelDelta.INSTALL);
                }
            }
        }

        
        /**
         * Insert elements from the reference container to an existing container.
         * 
         * @param container the existing  container to insert the new elements.
         * @param refContainer the reference container to compare elements that are added.
         * @param containerDelta the delta of the existing container.
         */
        private IBreakpoint insertAddedElements(BreakpointContainer container, BreakpointContainer refContainer, ModelDelta containerDelta) {
            IBreakpoint newBreakpoint = null;
            
            Object[] children = container.getChildren();
            Object[] refChildren = refContainer.getChildren();
            

            for (int i = 0; i < refChildren.length; ++i) {
                Object element = getElement(children, refChildren[i]);

                // if a child of refContainer doesn't exist in container, than insert it to container
                //      - if the reference child is a container, than copy the reference child container to container
                //      - otherwise (Breakpoint), add the breakpoint to container
                if (element == null) {
                    if (refChildren[i] instanceof BreakpointContainer) {
                        BreakpointContainer.addChildContainer(container, (BreakpointContainer) refChildren[i], containerDelta);
                    } else {
                        BreakpointContainer.addBreakpoint(container, (IBreakpoint) refChildren[i], containerDelta);
                        if (newBreakpoint == null)
                            newBreakpoint = (IBreakpoint) refChildren[i];
                    }
                    
                // if a child exist in container, than recursively search into container. And also update the organizer of
                // of container to the one in the refContainer's child.
                } else if (element instanceof BreakpointContainer) {
                    ModelDelta childDelta = containerDelta.addNode(element, container.getChildIndex(element), IModelDelta.STATE, -1);
                    BreakpointContainer.copyOrganizers((BreakpointContainer) element, (BreakpointContainer) refChildren[i]);
                    newBreakpoint = insertAddedElements((BreakpointContainer) element, (BreakpointContainer) refChildren[i], childDelta);
                    childDelta.setChildCount(((BreakpointContainer) element).getChildren().length);
                }
            }
            
            return newBreakpoint;
        }
        
        /**
         * Delete elements from existing container that doesn't exist in the reference container.
         * 
         * @param container the existing container to delete the removed elements.
         * @param refContainer the reference container to compare elements that are removed.
         * @param containerDelta the delta of the existing container.
         */
        private void deleteRemovedElements(BreakpointContainer container, BreakpointContainer refContainer, ModelDelta containerDelta) {
            Object[] children = container.getChildren();
            Object[] refChildren = refContainer.getChildren();
            
            // if a child of container doesn't exist in refContainer, than remove it from container
            for (int i = 0; i < children.length; ++i) {
                Object element = getElement(refChildren, children[i]);
                
                if (element == null) {
                    if (children[i] instanceof BreakpointContainer) {
                        BreakpointContainer.removeAll((BreakpointContainer) children[i], containerDelta);
                    } else {
                        BreakpointContainer.removeBreakpoint(container, (IBreakpoint) children[i], containerDelta);
                    }
                } else if (element instanceof BreakpointContainer){

                    ModelDelta childDelta = containerDelta.addNode(children[i], IModelDelta.STATE);                     
                    deleteRemovedElements((BreakpointContainer) children[i], (BreakpointContainer) element, childDelta);
                }
            }
        }
        
        /**
         * Get the element that is in the collection.
         * 
         * @param collection the collection of elements.
         * @param element the element to search.
         * @return if element exist in collection, than it is returned, otherwise <code>null</code> is returned.
         * @see insertAddedElements
         * @see deleteRemovedElements
         */
        private Object getElement(Object[] collection, Object element) {
            for (int i = 0; i < collection.length; ++i)
                if (collection[i] instanceof BreakpointContainer && element instanceof BreakpointContainer) {               
                    if (collection[i].equals(element))
                        return collection[i];
                } else {
                    if (collection[i].equals(element))
                        return collection[i];
                }
            return null;
        }
        
        /**
         * Create a root container.
         * 
         * @param rootDelta the root delta.
         * @param input the view input.
         * @param organizers the breakpoint organizers.
         * @param oldContainer the old container, use to determine whether a new breakpoint should be expanded.
         * @param the breakpoint manager.
         */
        private BreakpointContainer createRootContainer(
            ModelDelta rootDelta, DefaultBreakpointsViewInput input, 
            IBreakpointOrganizer[] organizers, IBreakpoint[] breakpoints) 
        {
            
            BreakpointContainer container = new BreakpointContainer(organizers, fComparator);
            container.initDefaultContainers(rootDelta);
            
            for (int i = 0; i < breakpoints.length; ++i) {
                container.addBreakpoint(breakpoints[i], rootDelta);             
            }
            
            return container;
        }       
        
        /**
         * Fire model change event for the input.
         * 
         * @param input the input.
         * @param delta the model delta.
         * @param debugReason the debug string.
         */
        synchronized private void postModelChanged(final IModelDelta delta, boolean select) {
            for (int i = 0; fProxies != null && i < fProxies.size(); i++) {
                ((BreakpointManagerProxy)fProxies.get(i)).postModelChanged(delta, select);
            }       
        }


    }
    
    private class InputDataMap extends LinkedHashMap {
        private static final long serialVersionUID = 1L;

        public InputDataMap() {
            super(1, (float)0.75, true);
        }
        
        protected boolean removeEldestEntry(java.util.Map.Entry arg0) {
            InputData data = (InputData)arg0.getValue();
            if (size() > getMaxInputsCache() && data.fProxies.isEmpty()) {
                data.dispose();
                return true;
            }
            return false;
        }
    }
    
    /**
     * Scheduling rule to make sure that breakpoint manager listener updates
     * are process serially.
     */
    private ISchedulingRule fBreakpointsListenerSchedulingRule = new ISchedulingRule() {
        
        public boolean isConflicting(ISchedulingRule rule) {
            return rule == this;
        }
        
        public boolean contains(ISchedulingRule rule) {
            return rule == this;
        }
    };
    
    // debug flags
    public static boolean DEBUG_BREAKPOINT_DELTAS = false;
    
    static {
        DEBUG_BREAKPOINT_DELTAS = DebugUIPlugin.DEBUG && "true".equals(                     //$NON-NLS-1$
         Platform.getDebugOption("org.eclipse.debug.ui/debug/viewers/breakpointDeltas"));   //$NON-NLS-1$
    } 
        
    /**
     * A map of input to info data cache
     */
    final private Map fInputToData = Collections.synchronizedMap(new InputDataMap());
    
    /**
     * Flag indicating whether the content provider is currently a breakpoints listener.
     */
    private boolean fIsBreakpointListener = false;
    
    /**
     * The breakpoint manager.
     */
    final private IBreakpointManager fBpManager = DebugPlugin.getDefault().getBreakpointManager();
    
    /**
     * Sub-classes may override this method to filter the breakpoints.
     * 
     * @param input the breakpoint manager input.
     * @param breakpoints the list of breakpoint to filter.
     * @return the filtered list of breakpoint based on the input.
     */
    protected IBreakpoint[] filterBreakpoints(DefaultBreakpointsViewInput input, IStructuredSelection selectionFilter, IBreakpoint[] breakpoints) {      
        if (selectionFilter != null && !selectionFilter.isEmpty()) {
            List targets = getDebugTargets(selectionFilter);
            ArrayList retVal = new ArrayList();
            if (targets != null) {
                for (int i = 0; i < breakpoints.length; ++i) {
                    if (supportsBreakpoint(targets, breakpoints[i]))
                        retVal.add(breakpoints[i]);
                }
            }
            return (IBreakpoint[]) retVal.toArray(new IBreakpoint[retVal.size()]);
        } else {
            return breakpoints;
        }
    }
    
    /**
     * Sub-classes may override this to determine whether the breakpoint is supported by the selection.
     * 
     * @param ss the selection of the debug elements.
     * @param breakpoint the breakpoint.
     * @return true if supported.
     */
    protected boolean supportsBreakpoint(IStructuredSelection ss, IBreakpoint breakpoint) {
        return supportsBreakpoint(getDebugTargets(ss), breakpoint);
    }
    
    /**
     * Returns true if the breakpoint contains in one of the targets.
     * 
     * @param targets a list of <code>IDebugTarget</code> objects.
     * @param breakpoint the breakpoint.
     * @return true if breakpoint contains in the list of targets.
     */
    protected boolean supportsBreakpoint(List targets, IBreakpoint breakpoint) {
        boolean exist = targets.size() == 0 ? true : false;
        for (int i = 0; !exist && i < targets.size(); ++i) {
            IDebugTarget target = (IDebugTarget) targets.get(i);
            exist |= target.supportsBreakpoint(breakpoint);
        }
        return exist;
    }
    
    /**
     * Returns the list of IDebugTarget for the selection.
     * 
     * @param ss the selection.
     * @return list of IDebugTarget object.
     */
    protected List getDebugTargets(IStructuredSelection ss) {
        List debugTargets = new ArrayList(2);
        if (ss != null) {
            Iterator i = ss.iterator();
            while (i.hasNext()) {
                Object next = i.next();
                if (next instanceof IDebugElement) {
                    debugTargets.add(((IDebugElement)next).getDebugTarget());
                } else if (next instanceof ILaunch) {
                    IDebugTarget[] targets = ((ILaunch)next).getDebugTargets();
                    for (int j = 0; j < targets.length; j++) {
                        debugTargets.add(targets[j]);
                    }
                } else if (next instanceof IProcess) {
                    IDebugTarget target = (IDebugTarget)((IProcess)next).getAdapter(IDebugTarget.class);
                    if (target != null) {
                        debugTargets.add(target);
                    }
                }   
            }
        }
        return debugTargets;
    }


    /**
     * Maximum number of breakpoint manager input objects that this provider 
     * will cache data for.  This method is called once upon class creation 
     * when setting up the data cache.  Sub-classes may override to provide
     * a custom setting.
     * 
     * @return Maximum data cache size
     */
    protected int getMaxInputsCache() {
        return 2;
    }
    
    /**
     * Handles the event when a presentation context is dispoed.
     * Sub-classes may override to perform additional cleanup.
     * 
     * @param context Presetnation context that was disposed.
     */
    protected void contextDisposed(IPresentationContext context) {
        List removed = new ArrayList(1);
        synchronized (fInputToData) {
            for (Iterator itr = fInputToData.entrySet().iterator(); itr.hasNext();) {
                Map.Entry entry = (Map.Entry)itr.next();
                IPresentationContext entryContext = ((DefaultBreakpointsViewInput)entry.getKey()).getContext();
                if (context.equals(entryContext)) {
                    removed.add(entry.getValue());
                    itr.remove();
                }
            }
        }

        // Dispose the removed input datas
        for (int i = 0; i < removed.size(); i++) {
            ((InputData)removed.get(i)).dispose();
        }
    }
    
    /**
     * Register the breakpoint manager input with this content provider.
     * 
     * @param input the breakpoint manager input to register.
     * @param proxy the model proxy of the input.
     * @param organizers the breakpoint organizer, can be <code>null</code>.
     * @param selectionFilter the selection filter, can be <code>null</code>.
     * @param comparator the element comparator.
     */
    public void registerModelProxy(DefaultBreakpointsViewInput input, BreakpointManagerProxy proxy) {
        synchronized(this) {
            if (!fIsBreakpointListener) {
                fBpManager.addBreakpointListener(this);
                fIsBreakpointListener = true;
            }
        }
        InputData inputData = getInputData(input);
        if (inputData != null) {
            inputData.proxyInstalled(proxy);
        }
    }
    
    /**
     * Unregister the breakpoint manager input with this content provider.
     * 
     * @param input the breakpoint manager input to unregister.
     */
    public void unregisterModelProxy(DefaultBreakpointsViewInput input, BreakpointManagerProxy proxy) {
        InputData inputData = (InputData)fInputToData.get(input);
        if (inputData != null) {
            inputData.proxyDisposed(proxy);
            
            if (fInputToData.isEmpty()) {
                synchronized(this) {
                    if (fIsBreakpointListener) {
                        fBpManager.removeBreakpointListener(this);
                        fIsBreakpointListener = false;
                    }
                }
            }
        }
    }   
    
    private InputData getInputData(DefaultBreakpointsViewInput input) {
        if (Boolean.TRUE.equals(input.getContext().getProperty(IPresentationContext.PROPERTY_DISPOSED)) ) {
            return null;
        }
        
        InputData data = null;
        synchronized (fInputToData) {
            data = (InputData)fInputToData.get(input); 
            if (data == null) {
                data = new InputData(input);
                fInputToData.put(input, data);
            }
        }
        return data;
    }
    
    /**
     * Returns the selection filter for the input.
     * 
     * @param input the selection.
     */
    protected IStructuredSelection getSelectionFilter(Object input, IStructuredSelection debugContext) {
        if (input instanceof DefaultBreakpointsViewInput) {
            IPresentationContext presentation = ((DefaultBreakpointsViewInput)input).getContext();
            if ( Boolean.TRUE.equals(presentation.getProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_FILTER_SELECTION)) ) {
                return debugContext;
            }
        }
        return null;
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.model.elements.ElementContentProvider#supportsContextId(java.lang.String)
     */
    protected boolean supportsContextId(String id) {
        return id.equals(IDebugUIConstants.ID_BREAKPOINT_VIEW);
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.model.elements.ElementContentProvider#getChildCount(java.lang.Object, org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext, org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate)
     */
    protected int getChildCount(Object element, IPresentationContext context, IViewerUpdate monitor) throws CoreException {
        Object input = monitor.getViewerInput();
        if (input instanceof DefaultBreakpointsViewInput) {
            DefaultBreakpointsViewInput bpManagerInput = (DefaultBreakpointsViewInput)input;
            InputData inputData = getInputData(bpManagerInput);
            if (inputData != null) {
                return inputData.fContainer.getChildren().length;
            }
        }       
        return 0;       
    }   

    /* (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.model.elements.ElementContentProvider#getChildren(java.lang.Object, int, int, org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext, org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate)
     */
    protected Object[] getChildren(Object parent, int index, int length, IPresentationContext context, IViewerUpdate monitor) throws CoreException {
        Object input = monitor.getViewerInput();
        if (input instanceof DefaultBreakpointsViewInput) {
            DefaultBreakpointsViewInput bpManagerInput = (DefaultBreakpointsViewInput)input;
            InputData inputData = getInputData(bpManagerInput);
            if (inputData != null) {
                Object[] children =  inputData.fContainer.getChildren();
                return getElements(children, index, length);
            }
        }       
                
        return EMPTY;
    }   

    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.core.IBreakpointsListener#breakpointsAdded(org.eclipse.debug.core.model.IBreakpoint[])
     */
    public void breakpointsAdded(final IBreakpoint[] breakpoints) {
        new Job("Breakpoints View Update Job") { //$NON-NLS-1$
            {
                setSystem(true);
                setRule(fBreakpointsListenerSchedulingRule);
            }
            
            protected IStatus run(IProgressMonitor monitor) {
                InputData[] datas = (InputData[])fInputToData.values().toArray(new InputData[0]);
                for (int i = 0; i < datas.length; i++) {
                    datas[i].breakpointsAdded(breakpoints);
                }
                return Status.OK_STATUS;
            }
        }.schedule();               
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.core.IBreakpointsListener#breakpointsRemoved(org.eclipse.debug.core.model.IBreakpoint[], org.eclipse.core.resources.IMarkerDelta[])
     */
    public void breakpointsRemoved(final IBreakpoint[] breakpoints, IMarkerDelta[] deltas) {
        new Job("Breakpoints View Update Job") { //$NON-NLS-1$
            {
                setSystem(true);
                setRule(fBreakpointsListenerSchedulingRule);
            }
            
            protected IStatus run(IProgressMonitor monitor) {
                InputData[] datas = (InputData[])fInputToData.values().toArray(new InputData[0]);
                for (int i = 0; i < datas.length; i++) {
                    datas[i].breakpointsRemoved(breakpoints);
                }               
                return Status.OK_STATUS;
            }
        }.schedule();
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.core.IBreakpointsListener#breakpointsChanged(org.eclipse.debug.core.model.IBreakpoint[], org.eclipse.core.resources.IMarkerDelta[])
     */
    public void breakpointsChanged(final IBreakpoint[] breakpoints, IMarkerDelta[] deltas) {
        new Job("Breakpoints View Update Job") { //$NON-NLS-1$
            {
                setSystem(true);
                setRule(fBreakpointsListenerSchedulingRule);
            }
            
            protected IStatus run(IProgressMonitor monitor) {
                InputData[] datas = (InputData[])fInputToData.values().toArray(new InputData[0]);
                for (int i = 0; i < datas.length; i++) {
                    datas[i].breakpointsChanged(breakpoints);
                }               
                return Status.OK_STATUS;
            }
        }.schedule();
    }   
    /**
     * Appends the model delta flags to child containers that contains the breakpoint.
     * 
     * @param parent the parent container.
     * @param parentDelta the parent model delta.
     * @param flags the model delta flags.
     * @param breakpoint the breakpoint to search in the children containers.
     */
    private void appendModelDelta(BreakpointContainer parent, ModelDelta parentDelta, int flags, IBreakpoint breakpoint) {
        BreakpointContainer[] containers = parent.getContainers();
        
        if (parent.contains(breakpoint)) {
            if ((containers.length != 0)) {
                for (int i = 0; i < containers.length; ++i) {
                    ModelDelta nodeDelta = parentDelta.addNode(containers[i], IModelDelta.STATE);
                    appendModelDelta(containers[i], nodeDelta, flags, breakpoint);
                }           
            } else {
                parentDelta.addNode(breakpoint, flags);
            }
        }           
    }
    
    /**
     * Appends the model delta to the first found element in the model delta tree.
     * 
     * @param parentDelta the parent delta
     * @param element the element to search
     * @param flags the delta flags
     */
    private void appendModelDeltaToElement(IModelDelta parentDelta, Object element, int flags) {
        if (element.equals(parentDelta.getElement())) {
            ((ModelDelta) parentDelta).setFlags(parentDelta.getFlags() | flags);
            return;
        }
        
        IModelDelta[] childDeltas = parentDelta.getChildDeltas();
        for (int i = 0; i < childDeltas.length; ++i) {
            if (element.equals(childDeltas[i].getElement())) {
                ((ModelDelta) childDeltas[i]).setFlags(childDeltas[i].getFlags() | flags);
                return;
            }
            
            appendModelDeltaToElement(childDeltas[i], element, flags);
        }
    }
}
