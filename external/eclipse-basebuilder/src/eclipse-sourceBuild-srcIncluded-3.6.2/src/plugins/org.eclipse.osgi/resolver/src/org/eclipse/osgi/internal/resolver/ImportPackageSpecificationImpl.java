/*******************************************************************************
 * Copyright (c) 2003, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Danail Nachev -  ProSyst - bug 218625
 *     Rob Harrop - SpringSource Inc. (bug 247522)
 *******************************************************************************/
package org.eclipse.osgi.internal.resolver;

import java.util.*;
import org.eclipse.osgi.framework.internal.core.Constants;
import org.eclipse.osgi.service.resolver.*;

public class ImportPackageSpecificationImpl extends VersionConstraintImpl implements ImportPackageSpecification {
	private String resolution = ImportPackageSpecification.RESOLUTION_STATIC; // the default is static
	private String symbolicName;
	private VersionRange bundleVersionRange;
	private Map attributes;

	public Map getDirectives() {
		synchronized (this.monitor) {
			Map result = new HashMap(5);
			if (resolution != null)
				result.put(Constants.RESOLUTION_DIRECTIVE, resolution);
			return result;
		}
	}

	public Object getDirective(String key) {
		synchronized (this.monitor) {
			if (key.equals(Constants.RESOLUTION_DIRECTIVE))
				return resolution;
			return null;
		}
	}

	public Object setDirective(String key, Object value) {
		synchronized (this.monitor) {
			if (key.equals(Constants.RESOLUTION_DIRECTIVE))
				return resolution = (String) value;
			return null;
		}
	}

	public void setDirectives(Map directives) {
		synchronized (this.monitor) {
			if (directives == null)
				return;
			resolution = (String) directives.get(Constants.RESOLUTION_DIRECTIVE);
		}
	}

	public String getBundleSymbolicName() {
		synchronized (this.monitor) {
			if (Constants.SYSTEM_BUNDLE_SYMBOLICNAME.equals(symbolicName)) {
				StateImpl state = (StateImpl) getBundle().getContainingState();
				return state == null ? Constants.getInternalSymbolicName() : state.getSystemBundle();
			}
			return symbolicName;
		}
	}

	public VersionRange getBundleVersionRange() {
		synchronized (this.monitor) {
			if (bundleVersionRange == null)
				return VersionRange.emptyRange;
			return bundleVersionRange;
		}
	}

	public Map getAttributes() {
		synchronized (this.monitor) {
			return attributes;
		}
	}

	public boolean isSatisfiedBy(BaseDescription supplier) {
		if (!(supplier instanceof ExportPackageDescription))
			return false;
		ExportPackageDescriptionImpl pkgDes = (ExportPackageDescriptionImpl) supplier;

		// If we are in strict mode, check to see if the export specifies friends.
		// If it does, are we one of the friends 
		String[] friends = (String[]) pkgDes.getDirective(Constants.FRIENDS_DIRECTIVE);
		Boolean internal = (Boolean) pkgDes.getDirective(Constants.INTERNAL_DIRECTIVE);
		if (internal.booleanValue() || friends != null) {
			StateImpl state = (StateImpl) getBundle().getContainingState();
			boolean strict = state == null ? false : state.inStrictMode();
			if (strict) {
				if (internal.booleanValue())
					return false;
				boolean found = false;
				if (friends != null && getBundle().getSymbolicName() != null)
					for (int i = 0; i < friends.length; i++)
						if (getBundle().getSymbolicName().equals(friends[i]))
							found = true;
				if (!found)
					return false;
			}
		}
		String exporterSymbolicName = getBundleSymbolicName();
		if (exporterSymbolicName != null) {
			BundleDescription exporter = pkgDes.getExporter();
			if (!exporterSymbolicName.equals(exporter.getSymbolicName()))
				return false;
			if (getBundleVersionRange() != null && !getBundleVersionRange().isIncluded(exporter.getVersion()))
				return false;
		}

		String name = getName();
		// shortcut '*'
		// NOTE: wildcards are supported only in cases where this is a dynamic import
		if (!"*".equals(name) && !(name.endsWith(".*") && pkgDes.getName().startsWith(name.substring(0, name.length() - 1))) && !pkgDes.getName().equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		if (getVersionRange() != null && !getVersionRange().isIncluded(pkgDes.getVersion()))
			return false;

		Map importAttrs = getAttributes();
		if (importAttrs != null) {
			Map exportAttrs = pkgDes.getAttributes();
			if (exportAttrs == null)
				return false;
			for (Iterator i = importAttrs.keySet().iterator(); i.hasNext();) {
				String importKey = (String) i.next();
				Object importValue = importAttrs.get(importKey);
				Object exportValue = exportAttrs.get(importKey);
				if (exportValue == null || !importValue.equals(exportValue))
					return false;
			}
		}
		String[] mandatory = (String[]) pkgDes.getDirective(Constants.MANDATORY_DIRECTIVE);
		if (mandatory != null) {
			for (int i = 0; i < mandatory.length; i++) {
				if (Constants.BUNDLE_SYMBOLICNAME_ATTRIBUTE.equals(mandatory[i])) {
					if (exporterSymbolicName == null)
						return false;
				} else if (Constants.BUNDLE_VERSION_ATTRIBUTE.equals(mandatory[i])) {
					if (bundleVersionRange == null)
						return false;
				} else if (Constants.PACKAGE_SPECIFICATION_VERSION.equals(mandatory[i]) || Constants.VERSION_ATTRIBUTE.equals(mandatory[i])) {
					if (getVersionRange() == null)
						return false;
				} else { // arbitrary attribute
					if (importAttrs == null)
						return false;
					if (importAttrs.get(mandatory[i]) == null)
						return false;
				}
			}
		}
		// finally check the ee index
		if (((BundleDescriptionImpl) getBundle()).getEquinoxEE() < 0)
			return true;
		int eeIndex = ((Integer) pkgDes.getDirective(ExportPackageDescriptionImpl.EQUINOX_EE)).intValue();
		return eeIndex < 0 || eeIndex == ((BundleDescriptionImpl) getBundle()).getEquinoxEE();
	}

	protected void setBundleSymbolicName(String symbolicName) {
		synchronized (this.monitor) {
			this.symbolicName = symbolicName;
		}
	}

	protected void setBundleVersionRange(VersionRange bundleVersionRange) {
		synchronized (this.monitor) {
			this.bundleVersionRange = bundleVersionRange;
		}
	}

	protected void setAttributes(Map attributes) {
		synchronized (this.monitor) {
			this.attributes = attributes;
		}
	}

	public String toString() {
		return "Import-Package: " + getName() + "; version=\"" + getVersionRange() + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
}
