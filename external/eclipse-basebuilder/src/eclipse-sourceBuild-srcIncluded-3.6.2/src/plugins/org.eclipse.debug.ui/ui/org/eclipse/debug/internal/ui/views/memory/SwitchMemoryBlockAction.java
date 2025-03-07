/*******************************************************************************
 * Copyright (c) 2005, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     WindRiver - Bug 192028 [Memory View] Memory view does not 
 *                 display memory blocks that do not reference IDebugTarget
 *******************************************************************************/

package org.eclipse.debug.internal.ui.views.memory;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IMemoryBlockListener;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IMemoryBlockExtension;
import org.eclipse.debug.core.model.IMemoryBlockRetrieval;
import org.eclipse.debug.internal.core.IInternalDebugCoreConstants;
import org.eclipse.debug.internal.ui.DebugUIMessages;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.contexts.DebugContextEvent;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.debug.ui.memory.IMemoryRendering;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IActionDelegate2;
import org.eclipse.ui.IViewActionDelegate;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.progress.UIJob;
import org.eclipse.ui.progress.WorkbenchJob;

/**
 * The switch memory block action, used 
 */
public class SwitchMemoryBlockAction extends Action implements IViewActionDelegate, IActionDelegate2 {

	/**
	 * A job that updates the enablement of the of the backing action delegate in the UI thread
	 * 
	 * @since 3.3.0
	 */
	class UpdateActionEnablementJob extends UIJob {

		/**
		 * Constructor
		 */
		public UpdateActionEnablementJob() {
			super("Update Action Enablement"); //$NON-NLS-1$
			setSystem(true);
		}

		/* (non-Javadoc)
		 * @see org.eclipse.ui.progress.UIJob#runInUIThread(org.eclipse.core.runtime.IProgressMonitor)
		 */
		public IStatus runInUIThread(IProgressMonitor monitor) {
			if (fAction != null) {
				IAdaptable context = DebugUITools.getDebugContext();
				if (context != null) {
					IMemoryBlockRetrieval retrieval = MemoryViewUtil.getMemoryBlockRetrieval(context);
					
					if (retrieval != null) {
						IMemoryBlock[] memoryBlocks = DebugPlugin.getDefault().getMemoryBlockManager().getMemoryBlocks(retrieval);
						fAction.setEnabled(memoryBlocks.length > 0);
						return Status.OK_STATUS;
					}
					else if (getViewer() != null) {
						Object input = getViewer().getInput();
						if (input instanceof IMemoryBlockRetrieval) {
							retrieval = (IMemoryBlockRetrieval)input;
							IMemoryBlock[] memoryBlocks = DebugPlugin.getDefault().getMemoryBlockManager().getMemoryBlocks(retrieval);
							fAction.setEnabled(memoryBlocks.length > 0);
							return Status.OK_STATUS;
						}
					}
				}
				fAction.setEnabled(false);
			}
			return Status.CANCEL_STATUS;
		}
	}
	
	private IViewPart fView;
	private MenuCreator fMenuCreator;
	private IAction fAction;
	private UpdateActionEnablementJob fUpdateJob = new UpdateActionEnablementJob();
	
	/**
	 * Memoryblock listener to update action delegate enablement
	 */
	private IMemoryBlockListener fListener = new IMemoryBlockListener() {
		public void memoryBlocksAdded(IMemoryBlock[] memory) {
			if (fAction != null) {
				fUpdateJob.schedule();
			}
		}

		public void memoryBlocksRemoved(IMemoryBlock[] memory) {
			if (fAction != null) {
				fUpdateJob.schedule();
			}
		}
	};
	
	/**
	 * Listens for debug context changes and updates action delegate enablement
	 */
	private IDebugContextListener fDebugContextListener = new IDebugContextListener() {
		public void debugContextChanged(DebugContextEvent event) {
			if (fAction != null) {		
				fUpdateJob.schedule();
			}
		}
	};
	
	/**
	 * Switch tab folder for fMemoryBlock to the top in Memory Rendering View
	 */
	class SwitchToAction extends Action {
		private IMemoryBlock fMemoryblock;
		
		/* (non-Javadoc)
		 * @see org.eclipse.jface.action.IAction#run()
		 */
		public void run() {
			if (fView == null) {
				return;
			}
			// tell the view to switch memory block
			fView.getSite().getSelectionProvider().setSelection(new StructuredSelection(fMemoryblock));
		}

		public SwitchToAction(final IMemoryBlock memBlk, boolean buildLabel) {
			super();
			if (buildLabel) {
				setText(DebugUIMessages.SwitchMemoryBlockAction_4);
				Job job = new Job("SwtichToAction"){ //$NON-NLS-1$
					protected IStatus run(IProgressMonitor monitor) {
						getLabels(memBlk);
						return Status.OK_STATUS;
					}}; 
				job.setSystem(true);
				job.schedule();
			}
			fMemoryblock = memBlk;
		}
		
		public SwitchToAction(final IMemoryBlock memBlk, String label) {
			super(label);
			fMemoryblock = memBlk;
		}
		
		private void getLabels(final IMemoryBlock memBlk) {
			StringBuffer text = new StringBuffer(IInternalDebugCoreConstants.EMPTY_STRING);
			String label = new String(IInternalDebugCoreConstants.EMPTY_STRING);
			if (memBlk instanceof IMemoryBlockExtension) {
				String expression = ((IMemoryBlockExtension)memBlk).getExpression();
				if (expression == null) {
					expression = DebugUIMessages.SwitchMemoryBlockAction_0;
				}
				text.append(expression);
			}
			else {
				long address = memBlk.getStartAddress();
				text.append(Long.toHexString(address));
			}
			
			label = text.toString();
			label = decorateLabel(memBlk, label);

			final String finalLabel = label;
			WorkbenchJob job = new WorkbenchJob("SwtichToAction Update Label") { //$NON-NLS-1$
				public IStatus runInUIThread(IProgressMonitor monitor) {
					SwitchToAction.super.setText(finalLabel);
					return Status.OK_STATUS;
				}};
			job.setSystem(true);
			job.schedule();
		}
	}
	
	/**
	 * Menu creator for the action
	 */
	class MenuCreator implements IMenuCreator {
		Menu dropdown;

		/* (non-Javadoc)
		 * @see org.eclipse.jface.action.IMenuCreator#dispose()
		 */
		public void dispose() {
			if (dropdown != null)
				dropdown.dispose();
		}

		/* (non-Javadoc)
		 * @see org.eclipse.jface.action.IMenuCreator#getMenu(org.eclipse.swt.widgets.Control)
		 */
		public Menu getMenu(Control parent) {
			if (dropdown != null) {	
				dropdown.dispose();
				dropdown = null;
			}
			if (dropdown == null) {	
				dropdown =  new Menu(parent);

				// get all memory blocks from tree viewer
				IMemoryBlock[] allMemoryBlocks = null;
				
				// get selection from memory view
				IMemoryBlock memoryBlock = getCurrentMemoryBlock();
			
				Object context = DebugUITools.getDebugContext();
				IMemoryBlockRetrieval retrieval =  MemoryViewUtil.getMemoryBlockRetrieval(context);
				if (retrieval != null) {
					allMemoryBlocks = DebugPlugin.getDefault().getMemoryBlockManager().getMemoryBlocks(retrieval);
				}
				if (allMemoryBlocks != null) {
					for (int i=0; i<allMemoryBlocks.length; i++) {	
						SwitchToAction action = new SwitchToAction(allMemoryBlocks[i], true);
						if (allMemoryBlocks[i] == memoryBlock) {
							action.setChecked(true);
						}
						ActionContributionItem item = new ActionContributionItem(action);
						item.fill(dropdown, -1);
						item.getAction().setChecked(true);
					}
				}
			}
			return dropdown;
		}

		/* (non-Javadoc)
		 * @see org.eclipse.jface.action.IMenuCreator#getMenu(org.eclipse.swt.widgets.Menu)
		 */
		public Menu getMenu(Menu parent) {
			return null;
		}
		
	}	
	
	/* (non-Javadoc)
	 * @see org.eclipse.ui.IViewActionDelegate#init(org.eclipse.ui.IViewPart)
	 */
	public void init(IViewPart view) {
		fView = view;
		DebugUITools.getDebugContextManager().getContextService(fView.getViewSite().getWorkbenchWindow()).addDebugContextListener(fDebugContextListener);
		DebugPlugin.getDefault().getMemoryBlockManager().addListener(fListener);
		fUpdateJob.runInUIThread(new NullProgressMonitor());
	}
	
	/**
	 * Returns the current memory blocks tree viewer, or <code>null</code>
	 * @return the memory blocks tree viewer or <code>null</code>
	 */
	private StructuredViewer getViewer() {
		if (fView instanceof MemoryView) {
			MemoryView memView = (MemoryView)fView;
			IMemoryViewPane pane = memView.getViewPane(MemoryBlocksTreeViewPane.PANE_ID);
			if (pane instanceof MemoryBlocksTreeViewPane) {
				 StructuredViewer viewer = ((MemoryBlocksTreeViewPane)pane).getViewer();
				return viewer;
			}
		}
		return null;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.ui.IActionDelegate#run(org.eclipse.jface.action.IAction)
	 */
	public void run(IAction action) {
		switchToNext();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.action.Action#run()
	 */
	public void run() {
		switchToNext();
	}

	private void switchToNext() {		
		IAdaptable context = DebugUITools.getDebugContext();
		if (context instanceof IDebugElement) {
			IDebugElement debugContext = (IDebugElement)context;
			IMemoryBlockRetrieval retrieval = MemoryViewUtil.getMemoryBlockRetrieval(debugContext);

			if (retrieval != null) {
				IMemoryBlock[] memoryBlocks = DebugPlugin.getDefault().getMemoryBlockManager().getMemoryBlocks(retrieval);
				doSwitchToNext(memoryBlocks);
			}
		}
	}

	/**
	 * @param memoryBlocks
	 */
	private void doSwitchToNext(IMemoryBlock[] memoryBlocks) {
		// only run if there is more than one memory block
		if (memoryBlocks.length > 1) {
			IMemoryBlock current = getCurrentMemoryBlock();
			int next = 0;
			if (current != null) {
				for (int i=0; i<memoryBlocks.length; i++) {
					if (memoryBlocks[i] == current) {
						next = i+1;
					}
				}
			}
			if (next > memoryBlocks.length-1) {
				next = 0;
			}
			SwitchToAction switchAction = new SwitchToAction(memoryBlocks[next], false);
			switchAction.run();
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.IActionDelegate#selectionChanged(org.eclipse.jface.action.IAction, org.eclipse.jface.viewers.ISelection)
	 */
	public void selectionChanged(IAction action, ISelection selection) {}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.IActionDelegate2#init(org.eclipse.jface.action.IAction)
	 */
	public void init(IAction action) {
		fAction = action;
		fUpdateJob.runInUIThread(new NullProgressMonitor());
		fMenuCreator = new MenuCreator();
		action.setMenuCreator(fMenuCreator);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.IActionDelegate2#dispose()
	 */
	public void dispose() {
		fAction = null;
		DebugPlugin.getDefault().getMemoryBlockManager().removeListener(fListener);
		DebugUITools.getDebugContextManager().getContextService(fView.getViewSite().getWorkbenchWindow()).removeDebugContextListener(fDebugContextListener);
		if (fMenuCreator != null) {
			fMenuCreator.dispose();
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.IActionDelegate2#runWithEvent(org.eclipse.jface.action.IAction, org.eclipse.swt.widgets.Event)
	 */
	public void runWithEvent(IAction action, Event event) {
		switchToNext();
	}

	/**
	 * Returns the current memory block
	 * @return the current memory block or <code>null</code>
	 */
	private IMemoryBlock getCurrentMemoryBlock() {
		if (fView == null) {
			return null;
		}
		ISelection memBlkSelection = fView.getSite().getSelectionProvider().getSelection();
		IMemoryBlock memoryBlock = null;
		
		if (memBlkSelection != null) {	
			if (!memBlkSelection.isEmpty() && memBlkSelection instanceof IStructuredSelection) {	
				Object obj = ((IStructuredSelection)memBlkSelection).getFirstElement();
				if (obj instanceof IMemoryBlock) {	
					memoryBlock = (IMemoryBlock)obj;
				}
				else if (obj instanceof IMemoryRendering) {
					memoryBlock = ((IMemoryRendering)obj).getMemoryBlock();
				}
			}
		}
		return memoryBlock;
	}

	/**
	 * Decorate the label for the specified <code>IMemoryBlock</code>
	 * @param memBlk
	 * @param label
	 * @return the decorated label for the specified <code>IMemoryBlock</code>
	 */
	private String decorateLabel(final IMemoryBlock memBlk, String label) {
		ILabelDecorator decorator = (ILabelDecorator)memBlk.getAdapter(ILabelDecorator.class);
		if (decorator != null) {
			label = decorator.decorateText(label, memBlk);
		}
		return label;
	}

}
