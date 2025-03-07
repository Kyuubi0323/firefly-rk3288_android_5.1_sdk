/*******************************************************************************
 * Copyright (c) 2005, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.help.ui.internal.views;

import org.eclipse.help.ui.internal.Messages;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.DialogTray;
import org.eclipse.jface.dialogs.IDialogPage;
import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.dialogs.TrayDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.HyperlinkGroup;
import org.eclipse.ui.forms.widgets.FormToolkit;

/**
 * The tray that appears on the side of dialogs when the user summons context
 * help or a cheat sheet follows the user into a dialog.
 */
public class HelpTray extends DialogTray implements IPageChangedListener {
	
	public static final int MINIMUM_HEIGHT = 450;
	private static final int DEFAULT_WIDTH = 210;
	
	private int originalHeight;
	private int heightAdded;
	
	private FormToolkit toolkit;
	private ReusableHelpPart helpPart;
	private Shell shell;
	private IContributionItem closeAction;
	private Image normal;
	private Image hover;
	
	/**
	 * Creates any actions needed by the tray.
	 */
	private void createActions() {
		createImages();
		closeAction = new ContributionItem() {
			public void fill(ToolBar parent, int index) {
				final ToolItem item = new ToolItem(parent, SWT.PUSH);
				item.setImage(normal);
				item.setHotImage(hover);
				item.setToolTipText(Messages.ReusableHelpPart_closeAction_tooltip);
				item.addListener(SWT.Selection, new Listener() {
					public void handleEvent(Event event) {
						// close the tray
						TrayDialog dialog = (TrayDialog)shell.getData();
						dialog.closeTray();
						
						// set focus back to shell
						shell.setFocus();
					}
				});
			}
		};
	}
	
	/**
	 * Creates the contents of the tray.
	 * 
	 * @param parent the parent composite that will contain the tray
	 */
	protected Control createContents(Composite parent) {
		// if the dialog is too short, make it taller
		ensureMinimumHeight(parent.getShell());
		
		toolkit = new FormToolkit(parent.getDisplay());
		toolkit.getHyperlinkGroup().setHyperlinkUnderlineMode(HyperlinkGroup.UNDERLINE_HOVER);
		toolkit.getColors().initializeSectionToolBarColors();
		Composite container = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.marginWidth = layout.marginHeight = 0;
		layout.verticalSpacing = 0;
		container.setLayout(layout);
		container.addListener(SWT.Dispose, new Listener() {
			public void handleEvent(Event event) {
				dispose();
			}
		});
		
		ToolBarManager tbm = new ToolBarManager(SWT.FLAT);
		tbm.createControl(container);
		GridData gd = new GridData(GridData.HORIZONTAL_ALIGN_END);
		gd.grabExcessHorizontalSpace = true;
		tbm.getControl().setLayoutData(gd);
		Label separator = new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL);
		gd = new GridData(GridData.HORIZONTAL_ALIGN_FILL);
		gd.heightHint = 1;
		separator.setLayoutData(gd);
		helpPart = new ReusableHelpPart(PlatformUI.getWorkbench().getProgressService());
		helpPart.init(null, tbm, null, null, null);
		helpPart.setDefaultContextHelpText(Messages.HelpView_defaultText);
		helpPart.createControl(container, toolkit);
		gd = new GridData(GridData.FILL_BOTH);
		gd.widthHint = DEFAULT_WIDTH;
		helpPart.getControl().setLayoutData(gd);
		
		createActions();
		tbm.add(closeAction);
		
		shell = parent.getShell();
		hookPageChangeListener(shell);
		helpPart.getControl().addListener(SWT.Dispose, new Listener() {
			public void handleEvent(Event event) {
				unhookPageChangeListener(shell);
			}
		});
		
		return container;
	}

	/**
	 * Creates any custom needed by the tray, such as the close button.
	 */
	private void createImages() {
		Display display = Display.getCurrent();
		int[] shape = new int[] { 
				3,  3, 5,  3, 7,  5, 8,  5, 10, 3, 12, 3, 
				12, 5, 10, 7, 10, 8, 12,10, 12,12,
				10,12, 8, 10, 7, 10, 5, 12, 3, 12,
				3, 10, 5,  8, 5,  7, 3,  5
		};
		
		/*
		 * Use magenta as transparency color since it is used infrequently.
		 */
		Color border = display.getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW);
		Color background = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
		Color backgroundHot = new Color(display, new RGB(252, 160, 160));
		Color transparent = display.getSystemColor(SWT.COLOR_MAGENTA);

		PaletteData palette = new PaletteData(new RGB[] { transparent.getRGB(), border.getRGB(), background.getRGB(), backgroundHot.getRGB() });
		ImageData data = new ImageData(16, 16, 8, palette);
		data.transparentPixel = 0;

		normal = new Image(display, data);
		normal.setBackground(transparent);
		GC gc = new GC(normal);
		gc.setBackground(background);
		gc.fillPolygon(shape);
		gc.setForeground(border);
		gc.drawPolygon(shape);
		gc.dispose();

		hover = new Image(display, data);
		hover.setBackground(transparent);
		gc = new GC(hover);
		gc.setBackground(backgroundHot);
		gc.fillPolygon(shape);
		gc.setForeground(border);
		gc.drawPolygon(shape);
		gc.dispose();
		
		backgroundHot.dispose();
	}

	/**
	 * Disposes any resources used by the tray.
	 */
	private void dispose() {
		normal.dispose();
		hover.dispose();
		toolkit.dispose();
		helpPart.dispose();
		
		/*
		 * Shell is about to be closed. Add a one-time-only listener
		 * that will return the dialog height back to original.
		 */
		shell.addListener(SWT.Resize, new Listener() {
			public void handleEvent(Event event) {
				shell.removeListener(SWT.Resize, this);
				Point p = shell.getSize();
				if (heightAdded > 0 && p.y > originalHeight) {
					p.y = Math.max(p.y - heightAdded, originalHeight);
					shell.setSize(p);
				}
			}
		});
	}

	/**
	 * Ensures that the dialog's height is sufficient to contain the help tray.
	 * If the dialog is too short, its height is increased. When closing the tray,
	 * the height is returned to original (see dispose()).
	 * 
	 * @param shell the dialog's shell
	 */
	private void ensureMinimumHeight(Shell shell) {
		Point p = shell.getSize();
		originalHeight = p.y;
		if (p.y < MINIMUM_HEIGHT) {
			heightAdded = MINIMUM_HEIGHT - p.y;
			p.y = MINIMUM_HEIGHT;
			shell.setSize(p);
		}
		else {
			heightAdded = 0;
		}
	}
	
	/**
	 * Returns the ReusableHelpPart contained in the tray.
	 * 
	 * @return the tray's ReusableHelpPart
	 */
	public ReusableHelpPart getHelpPart() {
		return helpPart;
	}
	
	/**
	 * Add the listener that gets notified of page changes (to automatically
	 * update context help).
	 * 
	 * @param parent the Composite to hook the listener to
	 */
	private void hookPageChangeListener(Composite parent) {
		Object data = parent.getData();
		if (data instanceof IPageChangeProvider) {
			((IPageChangeProvider)data).addPageChangedListener(this);
		}
	}

	/**
	 * Returns whether or not the help tray can handle the given shell. In some
	 * cases the help tray is not appropriate for shells that are too short and
	 * not resizable. In these cases infopops are used.
	 * 
	 * @param shell the shell to check
	 * @return whether or not the help tray is appropriate for the hsell
	 */
	public static boolean isAppropriateFor(Shell shell) {
		if (shell != null && !shell.isDisposed() && shell.isVisible()) {
			Object data = shell.getData();
			return (data instanceof TrayDialog && (shell.getSize().y >= MINIMUM_HEIGHT || (shell.getStyle() & SWT.RESIZE) != 0));
		}
		return false;
	}

	/**
	 * Called whenever the dialog we're inside has changed pages. This updates
	 * the context help page if it is visible.
	 * 
	 * @param event the page change event
	 */
	public void pageChanged(PageChangedEvent event) {
		Object page = event.getSelectedPage();
		Control c = null;
		if (page instanceof IDialogPage) {
			c = ((IDialogPage) page).getControl();
		} else {
			c = shell.getDisplay().getFocusControl();
			if (c instanceof TabFolder) {
				TabFolder folder = (TabFolder) c;
				TabItem[] selection = folder.getSelection();
				if (selection.length == 1) {
					c = selection[0].getControl();
				}
			}
		}
		helpPart.update(null, null, null, c, false);
	}
	
	/**
	 * Remove the listener that gets notified of page changes (to automatically
	 * update context help).
	 * 
	 * @param parent the Composite that had the listener
	 */
	private void unhookPageChangeListener(Composite parent) {
		Object data = parent.getData();
		if (data instanceof IPageChangeProvider) {
			((IPageChangeProvider)data).removePageChangedListener(this);
		}
	}
}
