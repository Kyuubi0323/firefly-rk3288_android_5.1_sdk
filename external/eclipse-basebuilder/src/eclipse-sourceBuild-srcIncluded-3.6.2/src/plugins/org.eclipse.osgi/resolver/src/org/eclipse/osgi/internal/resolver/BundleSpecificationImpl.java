/*******************************************************************************
 * Copyright (c) 2003, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Rob Harrop - SpringSource Inc. (bug 247522)
 *******************************************************************************/
package org.eclipse.osgi.internal.resolver;

import org.eclipse.osgi.service.resolver.*;

public class BundleSpecificationImpl extends VersionConstraintImpl implements BundleSpecification {
	private boolean exported;
	private boolean optional;

	protected void setExported(boolean exported) {
		synchronized (this.monitor) {
			this.exported = exported;
		}
	}

	protected void setOptional(boolean optional) {
		synchronized (this.monitor) {
			this.optional = optional;
		}
	}

	public boolean isExported() {
		synchronized (this.monitor) {
			return exported;
		}
	}

	public boolean isOptional() {
		synchronized (this.monitor) {
			return optional;
		}
	}

	public boolean isSatisfiedBy(BaseDescription supplier) {
		if (!(supplier instanceof BundleDescription))
			return false;
		BundleDescription candidate = (BundleDescription) supplier;
		if (candidate.getHost() != null)
			return false;
		if (getName() != null && getName().equals(candidate.getSymbolicName()) && (getVersionRange() == null || getVersionRange().isIncluded(candidate.getVersion())))
			return true;
		return false;
	}

	public String toString() {
		return "Require-Bundle: " + getName() + "; bundle-version=\"" + getVersionRange() + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
}
