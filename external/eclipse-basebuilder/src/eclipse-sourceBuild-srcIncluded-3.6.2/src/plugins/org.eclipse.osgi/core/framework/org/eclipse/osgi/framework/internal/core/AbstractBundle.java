/*******************************************************************************
 * Copyright (c) 2003, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.framework.internal.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import org.eclipse.osgi.framework.adaptor.*;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.util.KeyedElement;
import org.eclipse.osgi.internal.composite.CompositeImpl;
import org.eclipse.osgi.internal.composite.SurrogateImpl;
import org.eclipse.osgi.internal.loader.BundleLoader;
import org.eclipse.osgi.internal.permadmin.EquinoxSecurityManager;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.ResolverError;
import org.eclipse.osgi.signedcontent.*;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.*;

/**
 * This object is given out to bundles and wraps the internal Bundle object. It
 * is destroyed when a bundle is uninstalled and reused if a bundle is updated.
 * This class is abstract and is extended by BundleHost and BundleFragment.
 */
public abstract class AbstractBundle implements Bundle, Comparable, KeyedElement {
	/** The Framework this bundle is part of */
	protected Framework framework;
	/** The state of the bundle. */
	protected volatile int state;
	/** A flag to denote whether a bundle state change is in progress */
	protected volatile Thread stateChanging;
	/** Bundle's BundleData object */
	protected BundleData bundledata;
	/** Internal object used for state change synchronization */
	protected Object statechangeLock = new Object();
	/** ProtectionDomain for the bundle */
	protected BundleProtectionDomain domain;

	volatile protected ManifestLocalization manifestLocalization = null;

	/**
	 * Bundle object constructor. This constructor should not perform any real
	 * work.
	 * 
	 * @param bundledata
	 *            BundleData for this bundle
	 * @param framework
	 *            Framework this bundle is running in
	 */
	protected static AbstractBundle createBundle(BundleData bundledata, Framework framework, boolean setBundle) throws BundleException {
		AbstractBundle result;
		if ((bundledata.getType() & BundleData.TYPE_FRAGMENT) > 0)
			result = new BundleFragment(bundledata, framework);
		else if ((bundledata.getType() & BundleData.TYPE_COMPOSITEBUNDLE) > 0)
			result = new CompositeImpl(bundledata, framework);
		else if ((bundledata.getType() & BundleData.TYPE_SURROGATEBUNDLE) > 0)
			result = new SurrogateImpl(bundledata, framework);
		else
			result = new BundleHost(bundledata, framework);
		if (setBundle)
			bundledata.setBundle(result);
		return result;
	}

	/**
	 * Bundle object constructor. This constructor should not perform any real
	 * work.
	 * 
	 * @param bundledata
	 *            BundleData for this bundle
	 * @param framework
	 *            Framework this bundle is running in
	 */
	protected AbstractBundle(BundleData bundledata, Framework framework) {
		state = INSTALLED;
		stateChanging = null;
		this.bundledata = bundledata;
		this.framework = framework;
	}

	/**
	 * Load the bundle.
	 */
	protected abstract void load();

	/**
	 * Reload from a new bundle. This method must be called while holding the
	 * bundles lock.
	 * 
	 * @param newBundle
	 *            Dummy Bundle which contains new data.
	 * @return true if an exported package is "in use". i.e. it has been
	 *         imported by a bundle
	 */
	protected abstract boolean reload(AbstractBundle newBundle);

	/**
	 * Refresh the bundle. This is called by Framework.refreshPackages. This
	 * method must be called while holding the bundles lock.
	 * this.loader.unimportPackages must have already been called before
	 * calling this method!
	 */
	protected abstract void refresh();

	/**
	 * Unload the bundle. This method must be called while holding the bundles
	 * lock.
	 * 
	 * @return true if an exported package is "in use". i.e. it has been
	 *         imported by a bundle
	 */
	protected abstract boolean unload();

	/**
	 * Close the the Bundle's file.
	 *  
	 */
	protected void close() {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			if ((state & (INSTALLED)) == 0) {
				Debug.println("Bundle.close called when state != INSTALLED: " + this); //$NON-NLS-1$
				Debug.printStackTrace(new Exception("Stack trace")); //$NON-NLS-1$
			}
		}
		state = UNINSTALLED;
	}

	/** 
	 * Load and instantiate bundle's BundleActivator class
	 */
	protected BundleActivator loadBundleActivator() throws BundleException {
		/* load Bundle's BundleActivator if it has one */
		String activatorClassName = bundledata.getActivator();
		if (activatorClassName != null) {
			try {
				Class activatorClass = loadClass(activatorClassName, false);
				/* Create the activator for the bundle */
				return (BundleActivator) (activatorClass.newInstance());
			} catch (Throwable t) {
				if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
					Debug.printStackTrace(t);
				}
				throw new BundleException(NLS.bind(Msg.BUNDLE_INVALID_ACTIVATOR_EXCEPTION, activatorClassName, bundledata.getSymbolicName()), BundleException.ACTIVATOR_ERROR, t);
			}
		}
		return (null);
	}

	/**
	 * This method loads a class from the bundle.
	 * 
	 * @param name
	 *            the name of the desired Class.
	 * @param checkPermission
	 *            indicates whether a permission check should be done.
	 * @return the resulting Class
	 * @exception java.lang.ClassNotFoundException
	 *                if the class definition was not found.
	 */
	protected abstract Class loadClass(String name, boolean checkPermission) throws ClassNotFoundException;

	/**
	 * Returns the current state of the bundle.
	 * 
	 * A bundle can only be in one state at any time.
	 * 
	 * @return bundle's state.
	 */
	public int getState() {
		return (state);
	}

	public Framework getFramework() {
		return framework;
	}

	/**
	 * Return true if the bundle is starting or active.
	 *  
	 */
	protected boolean isActive() {
		return ((state & (ACTIVE | STARTING)) != 0);
	}

	boolean isLazyStart() {
		int status = bundledata.getStatus();
		return (status & Constants.BUNDLE_ACTIVATION_POLICY) != 0 && (status & Constants.BUNDLE_LAZY_START) != 0;
	}

	/**
	 * Return true if the bundle is resolved.
	 *  
	 */
	public boolean isResolved() {
		return (state & (INSTALLED | UNINSTALLED)) == 0;
	}

	/**
	 * Start this bundle.
	 * 
	 * If the current start level is less than this bundle's start level, then
	 * the Framework must persistently mark this bundle as started and delay
	 * the starting of this bundle until the Framework's current start level
	 * becomes equal or more than the bundle's start level.
	 * <p>
	 * Otherwise, the following steps are required to start a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #ACTIVE}or {@link #STARTING}then this
	 * method returns immediately.
	 * <li>If the bundle is {@link #STOPPING}then this method may wait for
	 * the bundle to return to the {@link #RESOLVED}state before continuing.
	 * If this does not occur in a reasonable time, a {@link BundleException}
	 * is thrown to indicate the bundle was unable to be started.
	 * <li>If the bundle is not {@link #RESOLVED}, an attempt is made to
	 * resolve the bundle. If the bundle cannot be resolved, a
	 * {@link BundleException}is thrown.
	 * <li>The state of the bundle is set to {@link #STARTING}.
	 * <li>The {@link BundleActivator#start(BundleContext) start}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}is invalid or throws an exception, the state of
	 * the bundle is set back to {@link #RESOLVED}, the bundle's listeners, if
	 * any, are removed, service's registered by the bundle, if any, are
	 * unregistered, and service's used by the bundle, if any, are released. A
	 * {@link BundleException}is then thrown.
	 * <li>It is recorded that this bundle has been started, so that when the
	 * framework is restarted, this bundle will be automatically started.
	 * <li>The state of the bundle is set to {@link #ACTIVE}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STARTED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #INSTALLED},{@link #RESOLVED}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * <li>{@link BundleActivator#start(BundleContext) BundleActivator.start}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #STARTING},{@link #ACTIVE}}.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the bundle couldn't be started. This could be because
	 *                a code dependency could not be resolved or the specified
	 *                BundleActivator could not be loaded or threw an
	 *                exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled or the bundle tries to
	 *                change its own state.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public void start() throws BundleException {
		start(0);
	}

	public void start(int options) throws BundleException {
		framework.checkAdminPermission(this, AdminPermission.EXECUTE);
		checkValid();
		beginStateChange();
		try {
			startWorker(options);
		} finally {
			completeStateChange();
		}
	}

	/**
	 * Internal worker to start a bundle.
	 * 
	 * @param options
	 */
	protected abstract void startWorker(int options) throws BundleException;

	/**
	 * This method does the following
	 * <ol>
	 * <li> Return false if the bundle is a fragment
	 * <li> Return false if the bundle is not at the correct start-level
	 * <li> Return false if the bundle is not persistently marked for start
	 * <li> Return true if the bundle's activation policy is persistently ignored
	 * <li> Return true if the bundle does not define an activation policy
	 * <li> Transition to STARTING state and Fire LAZY_ACTIVATION event
	 * <li> Return false
	 * </ol>
	 * @return true if the bundle should be resumed
	 */
	protected boolean readyToResume() {
		return false;
	}

	/**
	 * Start this bundle w/o marking is persistently started.
	 * 
	 * <p>
	 * The following steps are followed to start a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #ACTIVE}or {@link #STARTING}then this
	 * method returns immediately.
	 * <li>If the bundle is {@link #STOPPING}then this method may wait for
	 * the bundle to return to the {@link #RESOLVED}state before continuing.
	 * If this does not occur in a reasonable time, a {@link BundleException}
	 * is thrown to indicate the bundle was unable to be started.
	 * <li>If the bundle is not {@link #RESOLVED}, an attempt is made to
	 * resolve the bundle. If the bundle cannot be resolved, a
	 * {@link BundleException}is thrown.
	 * <li>The state of the bundle is set to {@link #STARTING}.
	 * <li>The {@link BundleActivator#start(BundleContext) start}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}is invalid or throws an exception, the state of
	 * the bundle is set back to {@link #RESOLVED}, the bundle's listeners, if
	 * any, are removed, service's registered by the bundle, if any, are
	 * unregistered, and service's used by the bundle, if any, are released. A
	 * {@link BundleException}is then thrown.
	 * <li>The state of the bundle is set to {@link #ACTIVE}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STARTED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #INSTALLED},{@link #RESOLVED}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * <li>{@link BundleActivator#start(BundleContext) BundleActivator.start}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #STARTING},{@link #ACTIVE}}.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the bundle couldn't be started. This could be because
	 *                a code dependency could not be resolved or the specified
	 *                BundleActivator could not be loaded or threw an
	 *                exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle tries to change its own state.
	 */
	protected void resume() throws BundleException {
		if (state == UNINSTALLED) {
			return;
		}
		beginStateChange();
		try {
			if (readyToResume())
				startWorker(START_TRANSIENT);
		} finally {
			completeStateChange();
		}
	}

	/**
	 * Stop this bundle.
	 * 
	 * Any services registered by this bundle will be unregistered. Any
	 * services used by this bundle will be released. Any listeners registered
	 * by this bundle will be removed.
	 * 
	 * <p>
	 * The following steps are followed to stop a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #STOPPING},{@link #RESOLVED}, or
	 * {@link #INSTALLED}then this method returns immediately.
	 * <li>If the bundle is {@link #STARTING}then this method may wait for
	 * the bundle to reach the {@link #ACTIVE}state before continuing. If this
	 * does not occur in a reasonable time, a {@link BundleException}is thrown
	 * to indicate the bundle was unable to be stopped.
	 * <li>The state of the bundle is set to {@link #STOPPING}.
	 * <li>It is recorded that this bundle has been stopped, so that when the
	 * framework is restarted, this bundle will not be automatically started.
	 * <li>The {@link BundleActivator#stop(BundleContext) stop}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}throws an exception, this method will continue
	 * to stop the bundle. A {@link BundleException}will be thrown after
	 * completion of the remaining steps.
	 * <li>The bundle's listeners, if any, are removed, service's registered
	 * by the bundle, if any, are unregistered, and service's used by the
	 * bundle, if any, are released.
	 * <li>The state of the bundle is set to {@link #RESOLVED}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STOPPED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #ACTIVE},{@link #STOPPING}}.
	 * <li>{@link BundleActivator#stop(BundleContext) BundleActivator.stop}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>None.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the bundle's BundleActivator could not be loaded or
	 *                threw an exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled or the bundle tries to
	 *                change its own state.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public void stop() throws BundleException {
		stop(0);
	}

	public void stop(int options) throws BundleException {
		framework.checkAdminPermission(this, AdminPermission.EXECUTE);
		checkValid();
		beginStateChange();
		try {
			stopWorker(options);
		} finally {
			completeStateChange();
		}
	}

	/**
	 * Internal worker to stop a bundle.
	 * 
	 * @param options
	 */
	protected abstract void stopWorker(int options) throws BundleException;

	/**
	 * Set the persistent status bit for the bundle.
	 * 
	 * @param mask
	 *            Mask for bit to set/clear
	 * @param state
	 *            true to set bit, false to clear bit
	 */
	protected void setStatus(final int mask, final boolean state) {
		try {
			AccessController.doPrivileged(new PrivilegedExceptionAction() {
				public Object run() throws BundleException, IOException {
					int status = bundledata.getStatus();
					boolean test = ((status & mask) != 0);
					if (test != state) {
						bundledata.setStatus(state ? (status | mask) : (status & ~mask));
						bundledata.save();
					}
					return null;
				}
			});
		} catch (PrivilegedActionException pae) {
			framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, pae.getException());
		}
	}

	/**
	 * Stop this bundle w/o marking is persistently stopped.
	 * 
	 * Any services registered by this bundle will be unregistered. Any
	 * services used by this bundle will be released. Any listeners registered
	 * by this bundle will be removed.
	 * 
	 * <p>
	 * The following steps are followed to stop a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #STOPPING},{@link #RESOLVED}, or
	 * {@link #INSTALLED}then this method returns immediately.
	 * <li>If the bundle is {@link #STARTING}then this method may wait for
	 * the bundle to reach the {@link #ACTIVE}state before continuing. If this
	 * does not occur in a reasonable time, a {@link BundleException}is thrown
	 * to indicate the bundle was unable to be stopped.
	 * <li>The state of the bundle is set to {@link #STOPPING}.
	 * <li>The {@link BundleActivator#stop(BundleContext) stop}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}throws an exception, this method will continue
	 * to stop the bundle. A {@link BundleException}will be thrown after
	 * completion of the remaining steps.
	 * <li>The bundle's listeners, if any, are removed, service's registered
	 * by the bundle, if any, are unregistered, and service's used by the
	 * bundle, if any, are released.
	 * <li>The state of the bundle is set to {@link #RESOLVED}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STOPPED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #ACTIVE},{@link #STOPPING}}.
	 * <li>{@link BundleActivator#stop(BundleContext) BundleActivator.stop}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>None.
	 * </ul>
	 * 
	 * @param lock
	 *            true if state change lock should be held when returning from
	 *            this method.
	 * @exception BundleException
	 *                If the bundle's BundleActivator could not be loaded or
	 *                threw an exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle tries to change its own state.
	 */
	protected void suspend(boolean lock) throws BundleException {
		if (state == UNINSTALLED) {
			return;
		}
		beginStateChange();
		try {
			stopWorker(STOP_TRANSIENT);
		} finally {
			if (!lock) {
				completeStateChange();
			}
		}
	}

	public void update() throws BundleException {
		update(null);
	}

	public void update(final InputStream in) throws BundleException {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			Debug.println("update location " + bundledata.getLocation()); //$NON-NLS-1$
			Debug.println("   from: " + in); //$NON-NLS-1$
		}
		framework.checkAdminPermission(this, AdminPermission.LIFECYCLE);
		if ((bundledata.getType() & (BundleData.TYPE_BOOTCLASSPATH_EXTENSION | BundleData.TYPE_FRAMEWORK_EXTENSION | BundleData.TYPE_EXTCLASSPATH_EXTENSION)) != 0)
			// need special permission to update extensions
			framework.checkAdminPermission(this, AdminPermission.EXTENSIONLIFECYCLE);
		checkValid();
		beginStateChange();
		try {
			final AccessControlContext callerContext = AccessController.getContext();
			//note AdminPermission is checked again after updated bundle is loaded
			updateWorker(new PrivilegedExceptionAction() {
				public Object run() throws BundleException {
					/* compute the update location */
					URLConnection source = null;
					if (in == null) {
						String updateLocation = (String) bundledata.getManifest().get(Constants.BUNDLE_UPDATELOCATION);
						if (updateLocation == null)
							updateLocation = bundledata.getLocation();
						if (Debug.DEBUG && Debug.DEBUG_GENERAL)
							Debug.println("   from location: " + updateLocation); //$NON-NLS-1$
						/* Map the update location to a URLConnection */
						source = framework.adaptor.mapLocationToURLConnection(updateLocation);
					} else {
						/* Map the InputStream to a URLConnection */
						source = new BundleSource(in);
					}
					/* call the worker */
					updateWorkerPrivileged(source, callerContext);
					return null;
				}
			});
		} finally {
			completeStateChange();
		}
	}

	/**
	 * Update worker. Assumes the caller has the state change lock.
	 */
	protected void updateWorker(PrivilegedExceptionAction action) throws BundleException {
		int previousState = 0;
		if (!isFragment())
			previousState = state;
		if ((previousState & (ACTIVE | STARTING)) != 0) {
			try {
				stopWorker(STOP_TRANSIENT);
			} catch (BundleException e) {
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
				if ((state & (ACTIVE | STARTING)) != 0) /* if the bundle is still active */{
					throw e;
				}
			}
		}
		try {
			AccessController.doPrivileged(action);
			framework.publishBundleEvent(BundleEvent.UPDATED, this);
		} catch (PrivilegedActionException pae) {
			if (pae.getException() instanceof RuntimeException)
				throw (RuntimeException) pae.getException();
			throw (BundleException) pae.getException();
		} finally {
			if ((previousState & (ACTIVE | STARTING)) != 0) {
				try {
					startWorker(START_TRANSIENT | ((previousState & STARTING) != 0 ? START_ACTIVATION_POLICY : 0));
				} catch (BundleException e) {
					framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
				}
			}
		}
	}

	/**
	 * Update worker. Assumes the caller has the state change lock.
	 */
	protected void updateWorkerPrivileged(URLConnection source, AccessControlContext callerContext) throws BundleException {
		AbstractBundle oldBundle = AbstractBundle.createBundle(bundledata, framework, false);
		boolean reloaded = false;
		BundleOperation storage = framework.adaptor.updateBundle(this.bundledata, source);
		BundleRepository bundles = framework.getBundles();
		try {
			BundleData newBundleData = storage.begin();
			// Must call framework createBundle to check execution environment.
			final AbstractBundle newBundle = framework.createAndVerifyBundle(newBundleData, false);
			boolean exporting;
			int st = getState();
			synchronized (bundles) {
				String oldBSN = this.getSymbolicName();
				exporting = reload(newBundle);
				// update this to flush the old BSN/version etc.
				bundles.update(oldBSN, this);
				manifestLocalization = null;
			}
			// indicate we have loaded from the new version of the bundle
			reloaded = true;
			if (System.getSecurityManager() != null) {
				final boolean extension = (bundledata.getType() & (BundleData.TYPE_BOOTCLASSPATH_EXTENSION | BundleData.TYPE_FRAMEWORK_EXTENSION | BundleData.TYPE_EXTCLASSPATH_EXTENSION)) != 0;
				// must check for AllPermission before allow a bundle extension to be updated
				if (extension && !hasPermission(new AllPermission()))
					throw new BundleException(Msg.BUNDLE_EXTENSION_PERMISSION, BundleException.SECURITY_ERROR, new SecurityException(Msg.BUNDLE_EXTENSION_PERMISSION));
				try {
					AccessController.doPrivileged(new PrivilegedExceptionAction() {
						public Object run() throws Exception {
							framework.checkAdminPermission(newBundle, AdminPermission.LIFECYCLE);
							if (extension) // need special permission to update extension bundles
								framework.checkAdminPermission(newBundle, AdminPermission.EXTENSIONLIFECYCLE);
							return null;
						}
					}, callerContext);
				} catch (PrivilegedActionException e) {
					throw e.getException();
				}
			}
			// send out unresolved events outside synch block (defect #80610)
			if (st == RESOLVED)
				framework.publishBundleEvent(BundleEvent.UNRESOLVED, this);
			storage.commit(exporting);
		} catch (Throwable t) {
			try {
				storage.undo();
				if (reloaded)
				/*
				 * if we loaded from the new version of the
				 * bundle
				 */{
					synchronized (bundles) {
						String oldBSN = this.getSymbolicName();
						reload(oldBundle);
						// update this to flush the new BSN/version back to the old one etc.
						bundles.update(oldBSN, this);
					}
				}
			} catch (BundleException ee) {
				/* if we fail to revert then we are in big trouble */
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, ee);
			}
			if (t instanceof SecurityException)
				throw (SecurityException) t;
			if (t instanceof BundleException)
				throw (BundleException) t;
			throw new BundleException(t.getMessage(), t);
		}
	}

	/**
	 * Uninstall this bundle.
	 * <p>
	 * This method removes all traces of the bundle, including any data in the
	 * persistent storage area provided for the bundle by the framework.
	 * 
	 * <p>
	 * The following steps are followed to uninstall a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #ACTIVE}or {@link #STARTING}, the bundle
	 * is stopped as described in the {@link #stop()}method. If {@link #stop()}
	 * throws an exception, a {@link FrameworkEvent}of type
	 * {@link FrameworkEvent#ERROR}is broadcast containing the exception.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#UNINSTALLED}is
	 * broadcast.
	 * <li>The state of the bundle is set to {@link #UNINSTALLED}.
	 * <li>The bundle and the persistent storage area provided for the bundle
	 * by the framework, if any, is removed.
	 * </ol>
	 * 
	 * <h5>Preconditions</h5>
	 * <ul>
	 * <li>getState() not in {{@link #UNINSTALLED}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #UNINSTALLED}}.
	 * <li>The bundle has been uninstalled.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #UNINSTALLED}}.
	 * <li>The Bundle has not been uninstalled.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the uninstall failed.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled or the bundle tries to
	 *                change its own state.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 * @see #stop()
	 */
	public void uninstall() throws BundleException {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			Debug.println("uninstall location: " + bundledata.getLocation()); //$NON-NLS-1$
		}
		framework.checkAdminPermission(this, AdminPermission.LIFECYCLE);
		if ((bundledata.getType() & (BundleData.TYPE_BOOTCLASSPATH_EXTENSION | BundleData.TYPE_FRAMEWORK_EXTENSION | BundleData.TYPE_EXTCLASSPATH_EXTENSION)) != 0)
			// need special permission to uninstall extensions
			framework.checkAdminPermission(this, AdminPermission.EXTENSIONLIFECYCLE);
		checkValid();
		beginStateChange();
		try {
			uninstallWorker(new PrivilegedExceptionAction() {
				public Object run() throws BundleException {
					uninstallWorkerPrivileged();
					return null;
				}
			});
		} finally {
			completeStateChange();
		}
	}

	/**
	 * Uninstall worker. Assumes the caller has the state change lock.
	 */
	protected void uninstallWorker(PrivilegedExceptionAction action) throws BundleException {
		boolean bundleActive = false;
		if (!isFragment())
			bundleActive = (state & (ACTIVE | STARTING)) != 0;
		if (bundleActive) {
			try {
				stopWorker(STOP_TRANSIENT);
			} catch (BundleException e) {
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
			}
		}
		try {
			AccessController.doPrivileged(action);
		} catch (PrivilegedActionException pae) {
			if (bundleActive) /* if we stopped the bundle */{
				try {
					startWorker(START_TRANSIENT);
				} catch (BundleException e) {
					/*
					 * if we fail to start the original bundle then we are in
					 * big trouble
					 */
					framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
				}
			}
			throw (BundleException) pae.getException();
		}
		framework.publishBundleEvent(BundleEvent.UNINSTALLED, this);
	}

	/**
	 * Uninstall worker. Assumes the caller has the state change lock.
	 */
	protected void uninstallWorkerPrivileged() throws BundleException {
		if (Debug.DEBUG) {
			BundleWatcher bundleStats = framework.adaptor.getBundleWatcher();
			if (bundleStats != null)
				bundleStats.watchBundle(this, BundleWatcher.START_UNINSTALLING);
		}
		boolean unloaded = false;
		//cache the bundle's headers
		getHeaders();
		BundleOperation storage = framework.adaptor.uninstallBundle(this.bundledata);
		BundleRepository bundles = framework.getBundles();
		try {
			storage.begin();
			boolean exporting;
			int st = getState();
			synchronized (bundles) {
				bundles.remove(this); /* remove before calling unload */
				exporting = unload();
			}
			// send out unresolved events outside synch block (defect #80610)
			if (st == RESOLVED)
				framework.publishBundleEvent(BundleEvent.UNRESOLVED, this);
			unloaded = true;
			storage.commit(exporting);
			close();
		} catch (BundleException e) {
			try {
				storage.undo();
				if (unloaded) /* if we unloaded the bundle */{
					synchronized (bundles) {
						load(); /* reload the bundle */
						bundles.add(this);
					}
				}
			} catch (BundleException ee) {
				/*
				 * if we fail to load the original bundle then we are in big
				 * trouble
				 */
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, ee);
			}
			throw e;
		} finally {
			if (Debug.DEBUG) {
				BundleWatcher bundleStats = framework.adaptor.getBundleWatcher();
				if (bundleStats != null)
					bundleStats.watchBundle(this, BundleWatcher.END_UNINSTALLING);
			}
		}
	}

	/**
	 * Return the bundle's manifest headers and values from the manifest's
	 * preliminary section. That is all the manifest's headers and values prior
	 * to the first blank line.
	 * 
	 * <p>
	 * Manifest header names are case-insensitive. The methods of the returned
	 * <code>Dictionary</code> object will operate on header names in a
	 * case-insensitive manner.
	 * 
	 * <p>
	 * For example, the following manifest headers and values are included if
	 * they are present in the manifest:
	 * 
	 * <pre>
	 *  Bundle-Name
	 *  Bundle-Vendor
	 *  Bundle-Version
	 *  Bundle-Description
	 *  Bundle-DocURL
	 *  Bundle-ContactAddress
	 * </pre>
	 * 
	 * <p>
	 * This method will continue to return this information when the bundle is
	 * in the {@link #UNINSTALLED}state.
	 * 
	 * @return A <code>Dictionary</code> object containing the bundle's
	 *         manifest headers and values.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public Dictionary getHeaders() {
		return getHeaders(null);
	}

	/**
	 * Returns this bundle's Manifest headers and values. This method returns
	 * all the Manifest headers and values from the main section of the
	 * bundle's Manifest file; that is, all lines prior to the first blank
	 * line.
	 * 
	 * <p>
	 * Manifest header names are case-insensitive. The methods of the returned
	 * <tt>Dictionary</tt> object will operate on header names in a
	 * case-insensitive manner.
	 * 
	 * If a Manifest header begins with a '%', it will be evaluated with the
	 * specified properties file for the specied Locale.
	 * 
	 * <p>
	 * For example, the following Manifest headers and values are included if
	 * they are present in the Manifest file:
	 * 
	 * <pre>
	 *  Bundle-Name
	 *  Bundle-Vendor
	 *  Bundle-Version
	 *  Bundle-Description
	 *  Bundle-DocURL
	 *  Bundle-ContactAddress
	 * </pre>
	 * 
	 * <p>
	 * This method will continue to return Manifest header information while
	 * this bundle is in the <tt>UNINSTALLED</tt> state.
	 * 
	 * @return A <tt>Dictionary</tt> object containing this bundle's Manifest
	 *         headers and values.
	 * 
	 * @exception java.lang.SecurityException
	 *                If the caller does not have the <tt>AdminPermission</tt>,
	 *                and the Java Runtime Environment supports permissions.
	 */
	public Dictionary getHeaders(String localeString) {
		framework.checkAdminPermission(this, AdminPermission.METADATA);
		ManifestLocalization localization;
		try {
			localization = getManifestLocalization();
		} catch (BundleException e) {
			framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
			// return an empty dictinary.
			return new Hashtable();
		}
		if (localeString == null)
			localeString = Locale.getDefault().toString();
		return localization.getHeaders(localeString);
	}

	/**
	 * Retrieve the bundle's unique identifier, which the framework assigned to
	 * this bundle when it was installed.
	 * 
	 * <p>
	 * The unique identifier has the following attributes:
	 * <ul>
	 * <li>It is unique and persistent.
	 * <li>The identifier is a long.
	 * <li>Once its value is assigned to a bundle, that value is not reused
	 * for another bundle, even after the bundle is uninstalled.
	 * <li>Its value does not change as long as the bundle remains installed.
	 * <li>Its value does not change when the bundle is updated
	 * </ul>
	 * 
	 * <p>
	 * This method will continue to return the bundle's unique identifier when
	 * the bundle is in the {@link #UNINSTALLED}state.
	 * 
	 * @return This bundle's unique identifier.
	 */
	public long getBundleId() {
		return (bundledata.getBundleID());
	}

	/**
	 * Retrieve the location identifier of the bundle. This is typically the
	 * location passed to
	 * {@link BundleContextImpl#installBundle(String) BundleContext.installBundle}when the
	 * bundle was installed. The location identifier of the bundle may change
	 * during bundle update. Calling this method while framework is updating
	 * the bundle results in undefined behavior.
	 * 
	 * <p>
	 * This method will continue to return the bundle's location identifier
	 * when the bundle is in the {@link #UNINSTALLED}state.
	 * 
	 * @return A string that is the location identifier of the bundle.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public String getLocation() {
		framework.checkAdminPermission(this, AdminPermission.METADATA);
		return (bundledata.getLocation());
	}

	/**
	 * Determine whether the bundle has the requested permission.
	 * 
	 * <p>
	 * If the Java runtime environment does not supports permissions this
	 * method always returns <code>true</code>. The permission parameter is
	 * of type <code>Object</code> to avoid referencing the <code>java.security.Permission</code>
	 * class directly. This is to allow the framework to be implemented in Java
	 * environments which do not support permissions.
	 * 
	 * @param permission
	 *            The requested permission.
	 * @return <code>true</code> if the bundle has the requested permission
	 *         or <code>false</code> if the bundle does not have the
	 *         permission or the permission parameter is not an <code>instanceof java.security.Permission</code>.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled.
	 */
	public boolean hasPermission(Object permission) {
		checkValid();
		if (domain != null) {
			if (permission instanceof Permission) {
				SecurityManager sm = System.getSecurityManager();
				if (sm instanceof EquinoxSecurityManager) {
					/*
					 * If the FrameworkSecurityManager is active, we need to do checks the "right" way.
					 * We can exploit our knowledge that the security context of FrameworkSecurityManager
					 * is an AccessControlContext to invoke it properly with the ProtectionDomain.
					 */
					AccessControlContext acc = new AccessControlContext(new ProtectionDomain[] {domain});
					try {
						sm.checkPermission((Permission) permission, acc);
						return true;
					} catch (Exception e) {
						return false;
					}
				}
				return domain.implies((Permission) permission);
			}
			return false;
		}
		return true;
	}

	/**
	 * This method marks the bundle's state as changing so that other calls to
	 * start/stop/suspend/update/uninstall can wait until the state change is
	 * complete. If stateChanging is non-null when this method is called, we
	 * will wait for the state change to complete. If the timeout expires
	 * without changing state (this may happen if the state change is back up
	 * our call stack), a BundleException is thrown so that we don't wait
	 * forever.
	 * 
	 * A call to this method should be immediately followed by a try block
	 * whose finally block calls completeStateChange().
	 * 
	 * beginStateChange(); try { // change the bundle's state here... } finally {
	 * completeStateChange(); }
	 * 
	 * @exception org.osgi.framework.BundleException
	 *                if the bundles state is still changing after waiting for
	 *                the timeout.
	 */
	protected void beginStateChange() throws BundleException {
		synchronized (statechangeLock) {
			boolean doubleFault = false;
			while (true) {
				if (stateChanging == null) {
					stateChanging = Thread.currentThread();
					return;
				}
				if (doubleFault || (stateChanging == Thread.currentThread())) {
					throw new BundleException(NLS.bind(Msg.BUNDLE_STATE_CHANGE_EXCEPTION, getBundleData().getLocation(), stateChanging.getName()), BundleException.STATECHANGE_ERROR, new BundleStatusException(null, StatusException.CODE_WARNING, stateChanging));
				}
				try {
					long start = 0;
					if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
						Debug.println(" Waiting for state to change in bundle " + this); //$NON-NLS-1$
						start = System.currentTimeMillis();
					}
					statechangeLock.wait(5000);
					/*
					 * wait for other thread to
					 * finish changing state
					 */
					if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
						long end = System.currentTimeMillis();
						if (end - start > 0)
							System.out.println("Waiting... : " + getSymbolicName() + ' ' + (end - start)); //$NON-NLS-1$
					}
				} catch (InterruptedException e) {
					//Nothing to do
				}
				doubleFault = true;
			}
		}
	}

	/**
	 * This method completes the bundle state change by setting stateChanging
	 * to null and notifying one waiter that the state change has completed.
	 */
	protected void completeStateChange() {
		synchronized (statechangeLock) {
			if (stateChanging == Thread.currentThread()) {
				stateChanging = null;
				statechangeLock.notify();
				/*
				 * notify one waiting thread that the
				 * state change is complete
				 */
			}
		}
	}

	/**
	 * Return a string representation of this bundle.
	 * 
	 * @return String
	 */
	public String toString() {
		String name = bundledata.getSymbolicName();
		if (name == null)
			name = "unknown"; //$NON-NLS-1$
		return (name + '_' + bundledata.getVersion() + " [" + getBundleId() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Answers an integer indicating the relative positions of the receiver and
	 * the argument in the natural order of elements of the receiver's class.
	 * 
	 * @return int which should be <0 if the receiver should sort before the
	 *         argument, 0 if the receiver should sort in the same position as
	 *         the argument, and >0 if the receiver should sort after the
	 *         argument.
	 * @param obj
	 *            another Bundle an object to compare the receiver to
	 * @exception ClassCastException
	 *                if the argument can not be converted into something
	 *                comparable with the receiver.
	 */
	public int compareTo(Object obj) {
		int slcomp = getStartLevel() - ((AbstractBundle) obj).getStartLevel();
		if (slcomp != 0) {
			return slcomp;
		}
		long idcomp = getBundleId() - ((AbstractBundle) obj).getBundleId();
		return (idcomp < 0L) ? -1 : ((idcomp > 0L) ? 1 : 0);
	}

	/**
	 * This method checks that the bundle is not uninstalled. If the bundle is
	 * uninstalled, an IllegalStateException is thrown.
	 * 
	 * @exception java.lang.IllegalStateException
	 *                If the bundle is uninstalled.
	 */
	protected void checkValid() {
		if (state == UNINSTALLED) {
			throw new IllegalStateException(NLS.bind(Msg.BUNDLE_UNINSTALLED_EXCEPTION, getBundleData().getLocation()));
		}
	}

	/**
	 * Get the bundle's ProtectionDomain.
	 * 
	 * @return bundle's ProtectionDomain.
	 */
	public BundleProtectionDomain getProtectionDomain() {
		return domain;
	}

	protected BundleFragment[] getFragments() {
		checkValid();
		return null;
	}

	protected boolean isFragment() {
		return false;
	}

	BundleHost[] getHosts() {
		checkValid();
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.framework.Bundle#findClass(java.lang.String)
	 */
	public Class loadClass(String classname) throws ClassNotFoundException {
		return loadClass(classname, true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.framework.Bundle#getResourcePaths(java.lang.String)
	 */
	public Enumeration getEntryPaths(final String path) {
		try {
			framework.checkAdminPermission(this, AdminPermission.RESOURCE);
		} catch (SecurityException e) {
			return null;
		}
		checkValid();
		// TODO this doPrivileged is probably not needed.  The adaptor isolates callers from disk access
		return (Enumeration) AccessController.doPrivileged(new PrivilegedAction() {
			public Object run() {
				return bundledata.getEntryPaths(path);
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.framework.Bundle#getFile(java.lang.String)
	 */
	public URL getEntry(String fileName) {
		try {
			framework.checkAdminPermission(this, AdminPermission.RESOURCE);
		} catch (SecurityException e) {
			return null;
		}
		return getEntry0(fileName);
	}

	URL getEntry0(String fileName) {
		checkValid();
		return bundledata.getEntry(fileName);
	}

	public String getSymbolicName() {
		return bundledata.getSymbolicName();
	}

	public long getLastModified() {
		return bundledata.getLastModified();
	}

	public BundleData getBundleData() {
		return bundledata;
	}

	public Version getVersion() {
		return bundledata.getVersion();
	}

	public BundleDescription getBundleDescription() {
		return framework.adaptor.getState().getBundle(getBundleId());
	}

	protected int getStartLevel() {
		return bundledata.getStartLevel();
	}

	protected abstract BundleLoader getBundleLoader();

	/**
	 * Mark this bundle as resolved.
	 */
	protected void resolve() {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			if ((state & (INSTALLED)) == 0) {
				Debug.println("Bundle.resolve called when state != INSTALLED: " + this); //$NON-NLS-1$
				Debug.printStackTrace(new Exception("Stack trace")); //$NON-NLS-1$
			}
		}
		if (state == INSTALLED) {
			state = RESOLVED;
			// Do not publish RESOLVED event here.  This is done by caller 
			// to resolve if appropriate.
		}
	}

	public BundleContext getBundleContext() {
		framework.checkAdminPermission(this, AdminPermission.CONTEXT);
		return getContext();
	}

	/**
	 * Return the current context for this bundle.
	 * 
	 * @return BundleContext for this bundle.
	 */
	abstract protected BundleContextImpl getContext();

	public BundleException getResolutionFailureException() {
		BundleDescription bundleDescription = getBundleDescription();
		if (bundleDescription == null)
			return new BundleException(NLS.bind(Msg.BUNDLE_UNRESOLVED_EXCEPTION, this.toString()), BundleException.RESOLVE_ERROR);
		// just a sanity check - this would be an inconsistency between the framework and the state
		if (bundleDescription.isResolved())
			return new BundleException(Msg.BUNDLE_UNRESOLVED_STATE_CONFLICT, BundleException.RESOLVE_ERROR);
		return getResolverError(bundleDescription);
	}

	private BundleException getResolverError(BundleDescription bundleDesc) {
		ResolverError[] errors = framework.adaptor.getState().getResolverErrors(bundleDesc);
		if (errors == null || errors.length == 0)
			return new BundleException(NLS.bind(Msg.BUNDLE_UNRESOLVED_EXCEPTION, this.toString()), BundleException.RESOLVE_ERROR);
		StringBuffer message = new StringBuffer();
		int errorType = BundleException.RESOLVE_ERROR;
		for (int i = 0; i < errors.length; i++) {
			if ((errors[i].getType() & ResolverError.INVALID_NATIVECODE_PATHS) != 0)
				errorType = BundleException.NATIVECODE_ERROR;
			message.append(errors[i].toString());
			if (i < errors.length - 1)
				message.append(", "); //$NON-NLS-1$
		}
		return new BundleException(NLS.bind(Msg.BUNDLE_UNRESOLVED_UNSATISFIED_CONSTRAINT_EXCEPTION, this.toString(), message.toString()), errorType);
	}

	public int getKeyHashCode() {
		long id = getBundleId();
		return (int) (id ^ (id >>> 32));
	}

	public boolean compare(KeyedElement other) {
		return getBundleId() == ((AbstractBundle) other).getBundleId();
	}

	public Object getKey() {
		return new Long(getBundleId());
	}

	/* This method is used by the Bundle Localization Service to obtain
	 * a ResourceBundle that resides in a bundle.  This is not an OSGi
	 * defined method for org.osgi.framework.Bundle
	 * 
	 */
	public ResourceBundle getResourceBundle(String localeString) {
		ManifestLocalization localization;
		try {
			localization = getManifestLocalization();
		} catch (BundleException ex) {
			return (null);
		}
		if (localeString == null) {
			localeString = Locale.getDefault().toString();
		}
		return localization.getResourceBundle(localeString);
	}

	private synchronized ManifestLocalization getManifestLocalization() throws BundleException {
		ManifestLocalization currentLocalization = manifestLocalization;
		if (currentLocalization == null) {
			Dictionary rawHeaders = bundledata.getManifest();
			manifestLocalization = currentLocalization = new ManifestLocalization(this, rawHeaders);
		}
		return currentLocalization;
	}

	public boolean testStateChanging(Object thread) {
		return stateChanging == thread;
	}

	public Thread getStateChanging() {
		return stateChanging;
	}

	public Enumeration findEntries(String path, String filePattern, boolean recurse) {
		try {
			framework.checkAdminPermission(this, AdminPermission.RESOURCE);
		} catch (SecurityException e) {
			return null;
		}
		checkValid();
		// check to see if the bundle is resolved
		if (!isResolved())
			framework.packageAdmin.resolveBundles(new Bundle[] {this});

		// a list used to store the results of the search
		List pathList = new ArrayList();
		Filter patternFilter = null;
		Hashtable patternProps = null;
		if (filePattern != null)
			try {
				// create a file pattern filter with 'filename' as the key
				patternFilter = FilterImpl.newInstance("(filename=" + sanitizeFilterInput(filePattern) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
				// create a single hashtable to be shared during the recursive search
				patternProps = new Hashtable(2);
			} catch (InvalidSyntaxException e) {
				// something unexpected happened; log error and return nothing
				Bundle b = framework.systemBundle;
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, b, e);
				return null;
			}
		// find the local entries of this bundle
		findLocalEntryPaths(path, patternFilter, patternProps, recurse, pathList);
		// if this bundle is a host to fragments then search the fragments
		final BundleFragment[] fragments = getFragments();
		final int numFragments = fragments == null ? -1 : fragments.length;
		for (int i = 0; i < numFragments; i++)
			((AbstractBundle) fragments[i]).findLocalEntryPaths(path, patternFilter, patternProps, recurse, pathList);
		// return null if no entries found
		if (pathList.size() == 0)
			return null;
		// create an enumeration to enumerate the pathList
		final String[] pathArray = (String[]) pathList.toArray(new String[pathList.size()]);
		return new Enumeration() {
			int curIndex = 0;
			int curFragment = -1;
			URL nextElement = null;

			public boolean hasMoreElements() {
				if (nextElement != null)
					return true;
				getNextElement();
				return nextElement != null;
			}

			public Object nextElement() {
				if (!hasMoreElements())
					throw new NoSuchElementException();
				URL result;
				result = nextElement;
				// force the next element search
				getNextElement();
				return result;
			}

			private void getNextElement() {
				nextElement = null;
				if (curIndex >= pathArray.length)
					// reached the end of the pathArray; no more elements
					return;
				String curPath = pathArray[curIndex];
				if (curFragment == -1) {
					// need to search ourselves first
					nextElement = getEntry0(curPath);
					curFragment++;
				}
				// if the element is not in the host look in the fragments until we have searched them all
				while (nextElement == null && curFragment < numFragments)
					nextElement = fragments[curFragment++].getEntry0(curPath);
				// if we have no fragments or we have searched all fragments then advance to the next path 
				if (numFragments == -1 || curFragment >= numFragments) {
					curIndex++;
					curFragment = -1;
				}
				// searched all fragments for the current path, move to the next one
				if (nextElement == null)
					getNextElement();
			}

		};
	}

	private String sanitizeFilterInput(String filePattern) throws InvalidSyntaxException {
		StringBuffer buffer = null;
		boolean foundEscape = false;
		for (int i = 0; i < filePattern.length(); i++) {
			char c = filePattern.charAt(i);
			switch (c) {
				case '\\' :
					// we either used the escape found or found a new escape.
					foundEscape = foundEscape ? false : true;
					if (buffer != null)
						buffer.append(c);
					break;
				case '(' :
				case ')' :
					if (!foundEscape) {
						if (buffer == null) {
							buffer = new StringBuffer(filePattern.length() + 16);
							buffer.append(filePattern.substring(0, i));
						}
						// must escape with '\'
						buffer.append('\\');
					} else {
						foundEscape = false; // used the escape found
					}
					if (buffer != null)
						buffer.append(c);
					break;
				default :
					// if we found an escape it has been used
					foundEscape = false;
					if (buffer != null)
						buffer.append(c);
					break;
			}
		}
		if (foundEscape)
			throw new InvalidSyntaxException("Trailing escape characters must be escaped.", filePattern); //$NON-NLS-1$
		return buffer == null ? filePattern : buffer.toString();
	}

	protected void findLocalEntryPaths(String path, Filter patternFilter, Hashtable patternProps, boolean recurse, List pathList) {
		Enumeration entryPaths = bundledata.getEntryPaths(path);
		if (entryPaths == null)
			return;
		while (entryPaths.hasMoreElements()) {
			String entry = (String) entryPaths.nextElement();
			int lastSlash = entry.lastIndexOf('/');
			if (patternProps != null) {
				int secondToLastSlash = entry.lastIndexOf('/', lastSlash - 1);
				int fileStart;
				int fileEnd = entry.length();
				if (lastSlash < 0)
					fileStart = 0;
				else if (lastSlash != entry.length() - 1)
					fileStart = lastSlash + 1;
				else {
					fileEnd = lastSlash; // leave the lastSlash out
					if (secondToLastSlash < 0)
						fileStart = 0;
					else
						fileStart = secondToLastSlash + 1;
				}
				String fileName = entry.substring(fileStart, fileEnd);
				// set the filename to the current entry
				patternProps.put("filename", fileName); //$NON-NLS-1$
			}
			// prevent duplicates and match on the patterFilter
			if (!pathList.contains(entry) && (patternFilter == null || patternFilter.matchCase(patternProps)))
				pathList.add(entry);
			// rescurse only into entries that are directories
			if (recurse && !entry.equals(path) && entry.length() > 0 && lastSlash == (entry.length() - 1))
				findLocalEntryPaths(entry, patternFilter, patternProps, recurse, pathList);
		}
		return;
	}

	class BundleStatusException extends Throwable implements StatusException {
		private static final long serialVersionUID = 7201911791818929100L;
		private int code;
		private Object status;

		BundleStatusException(String message, int code, Object status) {
			super(message);
			this.code = code;
			this.status = status;
		}

		public Object getStatus() {
			return status;
		}

		public int getStatusCode() {
			return code;
		}

	}

	public Map/* <X509Certificate, List<X509Certificate>> */getSignerCertificates(int signersType) {
		if (signersType != SIGNERS_ALL && signersType != SIGNERS_TRUSTED)
			throw new IllegalArgumentException("Invalid signers type: " + signersType); //$NON-NLS-1$
		if (framework == null)
			return Collections.EMPTY_MAP;
		SignedContentFactory factory = framework.getSignedContentFactory();
		if (factory == null)
			return Collections.EMPTY_MAP;
		try {
			SignedContent signedContent = factory.getSignedContent(this);
			SignerInfo[] infos = signedContent.getSignerInfos();
			if (infos.length == 0)
				return Collections.EMPTY_MAP;
			Map/* <X509Certificate, List<X509Certificate>> */results = new HashMap(infos.length);
			for (int i = 0; i < infos.length; i++) {
				if (signersType == SIGNERS_TRUSTED && !infos[i].isTrusted())
					continue;
				Certificate[] certs = infos[i].getCertificateChain();
				if (certs == null || certs.length == 0)
					continue;
				List/* <X509Certificate> */certChain = new ArrayList();
				for (int j = 0; j < certs.length; j++)
					certChain.add(certs[j]);
				results.put(certs[0], certChain);
			}
			return results;
		} catch (Exception e) {
			return Collections.EMPTY_MAP;
		}
	}
}
