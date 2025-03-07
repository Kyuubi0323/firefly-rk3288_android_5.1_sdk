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
 *****************************************************************/
package org.eclipse.debug.internal.ui.views.breakpoints;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManagerListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.IDebugHelpContextIds;
import org.eclipse.debug.internal.ui.VariablesViewModelPresentation;
import org.eclipse.debug.internal.ui.actions.breakpointGroups.PasteBreakpointsAction;
import org.eclipse.debug.internal.ui.actions.breakpointGroups.RemoveFromWorkingSetAction;
import org.eclipse.debug.internal.ui.actions.breakpoints.OpenBreakpointMarkerAction;
import org.eclipse.debug.internal.ui.actions.breakpoints.ShowTargetBreakpointsAction;
import org.eclipse.debug.internal.ui.actions.breakpoints.SkipAllBreakpointsAction;
import org.eclipse.debug.internal.ui.breakpoints.provisional.IBreakpointContainer;
import org.eclipse.debug.internal.ui.breakpoints.provisional.IBreakpointOrganizer;
import org.eclipse.debug.internal.ui.breakpoints.provisional.IBreakpointUIConstants;
import org.eclipse.debug.internal.ui.elements.adapters.DefaultBreakpointsViewInput;
import org.eclipse.debug.internal.ui.preferences.IDebugPreferenceConstants;
import org.eclipse.debug.internal.ui.viewers.model.ITreeModelViewer;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerInputUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdateListener;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.TreeModelViewer;
import org.eclipse.debug.internal.ui.viewers.model.provisional.VirtualTreeModelViewer;
import org.eclipse.debug.internal.ui.views.DebugUIViewsMessages;
import org.eclipse.debug.internal.ui.views.variables.VariablesView;
import org.eclipse.debug.internal.ui.views.variables.details.AvailableDetailPanesAction;
import org.eclipse.debug.ui.IBreakpointOrganizerDelegateExtension;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.SelectionListenerAction;

/**
 * This class implements the breakpoints view.
 */
public class BreakpointsView extends VariablesView implements IBreakpointManagerListener {	
	private static final String ACTION_GOTO_MARKER				= "GotoMarker";				//$NON-NLS-1$
	private static final String ACTION_SKIP_BREAKPOINTS			= "SkipBreakpoints";		//$NON-NLS-1$
	private static final String ACTION_SHOW_MODEL_BREAKPOINT	= "ShowBreakpointsForModel";//$NON-NLS-1$
	private static final String ACTION_REMOVE_FROM_GROUP 		= "RemoveFromGroup"; 		//$NON-NLS-1$
	
	
	private static final String KEY_VALUE						= "value";					//$NON-NLS-1$

	private Clipboard fClipboard;	
	private IBreakpointOrganizer[] fOrganizers;

	/**
	 * Flag used to determine whether the viewer input is being set for the 
	 * fist time.  If this is the case the view contents are expanded.
	 * (bug 297762)
	 */
	private boolean fFirstInputSet = false;
	
	public void dispose() {
		if (fClipboard != null)
			fClipboard.dispose();		
		DebugPlugin.getDefault().getBreakpointManager().removeBreakpointManagerListener(this);
		super.dispose();
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#getDetailPanePreferenceKey()
	 */
	protected String getDetailPanePreferenceKey() {
		return IDebugPreferenceConstants.BREAKPOINTS_DETAIL_PANE_ORIENTATION;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#getHelpContextId()
	 */
	protected String getHelpContextId() {
		return IDebugHelpContextIds.BREAKPOINT_VIEW;
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#getViewerStyle()
	 */
	protected int getViewerStyle() {
		return SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.VIRTUAL | SWT.FULL_SELECTION | SWT.CHECK;
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#createViewer(org.eclipse.swt.widgets.Composite)
	 */
	public Viewer createViewer(Composite parent) {
		TreeModelViewer viewer = (TreeModelViewer) super.createViewer(parent);		
		
		initBreakpointOrganizers(getMemento());

		IPresentationContext presentationContext = viewer.getPresentationContext(); 
		presentationContext.setProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_ORGANIZERS, fOrganizers);
        presentationContext.setProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_ELEMENT_COMPARATOR, new ElementComparator(presentationContext));

        return viewer;
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#getModelPresentation()
	 */
	protected IDebugModelPresentation getModelPresentation() {
		if (fModelPresentation == null) {
			fModelPresentation = new VariablesViewModelPresentation() {
				/**
				 * Undo double slashes.
				 */
				public String getText(Object element) {					
			    	IDebugModelPresentation lp= getConfiguredPresentation(element);
			    	if (lp != null) {
			    		return lp.getText(element);
			    	}
			    	return getDefaultText(element);
				}
			};
		}
		return fModelPresentation;
	}
	
	/**
	 * Returns the tree model viewer.
	 * @return
	 */
	public TreeModelViewer getTreeModelViewer() {
		return (TreeModelViewer) getViewer();
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#configureToolBar(org.eclipse.jface.action.IToolBarManager)
	 */
	protected void configureToolBar(IToolBarManager tbm) {
		tbm.add(new Separator(IDebugUIConstants.BREAKPOINT_GROUP));
		tbm.add(getAction(ACTION_SHOW_MODEL_BREAKPOINT)); 
		tbm.add(getAction(ACTION_GOTO_MARKER));
		tbm.add(getAction(ACTION_SKIP_BREAKPOINTS));
		tbm.add(new Separator(IDebugUIConstants.RENDER_GROUP));
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#fillContextMenu(org.eclipse.jface.action.IMenuManager)
	 */
	protected void fillContextMenu(IMenuManager menu) {
		updateObjects();
		menu.add(new Separator(IDebugUIConstants.EMPTY_NAVIGATION_GROUP));
		menu.add(new Separator(IDebugUIConstants.NAVIGATION_GROUP));
		menu.add(getAction(ACTION_GOTO_MARKER));
		menu.add(new Separator(IDebugUIConstants.EMPTY_BREAKPOINT_GROUP));
		menu.add(new Separator(IDebugUIConstants.BREAKPOINT_GROUP));
		menu.add(getAction(PASTE_ACTION));
		IAction action = getAction(ACTION_REMOVE_FROM_GROUP);
		if (action != null && action.isEnabled()) {
			menu.add(action);
		}
		menu.add(new Separator(IDebugUIConstants.EMPTY_RENDER_GROUP));
		action = new AvailableDetailPanesAction(this);
		if (isDetailPaneVisible() && action.isEnabled()) {
			menu.add(action);
		}
		menu.add(new Separator(IDebugUIConstants.BREAKPOINT_GROUP_GROUP));

		menu.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#createActions()
	 */
	protected void createActions() {
		IAction action = new OpenBreakpointMarkerAction(getViewer());
		setAction(ACTION_GOTO_MARKER, action);
		setAction(DOUBLE_CLICK_ACTION, action);
		setAction(ACTION_SHOW_MODEL_BREAKPOINT, new ShowTargetBreakpointsAction(this));
		setAction(ACTION_SKIP_BREAKPOINTS, new SkipAllBreakpointsAction(this));
        DebugPlugin.getDefault().getBreakpointManager().addBreakpointManagerListener(this);

		fClipboard = new Clipboard(getSite().getShell().getDisplay());
		        
		PasteBreakpointsAction paste = new PasteBreakpointsAction(this);
		setAction(PASTE_ACTION, paste);
		paste.setActionDefinitionId(ActionFactory.PASTE.getCommandId());
        getViewSite().getActionBars().setGlobalActionHandler(ActionFactory.PASTE.getId(), paste);
        getViewer().addSelectionChangedListener(paste);
        paste.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_TOOL_PASTE));
		        
		SelectionListenerAction remove = new RemoveFromWorkingSetAction(this);
		setAction(ACTION_REMOVE_FROM_GROUP, remove);
		getViewer().addSelectionChangedListener(remove);
		
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @seeorg.eclipse.debug.internal.ui.views.variables.VariablesView#getToggleActionLabel()
	 */
	protected String getToggleActionLabel() {
		return DebugUIViewsMessages.BreakpointsView_12;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#getPresentationContextId()
	 */
	protected String getPresentationContextId() {
		return IDebugUIConstants.ID_BREAKPOINT_VIEW;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#contextActivated(org.eclipse.jface.viewers.ISelection)
	 */
	protected void contextActivated(ISelection selection) {
	    IPresentationContext presentationContext = getTreeModelViewer().getPresentationContext();
	    
		if (selection == null || selection.isEmpty()) {
			Object input = new DefaultBreakpointsViewInput(presentationContext);
			super.contextActivated(new StructuredSelection(input));
		} else {
			super.contextActivated(selection);
		}
		if (isAvailable() && isVisible()) {
			updateAction("ContentAssist"); //$NON-NLS-1$
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#setViewerInput(java.lang.Object)
	 */
	protected void setViewerInput(Object context) {
		Object current = getViewer().getInput();
		if (current == null && context == null) {
			return;
		}

		if (current != null && current.equals(context)) {
			return;
		}
		
		showViewer();
		getViewer().setInput(context);
		
		// Expand all elements when the view is first shown. (bug 297762)
		if (!fFirstInputSet) {
		    fFirstInputSet = true;
		    expandAllElementsInViewer();
		}
	}
		
	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#viewerInputUpdateComplete(org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerInputUpdate)
	 */
	protected void viewerInputUpdateComplete(IViewerInputUpdate update) {		
		// handles non-standard debug model
	    IStatus status = update.getStatus();
        if ( (status == null || status.isOK()) && update.getElement() != null) {
            setViewerInput(update.getInputElement());
        } else {
            setViewerInput(new DefaultBreakpointsViewInput(getTreeModelViewer().getPresentationContext()));
        }
	}
	
	
	/**
	 * Returns whether this view is currently tracking the selection from the debug view.
	 * 
	 * @return whether this view is currently tracking the debug view's selection
	 */
	public boolean isTrackingSelection() {
        final TreeModelViewer viewer = getTreeModelViewer();
        if (viewer != null) {
            return Boolean.TRUE.equals( 
                viewer.getPresentationContext().getProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_TRACK_SELECTION) );
        }
        return false;
	}

	/**
	 * Sets whether this view should track the selection from the debug view.
	 * 
	 * @param trackSelection whether or not this view should track the debug view's selection.
	 */
	public void setTrackSelection(boolean trackSelection) {
		// set the track selection property for non-standard model to track the debug context
		final TreeModelViewer viewer = getTreeModelViewer();
		if (viewer != null) {
			viewer.getPresentationContext().setProperty(
			    IBreakpointUIConstants.PROP_BREAKPOINTS_TRACK_SELECTION, 
			    trackSelection ? Boolean.TRUE : Boolean.FALSE);
		}
	}
	
	/**
	 * Initializes the persisted breakpoints organizers.
	 */
	private void initBreakpointOrganizers(IMemento memento) {
		if (memento != null) {
			IMemento node = memento.getChild(IDebugUIConstants.EXTENSION_POINT_BREAKPOINT_ORGANIZERS);
			if (node == null) {
				fOrganizers = null;
            } else {
				String value = node.getString(KEY_VALUE);
                if (value != null) {
                    String[] ids = value.split(","); //$NON-NLS-1$
    				BreakpointOrganizerManager manager = BreakpointOrganizerManager.getDefault();
    				List organziers= new ArrayList();
                    for (int i = 0; i < ids.length; i++) {
                        IBreakpointOrganizer organizer = manager.getOrganizer(ids[i]);
                        if (organizer != null) {
                            organziers.add(organizer);
                        }
                    }
                    fOrganizers = (IBreakpointOrganizer[]) organziers.toArray(new IBreakpointOrganizer[organziers.size()]);
                    
                    for (int i = 0; i < fOrganizers.length; i++)
        				fOrganizers[i].addPropertyChangeListener(this);
                }
			}
		}
	}
	
    /**
     * Initializes drag and drop for the breakpoints viewer
     */
	protected void initDragAndDrop(TreeModelViewer viewer) {
        int ops = DND.DROP_MOVE | DND.DROP_COPY;
        // drop
        viewer.addDropSupport(ops, new Transfer[] {LocalSelectionTransfer.getTransfer()}, new BreakpointsDropAdapter(viewer, this));
        // Drag
        viewer.addDragSupport(ops, new Transfer[] {LocalSelectionTransfer.getTransfer()}, new BreakpointsDragAdapter(viewer, this));
    }
 
	/*
	 * (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.views.variables.VariablesView#saveViewerState(org.eclipse.ui.IMemento)
	 */
	public void saveViewerState(IMemento memento) {
		StringBuffer buffer = new StringBuffer();
        if (fOrganizers != null) {
            for (int i = 0; i < fOrganizers.length; i++) {
                IBreakpointOrganizer organizer = fOrganizers[i];
                buffer.append(organizer.getIdentifier());
                if (i < (fOrganizers.length - 1)) {
                    buffer.append(',');
                }
            }
            IMemento node = memento.createChild(IDebugUIConstants.EXTENSION_POINT_BREAKPOINT_ORGANIZERS);
            node.putString(KEY_VALUE, buffer.toString());
        }
		super.saveViewerState(memento);
	}
	
	/**
	 * Preserves the selection.
	 * 
	 * @param selection the selection
	 */
	public void preserveSelection(IStructuredSelection selection) {
		if (selection instanceof ITreeSelection && !selection.isEmpty()) {
			TreePath path = ((ITreeSelection) selection).getPaths()[0];
			TreeItem item = (TreeItem) ((TreeModelViewer) getViewer()).findItem(path);
			Object toselect = null;
			TreeItem[] siblings = null;
			if (item != null) {
				TreeItem parent = item.getParentItem();
				if (parent != null) {
					siblings = parent.getItems();
				} else {
					siblings = item.getParent().getItems();
				}
				if (siblings.length > 1) {
					for (int i = 0; i < siblings.length; i++) {
						if (item.equals(siblings[i])) {
							if (i + 1 >= siblings.length) {
								toselect = siblings[i - 1].getData();
								break;
							} else {
								toselect = siblings[i + 1].getData();
								break;
							}

						}
					}
				}
			}
			if (toselect != null) {
				getViewer().setSelection(new StructuredSelection(toselect),true);
			}
		}
	}

	/**
	 * Sets the breakpoint organizers for this view.
	 * 
	 * @param organizers the organizers, can be <code>null</code>.
	 */
	public void setBreakpointOrganizers(IBreakpointOrganizer[] organizers) {
		fOrganizers = organizers;
		
		TreeModelViewer viewer = getTreeModelViewer();
		if (viewer != null) {
			// update the presentation context organizer
			viewer.getPresentationContext().setProperty(IBreakpointUIConstants.PROP_BREAKPOINTS_ORGANIZERS, fOrganizers);			
		}
	}

	/**
	 * Sets the breakpoint filter for this view.
	 * 
	 * @param ss the selection, can be <code>null</code>.
	 */
	public void setFilterSelection(boolean filter) {
		TreeModelViewer viewer = getTreeModelViewer();
		if (viewer != null) {
			// update the presentation context filter
			viewer.getPresentationContext().setProperty(
			    IBreakpointUIConstants.PROP_BREAKPOINTS_FILTER_SELECTION, filter ? Boolean.TRUE : Boolean.FALSE);
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.IBreakpointManagerListener#breakpointManagerEnablementChanged(boolean)
	 */
	public void breakpointManagerEnablementChanged(boolean enabled) {
		DebugUIPlugin.getStandardDisplay().asyncExec(new Runnable() {
			public void run() {
				IAction action = getAction(ACTION_SKIP_BREAKPOINTS);
				if (action != null) {
					((SkipAllBreakpointsAction) action).updateActionCheckedState();
				}
			}
		});
	}
	
	/**
	 * Expands all elements in the viewer.
	 */
	public void expandAllElementsInViewer() {
        Display display = getSite().getShell().getDisplay(); 
        
        final VirtualTreeModelViewer virtualViewer = new VirtualTreeModelViewer(
            display, 0, ((ITreeModelViewer)getViewer()).getPresentationContext());
        
        virtualViewer.setAutoExpandLevel(-1);
        virtualViewer.addViewerUpdateListener(new IViewerUpdateListener() {
            public void viewerUpdatesComplete() {
            	ModelDelta stateDelta = new ModelDelta(virtualViewer.getInput(), IModelDelta.NO_CHANGE);
                virtualViewer.saveElementState(TreePath.EMPTY, stateDelta, IModelDelta.EXPAND);
                ITreeModelViewer treeModelViewer = ((ITreeModelViewer) getViewer());
                if (treeModelViewer != null) {
                    ((ITreeModelViewer) getViewer()).updateViewer(stateDelta);
                }
                virtualViewer.dispose();
            }
            public void viewerUpdatesBegin() {}
            public void updateStarted(IViewerUpdate update) {}
            public void updateComplete(IViewerUpdate update) {}
        });
        virtualViewer.setInput(getViewer().getInput());
	}
	
	
	/**
	 * Returns the breakpoint organizers for this view.
	 * 
	 * @return the breakpoint organizers.
	 */
	public IBreakpointOrganizer[] getBreakpointOrganizers() {
		return fOrganizers;
	}
	
	/**
     * Returns whether the given selection can be pasted into the given target.
     * <p>
     * Scheme:
     * <ul>
     * <li>Breakpoints can only be pasted into allowable containers (i..e. like workings sets)</li>
     * <li>Breakpoints can only be pasted into containers that they do not already reside in</li>
     * <li>Breakpoints can only be pasted into containers, not other breakpoints</li>
     * </ul>
     * </p>
     * 
     * @param target target of the paste
     * @param selection the selection to paste
     * @return whether the given selection can be pasted into the given target
     * 
     * TODO Remove in favor of using <code>TreeItem</code>s and <code>TreePath</code>s to determine paste targets
     */
    public boolean canPaste(Object target, ISelection selection) {
    	if(!(target instanceof IBreakpointContainer) || !(selection instanceof IStructuredSelection)) {
    		return false;
    	}
    	if(selection == null || selection.isEmpty()) {
    		return false;
    	}
    	IStructuredSelection ss = (IStructuredSelection) selection;
    	IBreakpointContainer container = (IBreakpointContainer) target;
    	for(Iterator iter = ss.iterator(); iter.hasNext();) {
    		IBreakpoint breakpoint = (IBreakpoint)DebugPlugin.getAdapter(iter.next(), IBreakpoint.class);
    		if (breakpoint == null || container.contains(breakpoint) || !container.getOrganizer().canAdd(breakpoint, container.getCategory())) {
                return false;
            }
    	}
        return true;
    }   
	
    /** 
     * Pastes the selection into the given target
     * 
     * @param target target of the paste, either a IBreakpointContainer,
     * or a Breakpoint within a IBreakpointContainer
     * @param selection breakpoints
     * @return whether successful
     * 
     * TODO remove in favor of using <code>TreeItem</code> as paste target 
     */
    public boolean performPaste(Object target, ISelection selection) {
        if (target instanceof IBreakpointContainer && selection instanceof IStructuredSelection) {
            IBreakpointContainer container = (IBreakpointContainer) target;            
            Object[] objects = ((IStructuredSelection)selection).toArray();
            for (int i = 0; i < objects.length; i++) {
                IBreakpoint breakpoint = (IBreakpoint)DebugPlugin.getAdapter(objects[i], IBreakpoint.class);
                if (breakpoint != null) {
                    container.getOrganizer().addBreakpoint(breakpoint, container.getCategory());
                }
            }
            return true;
        }
        return false;
    }
    
	/**
     * Returns the container from within the specified path that is the container the breakpoint can be removed from
     * @param breakpoint the breakpoint to get the container for
     * @return the first found container that includes the breakpoint that allows removal, or <code>null</code> if none found
     * @since 3.3
     */
    public IBreakpointContainer getRemovableContainer(TreePath path) {
    	if (path != null) {
            IBreakpoint breakpoint = (IBreakpoint)DebugPlugin.getAdapter(path.getLastSegment(), IBreakpoint.class);
        	if (breakpoint != null) {
		    	IBreakpointContainer container = null;
		    	for(int i = path.getSegmentCount()-2; i > -1; i--) {
		    	    Object segment = path.getSegment(i); 
		    	    if (segment instanceof IBreakpointContainer) {
    		    		container = (IBreakpointContainer) segment;
    		    		if(container.contains(breakpoint) && 
    		    			container.getOrganizer() != null && 
    		    			container.getOrganizer().canRemove(breakpoint, container.getCategory())) {
    		    			return container;
    		    		}
		    	    }
		    	}
        	}
    	}
    	return null;
    }

    /**
     * Returns the addable breakpoint container of the specified tree path
     * @param breakpoint the breakpoint to get the container for
     * @return the first found addable container for the specified tree path or <code>null</code> if none found
     * @since 3.3
     */
    protected IBreakpointContainer getAddableContainer(TreePath path) {
    	if (path != null) {
	    	Object element = path.getLastSegment();
	    	if (element instanceof IBreakpointContainer) {
	    		return (IBreakpointContainer)element;
	    	}
            IBreakpoint breakpoint = (IBreakpoint)DebugPlugin.getAdapter(element, IBreakpoint.class);
	    	if (breakpoint != null) {
		    	IBreakpointContainer container = null;
		    	for (int i = path.getSegmentCount()-2; i > -1; i--) {
                    Object segment = path.getSegment(i); 
                    if (segment instanceof IBreakpointContainer) {
                        container = (IBreakpointContainer) segment;
    		    		if (container.contains(breakpoint) && container.getOrganizer().canAdd(breakpoint, container.getCategory())) {
    		    			return container;
    		    		}
                    }
		    	}
	    	}
    	}
    	return null;
    }
	/**
	 * This method is used to determine if there is an addable parent container available for the specified drop target.
	 * <p>
	 * A drop target can be either a <code>IBreakpointContainer</code> or an <code>IBreakpoint</code>. This method always checks the entire heirarchy
	 * of the tree path for the specified target in the event one of the parent element does not support dropping. 
	 * </p>
	 * @param target
	 * @param breakpoint
	 * @return
	 */
	private boolean checkAddableParentContainers(TreePath path, IBreakpoint breakpoint) {
		if (path != null) {
			Object element = null;
			for (int i = path.getSegmentCount()-1; i > -1; i--) {
				element = path.getSegment(i);
				if (element instanceof IBreakpointContainer) {
					IBreakpointContainer container = (IBreakpointContainer) element;
					if (container.contains(breakpoint) || !container.getOrganizer().canAdd(breakpoint, container.getCategory())) {
		    			return false;
		    		}
				}
			}
		}
		return true;
	}
	
    /**
     * Returns if the selected item in the tree can be dragged
     * <p>
     * Scheme:
     * <ul>
     * <li>breakpoint containers cannot be dragged</li>
     * <li>breakpoints can be dragged iff the container they reside in supports the removal of breakpoints</li>
     * </ul>
     * </p>
     * @param element the element to test if it can be dragged
     * @return true if the selected element can be dragged, false otherwise
     * @since 3.3
     */
    boolean canDrag(TreePath[] items) {
    	if(items == null) {
    		return false;
    	}
    	if (items.length == 0) {
    		return false;
    	}
    	for (int i = 0; i < items.length; i++) {
    		if (getRemovableContainer(items[i]) == null) {
    			return false;
    		}
    	}
    	return true;
    }
    
    /**
     * Performs the actual removal of breakpoints from their respective (removable) containers on a successful drag operation
     * @param selection the selection of breakpoints involved in the drag
     * @since 3.3
     */
    void performDrag(TreePath[] paths) {
    	if (paths == null) {
    		return;
    	}

    	Map containersToBreakpoints = new HashMap();
    	for (int i = 0; i < paths.length; i++) {
            IBreakpoint breakpoint = (IBreakpoint)DebugPlugin.getAdapter(paths[i].getLastSegment(), IBreakpoint.class);
    		if (breakpoint != null) {
	    		IBreakpointContainer container = getRemovableContainer(paths[i]);
	    		if(container != null) {
	    			List list = (List) containersToBreakpoints.get(container);
	    			if (list == null) {
	    				list = new ArrayList();
	    				containersToBreakpoints.put(container, list);
	    			}
	    			list.add(breakpoint);
	    		}
    		}
    	}
    	Iterator iterator = containersToBreakpoints.entrySet().iterator();
    	while (iterator.hasNext()) {
    		Entry entry = (Entry) iterator.next();
    		IBreakpointContainer container = (IBreakpointContainer) entry.getKey();
    		List list = (List) entry.getValue();
    		IBreakpointOrganizer organizer = container.getOrganizer();
    		IBreakpoint[] breakpoints = (IBreakpoint[]) list.toArray(new IBreakpoint[list.size()]);
    		if (organizer instanceof IBreakpointOrganizerDelegateExtension) {
				IBreakpointOrganizerDelegateExtension extension = (IBreakpointOrganizerDelegateExtension) organizer;
				extension.removeBreakpoints(breakpoints, container.getCategory());
			} else {
				for (int i = 0; i < breakpoints.length; i++) {
					organizer.removeBreakpoint(breakpoints[i], container.getCategory());
				}
			}
    	}
    }	
    
	/**
     * Performs the actual addition of the selected breakpoints to the specified target
     * @param target the target to add the selection of breakpoints to
     * @param selection the selection of breakpoints
     * @return true if the drop occurred, false otherwise
     * @since 3.3
     */
    protected boolean performDrop(TreePath target, ITreeSelection selection) {
		if(target == null || selection == null) {
    		return false;
    	}
    	IBreakpointContainer container = getAddableContainer(target);
    	if (container == null) {
			return false;
		}
    	
    	IBreakpointOrganizer organizer = container.getOrganizer();
        List breakpoints = new ArrayList(selection.size());
        for (Iterator iter = selection.iterator(); iter.hasNext();) {
            IBreakpoint breakpoint = (IBreakpoint) DebugPlugin.getAdapter(iter.next(), IBreakpoint.class);
            if (breakpoint != null) {
                breakpoints.add(breakpoint);
            }
        }
    	if (organizer instanceof IBreakpointOrganizerDelegateExtension) {
    		IBreakpointOrganizerDelegateExtension extension = (IBreakpointOrganizerDelegateExtension) organizer;
    		extension.addBreakpoints(
    		    (IBreakpoint[])breakpoints.toArray(new IBreakpoint[breakpoints.size()]), 
    		    container.getCategory());
    	} else {
	    	for (int i = 0; i < breakpoints.size(); i++) {
	    	    organizer.addBreakpoint((IBreakpoint)breakpoints.get(i), container.getCategory());
	    	}
    	}
    	// TODO expandToLevel(target.getData(), ALL_LEVELS);
    	
    	return true;
    }

    /**
     * Determines if the specified element can be dropped into the specified target
     * <p>
     * Scheme:
     * <ul>
     * <li>Breakpoints can be dropped into working sets</li>
     * <li>Breakpoints can be dropped into breakpoints, provided there is a drop-able parent of the target breakpoint</li>
     * </ul>
     * </p>
     * @param target the target for the drop
     * @param element the element we want to drop
     * @return true if the specified element can be dropped into the specified target, false otherwise
     * @since 3.3
     */
    boolean canDrop(TreePath target, ITreeSelection selection) {
    	if (selection == null  || target == null) {
    		return false;
    	}
    	for(Iterator iter = selection.iterator(); iter.hasNext();) {
            IBreakpoint breakpoint = (IBreakpoint)DebugPlugin.getAdapter(iter.next(), IBreakpoint.class);

    		if (breakpoint == null || !checkAddableParentContainers(target, breakpoint)){
    			return false;
    		}
    	}
    	return true;
    }
}
