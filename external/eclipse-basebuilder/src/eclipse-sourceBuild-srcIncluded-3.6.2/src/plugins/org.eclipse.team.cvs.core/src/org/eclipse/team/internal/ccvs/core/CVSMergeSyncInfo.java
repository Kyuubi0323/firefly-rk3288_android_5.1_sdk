/*******************************************************************************
 * Copyright (c) 2000, 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ccvs.core;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.*;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.subscribers.Subscriber;
import org.eclipse.team.core.synchronize.SyncInfo;
import org.eclipse.team.core.variants.IResourceVariant;

public class CVSMergeSyncInfo extends CVSSyncInfo {

	public CVSMergeSyncInfo(IResource local, IResourceVariant base, IResourceVariant remote, Subscriber subscriber) {
		super(local, base, remote, subscriber);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.internal.ccvs.core.CVSSyncInfo#handleDeletionConflicts(int)
	 */
	protected int handleDeletionConflicts(int kind) {
		// (see bug 40053).
		if(kind == (SyncInfo.CONFLICTING | SyncInfo.DELETION | SyncInfo.PSEUDO_CONFLICT)) {
			return SyncInfo.IN_SYNC;
		}
		return kind;
	}

	protected int calculateKind() throws TeamException {
		int kind = super.calculateKind();

		// Report merged resources as in-sync
		if ((kind & DIRECTION_MASK) == INCOMING && ((CVSMergeSubscriber)getSubscriber()).isMerged(getLocal())) {
			return IN_SYNC;
		}
		
		// Report outgoing resources as in-sync when models are not shown
		if((kind & DIRECTION_MASK) == OUTGOING && !((CVSMergeSubscriber)getSubscriber()).isModelSync()) {
			return IN_SYNC;
		}
		
		return kind;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.internal.ccvs.core.CVSSyncInfo#makeOutgoing(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public IStatus makeOutgoing(IProgressMonitor monitor) throws TeamException {
		// Make the resource outgoing by marking it as merged with the subscriber
		CVSMergeSubscriber subscriber = (CVSMergeSubscriber)getSubscriber();
		subscriber.merged(new IResource[] {getLocal() });
		return Status.OK_STATUS;
	}
}
