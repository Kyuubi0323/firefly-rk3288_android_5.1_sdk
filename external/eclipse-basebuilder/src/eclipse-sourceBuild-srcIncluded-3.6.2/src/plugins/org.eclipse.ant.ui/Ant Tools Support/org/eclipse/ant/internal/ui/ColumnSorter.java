/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ant.internal.ui;

import org.eclipse.ant.internal.ui.preferences.AntObjectLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.TableColumn;

/**
 * A sorter that can be attached to a given column in a given viewer.
 * This comparator uses the {@link AntObjectLabelProvider} to get the text
 * to compare
 * 
 * @since 3.5
 */
public abstract class ColumnSorter extends ViewerComparator {
	
	private ColumnViewer cviewer = null;
	private TableColumn column = null;
	private int direction = SWT.DOWN;
	private int columnidx = 0;
	
	/**
	 * Constructor
	 * @param cviewer
	 * @param column
	 */
	public ColumnSorter(ColumnViewer cviewer, TableColumn column) {
		this.cviewer = cviewer;
		this.column = column;
		this.columnidx = getColumnIndex();
		this.column.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				if(ColumnSorter.this.cviewer.getComparator() != ColumnSorter.this) {
					setDirection(SWT.DOWN);
				}
				else {
					int tdirection = ColumnSorter.this.column.getParent().getSortDirection();
					if(tdirection == SWT.NONE) {
						setDirection(SWT.DOWN);
					}
					else {
						setDirection(tdirection == SWT.UP ? SWT.DOWN : SWT.UP);
					}
				}
			}
		});

	}
	
	/**
	 * Returns the compare text that should be used for the given object coming from 
	 * the given column index
	 * @param obj
	 * @param columnindex
	 * @return the text to compare with
	 */
	public abstract String getCompareText(Object obj, int columnindex);
	
	/**
	 * Sets the sorting direction for this sorter to use
	 * @param direction
	 */
	public void setDirection(int direction) {
		this.column.getParent().setSortColumn(this.column);
		this.direction = direction;
		this.column.getParent().setSortDirection(this.direction);
		if(this.cviewer.getComparator() == this) {
			this.cviewer.refresh();
		}
		else {
			this.cviewer.setComparator(this);
		}
	}
	
	/**
	 * @see org.eclipse.jface.viewers.ViewerComparator#compare(org.eclipse.jface.viewers.Viewer, java.lang.Object, java.lang.Object)
	 */
	public int compare(Viewer viewer, Object e1, Object e2) {
		String text1 = getCompareText(e1, this.columnidx);
		if(text1 == null) {
			text1 = ""; //$NON-NLS-1$
		}
		String text2 = getCompareText(e2, this.columnidx);
		if(text2 == null) {
			text2 = ""; //$NON-NLS-1$
		}
		return (this.direction == SWT.UP ? -1 : 1) * 
			text1.compareTo(text2);
	}
	
	/**
	 * Returns the index of the given column in the backing table for this page, or 0
	 * 
	 * @return the index of the column in the backing table for this page or 0
	 */
	private int getColumnIndex() {
		int idx = this.column.getParent().indexOf(this.column);
		return (idx < 0 ? 0 : idx);
	}
}
