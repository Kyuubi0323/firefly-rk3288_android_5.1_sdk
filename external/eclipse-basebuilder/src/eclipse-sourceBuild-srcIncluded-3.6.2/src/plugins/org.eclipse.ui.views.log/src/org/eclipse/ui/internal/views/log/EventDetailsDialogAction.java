/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Jacek Pospychala <jacek.pospychala@pl.ibm.com> - bugs 202583, 207344
 *******************************************************************************/
package org.eclipse.ui.internal.views.log;

import java.util.Comparator;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.actions.SelectionProviderAction;

/**
 * Opens EventDetailsDialog
 */
public class EventDetailsDialogAction extends SelectionProviderAction {

	/**
	 * The shell in which to open the property dialog
	 */
	private Shell shell;
	private ISelectionProvider provider;
	private EventDetailsDialog propertyDialog;
	private Comparator comparator;
	private IMemento memento;

	/**
	 * Creates a new action for opening a property dialog
	 * on the elements from the given selection provider
	 * @param shell - the shell in which the dialog will open
	 * @param provider - the selection provider whose elements
	 * @param memento - memento with EventDetails dialog options
	 * the property dialog will describe
	 */
	public EventDetailsDialogAction(Shell shell, ISelectionProvider provider, IMemento memento) {
		super(provider, Messages.EventDetailsDialog_title);
		Assert.isNotNull(shell);
		this.shell = shell;
		this.provider = provider;
		this.memento = memento;
		// setToolTipText
		//WorkbenchHelp.setHelp
	}

	public boolean resetSelection(byte sortType, int sortOrder) {
		IAdaptable element = (IAdaptable) getStructuredSelection().getFirstElement();
		if (element == null)
			return false;
		if (propertyDialog != null && propertyDialog.isOpen()) {
			propertyDialog.resetSelection(element, sortType, sortOrder);
			return true;
		}
		return false;
	}

	public void resetSelection() {
		IAdaptable element = (IAdaptable) getStructuredSelection().getFirstElement();
		if ((element == null) || (!(element instanceof LogEntry)))
			return;
		if (propertyDialog != null && propertyDialog.isOpen())
			propertyDialog.resetSelection(element);
	}

	public void resetDialogButtons() {
		if (propertyDialog != null && propertyDialog.isOpen())
			propertyDialog.resetButtons();
	}

	public void setComparator(Comparator comparator) {
		this.comparator = comparator;
		if (propertyDialog != null && propertyDialog.isOpen())
			propertyDialog.setComparator(comparator);
	}

	public void run() {
		if (propertyDialog != null && propertyDialog.isOpen()) {
			resetSelection();
			return;
		}

		//get initial selection
		IAdaptable element = (IAdaptable) getStructuredSelection().getFirstElement();
		if ((element == null) || (!(element instanceof LogEntry)))
			return;

		propertyDialog = new EventDetailsDialog(shell, element, provider, comparator, memento);
		propertyDialog.create();
		propertyDialog.getShell().setText(Messages.EventDetailsDialog_title);
		propertyDialog.open();
	}
}
