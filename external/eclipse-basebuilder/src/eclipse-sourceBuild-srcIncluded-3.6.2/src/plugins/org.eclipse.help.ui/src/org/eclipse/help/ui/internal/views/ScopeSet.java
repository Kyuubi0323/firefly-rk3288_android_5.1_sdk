/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.help.ui.internal.views;

import java.io.*;

import org.eclipse.core.runtime.IPath;
import org.eclipse.help.ui.internal.*;
import org.eclipse.jface.preference.*;

/**
 * Federated search scope.
 */
public class ScopeSet {
	public static final String SCOPE_DIR_NAME = "scope_sets"; //$NON-NLS-1$
	private static final String KEY_DEFAULT = "__DEFAULT__"; //$NON-NLS-1$
	public static final String EXT = ".pref"; //$NON-NLS-1$
	private String name;
	private PreferenceStore preferenceStore;
	private boolean needsSaving;
	private int defaultSet = -1;
	
	public ScopeSet() {
		this(Messages.ScopeSet_default);
		defaultSet = 1;
	}
	
	public ScopeSet(String name) {
		this.needsSaving = true;
		this.name = name;
	}
	
	public boolean isEditable() {
		return !isDefault();
	}
	
	public boolean isDefault() {
		if (defaultSet==1)
			return true;
		return getPreferenceStore().getBoolean(KEY_DEFAULT);
	}
	
	public boolean isImplicit() {
		return false;
	}

	public ScopeSet(ScopeSet set) {
		this(set.getName()+"_new"); //$NON-NLS-1$
		copyFrom(set);
	}
	
	public void copyFrom(ScopeSet set) {
		copy((PreferenceStore)set.getPreferenceStore());
	}
	
	public void dispose() {
		File file = new File(getFileName(name));
		if (file.exists())
			file.delete();
	}

	public IPreferenceStore getPreferenceStore() {
		if (preferenceStore==null) {
			preferenceStore = new PreferenceStore(getFileName(this.name));
			try {
				File file = new File(getFileName(this.name));
				if (file.exists()) {
					preferenceStore.load();
				}
			}
			catch (IOException e) {
				String message = Messages.bind(Messages.ScopeSet_errorLoading, name);
				HelpUIPlugin.logError(message, e);
			}
		}
		return preferenceStore;
	}
	
	protected String encodeFileName(String name) {
		return name;
	}

	private String getFileName(String name) {
		IPath location = HelpUIPlugin.getDefault().getStateLocation();
		location = location.append(SCOPE_DIR_NAME);
		location = location.append(encodeFileName(name)+getExtension()); 
		return location.toOSString();
	}
	
	protected String getExtension() {
		return EXT;
	}

	private void copy(PreferenceStore store) {
		try {
			File file = File.createTempFile("sset", null); //$NON-NLS-1$
			FileOutputStream fos = new FileOutputStream(file);
			store.save(fos, ""); //$NON-NLS-1$
			fos.close();
			FileInputStream fis = new FileInputStream(file);
			getPreferenceStore();
			preferenceStore.load(fis);
			//when we clone the defult set, we should
			//clear the default marker
			preferenceStore.setValue(KEY_DEFAULT, false);
			fis.close();
		}
		catch (IOException e) {
		}
	}
	/**
	 * @return Returns the name.
	 */
	public String getName() {
		return name;
	}
	/**
	 * @param name The name to set.
	 */
	public void setName(String name) {
		String oldFileName = getFileName(this.name);
		File oldFile = new File(oldFileName);
		if (oldFile.exists()) {
			// store under the old name already exists
			if (preferenceStore==null) {
				// just rename the file
				oldFile.renameTo(new File(getFileName(name)));
			}
			else {
				// remove the old file, set the new file name,
				// then save to create the new file
				oldFile.delete();
				preferenceStore.setFilename(getFileName(name));
				try {
					preferenceStore.save();
				}
				catch (IOException e) {
					String message = Messages.bind(Messages.ScopeSet_errorSaving, name);
					HelpUIPlugin.logError(message, e);
				}
			}
		}
		this.name = name;
	}

	public void save() {
		getPreferenceStore();
		if (preferenceStore.needsSaving() || needsSaving) {
			try {
				if (defaultSet != -1)
					preferenceStore.setValue(KEY_DEFAULT, defaultSet>0);
				preferenceStore.save();
				needsSaving = false;
			}
			catch (IOException e) {
				String message = Messages.bind(Messages.ScopeSet_errorSaving, name);
				HelpUIPlugin.logError(message, e);
			}
		}
	}

	public boolean getEngineEnabled(EngineDescriptor desc) {
		IPreferenceStore store = getPreferenceStore();
		String key = getMasterKey(desc.getId());
		if (store.contains(key))
			return store.getBoolean(key);
		store.setValue(key, desc.isEnabled());
		return desc.isEnabled();
	}
	public void setEngineEnabled(EngineDescriptor desc, boolean value) {
		IPreferenceStore store = getPreferenceStore();
		String key = getMasterKey(desc.getId());
		store.setValue(key, value);
	}
	public static String getMasterKey(String id) {
		return id + ".master"; //$NON-NLS-1$
	}
	public static String getLabelKey(String id) {
		return id+".label"; //$NON-NLS-1$
	}
	public static String getDescKey(String id) {
		return id+".desc"; //$NON-NLS-1$
	}
}
