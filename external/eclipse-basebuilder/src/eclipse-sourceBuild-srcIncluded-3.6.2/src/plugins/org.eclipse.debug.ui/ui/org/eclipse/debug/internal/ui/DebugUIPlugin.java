/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Sascha Radike - bug 56642
 *     Martin Oberhuber (Wind River) - [327446] Avoid unnecessary wait-for-build dialog.
 *******************************************************************************/
package org.eclipse.debug.internal.ui;

 
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.resources.ISaveContext;
import org.eclipse.core.resources.ISaveParticipant;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.IStatusHandler;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.internal.core.IInternalDebugCoreConstants;
import org.eclipse.debug.internal.ui.contextlaunching.LaunchingResourceManager;
import org.eclipse.debug.internal.ui.launchConfigurations.ClosedProjectFilter;
import org.eclipse.debug.internal.ui.launchConfigurations.DeletedProjectFilter;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationEditDialog;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationManager;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationPropertiesDialog;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationTypeFilter;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationsDialog;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchGroupExtension;
import org.eclipse.debug.internal.ui.launchConfigurations.PerspectiveManager;
import org.eclipse.debug.internal.ui.sourcelookup.SourceLookupFacility;
import org.eclipse.debug.internal.ui.sourcelookup.SourceLookupManager;
import org.eclipse.debug.internal.ui.stringsubstitution.SelectedResourceManager;
import org.eclipse.debug.internal.ui.views.breakpoints.BreakpointOrganizerManager;
import org.eclipse.debug.internal.ui.views.console.ProcessConsoleManager;
import org.eclipse.debug.internal.ui.views.launch.DebugElementHelper;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.ILaunchGroup;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.progress.IProgressService;
import org.eclipse.ui.themes.IThemeManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.w3c.dom.Document;

import com.ibm.icu.text.MessageFormat;

/**
 * The Debug UI Plug-in.
 * 
 * Since 3.3 this plugin registers an <code>ISaveParticipant</code>, allowing this plugin to participate
 * in workspace persistence life-cycles
 * 
 * @see ISaveParticipant
 * @see ILaunchListener
 * @see LaunchConfigurationManager
 * @see PerspectiveManager
 * @see ContextLaunch
 */
public class DebugUIPlugin extends AbstractUIPlugin implements ILaunchListener {
	
	
	/**
	 * The singleton debug plug-in instance
	 */
	private static DebugUIPlugin fgDebugUIPlugin = null;
	
	/**
	 * A utility presentation used to obtain labels
	 */
	protected static IDebugModelPresentation fgPresentation = null;

	/**
	 * Default label provider
	 */
	private static DefaultLabelProvider fgDefaultLabelProvider;
	
	/**
	 * Launch configuration attribute - used by the stand-in launch
	 * config working copies that are created while a launch is waiting
	 * for a build to finish. This attribute allows the EditLaunchConfigurationAction
	 * to access the original config if the user asks to edit it.
	 */
	public static String ATTR_LAUNCHING_CONFIG_HANDLE= getUniqueIdentifier() + "launching_config_handle"; //$NON-NLS-1$
	
	/**
	 * Singleton console document manager
	 */
	private ProcessConsoleManager fProcessConsoleManager = null;
	
	/**
	 * Perspective manager
	 */
	private PerspectiveManager fPerspectiveManager = null;
	
	/**
	 * Launch configuration manager
	 */
	private LaunchConfigurationManager fLaunchConfigurationManager = null;
    
	/**
	 * Context launching manager
	 */
	private LaunchingResourceManager fContextLaunchingManager = null;
	
    /**
     * Image descriptor registry used for images with common overlays.
     * 
     * @since 3.1
     */
    private ImageDescriptorRegistry fImageDescriptorRegistry;
    
    /**
     * Service tracker and service used for finding plug-ins associated
     * with a class.
     */
    private ServiceTracker fServiceTracker;
    private PackageAdmin fPackageAdminService;
    
    /**
     * A set of <code>ISaveParticipant</code>s that want to contribute to saving via this plugin
     * 
     * @since 3.3
     */
    private Set fSaveParticipants = new HashSet();
    
	/**
	 * Theme listener.
	 * 
	 * @since 3.4
	 */
	private IPropertyChangeListener fThemeListener;
    
	
    public static boolean DEBUG = false;
	
    /**
     * Dummy launch node representing a launch that is waiting
     * for a build to finish before proceeding. This node exists
     * to provide immediate feedback to the user in the Debug view and
     * allows termination, which equates to cancellation of the launch.
     */
	public static class PendingLaunch extends Launch {
        private Job fJob;
        public PendingLaunch(ILaunchConfiguration launchConfiguration, String mode, Job job) {
            super(launchConfiguration, mode, null);
            fJob= job;
        }

        // Allow the user to terminate the dummy launch as a means to
        // cancel the launch while waiting for a build to finish.
        public boolean canTerminate() {
            return true;
        }

        public void terminate() throws DebugException {
            fJob.cancel();
        }
    }
	
	/**
	 * Returns the bundle the given class originated from.
	 * 
	 * @param clazz a class
	 * @return the bundle the given class originated from, or <code>null</code>
	 * 	if unable to be determined
	 * @since 3.1
	 */
	public Bundle getBundle(Class clazz) {
		if (fPackageAdminService != null) {
			return fPackageAdminService.getBundle(clazz);
		}
		return null;
	}
	
	/**
	 * Constructs the debug UI plug-in
	 */
	public DebugUIPlugin() {
		super();
		fgDebugUIPlugin= this;
	}
	
	/**
	 * If the debug flag is set the specified message is printed to the console
	 * @param message
	 */
	public static void debug(String message) {
		if (DEBUG) {
			System.out.println(message);
		}
	}
		
	/**
	 * Returns the singleton instance of the debug plug-in.
	 */
	public static DebugUIPlugin getDefault() {
		if(fgDebugUIPlugin == null) {
			fgDebugUIPlugin = new DebugUIPlugin();
		}
		return fgDebugUIPlugin;
	}
	
	/**
	 * Convenience method which returns the unique identifier of this plug-in.
	 */
	public static String getUniqueIdentifier() {
		return IDebugUIConstants.PLUGIN_ID;
	}

	/**
	 * Returns the default delegating model presentation
	 * @return the default delegating model presentation
	 */
	public static IDebugModelPresentation getModelPresentation() {
		if (fgPresentation == null) {
			fgPresentation = new DelegatingModelPresentation();
		}
		return fgPresentation;
	}
	
	/**
	 * Returns the launch configuration manager
	 * @return the launch configuration manager
	 */
	public LaunchConfigurationManager getLaunchConfigurationManager() {
		if (fLaunchConfigurationManager == null) {
			fLaunchConfigurationManager = new LaunchConfigurationManager();
		}
		return fLaunchConfigurationManager;
	}
	
	/**
	 * Returns the context launching resource manager. If one has not been created prior to this
	 * method call, a new manager is created and initialized, by calling its startup() method.
	 * @return the context launching resource manager
	 * 
	 * @since 3.3
	 */
	public LaunchingResourceManager getLaunchingResourceManager() {
		if(fContextLaunchingManager == null) {
			fContextLaunchingManager = new LaunchingResourceManager();
			fContextLaunchingManager.startup();
		}
		return fContextLaunchingManager;
	}
	
	/**
	 * Returns the currently active workbench window or <code>null</code>
	 * if none.
	 * 
	 * @return the currently active workbench window or <code>null</code>
	 */
	public static IWorkbenchWindow getActiveWorkbenchWindow() {
		return getDefault().getWorkbench().getActiveWorkbenchWindow();
	}
	
	/**
	 * Returns the currently active workbench window shell or <code>null</code>
	 * if none.
	 * 
	 * @return the currently active workbench window shell or <code>null</code>
	 */
	public static Shell getShell() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window == null) {
			IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
			if (windows.length > 0) {
				return windows[0].getShell();
			}
		}
		else {
			return window.getShell();
		}
		return null;
	}

	/**
	 * Returns the default label provider for the debug UI.
	 */
	public static ILabelProvider getDefaultLabelProvider() {
		if (fgDefaultLabelProvider == null) {
			fgDefaultLabelProvider = new DefaultLabelProvider();
		}
		return fgDefaultLabelProvider;
	}

	/**
	 * Creates an extension.  If the extension plug-in has not
	 * been loaded a busy cursor will be activated during the duration of
	 * the load.
	 *
	 * @param element the config element defining the extension
	 * @param classAttribute the name of the attribute carrying the class
	 * @return the extension object
	 */
	public static Object createExtension(final IConfigurationElement element, final String classAttribute) throws CoreException {
		// If plug-n has been loaded create extension.
		// Otherwise, show busy cursor then create extension.
		Bundle bundle = Platform.getBundle(element.getContributor().getName());
		if (bundle.getState() == Bundle.ACTIVE) {
			return element.createExecutableExtension(classAttribute);
		}
		final Object [] ret = new Object[1];
		final CoreException [] exc = new CoreException[1];
		BusyIndicator.showWhile(null, new Runnable() {
			public void run() {
				try {
					ret[0] = element.createExecutableExtension(classAttribute);
				} catch (CoreException e) {
					exc[0] = e;
				}
			}
		});
		if (exc[0] != null) {
			throw exc[0];
		}
		return ret[0];
	}
	
	/**
	 * @see org.eclipse.ui.plugin.AbstractUIPlugin#createImageRegistry()
	 */
	protected ImageRegistry createImageRegistry() {
		return DebugPluginImages.initializeImageRegistry();
	}

	/* (non-Javadoc)
	 * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
	 */
	public void stop(BundleContext context) throws Exception {
		try {
            if (fProcessConsoleManager != null) {
                fProcessConsoleManager.shutdown();
            }
            
            BreakpointOrganizerManager.getDefault().shutdown();
            
			if (fPerspectiveManager != null) {
				fPerspectiveManager.shutdown();
			}
			if (fLaunchConfigurationManager != null) {
				fLaunchConfigurationManager.shutdown();
			}
			if(fContextLaunchingManager != null) {
				fContextLaunchingManager.shutdown();
			}
	
			ColorManager.getDefault().dispose();
			
			if (fgPresentation != null) {
				fgPresentation.dispose();
			}
            
            if (fImageDescriptorRegistry != null) {
                fImageDescriptorRegistry.dispose();
            }
            
            if (fgDefaultLabelProvider != null) {
            	fgDefaultLabelProvider.dispose();
            }
            
            SourceLookupFacility.shutdown();
			
			DebugElementHelper.dispose();
			
			fServiceTracker.close();
			fPackageAdminService = null;
			
			fSaveParticipants.clear();
			
			ResourcesPlugin.getWorkspace().removeSaveParticipant(this);
			
			if (fThemeListener != null) {
				if (PlatformUI.isWorkbenchRunning())
					PlatformUI.getWorkbench().getThemeManager().removePropertyChangeListener(fThemeListener);
				fThemeListener= null;
			}
			
		} finally {
			super.stop(context);
		}
	}

	/**
	 * Add the specified <code>ISaveParticipant</code> to the current listing of
	 * registered participants
	 * @param participant
	 * @return true if this current listing did not already contain the specified participant
	 * @since 3.3
	 */
	public boolean addSaveParticipant(ISaveParticipant participant) {
		return fSaveParticipants.add(participant);
	}
	
	/**
	 * Removes the specified <code>ISaveParticipant</code> from the current listing of registered
	 * participants
	 * @param participant
	 * @return true if the set contained the specified element
	 * 
	 * @since 3.3
	 */
	public boolean removeSaveParticipant(ISaveParticipant participant) {
		return fSaveParticipants.remove(participant);
	}
	
	/**
	 * @see AbstractUIPlugin#startup()
	 */
	public void start(BundleContext context) throws Exception {
		super.start(context);
		ResourcesPlugin.getWorkspace().addSaveParticipant(this,
				new ISaveParticipant() {
					public void saving(ISaveContext saveContext) throws CoreException {
						savePluginPreferences();
						for(Iterator iter = fSaveParticipants.iterator(); iter.hasNext();) {
							((ISaveParticipant)iter.next()).saving(saveContext);
						}
					}
					public void rollback(ISaveContext saveContext) {
						for(Iterator iter = fSaveParticipants.iterator(); iter.hasNext();) {
							((ISaveParticipant)iter.next()).rollback(saveContext);
						}
					}
					public void prepareToSave(ISaveContext saveContext) throws CoreException {
						for(Iterator iter = fSaveParticipants.iterator(); iter.hasNext();) {
							((ISaveParticipant)iter.next()).prepareToSave(saveContext);
						}
					}
					public void doneSaving(ISaveContext saveContext) {
						for(Iterator iter = fSaveParticipants.iterator(); iter.hasNext();) {
							((ISaveParticipant)iter.next()).doneSaving(saveContext);
						}
					}
				});
		DEBUG = "true".equals(Platform.getDebugOption("org.eclipse.debug.ui/debug"));  //$NON-NLS-1$//$NON-NLS-2$
		
		// make sure the perspective manager is created
		// and be the first debug event listener
		fPerspectiveManager = new PerspectiveManager();
		fPerspectiveManager.startup();
		
		getLaunchingResourceManager();
		
		// Listen to launches to lazily create "launch processors"
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		ILaunch[] launches = launchManager.getLaunches();
		if (launches.length > 0) {
			// if already launches, initialize processors
			initializeLaunchListeners();
		} else {
			// if no launches, wait for first launch to initialize processors
			launchManager.addLaunchListener(this);
		}
        
        // start the breakpoint organizer manager
        BreakpointOrganizerManager.getDefault();
				
		fServiceTracker = new ServiceTracker(context, PackageAdmin.class.getName(), null);
		fServiceTracker.open();
		fPackageAdminService = (PackageAdmin) fServiceTracker.getService();
		
		getLaunchConfigurationManager().startup();
		
		if (PlatformUI.isWorkbenchRunning()) {
			fThemeListener= new IPropertyChangeListener() {

				public void propertyChange(PropertyChangeEvent event) {
					if (IThemeManager.CHANGE_CURRENT_THEME.equals(event.getProperty()))
						DebugUIPreferenceInitializer.setThemeBasedPreferences(getPreferenceStore(), true);
				}
			};
			PlatformUI.getWorkbench().getThemeManager().addPropertyChangeListener(fThemeListener);
		}
		
		// do the asynchronous exec last - see bug 209920
		getStandardDisplay().asyncExec(
				new Runnable() {
					public void run() {
						//initialize the selected resource `
						SelectedResourceManager.getDefault();
						// forces launch shortcuts to be initialized so their key-bindings work
						getLaunchConfigurationManager().getLaunchShortcuts();
					}
				});
	}

	/**
	 * Utility method with conventions
	 */
	public static void errorDialog(Shell shell, String title, String message, IStatus s) {
		// if the 'message' resource string and the IStatus' message are the same,
		// don't show both in the dialog
		if (s != null && message.equals(s.getMessage())) {
			message= null;
		}
		ErrorDialog.openError(shell, title, message, s);
	}
	
	/**
	 * Utility method with conventions
	 */
	public static void errorDialog(Shell shell, String title, String message, Throwable t) {
		IStatus status;
		if (t instanceof CoreException) {
			status= ((CoreException)t).getStatus();
			// if the 'message' resource string and the IStatus' message are the same,
			// don't show both in the dialog
			if (status != null && message.equals(status.getMessage())) {
				message= null;
			}
		} else {
			status= new Status(IStatus.ERROR, getUniqueIdentifier(), IDebugUIConstants.INTERNAL_ERROR, "Error within Debug UI: ", t); //$NON-NLS-1$
			log(status);
		}
		ErrorDialog.openError(shell, title, message, status);
	}

	/**
	 * Logs the specified status with this plug-in's log.
	 * 
	 * @param status status to log
	 */
	public static void log(IStatus status) {
		getDefault().getLog().log(status);
	}
	
	/**
	 * Logs the specified throwable with this plug-in's log.
	 * 
	 * @param t throwable to log
	 */
	public static void log(Throwable t) {
		log(newErrorStatus("Error logged from Debug UI: ", t)); //$NON-NLS-1$
	}
	
	/**
	 * Logs an internal error with the specified message.
	 * 
	 * @param message the error message to log
	 */
	public static void logErrorMessage(String message) {
		// this message is intentionally not internationalized, as an exception may
		// be due to the resource bundle itself
		log(newErrorStatus("Internal message logged from Debug UI: " + message, null)); //$NON-NLS-1$
	}
	
	/**
	 * Returns a new error status for this plug-in with the given message
	 * @param message the message to be included in the status
	 * @param exception the exception to be included in the status or <code>null</code> if none
	 * @return a new error status
	 */
	public static IStatus newErrorStatus(String message, Throwable exception) {
		return new Status(IStatus.ERROR, getUniqueIdentifier(), IDebugUIConstants.INTERNAL_ERROR, message, exception);
	}
	
	/**
     * Open the launch configuration dialog on the specified launch
     * configuration. The dialog displays the tabs for a single configuration
     * only (a tree of launch configuration is not displayed)
     * <p>
     * If a status is specified, a status handler is consulted to handle the
     * status. The status handler is passed the instance of the launch
     * configuration dialog that is opened. This gives the status handler an
     * opportunity to perform error handling/initialization as required.
     * </p>
     * @param shell the parent shell for the launch configuration dialog
     * @param configuration the configuration to display
     * @param groupIdentifier group identifier of the launch group the launch configuration
     * belongs to
     * @param status the status to display, or <code>null</code> if none
     * @param showCancel if the cancel button should be shown in the particular instance of the dialog
     * @return the return code from opening the launch configuration dialog -
     *  one  of <code>Window.OK</code> or <code>Window.CANCEL</code>
     * 
     * @since 3.3
     *
     */
    public static int openLaunchConfigurationEditDialog(Shell shell, ILaunchConfiguration configuration, String groupIdentifier, IStatus status, boolean showCancel) {
    	LaunchGroupExtension group = DebugUIPlugin.getDefault().getLaunchConfigurationManager().getLaunchGroup(groupIdentifier);
    	if (group != null) {
    		LaunchConfigurationEditDialog dialog = new LaunchConfigurationEditDialog(shell, configuration, group, showCancel);
    		dialog.setInitialStatus(status);
    		return dialog.open();
    	}
    	return Window.CANCEL;
    }
	
    /**
     * Open the launch configuration dialog on the specified launch
     * configuration. The dialog displays the tabs for a single configuration
     * only (a tree of launch configuration is not displayed)
     * <p>
     * If a status is specified, a status handler is consulted to handle the
     * status. The status handler is passed the instance of the launch
     * configuration dialog that is opened. This gives the status handler an
     * opportunity to perform error handling/initialization as required.
     * </p>
     * @param shell the parent shell for the launch configuration dialog
     * @param configuration the configuration to display
     * @param groupIdentifier group identifier of the launch group the launch configuration
     * belongs to
     * @param reservednames a set of launch configuration names that cannot be used when creating or renaming
     * the specified launch configuration
     * @param status the status to display, or <code>null</code> if none
     * @param setDefaults whether to set default values in the configuration
     * @return the return code from opening the launch configuration dialog -
     *  one  of <code>Window.OK</code> or <code>Window.CANCEL</code>
     * 
     * @since 3.3
     * 
     */
    public static int openLaunchConfigurationPropertiesDialog(Shell shell, ILaunchConfiguration configuration, String groupIdentifier, Set reservednames, IStatus status, boolean setDefaults) {
    	LaunchGroupExtension group = DebugUIPlugin.getDefault().getLaunchConfigurationManager().getLaunchGroup(groupIdentifier);
    	if (group != null) {
    		LaunchConfigurationPropertiesDialog dialog = new LaunchConfigurationPropertiesDialog(shell, configuration, group, reservednames);
    		dialog.setInitialStatus(status);
    		dialog.setDefaultsOnOpen(setDefaults);
    		return dialog.open();
    	}
    	return Window.CANCEL;
    }
    
    /**
     * Opens the {@link LaunchConfigurationsDialog} on the given selection for the given group. A status
     * can be provided or <code>null</code> and the dialog can initialize the given {@link ILaunchConfiguration}
     * to its defaults when opening as well - as long as the specified configuration is an {@link ILaunchConfigurationWorkingCopy}.
     * @param shell
     * @param selection
     * @param groupIdentifier
     * @param status
     * @param setDefaults
     * @return the return code from the dialog.open() call
     * @since 3.6
     */
    public static int openLaunchConfigurationsDialog(Shell shell, IStructuredSelection selection, String groupIdentifier, boolean setDefaults) {
    	LaunchGroupExtension group = DebugUIPlugin.getDefault().getLaunchConfigurationManager().getLaunchGroup(groupIdentifier);
    	if (group != null) {
			LaunchConfigurationsDialog dialog = new LaunchConfigurationsDialog(shell, group);
			dialog.setOpenMode(LaunchConfigurationsDialog.LAUNCH_CONFIGURATION_DIALOG_OPEN_ON_SELECTION);
			dialog.setInitialSelection(selection);
			dialog.setDefaultsOnOpen(setDefaults);
			return dialog.open();
    	}
    	return Window.CANCEL;
    }
    
	/**
	 * Save all dirty editors in the workbench.
	 * Returns whether the operation succeeded.
	 * 
	 * @return whether all saving was completed
	 * @deprecated Saving has been moved to the launch delegate <code>LaunchConfigurationDelegate</code> to allow for scoped saving
	 * of resources that are only involved in the current launch, no longer the entire workspace
	 */
	protected static boolean saveAllEditors(boolean confirm) {
		if (getActiveWorkbenchWindow() == null) {
			return false;
		}
		return PlatformUI.getWorkbench().saveAllEditors(confirm);
	}
	
	/**
	 * Save & build the workspace according to the user-specified preferences.  Return <code>false</code> if
	 * any problems were encountered, <code>true</code> otherwise.
	 * 
	 * @deprecated this method is no longer to be used. It is an artifact from 2.0, and all saving is now done with the
	 * launch delegate <code>LaunchConfigurationDelegate</code>
	 */
	public static boolean saveAndBuild() {
		boolean status = true;
		String saveDirty = getDefault().getPreferenceStore().getString(IInternalDebugUIConstants.PREF_SAVE_DIRTY_EDITORS_BEFORE_LAUNCH);
		boolean buildBeforeLaunch = getDefault().getPreferenceStore().getBoolean(IDebugUIConstants.PREF_BUILD_BEFORE_LAUNCH);
		
		// If we're ignoring dirty editors, check if we need to build
		if (saveDirty.equals(MessageDialogWithToggle.NEVER)) {
			if (buildBeforeLaunch) {
				return doBuild();
			}
		} else {
			status = saveAllEditors(saveDirty.equals(MessageDialogWithToggle.PROMPT));
			if (status && buildBeforeLaunch) {
				status = doBuild();
			}
		}
				
		return status;
	}
	
	private static boolean doBuild() {
		try {
			PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor) throws InvocationTargetException {
					try {
						ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
					} catch (CoreException e) {
						throw new InvocationTargetException(e);
					}
				}
			});
		} catch (InterruptedException e) {
			// canceled by user
			return false;
		} catch (InvocationTargetException e) {
			String title= DebugUIMessages.DebugUIPlugin_Run_Debug_1;
			String message= DebugUIMessages.DebugUIPlugin_Build_error__Check_log_for_details__2;
			Throwable t = e.getTargetException();
			errorDialog(getShell(), title, message, t);
			return false;
		}
		return true;
	}
	
	/**
	 * Returns the workbench's display.
	 * 
	 */
	public static Display getStandardDisplay() {
		return PlatformUI.getWorkbench().getDisplay();
	}
	
	/**
	 * Returns the a color based on the type of output.
	 * Valid types:
	 * <li>CONSOLE_SYS_OUT_RGB</li>
	 * <li>CONSOLE_SYS_ERR_RGB</li>
	 * <li>CONSOLE_SYS_IN_RGB</li>
	 * <li>CHANGED_VARIABLE_RGB</li>
	 */
	public static Color getPreferenceColor(String type) {
		return ColorManager.getDefault().getColor(PreferenceConverter.getColor(getDefault().getPreferenceStore(), type));
	}

	/**
	 * Returns the process console manager. The manager will be created lazily on
	 * the first access.
	 * 
	 * @return ProcessConsoleManager
	 */
	public ProcessConsoleManager getProcessConsoleManager() {
		if (fProcessConsoleManager == null) {
			fProcessConsoleManager = new ProcessConsoleManager();
		}
		return fProcessConsoleManager;
	}
	
	/**
	 * Returns a Document that can be used to build a DOM tree
	 * @return the Document
	 * @throws ParserConfigurationException if an exception occurs creating the document builder
	 * @since 3.0
	 */
	public static Document getDocument() throws ParserConfigurationException {
		DocumentBuilderFactory dfactory= DocumentBuilderFactory.newInstance();

		DocumentBuilder docBuilder= dfactory.newDocumentBuilder();
		Document doc= docBuilder.newDocument();
		return doc;
	}
		
	/**
	 * When the first launch is added, instantiate launch processors,
	 * and stop listening to launch notifications.
	 * 
	 * @see org.eclipse.debug.core.ILaunchListener#launchAdded(org.eclipse.debug.core.ILaunch)
	 */
	public void launchAdded(ILaunch launch) {
		DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(this);
		initializeLaunchListeners();
	}
	
	/**
	 * Creates/starts launch listeners after a launch has been added.
	 * <p>
	 * Launch processors are:
	 * <ul>
	 * <li>console document manager</li>
	 * <li>perspective manager</li>
	 * </ul>
	 * </p>
	 */
	private void initializeLaunchListeners() {
		getProcessConsoleManager().startup();
		SourceLookupManager.getDefault();
	}
	
	/**
	 * Returns the perspective manager.
	 * 
	 * @return
	 */
	public PerspectiveManager getPerspectiveManager() {
		return fPerspectiveManager;
	}

	/**
	 * @see org.eclipse.debug.core.ILaunchListener#launchChanged(org.eclipse.debug.core.ILaunch)
	 */
	public void launchChanged(ILaunch launch) {}

	/**
	 * @see org.eclipse.debug.core.ILaunchListener#launchRemoved(org.eclipse.debug.core.ILaunch)
	 */
	public void launchRemoved(ILaunch launch) {}

	/**
     * Return the ILaunch associated with a model element, or null if there is
     * no such association.
     * 
     * @param element the model element
     * @return the ILaunch associated with the element, or null.
     * @since 3.6
     */
    public static ILaunch getLaunch(Object element) {
    	// support for custom models
        ILaunch launch= (ILaunch)DebugPlugin.getAdapter(element, ILaunch.class);
        if (launch == null) {
        	// support for standard debug model
            if (element instanceof IDebugElement) {
                launch= ((IDebugElement)element).getLaunch();
            } else if (element instanceof ILaunch) {
                launch= ((ILaunch)element);
            } else if (element instanceof IProcess) {
                launch= ((IProcess)element).getLaunch();
            }
        }
        return launch;
    }
    

    /**
	 * Save dirty editors before launching, according to preferences.
	 * 
	 * @return whether to proceed with launch
	 * @deprecated Saving has been moved to the launch delegate <code>LaunchConfigurationDelegate</code> to allow for scoped saving
	 * of resources that are only involved in the current launch, no longer the entire workspace
	 */
	public static boolean preLaunchSave() {
		String saveDirty = getDefault().getPreferenceStore().getString(IInternalDebugUIConstants.PREF_SAVE_DIRTY_EDITORS_BEFORE_LAUNCH);
		if (saveDirty.equals(MessageDialogWithToggle.NEVER)) {
			return true;
		}
		return saveAllEditors(saveDirty.equals(MessageDialogWithToggle.PROMPT));
	}
	
	/**
	 * Builds the workspace (according to preferences) and launches the given launch
	 * configuration in the specified mode. May return null if auto build is in process and
	 * user cancels the launch.
	 * 
	 * @param configuration the configuration to launch
	 * @param mode launch mode - run or debug
	 * @param monitor progress monitor
	 * @exception CoreException if an exception occurs while building or launching
	 * @return resulting launch or <code>null</code> if user cancels
	 */
	public static ILaunch buildAndLaunch(ILaunchConfiguration configuration, String mode, IProgressMonitor monitor) throws CoreException {
		boolean buildBeforeLaunch = getDefault().getPreferenceStore().getBoolean(IDebugUIConstants.PREF_BUILD_BEFORE_LAUNCH);
		
		monitor.beginTask(IInternalDebugCoreConstants.EMPTY_STRING, 1);
		try
		{
			return configuration.launch(
					mode,
					new SubProgressMonitor(monitor, 1),
					buildBeforeLaunch);
		}
		finally
		{
			monitor.done();
		}
	}
	
	/**
	 * Saves and builds the workspace according to current preference settings and
	 * launches the given launch configuration in the specified mode in the
	 * foreground with a progress dialog. Reports any exceptions that occur
	 * in an error dialog.
	 * 
	 * @param configuration the configuration to launch
	 * @param mode launch mode
	 * @since 3.0
	 */
	public static void launchInForeground(final ILaunchConfiguration configuration, final String mode) {
		final IJobManager jobManager = Job.getJobManager();
		IPreferenceStore store = DebugUIPlugin.getDefault().getPreferenceStore();
		boolean wait = false;
		
		if (jobManager.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length > 0 || jobManager.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length >0) {
			String waitForBuild = store.getString(IInternalDebugUIConstants.PREF_WAIT_FOR_BUILD);

			if (waitForBuild.equals(MessageDialogWithToggle.PROMPT)) {
				MessageDialogWithToggle dialog = MessageDialogWithToggle.openYesNoCancelQuestion(getShell(), DebugUIMessages.DebugUIPlugin_23, DebugUIMessages.DebugUIPlugin_24, null, false, store, IInternalDebugUIConstants.PREF_WAIT_FOR_BUILD); //
				
				switch (dialog.getReturnCode()) {
					case IDialogConstants.CANCEL_ID:
						return;
					case IDialogConstants.YES_ID:
						wait = false;
						break;
					case IDialogConstants.NO_ID:
						wait = true;
						break;
				}
			} else if (waitForBuild.equals(MessageDialogWithToggle.ALWAYS)) {
				wait = true;
			}
		}

		if (wait) {
			IWorkbench workbench = DebugUIPlugin.getDefault().getWorkbench();
			IProgressService progressService = workbench.getProgressService();
			final IRunnableWithProgress runnable = new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor) throws InvocationTargetException {
					/* Setup progress monitor
					 * - Waiting for jobs to finish (2)
					 * - Build & launch (98) */
					monitor.beginTask(MessageFormat.format(DebugUIMessages.DebugUIPlugin_25, new Object[] {configuration.getName()}), 100);

					try {
						jobManager.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, new SubProgressMonitor(monitor, 1));
						jobManager.join(ResourcesPlugin.FAMILY_AUTO_BUILD, new SubProgressMonitor(monitor, 1));
					}
					catch (InterruptedException e) {/* continue*/}
					if (!monitor.isCanceled()) {
						try {
							buildAndLaunch(configuration, mode,	new SubProgressMonitor(monitor, 98));
						}
						catch (CoreException e) {
							throw new InvocationTargetException(e);
						}
					}
				}
			};
			try {
				progressService.busyCursorWhile(runnable);
			}
			catch (InterruptedException e) {}
			catch (InvocationTargetException e2) {
				handleInvocationTargetException(e2, configuration, mode);
			}
		} else {
			IRunnableWithProgress runnable = new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor) throws InvocationTargetException {
					/* Setup progress monitor
					 * - Build & launch (1) */
					monitor.beginTask(MessageFormat.format(DebugUIMessages.DebugUIPlugin_25, new Object[] {configuration.getName()}), 1);
					try {
						buildAndLaunch(configuration, mode,	new SubProgressMonitor(monitor, 1));
					}
					catch (CoreException e) {
						throw new InvocationTargetException(e);
					}
				}
			};
			try {
				PlatformUI.getWorkbench().getProgressService().busyCursorWhile(runnable);
			}
			catch (InvocationTargetException e) {
				handleInvocationTargetException(e, configuration, mode);
			}
			catch (InterruptedException e) {}

		}
	}
	
	private static void handleInvocationTargetException(InvocationTargetException e, ILaunchConfiguration configuration, String mode) {
		Throwable targetException = e.getTargetException();
		Throwable t = e;
		if (targetException instanceof CoreException) {
			t = targetException;
		}
		if (t instanceof CoreException) {
			CoreException ce = (CoreException)t;
			IStatusHandler handler = DebugPlugin.getDefault().getStatusHandler(ce.getStatus());
			if (handler != null) {
				ILaunchGroup group = DebugUITools.getLaunchGroup(configuration, mode);
				if (group != null) {
					DebugUITools.openLaunchConfigurationDialogOnGroup(DebugUIPlugin.getShell(), new StructuredSelection(configuration), group.getIdentifier(), ce.getStatus());
					return;
				}
			}
			if ((ce.getStatus().getSeverity() & (IStatus.ERROR | IStatus.WARNING)) == 0) {
				// If the exception is a CoreException with a status other
				// than ERROR or WARNING, don't open an error dialog.
				return;
			}
		}
		DebugUIPlugin.errorDialog(DebugUIPlugin.getShell(), DebugUIMessages.DebugUITools_Error_1, DebugUIMessages.DebugUITools_Exception_occurred_during_launch_2, t); //
	}
	
	/**
	 * Saves and builds the workspace according to current preference settings and
	 * launches the given launch configuration in the specified mode in a background
	 * Job with progress reported via the Job. Exceptions are reported in the Progress
	 * view.
	 * 
	 * @param configuration the configuration to launch
	 * @param mode launch mode
	 * @since 3.0
	 */
	public static void launchInBackground(final ILaunchConfiguration configuration, final String mode) {
		final IJobManager jobManager = Job.getJobManager();
		IPreferenceStore store = DebugUIPlugin.getDefault().getPreferenceStore();
		boolean wait = (jobManager.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length > 0 && ResourcesPlugin.getWorkspace().isAutoBuilding())
				|| (jobManager.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length > 0);
		String waitPref = store.getString(IInternalDebugUIConstants.PREF_WAIT_FOR_BUILD);
		if (wait) { // if there are build jobs running, do we wait or not??
			if (waitPref.equals(MessageDialogWithToggle.PROMPT)) {
				MessageDialogWithToggle dialog = MessageDialogWithToggle.openYesNoCancelQuestion(getShell(), DebugUIMessages.DebugUIPlugin_23, DebugUIMessages.DebugUIPlugin_24, null, false, store, IInternalDebugUIConstants.PREF_WAIT_FOR_BUILD); //
				switch (dialog.getReturnCode()) {
					case IDialogConstants.CANCEL_ID:
						return;
					case IDialogConstants.YES_ID:
						wait = true;
						break;
					case IDialogConstants.NO_ID:
						wait = false;
						break;
				}
			}
			else {
				wait = waitPref.equals(MessageDialogWithToggle.ALWAYS);
			}
		}
		final boolean waitInJob = wait;
		Job job = new Job(MessageFormat.format(DebugUIMessages.DebugUIPlugin_25, new Object[] {configuration.getName()})) {
			public IStatus run(final IProgressMonitor monitor) {
				/* Setup progress monitor
				 * - Waiting for jobs to finish (2)
				 * - Build & launch (98) */
				monitor.beginTask(DebugUIMessages.DebugUITools_3, 100);
				try {
					if(waitInJob) {
						StringBuffer buffer = new StringBuffer(configuration.getName());
						buffer.append(DebugUIMessages.DebugUIPlugin_0);
						ILaunchConfigurationWorkingCopy workingCopy = configuration.copy(buffer.toString());
						workingCopy.setAttribute(ATTR_LAUNCHING_CONFIG_HANDLE, configuration.getMemento());
						final ILaunch pendingLaunch = new PendingLaunch(workingCopy, mode, this);
						DebugPlugin.getDefault().getLaunchManager().addLaunch(pendingLaunch);
                        IJobChangeListener listener= new IJobChangeListener() {
                            public void sleeping(IJobChangeEvent event) {}
                            public void scheduled(IJobChangeEvent event) {}
                            public void running(IJobChangeEvent event) {}
                            public void awake(IJobChangeEvent event) {}
                            public void aboutToRun(IJobChangeEvent event) {}
                            public void done(IJobChangeEvent event) {
                                DebugPlugin dp = DebugPlugin.getDefault();
                                if (dp != null) {
                                	dp.getLaunchManager().removeLaunch(pendingLaunch);
                                }
                                removeJobChangeListener(this);
                            }
                        };
                        addJobChangeListener(listener);
						try {
							jobManager.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, new SubProgressMonitor(monitor, 1));
							jobManager.join(ResourcesPlugin.FAMILY_AUTO_BUILD, new SubProgressMonitor(monitor, 1));
						}
						catch (InterruptedException e) {/*just continue.*/}
                        DebugPlugin.getDefault().getLaunchManager().removeLaunch(pendingLaunch);
					}
					else {
						monitor.worked(2); /* don't wait for jobs to finish */
					}
					if (!monitor.isCanceled()) {
						buildAndLaunch(configuration, mode, new SubProgressMonitor(monitor, 98));
					}
				} catch (CoreException e) {
					final IStatus status = e.getStatus();
					IStatusHandler handler = DebugPlugin.getDefault().getStatusHandler(status);
					if (handler == null) {
						return status;
					}
					final ILaunchGroup group = DebugUITools.getLaunchGroup(configuration, mode);
					if (group == null) {
						return status;
					}
					Runnable r = new Runnable() {
						public void run() {
							DebugUITools.openLaunchConfigurationDialogOnGroup(DebugUIPlugin.getShell(), new StructuredSelection(configuration), group.getIdentifier(), status);
						}
					};
					DebugUIPlugin.getStandardDisplay().asyncExec(r);
				}
				finally	{
					monitor.done();
				}
				
				return Status.OK_STATUS;
			}
		};

		IWorkbench workbench = DebugUIPlugin.getDefault().getWorkbench();
		IProgressService progressService = workbench.getProgressService();

		job.setPriority(Job.INTERACTIVE);
		job.setName(MessageFormat.format(DebugUIMessages.DebugUIPlugin_25, new Object[] {configuration.getName()}));
		
		if (wait) {
			progressService.showInDialog(workbench.getActiveWorkbenchWindow().getShell(), job);
		}
		job.schedule();
	}

	/**
	 * Returns the label with any accelerators removed.
	 * 
	 * @return label without accelerators
	 */
    public static String removeAccelerators(String label) {
        String title = label;
        if (title != null) {
            // strip out any '&' (accelerators)
            int index = title.indexOf('&');
            if (index == 0) {
                title = title.substring(1);
            } else if (index > 0) {
                //DBCS languages use "(&X)" format
                if (title.charAt(index - 1) == '(' && title.length() >= index + 3 && title.charAt(index + 2) == ')') {
                    String first = title.substring(0, index - 1);
                    String last = title.substring(index + 3);
                    title = first + last;
                } else if (index < (title.length() - 1)) {
                    String first = title.substring(0, index);
                    String last = title.substring(index + 1);
                    title = first + last;
                }
            }
        }
        return title;
    }
    
	/**
	 * Returns the label with any DBCS accelerator moved to the end of the string.
	 * See bug 186921.
	 * 
	 * @return label with moved accelerator
	 */
    public static String adjustDBCSAccelerator(String label) {
        String title = label;
        if (title != null) {
            // strip out any '&' (accelerators)
            int index = title.indexOf('&');
            if (index > 0) {
                //DBCS languages use "(&X)" format
                if (title.charAt(index - 1) == '(' && title.length() >= index + 3 && title.charAt(index + 2) == ')') {
                    String first = title.substring(0, index - 1);
                    String accel = title.substring(index - 1, index + 3);
                    String last = title.substring(index + 3);
                    title = first + last;
                    if (title.endsWith("...")) { //$NON-NLS-1$
                    	title = title.substring(0, title.length() - 3);
                    	title = title + accel + "..."; //$NON-NLS-1$
                    } else {
                    	title = title + accel;
                    }
                }
            }
        }
        return title;
    }

    /**
     * Returns the image descriptor registry used for this plug-in.
     * 
     * @since 3.1
     */
    public static ImageDescriptorRegistry getImageDescriptorRegistry() {
        if (getDefault().fImageDescriptorRegistry == null) {
            getDefault().fImageDescriptorRegistry = new ImageDescriptorRegistry();
        }
        return getDefault().fImageDescriptorRegistry;
    }
    
    /**
     * Returns an image descriptor for the icon referenced by the given attribute
     * and configuration element, or <code>null</code> if none.
     * 
     * @param element
     * @param attr
     * @return image descriptor or <code>null</code>
     */
    public static ImageDescriptor getImageDescriptor(IConfigurationElement element, String attr) {
		Bundle bundle = Platform.getBundle(element.getContributor().getName());
		String iconPath = element.getAttribute(attr);
		if (iconPath != null) {
			URL iconURL = FileLocator.find(bundle , new Path(iconPath), null);
			if (iconURL != null) {
				return ImageDescriptor.createFromURL(iconURL);
			}
		}
		return null;
    }
    
    /**
     * Returns an image descriptor for the icon referenced by the given path
     * and contributor name, or <code>null</code> if none.
     * 
     * @param name the name of the contributor
     * @param path the path of the icon (from the configuration element)
     * @return image descriptor or <code>null</code>
     * @since 3.3
     */
    public static ImageDescriptor getImageDescriptor(String name, String path) {
		Bundle bundle = Platform.getBundle(name);
		if (path != null) {
			URL iconURL = FileLocator.find(bundle , new Path(path), null);
			if (iconURL != null) {
				return ImageDescriptor.createFromURL(iconURL);
			}
		}
		return null;
    }
    
    /**
	 * Performs extra filtering for launch configurations based on the preferences set on the
	 * Launch Configurations page
	 * @param config the config to filter
	 * @return true if it should pass the filter, false otherwise
	 * @since 3.2
	 */
	public static boolean doLaunchConfigurationFiltering(ILaunchConfiguration config) {
		boolean ret = true;
		if(DebugUIPlugin.getDefault().getPreferenceStore().getBoolean(IInternalDebugUIConstants.PREF_FILTER_LAUNCH_CLOSED)) {
			ret &= new ClosedProjectFilter().select(null, null, config);
		}
		if(DebugUIPlugin.getDefault().getPreferenceStore().getBoolean(IInternalDebugUIConstants.PREF_FILTER_LAUNCH_DELETED)) {
			ret &= new DeletedProjectFilter().select(null, null, config);
		}
		if(DebugUIPlugin.getDefault().getPreferenceStore().getBoolean(IInternalDebugUIConstants.PREF_FILTER_LAUNCH_TYPES)) {
			try {
				ret &= new LaunchConfigurationTypeFilter().select(null, null, config.getType());
			}
			catch(CoreException e) {
			    DebugUIPlugin.log(e);
			}
		}
		return ret;
	}
}

