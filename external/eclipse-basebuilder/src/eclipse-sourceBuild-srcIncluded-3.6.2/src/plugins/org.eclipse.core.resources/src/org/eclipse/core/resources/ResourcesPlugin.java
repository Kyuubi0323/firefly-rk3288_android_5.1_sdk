/*******************************************************************************
 *  Copyright (c) 2000, 2009 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *     Serge Beauchamp (Freescale Semiconductor) - [252996] add PT_FILTER_PROVIDERS
 *     Serge Beauchamp (Freescale Semiconductor) - [229633] add PT_VARIABLE_PROVIDERS
 *******************************************************************************/
package org.eclipse.core.resources;

import org.eclipse.core.internal.resources.*;
import org.eclipse.core.internal.utils.Messages;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.*;

/**
 * The plug-in runtime class for the Resources plug-in.  This is
 * the starting point for all workspace and resource manipulation.
 * A typical sequence of events would be for a dependent plug-in
 * to call <code>ResourcesPlugin.getWorkspace()</code>.
 * Doing so would cause this plug-in to be activated and the workspace
 * (if any) to be loaded from disk and initialized.
 * 
 * @noinstantiate This class is not intended to be instantiated by clients.
 */
public final class ResourcesPlugin extends Plugin {
	/**
	 * Unique identifier constant (value <code>"org.eclipse.core.resources"</code>)
	 * for the standard Resources plug-in.
	 */
	public static final String PI_RESOURCES = "org.eclipse.core.resources"; //$NON-NLS-1$

	/*====================================================================
	 * Constants defining the ids of the standard workspace extension points:
	 *====================================================================*/

	/**
	 * Simple identifier constant (value <code>"builders"</code>)
	 * for the builders extension point.
	 */
	public static final String PT_BUILDERS = "builders"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"natures"</code>)
	 * for the natures extension point.
	 */
	public static final String PT_NATURES = "natures"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"markers"</code>)
	 * for the markers extension point.
	 */
	public static final String PT_MARKERS = "markers"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"fileModificationValidator"</code>)
	 * for the file modification validator extension point.
	 */
	public static final String PT_FILE_MODIFICATION_VALIDATOR = "fileModificationValidator"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"moveDeleteHook"</code>)
	 * for the move/delete hook extension point.
	 * 
	 * @since 2.0
	 */
	public static final String PT_MOVE_DELETE_HOOK = "moveDeleteHook"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"teamHook"</code>)
	 * for the team hook extension point.
	 * 
	 * @since 2.1
	 */
	public static final String PT_TEAM_HOOK = "teamHook"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"refreshProviders"</code>)
	 * for the auto-refresh refresh providers extension point.
	 * 
	 * @since 3.0
	 */
	public static final String PT_REFRESH_PROVIDERS = "refreshProviders"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"modelProviders"</code>)
	 * for the model providers extension point.
	 * 
	 * @since 3.2
	 */
	public static final String PT_MODEL_PROVIDERS = "modelProviders"; //$NON-NLS-1$
	
	/**
	 * Simple identifier constant (value <code>"variableProviders"</code>)
	 * for the variable providers extension point.
	 * 
	 * @since 3.6
	 */
	public static final String PT_VARIABLE_PROVIDERS = "variableResolvers"; //$NON-NLS-1$

	/**
	 * Simple identifier constant (value <code>"filterMatchers"</code>)
	 * for the filter matchers extension point.
	 * 
	 * @since 3.6
	 */
	public static final String PT_FILTER_MATCHERS = "filterMatchers"; //$NON-NLS-1$

	/**
	 * Constant identifying the job family identifier for the background autobuild job.
	 * 
	 * @see IJobManager#join(Object, IProgressMonitor)
	 * @since 3.0
	 */
	public static final Object FAMILY_AUTO_BUILD = new Object();
	
	/**
	 * Constant identifying the job family identifier for the background auto-refresh job.
	 * 
	 * @see IJobManager#join(Object, IProgressMonitor)
	 * @since 3.1
	 */
	public static final Object FAMILY_AUTO_REFRESH = new Object();	
	
	/**
	 * Constant identifying the job family identifier for a background build job. All clients
	 * that schedule background jobs for performing builds should include this job
	 * family in their implementation of <code>belongsTo</code>.
	 * 
	 * @see IJobManager#join(Object, IProgressMonitor)
	 * @see Job#belongsTo(Object)
	 * @since 3.0
	 */
	public static final Object FAMILY_MANUAL_BUILD = new Object();

	/**
	 * Constant identifying the job family identifier for a background refresh job. All clients
	 * that schedule background jobs for performing refreshing should include this job
	 * family in their implementation of <code>belongsTo</code>.
	 * 
	 * @see IJobManager#join(Object, IProgressMonitor)
	 * @see Job#belongsTo(Object)
	 * @since 3.4
	 */
	public static final Object FAMILY_MANUAL_REFRESH = new Object();
	
	/**
	 * Name of a preference indicating the encoding to use when reading text 
	 * files in the workspace.  The value is a string, and may 
	 * be the default empty string, indicating that the file system encoding should
	 * be used instead.  The file system encoding can be retrieved using
	 * <code>System.getProperty("file.encoding")</code>.
	 * There is also a convenience method <code>getEncoding</code> which returns
	 * the value of this preference, or the file system encoding if this 
	 * preference is not set.
	 * <p>
	 * Note that there is no guarantee that the value is a supported encoding.
	 * Callers should be prepared to handle <code>UnsupportedEncodingException</code>
	 * where this encoding is used.
	 * </p>
	 * 
	 * @see #getEncoding()
	 * @see java.io.UnsupportedEncodingException
	 */
	public static final String PREF_ENCODING = "encoding"; //$NON-NLS-1$

	/** 
	 * Common prefix for workspace preference names.
	 * @since 2.1 
	 */
	private static final String PREF_DESCRIPTION_PREFIX = "description."; //$NON-NLS-1$

	/**
	 * @deprecated Do not use.
	 * @since 3.0
	 */
	public static final String PREF_MAX_NOTIFICATION_DELAY = "maxnotifydelay"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring whether the workspace performs auto-
	 * builds.
	 * 
	 * @see IWorkspaceDescription#isAutoBuilding()
	 * @see IWorkspaceDescription#setAutoBuilding(boolean)
	 * @since 2.1
	 */
	public static final String PREF_AUTO_BUILDING = PREF_DESCRIPTION_PREFIX + "autobuilding"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring the order projects in the workspace
	 * are built.
	 * 
	 * @see IWorkspaceDescription#getBuildOrder()
	 * @see IWorkspaceDescription#setBuildOrder(String[])
	 * @since 2.1
	 */
	public static final String PREF_BUILD_ORDER = PREF_DESCRIPTION_PREFIX + "buildorder"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring whether to use the workspace's
	 * default order for building projects.
	 * @since 2.1
	 */
	public static final String PREF_DEFAULT_BUILD_ORDER = PREF_DESCRIPTION_PREFIX + "defaultbuildorder"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring the maximum number of times that the
	 * workspace should rebuild when builders affect projects that have already
	 * been built.
	 * 
	 * @see IWorkspaceDescription#getMaxBuildIterations()
	 * @see IWorkspaceDescription#setMaxBuildIterations(int)
	 * @since 2.1
	 */
	public static final String PREF_MAX_BUILD_ITERATIONS = PREF_DESCRIPTION_PREFIX + "maxbuilditerations"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring whether to apply the specified history size policy.
	 * 
	 * @see IWorkspaceDescription#isApplyFileStatePolicy()
	 * @see IWorkspaceDescription#setApplyFileStatePolicy(boolean)
	 * @since 3.6
	 */
	public static final String PREF_APPLY_FILE_STATE_POLICY = PREF_DESCRIPTION_PREFIX + "applyfilestatepolicy"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring the maximum number of milliseconds a
	 * file state should be kept in the local history
	 * 
	 * @see IWorkspaceDescription#getFileStateLongevity()
	 * @see IWorkspaceDescription#setFileStateLongevity(long)
	 * @since 2.1
	 */
	public static final String PREF_FILE_STATE_LONGEVITY = PREF_DESCRIPTION_PREFIX + "filestatelongevity"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring the maximum permitted size of a file
	 * to be stored in the local history
	 * 
	 * @see IWorkspaceDescription#getMaxFileStateSize()
	 * @see IWorkspaceDescription#setMaxFileStateSize(long)
	 * @since 2.1
	 */
	public static final String PREF_MAX_FILE_STATE_SIZE = PREF_DESCRIPTION_PREFIX + "maxfilestatesize"; //$NON-NLS-1$

	/**
	 * Name of a preference for configuring the maximum number of states per
	 * file that can be stored in the local history.
	 * 
	 * @see IWorkspaceDescription#getMaxFileStates()
	 * @see IWorkspaceDescription#setMaxFileStates(int)
	 * @since 2.1
	 */
	public static final String PREF_MAX_FILE_STATES = PREF_DESCRIPTION_PREFIX + "maxfilestates"; //$NON-NLS-1$	
	/**
	 * Name of a preference for configuring the amount of time in milliseconds
	 * between automatic workspace snapshots
	 * 
	 * @see IWorkspaceDescription#getSnapshotInterval()
	 * @see IWorkspaceDescription#setSnapshotInterval(long)
	 * @since 2.1
	 */
	public static final String PREF_SNAPSHOT_INTERVAL = PREF_DESCRIPTION_PREFIX + "snapshotinterval"; //$NON-NLS-1$	

	/**
	 * Name of a preference for turning off support for linked resources.  When
	 * this preference is set to "true", attempting to create linked resources will fail.
	 * @since 2.1
	 */
	public static final String PREF_DISABLE_LINKING = PREF_DESCRIPTION_PREFIX + "disableLinking";//$NON-NLS-1$

	/**
	 * Name of a preference for configuring whether the workspace performs auto-
	 * refresh.
	 * @since 3.0
	 */
	public static final String PREF_AUTO_REFRESH = "refresh.enabled"; //$NON-NLS-1$

	/**
	 * The single instance of this plug-in runtime class.
	 */
	private static ResourcesPlugin plugin;

	/**
	 * The workspace managed by the single instance of this
	 * plug-in runtime class, or <code>null</code> is there is none.
	 */
	private static Workspace workspace = null;

	private ServiceRegistration workspaceRegistration;

	/** 
	 * Constructs an instance of this plug-in runtime class.
	 * <p>
	 * An instance of this plug-in runtime class is automatically created 
	 * when the facilities provided by the Resources plug-in are required.
	 * <b>Clients must never explicitly instantiate a plug-in runtime class.</b>
	 * </p>
	 */
	public ResourcesPlugin() {
		plugin = this;
	}

	/**
	 * Constructs a brand new workspace structure at the location in the local file system
	 * identified by the given path and returns a new workspace object.
	 * 
	 * @exception CoreException if the workspace structure could not be constructed.
	 * Reasons include:
	 * <ul>
	 * <li> There is an existing workspace structure on at the given location
	 *      in the local file system.
	 * <li> A file exists at the given location in the local file system.
	 * <li> A directory could not be created at the given location in the
	 *      local file system.
	 * </ul>
	 */
	private static void constructWorkspace() throws CoreException {
		new LocalMetaArea().createMetaArea();
	}

	/**
	 * Returns the encoding to use when reading text files in the workspace.
	 * This is the value of the <code>PREF_ENCODING</code> preference, or the
	 * file system encoding (<code>System.getProperty("file.encoding")</code>)
	 * if the preference is not set.
	 * <p>
	 * Note that this method does not check whether the result is a supported
	 * encoding.  Callers should be prepared to handle 
	 * <code>UnsupportedEncodingException</code> where this encoding is used.
	 * 
	 * @return  the encoding to use when reading text files in the workspace
	 * @see java.io.UnsupportedEncodingException
	 */
	public static String getEncoding() {
		String enc = getPlugin().getPluginPreferences().getString(PREF_ENCODING);
		if (enc == null || enc.length() == 0) {
			enc = System.getProperty("file.encoding"); //$NON-NLS-1$
		}
		return enc;
	}

	/**
	 * Returns the Resources plug-in.
	 *
	 * @return the single instance of this plug-in runtime class
	 */
	public static ResourcesPlugin getPlugin() {
		return plugin;
	}

	/**
	 * Returns the workspace. The workspace is not accessible after the resources
	 * plug-in has shutdown.
	 *
	 * @return the workspace that was created by the single instance of this
	 *   plug-in class.
	 */
	public static IWorkspace getWorkspace() {
		if (workspace == null)
			throw new IllegalStateException(Messages.resources_workspaceClosed);
		return workspace;
	}

	/**
	 * This implementation of the corresponding {@link BundleActivator} method
	 * closes the workspace without saving.
	 * @see BundleActivator#stop(BundleContext)
	 */
	public void stop(BundleContext context) throws Exception {
		super.stop(context);
		if (workspace == null)
			return;
		workspaceRegistration.unregister();
		// save the preferences for this plug-in
		getPlugin().savePluginPreferences();
		workspace.close(null);

		// Forget workspace only if successfully closed, to
		// make it easier to debug cases where close() is failing.
		workspace = null;
		workspaceRegistration = null;
	}

	/**
	 * This implementation of the corresponding {@link BundleActivator} method
	 * opens the workspace.
	 * @see BundleActivator#start(BundleContext)
	 */
	public void start(BundleContext context) throws Exception {
		super.start(context);
		if (!new LocalMetaArea().hasSavedWorkspace()) {
			constructWorkspace();
		}
		Workspace.DEBUG = ResourcesPlugin.getPlugin().isDebugging();
		// Remember workspace before opening, to
		// make it easier to debug cases where open() is failing.
		workspace = new Workspace();
		PlatformURLResourceConnection.startup(workspace.getRoot().getLocation());
		IStatus result = workspace.open(null);
		if (!result.isOK())
			getLog().log(result);
		workspaceRegistration = context.registerService(IWorkspace.SERVICE_NAME, workspace, null);
	}
}
