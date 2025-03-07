/*******************************************************************************
 * Copyright (c) 2010 EclipseSource Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Chris Aniszczyk <caniszczyk@gmail.com> - initial API and implementation
 *     Ian Bull <irbull@eclipsesource.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.ui.wizards.exports;

import java.io.File;
import java.io.IOException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.pde.internal.core.target.TargetDefinition;
import org.eclipse.pde.internal.core.target.TargetPlatformService;
import org.eclipse.pde.internal.ui.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.*;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;

public class TargetDefinitionExportWizardPage extends WizardPage {

	private static final String PAGE_ID = "org.eclipse.pde.target.exportPage"; //$NON-NLS-1$
	private Button fBrowseButton = null;
	private Combo fDestinationCombo = null;
	private Button fClearDestinationButton = null;

	protected TargetDefinitionExportWizardPage() {
		super(PAGE_ID);
		setPageComplete(false);
		setTitle(PDEUIMessages.ExportActiveTargetDefinition);
		setMessage(PDEUIMessages.ExportActiveTargetDefinition_message);
	}

	public void createControl(Composite parent) {
		Composite container = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(1, false);
		container.setLayout(layout);
		createExportDirectoryControl(container);
		setControl(container);
		Dialog.applyDialogFont(container);
		PlatformUI.getWorkbench().getHelpSystem().setHelp(container, IHelpContextIds.TARGET_EXPORT_WIZARD);
	}

	private void createExportDirectoryControl(Composite parent) {
		parent.setLayout(new GridLayout(3, false));
		parent.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		new Label(parent, SWT.NONE).setText(PDEUIMessages.ExportTargetCurrentTarget);
		Label l = new Label(parent, SWT.NONE);

		try {
			// TODO this is a bit dirty
			TargetDefinition definition = ((TargetDefinition) TargetPlatformService.getDefault().getWorkspaceTargetHandle().getTargetDefinition());
			l.setText(definition.getName());
		} catch (CoreException e) {
			// TODO log something?
		}

		GridData gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.horizontalSpan = 2;
		l.setLayoutData(gd);
		new Label(parent, SWT.NONE).setText(PDEUIMessages.ExportTargetChooseFolder);

		fDestinationCombo = SWTFactory.createCombo(parent, SWT.BORDER, 1, null);
		fDestinationCombo.addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent e) {
				controlChanged();
			}
		});

		fBrowseButton = new Button(parent, SWT.PUSH);
		fBrowseButton.setText(PDEUIMessages.ExportTargetBrowse);
		fBrowseButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				DirectoryDialog dialog = new DirectoryDialog(getShell());
				dialog.setText(PDEUIMessages.ExportTargetSelectDestination);
				dialog.setMessage(PDEUIMessages.ExportTargetSpecifyDestination);
				String dir = fDestinationCombo.getText();
				dialog.setFilterPath(dir);
				dir = dialog.open();
				if (dir == null || dir.equals("")) { //$NON-NLS-1$
					return;
				}
				fDestinationCombo.setText(dir);
				controlChanged();
			}
		});

		fClearDestinationButton = new Button(parent, SWT.CHECK);
		fClearDestinationButton.setText(PDEUIMessages.ExportTargetClearDestination);
		gd = new GridData();
		gd.horizontalSpan = 2;
		gd.horizontalIndent = 15;
		fClearDestinationButton.setLayoutData(gd);
	}

	public String getDestinationDirectory() {
		return fDestinationCombo.getText();
	}

	public boolean isClearDestinationDirectory() {
		return fClearDestinationButton.getSelection();
	}

	public void controlChanged() {
		setPageComplete(validate());
	}

	protected boolean validate() {
		setMessage(null);

		if (fDestinationCombo.getText().equals("")) { //$NON-NLS-1$
			setMessage(PDEUIMessages.ExportTargetError_ChooseDestination, IStatus.ERROR);
			return false;
		} else if (!isValidLocation(fDestinationCombo.getText().trim())) {
			setMessage(PDEUIMessages.ExportTargetError_validPath, IStatus.ERROR);
			return false;
		}

		return true;
	}

	protected void initializeCombo(IDialogSettings settings, String key, Combo combo) {
		for (int i = 0; i < 6; i++) {
			String curr = settings.get(key + String.valueOf(i));
			if (curr != null && combo.indexOf(curr) == -1) {
				combo.add(curr);
			}
		}
		if (combo.getItemCount() > 0)
			combo.setText(combo.getItem(0));
	}

	protected void saveCombo(IDialogSettings settings, String key, Combo combo) {
		if (combo.getText().trim().length() > 0) {
			settings.put(key + String.valueOf(0), combo.getText().trim());
			String[] items = combo.getItems();
			int nEntries = Math.min(items.length, 5);
			for (int i = 0; i < nEntries; i++) {
				settings.put(key + String.valueOf(i + 1), items[i].trim());
			}
		}
	}

	protected boolean isValidLocation(String location) {
		try {
			String destinationPath = new File(location).getCanonicalPath();
			if (destinationPath == null || destinationPath.length() == 0)
				return false;
		} catch (IOException e) {
			return false;
		}

		return true;
	}

}
