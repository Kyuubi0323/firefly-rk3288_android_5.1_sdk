/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ccvs.ui;

import java.util.Date;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.team.internal.ccvs.core.filehistory.CVSFileRevision;
import org.eclipse.team.internal.ui.history.AbstractHistoryCategory;

public class CVSHistoryFilter extends ViewerFilter {
	public String author;
	public Date fromDate;
	public Date toDate;
	public String comment;
	public boolean isOr;
	private int matchCounter;
	
	public CVSHistoryFilter(String author, String comment, Date fromDate, Date toDate, boolean isOr) {
		this.author = author;
		this.comment = comment;
		this.fromDate = fromDate;
		this.toDate = toDate;
		this.isOr = isOr;
		this.matchCounter = 0;
	}

	/**
	 * @see ViewerFilter#select(Viewer, Object, Object)
	 */
	public boolean select(Viewer aviewer, Object parentElement, Object element) {
		if (element instanceof AbstractHistoryCategory)
			return true;
		
		if (element instanceof CVSFileRevision) {
			CVSFileRevision entry = (CVSFileRevision) element;
			if (isOr) {
				//empty fields should be considered a non-match
				boolean orSearch = (hasAuthor() && authorMatch(entry)) || (hasDate() && dateMatch(entry)) || (hasComment() && commentMatch(entry));
				if (orSearch)
					matchCounter++;
				
				return orSearch;
			} else {
				//"and" search
				//empty fields should be considered a match
				boolean andSearch = (!hasAuthor() || authorMatch(entry)) && (!hasDate() || dateMatch(entry)) && (!hasComment() || commentMatch(entry));
				if (andSearch)
					matchCounter++;
				
				return andSearch;
			}
		}
		return false;
	}

	protected boolean authorMatch(CVSFileRevision revision) {
		return revision.getAuthor().equals(author);
	}

	protected boolean commentMatch(CVSFileRevision revision) {
		return !(revision.getComment().toLowerCase().indexOf(comment.toLowerCase()) == -1);
	}

	protected boolean dateMatch(CVSFileRevision revision) {
		return isAfterFromDate(revision) && isBeforeToDate(revision);
	}

	private boolean isBeforeToDate(CVSFileRevision revision) {
		if (toDate == null)
			return true;
		return (toDate.after(new Date(revision.getTimestamp())));
	}

	private boolean isAfterFromDate(CVSFileRevision revision) {
		if (fromDate == null)
			return true;
		return (fromDate.before(new Date(revision.getTimestamp())));
	}

	protected boolean hasAuthor() {
		return !author.equals(""); //$NON-NLS-1$
	}

	protected boolean hasComment() {
		return !comment.equals(""); //$NON-NLS-1$
	}

	protected boolean hasDate() {
		return fromDate != null || toDate != null;
	}
	
	public int getMatchCount(){
		return matchCounter;
	}
}
