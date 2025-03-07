/*******************************************************************************
 * Copyright (c) 2007, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.ui.contextlaunching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.internal.core.IInternalDebugCoreConstants;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.IInternalDebugUIConstants;
import org.eclipse.debug.internal.ui.ILaunchHistoryChangedListener;
import org.eclipse.debug.internal.ui.ILaunchLabelChangedListener;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationManager;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchShortcutExtension;
import org.eclipse.debug.internal.ui.stringsubstitution.SelectedResourceManager;
import org.eclipse.debug.ui.ILaunchGroup;
import org.eclipse.jface.action.CoolBarManager;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.ToolBarContributionItem;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.activities.WorkbenchActivityHelper;
import org.eclipse.ui.internal.WorkbenchWindow;

import com.ibm.icu.text.MessageFormat;

/**
 * This manager is used to calculate the labels for the current resource or for the current 
 * state of the launch history, depending on the enabled status of contextual launching. More specifically
 * if contextual launching is enabled the calculated labels are for the current resource, otherwise 
 * the calculated labels are for the current state of the launch history.
 * 
 * Any actions interested in being notified of launch label updates need to register with this manager, and implement
 * the <code>ILaunchLabelChangedListener</code> interface.
 * 
 * @see ILaunchLabelChangedListener
 * @see org.eclipse.debug.ui.actions.AbstractLaunchHistoryAction
 * 
 * @since 3.3
 */
public class LaunchingResourceManager implements IPropertyChangeListener, IWindowListener, ISelectionListener, ILaunchHistoryChangedListener, ILaunchesListener2 {
	
	/**
	 *The set of label update listeners
	 */
	private ListenerList fLabelListeners = new ListenerList(); 
	
	/**
	 * The map of ToolBars that have mouse tracker listeners associated with them:
	 * stored as Map<IWorkbenchWindow, ToolBar>
	 */
	private HashMap fToolbars = new HashMap();
	
	/**
	 * the map of current labels
	 */
	private HashMap fCurrentLabels = new HashMap();
	
	/**
	 * The selection has changed and we need to update the labels
	 */
	private boolean fUpdateLabel = true;
	
	/**
	 * Set of windows that have been opened and that we have registered selection listeners with
	 */
	private HashSet fWindows = new HashSet();
	
	/**
	 * Cache of IResource -> ILaunchConfiguration[] used during a tooltip update job. 
	 * The cache is cleared after each tooltip update job is complete.
	 */
	private HashMap fConfigCache = new HashMap();
	
	/**
	 * Cache of IResource -> LaunchShortcutExtension used during a tooltip update job.
	 * The cache is cleared after each tooltip update job is complete.
	 */
	private HashMap fExtCache = new HashMap();
	
	/**
	 * Constant denoting the empty string;
	 */
	private static final String EMPTY_STRING = IInternalDebugCoreConstants.EMPTY_STRING;
	
	/**
	 * Provides a mouse tracker listener for the launching main toolbar 
	 */
	private MouseTrackAdapter fMouseListener = new MouseTrackAdapter() {
		public void mouseEnter(MouseEvent e) {
			if(fUpdateLabel) {
				fUpdateLabel = false;
				fCurrentLabels.clear();
				Job job = new Job("Compute launch button tooltip") { //$NON-NLS-1$
					protected IStatus run(IProgressMonitor monitor) {
						computeLabels();
						fConfigCache.clear();
						fExtCache.clear();
						return Status.OK_STATUS;
					}
				};
				job.setSystem(true);
				job.schedule();
			}
		}
	};
	
	/**
	 * Returns if context launching is enabled
	 * @return if context launching is enabled
	 */
	public static boolean isContextLaunchEnabled() {
		return DebugUIPlugin.getDefault().getPreferenceStore().getBoolean(IInternalDebugUIConstants.PREF_USE_CONTEXTUAL_LAUNCH);
	}
	
	/**
	 * Returns if context launching is enabled or not. Context launching is enabled iff:
	 * <ul>
	 * <li>The preference is turned on</li>
	 * <li>the launch group id is not <code>org.eclipse.ui.externaltools.launchGroup</code></li>
	 * </ul>
	 * @param launchgroupid
	 * @return <code>true</code> if context launching is enabled <code>false</code> otherwise
	 */
	public static boolean isContextLaunchEnabled(String launchgroupid) {
		return isContextLaunchEnabled() && !"org.eclipse.ui.externaltools.launchGroup".equals(launchgroupid); //$NON-NLS-1$
	}
	
	/**
	 * Allows an <code>AbstractLaunchHistoryAction</code> to register with this manager to be notified
	 * of a context (<code>IResource</code>) change and have its updateToolTip(..) method called back to.
	 * @param action the action to add
	 * @param group the launch group
	 * @return true if the <code>AbstractLaunchHistoryAction</code> was added as a listener, false otherwise
	 */
	public void addLaunchLabelUpdateListener(ILaunchLabelChangedListener listener) {
		fLabelListeners.add(listener);
	}
	
	/**
	 * Removes the specified <code>AbstractLaunchHistoryAction</code> from the listing of registered 
	 * listeners
	 * @param action the action to remove
	 * @param group the launch group
	 * @return true if the action was removed from the listing of <code>AbstractLaunchHistoryAction</code> listeners,
	 * false otherwise
	 */
	public void removeLaunchLabelChangedListener(ILaunchLabelChangedListener listener) {
		fLabelListeners.remove(listener);
	}
	
	/**
	 * Returns the current resource label to be displayed.
	 * 
	 * @param group the launch group to get the label for
	 * @return the current resource label;
	 */
	public String getLaunchLabel(ILaunchGroup group) {
		return (String) fCurrentLabels.get(group);
	}
	
	/**
	 * Returns if the parent project should be checked automatically
	 * @return true if the parent project should checked automatically, false otherwise
	 */
	protected boolean shouldCheckParent() {
		return DebugUIPlugin.getDefault().getPreferenceStore().getBoolean(IInternalDebugUIConstants.PREF_LAUNCH_PARENT_PROJECT);
	}
	
	/**
	 * Returns if the the last launch configuration should be launched if the selected resource is not launchable and context launching is enabled
	 * @return true if the last launched should be launched, false otherwise
	 */
	protected boolean shouldLaunchLast() {
		return DebugUIPlugin.getDefault().getPreferenceStore().getBoolean(IInternalDebugUIConstants.PREF_LAUNCH_LAST_IF_NOT_LAUNCHABLE);
	}
	
	/**
	 * Computes the current listing of labels for the given <code>IResource</code> context change or the 
	 * current launch history changed event
	 */
	protected void computeLabels() {
		ILaunchGroup group = null;
		ILaunchConfiguration config = null;
		String label = null;
		Object[] listeners = fLabelListeners.getListeners();
		SelectedResourceManager srm = SelectedResourceManager.getDefault();
		IStructuredSelection selection = srm.getCurrentSelection();
		List shortcuts = null;
		IResource resource = srm.getSelectedResource();
		for(int i = 0; i < listeners.length; i++) {
			group = ((ILaunchLabelChangedListener)listeners[i]).getLaunchGroup();
			if(group != null) {
				if(isContextLaunchEnabled(group.getIdentifier())) {
					shortcuts = getShortcutsForSelection(selection, group.getMode());
					if(resource == null) {
						resource = getLaunchableResource(shortcuts, selection);
					}
					label = getLabel(selection, resource, shortcuts, group);
				}
				else {
					config = DebugUIPlugin.getDefault().getLaunchConfigurationManager().getFilteredLastLaunch(group.getIdentifier());
					if(config != null) {
						label = appendLaunched(config);
					}
				}
				fCurrentLabels.put(group, label);
				label = null;
			}
		}
		notifyLabelChanged();
	}
	
	/**
	 * Notifies all registered listeners that the known labels have changed
	 */
	protected void notifyLabelChanged() {
		Object[] listeners = fLabelListeners.getListeners();
		for(int i = 0; i < listeners.length; i++) {
			((ILaunchLabelChangedListener)listeners[i]).labelChanged();
		}
	}
	
	/**
	 * Appends the text '(already running)' to the tooltip label if there is a launch currently
	 * running (not terminated) with the same backing launch configuration as the one specified
	 * @param config
	 * @return the appended string for the tooltip label or the configuration name (default)
	 */
	private String appendLaunched(ILaunchConfiguration config) {
		ILaunch[] launches = DebugPlugin.getDefault().getLaunchManager().getLaunches();
		boolean launched = false;
		ILaunchConfiguration tmp = null;
		for(int i = 0; i < launches.length; i++) {
			tmp = launches[i].getLaunchConfiguration();
			if(tmp != null) {
				if(!launches[i].isTerminated() && tmp.equals(config)) {
					launched = true;
					break;
				}
			}
		}
		if(launched) {
			return MessageFormat.format(ContextMessages.LaunchingResourceManager_0, new String[] {config.getName()});
		}
		return config.getName();
	}
	
	/**
	 * Returns the label for the last launched configuration or and empty string if there was no last launch.
	 * @param group
	 * @return the name of the last launched configuration, altered with '(running)' if needed, or the empty
	 * string if there is no last launch.
	 */
	protected String getlastLaunchedLabel(ILaunchGroup group) {
		ILaunchConfiguration config = DebugUIPlugin.getDefault().getLaunchConfigurationManager().getFilteredLastLaunch(group.getIdentifier());
		if(config != null) {
			return appendLaunched(config);
		}
		return EMPTY_STRING;
	}
	
	/**
	 * Returns the label for the specified resource or the empty string, never <code>null</code>
	 * @param resource
	 * @param group
	 * @return the label for the resource or the empty string, never <code>null</code>
	 */
	protected String getLabel(IStructuredSelection selection, IResource resource, List shortcuts, ILaunchGroup group) {
		List sc = pruneShortcuts(shortcuts, resource, group.getMode());
		LaunchConfigurationManager lcm = DebugUIPlugin.getDefault().getLaunchConfigurationManager();
		//see if the context is a shared configuration
		ILaunchConfiguration config = lcm.isSharedConfig(resource);
		if(config != null) {
			return appendLaunched(config);
		}
		//TODO cache the results ?
 		List configs = getParticipatingLaunchConfigurations(selection, resource, sc, group.getMode());
		int csize = configs.size();
		if(csize == 1) {
			return appendLaunched((ILaunchConfiguration)configs.get(0));
		}
		else if(csize > 1) {
			config = lcm.getMRUConfiguration(configs, group, resource);
			if(config != null) {
				return appendLaunched(config);
			}
			else {
				return ContextMessages.ContextRunner_14;
			}
		}
		else {
			List exts = (List) fExtCache.get(resource);
			if(exts == null && resource != null) {
				fExtCache.put(resource, sc);
			}
			int esize = sc.size();
			if(esize == 0) {
				if(resource != null && shouldCheckParent()) {
					IProject project = resource.getProject();
					if(project != null && !project.equals(resource)) {
						return getLabel(selection, project, sc, group);
					}
				}
				else if(shouldLaunchLast() || resource == null) {
					return getlastLaunchedLabel(group);
				}
				else {
					return ContextMessages.ContextRunner_15;
				}
			}
			if(esize == 1) {
				if(resource != null) {
					return resource.getName();
				}
				else {
					return MessageFormat.format(ContextMessages.LaunchingResourceManager_1, new String[] {((LaunchShortcutExtension) sc.get(0)).getLabel()});
				}
			}
			else {
				return ContextMessages.ContextRunner_14;
			}
		}
	}
	
	/**
	 * Prunes the original listing of shortcuts
	 * @param shortcuts the original listing of <code>LaunchShortcutExtension</code>s
	 * @param resource the derived resource
	 * 
	 * @since 3.4
	 */
	protected List pruneShortcuts(List shortcuts, IResource resource, String mode) {
		List list = new ArrayList(shortcuts);
		if(resource == null) {
			LaunchShortcutExtension ext = null;
			for(ListIterator iter = list.listIterator(); iter.hasNext();) {
				ext = (LaunchShortcutExtension) iter.next();
				if(!ext.isParticipant()) {
					iter.remove();
				}
			}
		}
		else {
			list = getShortcutsForSelection(new StructuredSelection(resource), mode);
		}
		return list;
	}
	
	/**
	 * Computes the current resources context, given all of the launch shortcut participants
	 * and the current selection
	 * @param shortcuts
	 * @param selection
	 * @return The set of resources who care about this launch
	 * 
	 * @since 3.4
	 */
	public IResource getLaunchableResource(List shortcuts, IStructuredSelection selection) {
		if(selection != null && !selection.isEmpty()) {
			ArrayList resources = new ArrayList();
			IResource resource = null;
			Object o = selection.getFirstElement();
			LaunchShortcutExtension ext = null;
			for(Iterator iter = shortcuts.iterator(); iter.hasNext();) {
				ext = (LaunchShortcutExtension) iter.next();
				if(o instanceof IEditorPart) {
					resource = ext.getLaunchableResource((IEditorPart) o);
				}
				else {
					resource = ext.getLaunchableResource(selection);
				}
				if(resource != null && !resources.contains(resource)) {
					resources.add(resource);
					resource = null;
				}
			}
			if(resources.size() > 0) {
				return (IResource) resources.get(0);
			}
		}
		return null;
	}
	
	/**
	 * Returns the launch shortcuts that apply to the current <code>IStructuredSelection</code>
	 * @param selection the current selection
	 * @param mode the mode
	 * @return the list of shortcuts that apply to the given selection and mode or an empty listing, never <code>null</code>
	 * 
	 * @since 3.4
	 */
	public List getShortcutsForSelection(IStructuredSelection selection, String mode) {
		ArrayList list = new ArrayList();
		List sc = DebugUIPlugin.getDefault().getLaunchConfigurationManager().getLaunchShortcuts();
		List ctxt = new ArrayList();
		// work around to bug in Structured Selection that returns actual underlying array in selection
		// @see bug 211646 
		ctxt.addAll(selection.toList());
		Object o = selection.getFirstElement();
		if(o instanceof IEditorPart) {
			ctxt.set(0, ((IEditorPart)o).getEditorInput());
		}
		IEvaluationContext context = new EvaluationContext(null, ctxt);
		context.addVariable("selection", ctxt); //$NON-NLS-1$
		LaunchShortcutExtension ext = null;
		for(Iterator iter = sc.iterator(); iter.hasNext();) {
			ext = (LaunchShortcutExtension) iter.next();
			try {
				if(ext.evalEnablementExpression(context, ext.getContextualLaunchEnablementExpression()) && 
						ext.getModes().contains(mode) && !WorkbenchActivityHelper.filterItem(ext)) {
					if(!list.contains(ext)) {
						list.add(ext);
					}
				}
			}
			catch(CoreException ce) {}
		}
		return list;
	}
	
	/**
	 * Returns a listing of all launch configurations that want to participate in the contextual 
	 * launch of the specified resource or specified selection
	 * @param resource the underlying resource
	 * @param selection the current selection in the workbench
	 * @param shortcuts the listing of shortcut extensions that apply to the current context
	 * @param mode the mode
	 * @return a listing of all launch configurations wanting to participate in the current launching
	 * 
	 * @since 3.4
	 */
	public List getParticipatingLaunchConfigurations(IStructuredSelection selection, IResource resource, List shortcuts, String mode) {
		HashSet configs = new HashSet();
		int voteDefault = 0;
		if(selection != null) {
			Object o = selection.getFirstElement();
			LaunchShortcutExtension ext = null;
			ILaunchConfiguration[] cfgs = null;
			//TODO this falls victim to contributors code performance
			for(int i = 0; i < shortcuts.size(); i++) {
				ext = (LaunchShortcutExtension) shortcuts.get(i);
				if(o instanceof IEditorPart) {
					cfgs = ext.getLaunchConfigurations((IEditorPart)o);
				}
				else {
					 cfgs = ext.getLaunchConfigurations(selection);
				}
				if (cfgs == null) {
					Set types = ext.getAssociatedConfigurationTypes();
					addAllToList(configs, DebugUIPlugin.getDefault().getLaunchConfigurationManager().getApplicableLaunchConfigurations((String[]) types.toArray(new String[types.size()]), resource));
					voteDefault++;
				} else {
					if(cfgs.length > 0) { 
						for(int j = 0; j < cfgs.length; j++) { 
							configs.add(cfgs[j]);
						}
					}
				}
			}
		}
		if (voteDefault == shortcuts.size()) {			
			// consider default configurations if no configurations were contributed
			addAllToList(configs, DebugUIPlugin.getDefault().getLaunchConfigurationManager().getApplicableLaunchConfigurations(null, resource));
		}
		Iterator iterator = configs.iterator();
		while (iterator.hasNext()) {
			ILaunchConfiguration config = (ILaunchConfiguration) iterator.next();
			try {
				Set modes = config.getModes();
				modes.add(mode);
				if (!config.getType().supportsModeCombination(modes)) {
					iterator.remove();
				}
			} 
			catch (CoreException e) {}
		}
		return new ArrayList(configs);
	}
	
	/**
	 * Adds all of the items in the given object array to the given collection.
	 * Does nothing if either the collection or array is <code>null</code>.
	 * @param list
	 * @param values
	 */
	private void addAllToList(Collection list, Object[] values) {
		if(list == null || values == null) {
			return;
		}
		for(int i = 0; i < values.length; i++) {
			list.add(values[i]);
		}
	}
	
	/**
	 * Starts up the manager
	 */
	public void startup() {
		IWorkbench workbench = PlatformUI.getWorkbench();
		if(workbench != null) {
			workbench.addWindowListener(this);
			// initialize for already open windows
			IWorkbenchWindow[] workbenchWindows = workbench.getWorkbenchWindows();
			for (int i = 0; i < workbenchWindows.length; i++) {
				windowOpened(workbenchWindows[i]);
			}
		}
		DebugUIPlugin.getDefault().getPreferenceStore().addPropertyChangeListener(this);
		DebugUIPlugin.getDefault().getLaunchConfigurationManager().addLaunchHistoryListener(this);
		DebugPlugin.getDefault().getLaunchManager().addLaunchListener(this);
	}

	/**
	 * Shutdown and clean up the manager
	 */
	public void shutdown() {
		IWorkbench workbench = PlatformUI.getWorkbench();
		if(workbench != null) {
			workbench.removeWindowListener(this);
		}
		DebugUIPlugin.getDefault().getPreferenceStore().removePropertyChangeListener(this);
		DebugUIPlugin.getDefault().getLaunchConfigurationManager().removeLaunchHistoryListener(this);
		DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(this);
		for(Iterator iter = fWindows.iterator(); iter.hasNext();) {
			((IWorkbenchWindow)iter.next()).getSelectionService().removeSelectionListener(this);
		}
		IWorkbenchWindow window = null;
		// set fUpdateLabel to false so that mouse track listener will do nothing if called
		// before the asynchronous execution disposes them
		fUpdateLabel = false;
		for(Iterator iter = fToolbars.keySet().iterator(); iter.hasNext();) {
			window = (IWorkbenchWindow) iter.next();
			final ToolBar bar = (ToolBar) fToolbars.get(window);
			if(bar != null && !bar.isDisposed()) {
				final MouseTrackAdapter listener = fMouseListener;
				DebugUIPlugin.getStandardDisplay().asyncExec(new Runnable() {
					public void run() {
						bar.removeMouseTrackListener(listener);
					}
				});
				
			}
		}
		fWindows.clear();
		fToolbars.clear();
		fLabelListeners.clear();
		fCurrentLabels.clear();
	}

	/**
	 * @see org.eclipse.ui.IWindowListener#windowActivated(org.eclipse.ui.IWorkbenchWindow)
	 */
	public void windowActivated(IWorkbenchWindow window) {
		if(!fToolbars.containsKey(window)) {
			addMouseListener(window);
		}
	}

	/**
	 * @see org.eclipse.ui.IWindowListener#windowClosed(org.eclipse.ui.IWorkbenchWindow)
	 */
	public void windowClosed(IWorkbenchWindow window) {
		ToolBar bar = (ToolBar) fToolbars.remove(window);
		if(bar != null && !bar.isDisposed()) {
			bar.removeMouseTrackListener(fMouseListener);
		}
		if(fWindows.remove(window)) {
			window.getSelectionService().removeSelectionListener(this);
		}
	}

	/**
	 * @see org.eclipse.ui.IWindowListener#windowDeactivated(org.eclipse.ui.IWorkbenchWindow)
	 */
	public void windowDeactivated(IWorkbenchWindow window) {}

	/**
	 * @see org.eclipse.ui.IWindowListener#windowOpened(org.eclipse.ui.IWorkbenchWindow)
	 */
	public void windowOpened(IWorkbenchWindow window) {
		if(fWindows.add(window)) {
			window.getSelectionService().addSelectionListener(this);
		}
	}
	
	/**
	 * Adds a mouse listener to the launch toolbar 
	 * 
	 * @param window
	 */
	private void addMouseListener(IWorkbenchWindow window) {
		CoolBarManager cmgr = ((WorkbenchWindow)window).getCoolBarManager();
		if(cmgr != null) {
			IContributionItem item = cmgr.find("org.eclipse.debug.ui.launchActionSet"); //$NON-NLS-1$
			if(item instanceof ToolBarContributionItem) {
				ToolBarManager tmgr = (ToolBarManager) ((ToolBarContributionItem)item).getToolBarManager();
				ToolBar bar = tmgr.getControl();
				if(bar != null && !bar.isDisposed()) {
					bar.addMouseTrackListener(fMouseListener);
					fToolbars.put(window, bar);
				}
			}
		}
	}
	
	/**
	 * @see org.eclipse.jface.util.IPropertyChangeListener#propertyChange(org.eclipse.jface.util.PropertyChangeEvent)
	 */
	public void propertyChange(PropertyChangeEvent event) {
		if(event.getProperty().equals(IInternalDebugUIConstants.PREF_USE_CONTEXTUAL_LAUNCH) ||
				event.getProperty().equals(IInternalDebugUIConstants.PREF_LAUNCH_LAST_IF_NOT_LAUNCHABLE)) {
			if(isContextLaunchEnabled()) {
				windowActivated(DebugUIPlugin.getActiveWorkbenchWindow());
			}
			fUpdateLabel = true;
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.ISelectionListener#selectionChanged(org.eclipse.ui.IWorkbenchPart, org.eclipse.jface.viewers.ISelection)
	 */
	public void selectionChanged(IWorkbenchPart part, ISelection selection) {
		if(isContextLaunchEnabled()) {
			fUpdateLabel = true;
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.ILaunchHistoryChangedListener#launchHistoryChanged()
	 */
	public void launchHistoryChanged() {
		//this always must be set to true, because as the history is loaded these events are fired, and we need to
		//update on workspace load.
		fUpdateLabel = true;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.ILaunchesListener2#launchesTerminated(org.eclipse.debug.core.ILaunch[])
	 */
	public void launchesTerminated(ILaunch[] launches) {
		fUpdateLabel = true;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.ILaunchesListener#launchesAdded(org.eclipse.debug.core.ILaunch[])
	 */
	public void launchesAdded(ILaunch[] launches) {
		fUpdateLabel = true;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.ILaunchesListener#launchesChanged(org.eclipse.debug.core.ILaunch[])
	 */
	public void launchesChanged(ILaunch[] launches) {}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.ILaunchesListener#launchesRemoved(org.eclipse.debug.core.ILaunch[])
	 */
	public void launchesRemoved(ILaunch[] launches) {
		//we want to ensure that even if a launch is removed from the debug view 
		//when it is not terminated we update the label just in case.
		//bug 195232
		for(int i = 0; i < launches.length; i++) {
			if(!launches[i].isTerminated()) {
				fUpdateLabel = true;
				return;
			}
		}
	}
}
