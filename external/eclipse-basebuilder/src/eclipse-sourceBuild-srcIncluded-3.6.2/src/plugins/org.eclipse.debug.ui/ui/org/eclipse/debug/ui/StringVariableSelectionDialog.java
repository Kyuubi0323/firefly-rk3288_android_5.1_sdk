/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 * Johann Draschwandtner (Wind River) - [300988] Support filtering variables
 *******************************************************************************/
package org.eclipse.debug.ui;

import java.util.ArrayList;
import java.util.Arrays;

import org.eclipse.core.variables.IDynamicVariable;
import org.eclipse.core.variables.IStringVariable;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.internal.core.IInternalDebugCoreConstants;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.IDebugHelpContextIds;
import org.eclipse.debug.internal.ui.SWTFactory;
import org.eclipse.debug.internal.ui.stringsubstitution.IArgumentSelector;
import org.eclipse.debug.internal.ui.stringsubstitution.StringSubstitutionMessages;
import org.eclipse.debug.internal.ui.stringsubstitution.StringVariableLabelProvider;
import org.eclipse.debug.internal.ui.stringsubstitution.StringVariablePresentationManager;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

/**
 * A dialog that prompts the user to choose and configure a string
 * substitution variable.
 * <p>
 * Clients may instantiate this class.
 * </p>
 * @since 3.1
 * @noextend This class is not intended to be subclassed by clients.
 */
public class StringVariableSelectionDialog extends ElementListSelectionDialog {
	
	// button to configure variable's argument
	private Button fArgumentButton;
	// variable description
	private Text fDescriptionText;
	// the argument value
	private Text fArgumentText;
	private String fArgumentValue;
	private Button fEditVariablesButton;
	private Button fShowAllButton;
	private Label fShowAllDescription;

	/** 
	 * Base class for custom variable filters. Clients may extend this class
	 * to filter specific dynamic variables from the selection dialog. 
	 * 
	 * @since 3.6
	 */
	public static class VariableFilter {
		/**
		 * Returns whether the given variable should be filtered.
		 *  
		 * @param var variable to be consider
		 * @return <code>true</code> to filter the variable, otherwise <code>false</code>
		 */
		public boolean isFiltered(IDynamicVariable var) {
			return false;
		}
	}

	//no filtering by default
	private ArrayList fFilters = new ArrayList();
	//when filtering is on, do not show all by default
	private boolean fShowAllSelected = false;

	/**
	 * Constructs a new string substitution variable selection dialog.
	 *  
	 * @param parent parent shell
	 */
	public StringVariableSelectionDialog(Shell parent) {
		super(parent, new StringVariableLabelProvider());
		setShellStyle(getShellStyle() | SWT.RESIZE);
		setTitle(StringSubstitutionMessages.StringVariableSelectionDialog_2); 
		setMessage(StringSubstitutionMessages.StringVariableSelectionDialog_3); 
		setMultipleSelection(false);
		setElements(VariablesPlugin.getDefault().getStringVariableManager().getVariables());
	}
	
	/**
	 * Returns the variable expression the user generated from this
	 * dialog, or <code>null</code> if none.
	 *  
	 * @return variable expression the user generated from this
	 * dialog, or <code>null</code> if none
	 */
	public String getVariableExpression() {
		Object[] selected = getResult();
		if (selected != null && selected.length == 1) {
			IStringVariable variable = (IStringVariable)selected[0];
			StringBuffer buffer = new StringBuffer();
			buffer.append("${"); //$NON-NLS-1$
			buffer.append(variable.getName());
			if (fArgumentValue != null && fArgumentValue.length() > 0) {
				buffer.append(":"); //$NON-NLS-1$
				buffer.append(fArgumentValue);
			}
			buffer.append("}"); //$NON-NLS-1$
			return buffer.toString();
		}
		return null;
	}

	/** 
	 *  Add the given variable filter. Has no effect if the given filter has
	 *  already been added. Must be called before the dialog is opened.
	 *  
	 *  @param filter the filter to add
	 * @since 3.6
	 */
	public void addVariableFilter(VariableFilter filter) {
		if(!fFilters.contains(filter)) {
			fFilters.add(filter);
		}
	}

	/**
	 * Sets the filters, replacing any previous filters.
	 *  Must be called before the dialog is opened.
	 * 
	 * @param filters
	 *            an array of variable filters, use empty Array or <code>null</code> to reset all Filters.
	 * @since 3.6
	 */
	public void setFilters(VariableFilter[] filters) {
		fFilters.clear();
		if(filters != null && filters.length > 0) {
			fFilters.addAll(Arrays.asList(filters));
		}
	}

	private void updateElements() {
		final Display display = DebugUIPlugin.getStandardDisplay();
		BusyIndicator.showWhile(display, new Runnable() {
			public void run() {
				final IStringVariable[] elements = VariablesPlugin.getDefault().getStringVariableManager().getVariables();
				display.asyncExec(new Runnable() {
					public void run() {
						setListElements(elements);
					}
				});
			}
		});		
	}
	
	private void updateDescription() {
		if((fShowAllDescription != null) && !fShowAllDescription.isDisposed()) {
			if(fShowAllSelected) {
				fShowAllDescription.setText(StringSubstitutionMessages.StringVariableSelectionDialog_11);
			} else {
				fShowAllDescription.setText(StringSubstitutionMessages.StringVariableSelectionDialog_10);
			}
		}
	}

	protected void setListElements(Object[] elements) {
		ArrayList filtered = new ArrayList();
		filtered.addAll(Arrays.asList(elements));
		if(!fFilters.isEmpty() && !fShowAllSelected) {
			for (int i = 0; i < elements.length; i++) {
				if(elements[i] instanceof IDynamicVariable) {
					boolean bFiltered = false;
					for (int j = 0; (j < fFilters.size()) && !bFiltered; j++) {
						VariableFilter filter = (VariableFilter)fFilters.get(j);
						if(filter.isFiltered((IDynamicVariable)elements[i])) {
							filtered.remove(elements[i]);
							bFiltered = true;
						}
					}
				}
			}
		}
		super.setListElements(filtered.toArray());
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.Dialog#createContents(org.eclipse.swt.widgets.Composite)
	 */
	protected Control createContents(Composite parent) {
		Control ctrl = super.createContents(parent);
		PlatformUI.getWorkbench().getHelpSystem().setHelp(ctrl, IDebugHelpContextIds.VARIABLE_SELECTION_DIALOG);
		return ctrl;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.Dialog#createDialogArea(org.eclipse.swt.widgets.Composite)
	 */
	protected Control createDialogArea(Composite parent) {
		Control control = super.createDialogArea(parent);
		createArgumentArea((Composite)control);
		return control;
	}

	/**
	 * Creates an area to display a description of the selected variable
	 * and a button to configure the variable's argument.
	 * 
	 * @param parent parent widget
	 */
	private void createArgumentArea(Composite parent) {
		Composite container = SWTFactory.createComposite(parent, parent.getFont(), 2, 1, GridData.FILL_HORIZONTAL, 0, 0);
		
		Composite btnContainer = SWTFactory.createComposite(container, parent.getFont(), 3, 2, GridData.FILL_HORIZONTAL, 0, 0);
		boolean bNeedShowAll = false;
		if(!fFilters.isEmpty()) {
			Object[] elements = VariablesPlugin.getDefault().getStringVariableManager().getVariables();
			for (int i = 0;(i < elements.length) && !bNeedShowAll; i++) {
				if(elements[i] instanceof IDynamicVariable) {
					for (int j = 0; (j < fFilters.size()) && !bNeedShowAll; j++) {
						VariableFilter filter = (VariableFilter)fFilters.get(j);
						if(filter.isFiltered((IDynamicVariable)elements[i])) {
							bNeedShowAll = true;
						}
					}
				}
			}
		}
		if (bNeedShowAll) {
			fShowAllDescription = SWTFactory.createLabel(btnContainer, "", 3); //$NON-NLS-1$
			updateDescription();
			fShowAllButton = SWTFactory.createCheckButton(btnContainer, StringSubstitutionMessages.StringVariableSelectionDialog_9, null, fShowAllSelected, 1);
			fShowAllButton.addSelectionListener(new SelectionAdapter() {
				public void widgetSelected(SelectionEvent e) {
					fShowAllSelected = fShowAllButton.getSelection();
					updateDescription();
					updateElements();
				}

			});
			SWTFactory.createHorizontalSpacer(btnContainer, 1);
		} else {
			SWTFactory.createHorizontalSpacer(btnContainer, 2);
		}
		
		fEditVariablesButton = SWTFactory.createPushButton(btnContainer, StringSubstitutionMessages.StringVariableSelectionDialog_0, null, GridData.HORIZONTAL_ALIGN_END);
		fEditVariablesButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				editVariables();
			}
		});	
		
		SWTFactory.createWrapLabel(container, StringSubstitutionMessages.StringVariableSelectionDialog_6, 2);
		
		Composite args = SWTFactory.createComposite(container, container.getFont(), 2, 2, GridData.FILL_HORIZONTAL, 0, 0);

		fArgumentText = new Text(args, SWT.BORDER);
		fArgumentText.setFont(container.getFont());
		fArgumentText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));		
		
		fArgumentButton = SWTFactory.createPushButton(args, StringSubstitutionMessages.StringVariableSelectionDialog_7, null);
		fArgumentButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				configureArgument();
			}
		});

		SWTFactory.createWrapLabel(container, StringSubstitutionMessages.StringVariableSelectionDialog_8, 2);
		
		fDescriptionText = new Text(container, SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
		fDescriptionText.setFont(container.getFont());
		fDescriptionText.setEditable(false);
		GridData gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.horizontalSpan = 2;
		gd.heightHint = 50;
		fDescriptionText.setLayoutData(gd);
	}

	/**
	 * Opens the preference dialog to the correct page an allows editing of variables
	 */
	protected void editVariables() {
		final Display display = DebugUIPlugin.getStandardDisplay();
		BusyIndicator.showWhile(display, new Runnable() {
			public void run() {
				SWTFactory.showPreferencePage("org.eclipse.debug.ui.StringVariablePreferencePage"); //$NON-NLS-1$
				final IStringVariable[] elements = VariablesPlugin.getDefault().getStringVariableManager().getVariables();
				display.asyncExec(new Runnable() {
					public void run() {
						setListElements(elements);
					}
				});
			}
		});		
	}

	/**
	 * Configures the argument for the selected variable.
	 */
	protected void configureArgument() {
		Object[] objects = getSelectedElements();
		IStringVariable variable = (IStringVariable)objects[0];
		IArgumentSelector selector = StringVariablePresentationManager.getDefault().getArgumentSelector(variable);
		String value = selector.selectArgument(variable, getShell());
		if (value != null) {
			fArgumentText.setText(value);
		}
	}

	/**
	 * Update variable description and argument button enablement.
	 * 
	 * @see org.eclipse.ui.dialogs.AbstractElementListSelectionDialog#handleSelectionChanged()
	 */
	protected void handleSelectionChanged() {
		super.handleSelectionChanged();
		Object[] objects = getSelectedElements();
		boolean buttonEnabled = false;
		boolean argEnabled = false;
		String text = null;
		if (objects.length == 1) {
			IStringVariable variable = (IStringVariable)objects[0];
			 IArgumentSelector selector = StringVariablePresentationManager.getDefault().getArgumentSelector(variable);
			 if (variable instanceof IDynamicVariable) {
			 	argEnabled = ((IDynamicVariable)variable).supportsArgument();
			 }
			 buttonEnabled = argEnabled && selector != null;
			 text = variable.getDescription();
		}
		if (text == null) {
			text = IInternalDebugCoreConstants.EMPTY_STRING;
		}
		fArgumentText.setEnabled(argEnabled);
		fArgumentButton.setEnabled(buttonEnabled);
		fDescriptionText.setText(text);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.Dialog#okPressed()
	 */
	protected void okPressed() {
		fArgumentValue = fArgumentText.getText().trim();
		super.okPressed();
	}

	/**
	 * Returns the name of the section that this dialog stores its settings in
	 * 
	 * @return String
	 */
	private String getDialogSettingsSectionName() {
		return IDebugUIConstants.PLUGIN_ID + ".STRING_VARIABLE_SELECTION_DIALOG_SECTION"; //$NON-NLS-1$
	}
	
	 /* (non-Javadoc)
     * @see org.eclipse.jface.dialogs.Dialog#getDialogBoundsSettings()
     */
    protected IDialogSettings getDialogBoundsSettings() {
    	 IDialogSettings settings = DebugUIPlugin.getDefault().getDialogSettings();
         IDialogSettings section = settings.getSection(getDialogSettingsSectionName());
         if (section == null) {
             section = settings.addNewSection(getDialogSettingsSectionName());
         } 
         return section;
    }
}
