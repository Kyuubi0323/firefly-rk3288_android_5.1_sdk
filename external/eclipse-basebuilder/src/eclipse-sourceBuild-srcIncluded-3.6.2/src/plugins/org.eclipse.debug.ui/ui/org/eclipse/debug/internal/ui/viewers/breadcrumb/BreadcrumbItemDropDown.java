/*******************************************************************************
 * Copyright (c) 2008, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Pawel Piech (Wind River) - adapted breadcrumb for use in Debug view (Bug 252677)
 *******************************************************************************/
package org.eclipse.debug.internal.ui.viewers.breadcrumb;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.resource.CompositeImageDescriptor;
import org.eclipse.jface.util.Geometry;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.events.ShellListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.Widget;


/**
 * The part of the breadcrumb item with the drop down menu.
 *
 * @since 3.5
 */
class BreadcrumbItemDropDown implements IBreadcrumbDropDownSite {

	/**
	 * Tells whether this class is in debug mode.
	 */
	private static boolean DEBUG= DebugUIPlugin.DEBUG && "true".equalsIgnoreCase(Platform.getDebugOption("org.eclipse.debug.ui/debug/breadcrumb")); //$NON-NLS-1$//$NON-NLS-2$

	private static final boolean IS_MAC_WORKAROUND= "carbon".equals(SWT.getPlatform()); //$NON-NLS-1$

	/**
	 * An arrow image descriptor. The images color is related to the list
	 * fore- and background color. This makes the arrow visible even in high contrast
	 * mode. If <code>ltr</code> is true the arrow points to the right, otherwise it
	 * points to the left.
	 */
	private final class AccessibelArrowImage extends CompositeImageDescriptor {

		private final static int ARROW_SIZE= 5;

		private final boolean fLTR;

		public AccessibelArrowImage(boolean ltr) {
			fLTR= ltr;
		}

		/*
		 * @see org.eclipse.jface.resource.CompositeImageDescriptor#drawCompositeImage(int, int)
		 */
		protected void drawCompositeImage(int width, int height) {
			Display display= fParentComposite.getDisplay();

			Image image= new Image(display, ARROW_SIZE, ARROW_SIZE * 2);

			GC gc= new GC(image);

			Color triangle= createColor(SWT.COLOR_LIST_FOREGROUND, SWT.COLOR_LIST_BACKGROUND, 20, display);
			Color aliasing= createColor(SWT.COLOR_LIST_FOREGROUND, SWT.COLOR_LIST_BACKGROUND, 30, display);
			gc.setBackground(triangle);

			if (fLTR) {
				gc.fillPolygon(new int[] { mirror(0), 0, mirror(ARROW_SIZE), ARROW_SIZE, mirror(0), ARROW_SIZE * 2 });
			} else {
				gc.fillPolygon(new int[] { ARROW_SIZE, 0, 0, ARROW_SIZE, ARROW_SIZE, ARROW_SIZE * 2 });
			}

			gc.setForeground(aliasing);
			gc.drawLine(mirror(0), 1, mirror(ARROW_SIZE - 1), ARROW_SIZE);
			gc.drawLine(mirror(ARROW_SIZE - 1), ARROW_SIZE, mirror(0), ARROW_SIZE * 2 - 1);

			gc.dispose();
			triangle.dispose();
			aliasing.dispose();

			ImageData imageData= image.getImageData();
			for (int y= 1; y < ARROW_SIZE; y++) {
				for (int x= 0; x < y; x++) {
					imageData.setAlpha(mirror(x), y, 255);
				}
			}
			for (int y= 0; y < ARROW_SIZE; y++) {
				for (int x= 0; x <= y; x++) {
					imageData.setAlpha(mirror(x), ARROW_SIZE * 2 - y - 1, 255);
				}
			}

			int offset= fLTR ? 0 : -1;
			drawImage(imageData, (width / 2) - (ARROW_SIZE / 2) + offset, (height / 2) - ARROW_SIZE - 1);

			image.dispose();
		}

		private int mirror(int x) {
			if (fLTR)
				return x;

			return ARROW_SIZE - x - 1;
		}

		/*
		 * @see org.eclipse.jface.resource.CompositeImageDescriptor#getSize()
		 */
		protected Point getSize() {
			return new Point(10, 16);
		}

		private Color createColor(int color1, int color2, int ratio, Display display) {
			RGB rgb1= display.getSystemColor(color1).getRGB();
			RGB rgb2= display.getSystemColor(color2).getRGB();

			RGB blend= BreadcrumbViewer.blend(rgb2, rgb1, ratio);

			return new Color(display, blend);
		}
	}

	private static final int DROP_DOWN_HIGHT= 300;
	private static final int DROP_DOWN_WIDTH= 500;

	private final BreadcrumbItem fParent;
	private final Composite fParentComposite;
	private final ToolBar fToolBar;

	private boolean fMenuIsShown;
	private boolean fEnabled;
	private Shell fShell;

	public BreadcrumbItemDropDown(BreadcrumbItem parent, Composite composite) {
		fParent= parent;
		fParentComposite= composite;
		fMenuIsShown= false;
		fEnabled= true;

		fToolBar= new ToolBar(composite, SWT.FLAT);
		fToolBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
		fToolBar.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            public void getName(AccessibleEvent e) {
                e.result= BreadcrumbMessages.BreadcrumbItemDropDown_showDropDownMenu_action_toolTip;
            }
        });
		ToolBarManager manager= new ToolBarManager(fToolBar);

		final Action showDropDownMenuAction= new Action(null, SWT.NONE) {
			public void run() {
				Shell shell= fParent.getDropDownShell();
				if (shell != null)
					return;

				shell= fParent.getViewer().getDropDownShell();
				if (shell != null && !shell.isDisposed())
					shell.close();

				showMenu();

				fShell.setFocus();
			}
		};

		showDropDownMenuAction.setImageDescriptor(new AccessibelArrowImage(isLeft()));
		showDropDownMenuAction.setToolTipText(BreadcrumbMessages.BreadcrumbItemDropDown_showDropDownMenu_action_toolTip);
		manager.add(showDropDownMenuAction);

		manager.update(true);
		if (IS_MAC_WORKAROUND) {
			manager.getControl().addMouseListener(new MouseAdapter() {
				// see also BreadcrumbItemDetails#addElementListener(Control)
				public void mouseDown(MouseEvent e) {
					showDropDownMenuAction.run();
				}
			});
		}
	}

	/**
	 * Return the width of this element.
	 *
	 * @return the width of this element
	 */
	public int getWidth() {
		return fToolBar.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
	}

	/**
	 * Set whether the drop down menu is available.
	 *
	 * @param enabled true if available
	 */
	public void setEnabled(boolean enabled) {
		fEnabled= enabled;

		fToolBar.setVisible(enabled);
	}

	/**
	 * Tells whether the menu is shown.
	 *
	 * @return true if the menu is open
	 */
	public boolean isMenuShown() {
		return fMenuIsShown;
	}

	/**
	 * Returns the shell used for the drop down menu if it is shown.
	 *
	 * @return the drop down shell or <code>null</code>
	 */
	public Shell getDropDownShell() {
		if (!isMenuShown())
			return null;

		return fShell;
	}

	/**
	 * Opens the drop down menu.
	 */
	public void showMenu() {
		if (DEBUG)
			System.out.println("BreadcrumbItemDropDown.showMenu()"); //$NON-NLS-1$

		if (!fEnabled || fMenuIsShown)
			return;

		fMenuIsShown= true;

		fShell= new Shell(fToolBar.getShell(), SWT.RESIZE | SWT.TOOL | SWT.ON_TOP);
		if (DEBUG)
			System.out.println("	creating new shell"); //$NON-NLS-1$

		GridLayout layout= new GridLayout(1, false);
		layout.marginHeight= 0;
		layout.marginWidth= 0;
		fShell.setLayout(layout);

		Composite composite= new Composite(fShell, SWT.NONE);
		composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		GridLayout gridLayout= new GridLayout(1, false);
		gridLayout.marginHeight= 0;
		gridLayout.marginWidth= 0;
		composite.setLayout(gridLayout);

        TreePath path= fParent.getPath();

		Control control = fParent.getViewer().createDropDown(composite, this, path);
		
		control.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		setShellBounds(fShell);
		fShell.setVisible(true);
		installCloser(fShell);
	}

	/**
	 * The closer closes the given shell when the focus is lost.
	 *
	 * @param shell the shell to install the closer to
	 */
	private void installCloser(final Shell shell) {
		final Listener focusListener= new Listener() {
			public void handleEvent(Event event) {
				Widget focusElement= event.widget;
				boolean isFocusBreadcrumbTreeFocusWidget= focusElement == shell || focusElement instanceof Control && ((Control)focusElement).getShell() == shell;
				boolean isFocusWidgetParentShell= focusElement instanceof Control && ((Control)focusElement).getShell().getParent() == shell;

				switch (event.type) {
					case SWT.FocusIn:
						if (DEBUG)
							System.out.println("focusIn - is breadcrumb tree: " + isFocusBreadcrumbTreeFocusWidget); //$NON-NLS-1$

						if (!isFocusBreadcrumbTreeFocusWidget && !isFocusWidgetParentShell) {
							if (DEBUG)
								System.out.println("==> closing shell since focus in other widget"); //$NON-NLS-1$
							shell.close();
						}
						break;

					case SWT.FocusOut:
						if (DEBUG)
							System.out.println("focusOut - is breadcrumb tree: " + isFocusBreadcrumbTreeFocusWidget); //$NON-NLS-1$

						if (event.display.getActiveShell() == null) {
							if (DEBUG)
								System.out.println("==> closing shell since event.display.getActiveShell() != shell"); //$NON-NLS-1$
							shell.close();
						}
						break;

					default:
						Assert.isTrue(false);
				}
			}
		};

		final Display display= shell.getDisplay();
		display.addFilter(SWT.FocusIn, focusListener);
		display.addFilter(SWT.FocusOut, focusListener);

		final ControlListener controlListener= new ControlListener() {
			public void controlMoved(ControlEvent e) {
			    if (!shell.isDisposed()) {
			        shell.close();
			    }
			}

			public void controlResized(ControlEvent e) {
                if (!shell.isDisposed()) {
                    shell.close();
                }
			}
		};
		fToolBar.getShell().addControlListener(controlListener);

		shell.addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				if (DEBUG)
					System.out.println("==> shell disposed"); //$NON-NLS-1$

				display.removeFilter(SWT.FocusIn, focusListener);
				display.removeFilter(SWT.FocusOut, focusListener);

				if (!fToolBar.isDisposed()) {
					fToolBar.getShell().removeControlListener(controlListener);
				}
			}
		});
		shell.addShellListener(new ShellListener() {
			public void shellActivated(ShellEvent e) {
			}

			public void shellClosed(ShellEvent e) {
				if (DEBUG)
					System.out.println("==> shellClosed"); //$NON-NLS-1$

				if (!fMenuIsShown)
					return;

				fMenuIsShown= false;
			}

			public void shellDeactivated(ShellEvent e) {
			}

			public void shellDeiconified(ShellEvent e) {
			}

			public void shellIconified(ShellEvent e) {
			}
		});
	}

	/**
	 * Calculates a useful size for the given shell.
	 *
	 * @param shell the shell to calculate the size for.
	 */
	private void setShellBounds(Shell shell) {
	    
		Rectangle rect= fParentComposite.getBounds();
		Rectangle toolbarBounds= fToolBar.getBounds();

		Point size = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, false);
		int height= Math.min(size.y, DROP_DOWN_HIGHT);
		// TODO: Because of bug 258196 the drop down does not resize correctly 
		// on GTK.  As a workaround temporarily increase the initial width of 
		// the drop down to 500.
		//int width= Math.max(Math.min(size.x, DROP_DOWN_WIDTH), 250);
        int width= Math.max(Math.min(size.x, DROP_DOWN_WIDTH), 500);

		int imageBoundsX= 0;
		if (fParent.getImage() != null) {
			imageBoundsX= fParent.getImage().getImageData().width;
		}
		
		Rectangle trim= fShell.computeTrim(0, 0, width, height);
		int x= toolbarBounds.x + toolbarBounds.width + 2 + trim.x - imageBoundsX;
		if (!isLeft())
			x+= width;
		
		int y = rect.y;
		if (isTop()) 
		    y+= rect.height;
		else 
		    y-= height;

		Point pt= new Point(x, y);
		pt= fParentComposite.toDisplay(pt);

		Rectangle monitor= getClosestMonitor(shell.getDisplay(), pt).getClientArea();
		int overlap= (pt.x + width) - (monitor.x + monitor.width);
		if (overlap > 0)
			pt.x-= overlap;
		if (pt.x < monitor.x)
			pt.x= monitor.x;

		shell.setLocation(pt);
		shell.setSize(width, height);
	}

	/**
	 * Returns the monitor whose client area contains the given point. If no monitor contains the
	 * point, returns the monitor that is closest to the point.
	 * <p>
	 * Copied from <code>org.eclipse.jface.window.Window.getClosestMonitor(Display, Point)</code>
	 * </p>
	 *
	 * @param display the display showing the monitors
	 * @param point point to find (display coordinates)
	 * @return the monitor closest to the given point
	 */
	private static Monitor getClosestMonitor(Display display, Point point) {
		int closest= Integer.MAX_VALUE;

		Monitor[] monitors= display.getMonitors();
		Monitor result= monitors[0];

		for (int i= 0; i < monitors.length; i++) {
			Monitor current= monitors[i];

			Rectangle clientArea= current.getClientArea();

			if (clientArea.contains(point))
				return current;

			int distance= Geometry.distanceSquared(Geometry.centerPoint(clientArea), point);
			if (distance < closest) {
				closest= distance;
				result= current;
			}
		}

		return result;
	}

	/**
	 * Set the size of the given shell such that more content can be shown. The shell size does not
	 * exceed {@link #DROP_DOWN_HIGHT} and {@link #DROP_DOWN_WIDTH}.
	 *
	 * @param shell the shell to resize
	 */
	private void resizeShell(final Shell shell) {
		Point size= shell.getSize();
		int currentWidth= size.x;
		int currentHeight= size.y;

		if (currentHeight >= DROP_DOWN_HIGHT && currentWidth >= DROP_DOWN_WIDTH)
			return;

		Point preferedSize= shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);

		int newWidth;
		if (currentWidth >= DROP_DOWN_WIDTH) {
			newWidth= currentWidth;
		} else {
			newWidth= Math.min(Math.max(preferedSize.x, currentWidth), DROP_DOWN_WIDTH);
		}
		int newHeight;
		if (currentHeight >= DROP_DOWN_HIGHT) {
			newHeight= currentHeight;
		} else {
			newHeight= Math.min(Math.max(preferedSize.y, currentHeight), DROP_DOWN_HIGHT);
		}

		if (newHeight != currentHeight || newWidth != currentWidth) {
			shell.setRedraw(false);
			try {
				shell.setSize(newWidth, newHeight);
				
				Point location = shell.getLocation();
				Point newLocation = location;
				if (!isLeft()) {
					newLocation = new Point(newLocation.x - (newWidth - currentWidth), newLocation.y);
				}
				if (!isTop()) {
                    newLocation = new Point(newLocation.x, newLocation.y - (newHeight - currentHeight));
				}				    
				if (!location.equals(newLocation)) {
	                shell.setLocation(newLocation.x, newLocation.y);
				}
			} finally {
				shell.setRedraw(true);
			}
		}
	}

	/**
	 * Tells whether this the breadcrumb is in LTR mode or RTL mode.  Or whether the breadcrumb
	 * is on the right-side status coolbar, which has the same effect on layout.
	 *
	 * @return <code>true</code> if the breadcrumb in left-to-right mode, <code>false</code>
	 *         otherwise
	 */
	private boolean isLeft() {
		return (fParentComposite.getStyle() & SWT.RIGHT_TO_LEFT) == 0 &&
		    (fParent.getViewer().getStyle() & SWT.RIGHT) == 0;
	}
	
	   /**
     * Tells whether this the breadcrumb is in LTR mode or RTL mode.  Or whether the breadcrumb
     * is on the right-side status coolbar, which has the same effect on layout.
     *
     * @return <code>true</code> if the breadcrumb in left-to-right mode, <code>false</code>
     *         otherwise
     */
    private boolean isTop() {
        return (fParent.getViewer().getStyle() & SWT.BOTTOM) == 0;
    }

    public void close() {
        if (fShell != null && !fShell.isDisposed()) {
            fShell.close();
        }
    }
    
    public void notifySelection(ISelection selection) {
        fParent.getViewer().fireMenuSelection(selection);        
    }
    
    public void updateSize() {
        if (fShell != null && !fShell.isDisposed()) {
            resizeShell(fShell);
        }
    }
}

