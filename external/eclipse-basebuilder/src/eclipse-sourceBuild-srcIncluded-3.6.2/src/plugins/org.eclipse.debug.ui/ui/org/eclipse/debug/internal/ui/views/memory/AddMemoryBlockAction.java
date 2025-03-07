/*******************************************************************************
 * Copyright (c) 2004, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.debug.internal.ui.views.memory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.StringTokenizer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IMemoryBlockExtension;
import org.eclipse.debug.core.model.IMemoryBlockRetrieval;
import org.eclipse.debug.core.model.IMemoryBlockRetrievalExtension;
import org.eclipse.debug.core.model.ITerminate;
import org.eclipse.debug.internal.core.IInternalDebugCoreConstants;
import org.eclipse.debug.internal.ui.DebugPluginImages;
import org.eclipse.debug.internal.ui.DebugUIMessages;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.IInternalDebugUIConstants;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.contexts.DebugContextEvent;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.debug.ui.memory.IMemoryRendering;
import org.eclipse.debug.ui.memory.IMemoryRenderingContainer;
import org.eclipse.debug.ui.memory.IMemoryRenderingSite;
import org.eclipse.debug.ui.memory.IMemoryRenderingType;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;


/**
 * Action for adding memory block.
 * 
 * @since 3.0
 */
public class AddMemoryBlockAction extends Action implements IDebugContextListener, IDebugEventSetListener{
	
	protected IAdaptable fCurrentContext = null;
	protected IMemoryBlock fLastMemoryBlock;
	private boolean fAddDefaultRenderings = true;
	protected IMemoryRenderingSite fSite;
	
	public AddMemoryBlockAction(IMemoryRenderingSite site)
	{
		initialize(site);
	}
	
	/**
	 * @param addDefaultRenderings - specify if the action should add
	 * default renderings for the new memory block when it is run
	 */
	AddMemoryBlockAction(IMemoryRenderingSite site, boolean addDefaultRenderings)
	{
		initialize(site);
		fAddDefaultRenderings = addDefaultRenderings;
	}
	
	/**
	 * 
	 */
	private void initialize(IMemoryRenderingSite site) {
		setText(DebugUIMessages.AddMemoryBlockAction_title);
		doInitialization(site);
	}


	public AddMemoryBlockAction(String text, int style, IMemoryRenderingSite site)
	{
		super(text, style);
		doInitialization(site);	
	}
	
	/**
	 * 
	 */
	private void doInitialization(IMemoryRenderingSite site) {
		fSite = site;
		setToolTipText(DebugUIMessages.AddMemoryBlockAction_tooltip);
		setImageDescriptor(DebugPluginImages.getImageDescriptor(IInternalDebugUIConstants.IMG_ELCL_MONITOR_EXPRESSION));
		setHoverImageDescriptor(DebugPluginImages.getImageDescriptor(IInternalDebugUIConstants.IMG_LCL_MONITOR_EXPRESSION));
		setDisabledImageDescriptor(DebugPluginImages.getImageDescriptor(IInternalDebugUIConstants.IMG_DLCL_MONITOR_EXPRESSION));
		PlatformUI.getWorkbench().getHelpSystem().setHelp(this, IDebugUIConstants.PLUGIN_ID + ".addMemoryMonitorAction_context"); //$NON-NLS-1$		
		
		// listen for context changed
		DebugUITools.getDebugContextManager().getContextService(site.getSite().getWorkbenchWindow()).addDebugContextListener(this);
		
		// get current context
		fCurrentContext = DebugUITools.getDebugContext();
		
		// set up enablement based on current selection
		updateAction(fCurrentContext);
		
		DebugPlugin.getDefault().addDebugEventListener(this);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.action.IAction#run()
	 */
	public void run() {
		boolean exit = false;
		String prefillExp = null;
		String prefillLength = null;
		while (!exit)
		{
			exit = true;
			
			Object elem = DebugUITools.getDebugContext();
			
			IMemoryBlockRetrieval retrieval = MemoryViewUtil.getMemoryBlockRetrieval(elem);
			
			if (retrieval == null)
				return;
			
			Shell shell= DebugUIPlugin.getDefault().getWorkbench().getActiveWorkbenchWindow().getShell();
			// create dialog to ask for expression/address to block
			MonitorMemoryBlockDialog dialog = new MonitorMemoryBlockDialog(shell, retrieval, prefillExp, prefillLength);
			dialog.open();
			int returnCode = dialog.getReturnCode();
			if (returnCode == Window.CANCEL)
			{
				return;
			}
			// get expression entered in dialog
			String input = dialog.getExpression();
			
			// remember expression and length
			prefillExp = input;
			prefillLength = dialog.getLength();
			
			ArrayList expressions = new ArrayList();
			
			if (input.length() == 0)
			{
				expressions.add(IInternalDebugCoreConstants.EMPTY_STRING);
			}
			else
			{
				StringTokenizer tokenizer = new StringTokenizer(input, ","); //$NON-NLS-1$
				while (tokenizer.hasMoreTokens())
				{
					expressions.add(tokenizer.nextToken());
				}
			}	
			final String[] expressionsArray = (String[])expressions.toArray(new String[expressions.size()]);
			exit = true;
			
			final boolean finalExit = exit;
			final Object finalElement = elem;
			final IMemoryBlockRetrieval finalRetrieval = retrieval;
			final MonitorMemoryBlockDialog finalDialog = dialog;
			Job job = new Job("Add Memory Block") { //$NON-NLS-1$
				protected IStatus run(IProgressMonitor monitor) {
					addMemoryBlocks(finalExit, finalElement, finalRetrieval, finalDialog, expressionsArray);
					return Status.OK_STATUS;
				}};
			job.setSystem(true);
			job.schedule();
		}
	}

	/**
	 * @param exit
	 * @param elem
	 * @param standardMemRetrieval
	 * @param dialog
	 * @param expressionsArray
	 * @return
	 */
	private boolean addMemoryBlocks(boolean exit, Object elem, IMemoryBlockRetrieval standardMemRetrieval, MonitorMemoryBlockDialog dialog, final String[] expressionsArray) {
		for (int i=0; i<expressionsArray.length; i++)
		{
			String expression = expressionsArray[i].trim();
			try {
				if (standardMemRetrieval instanceof IMemoryBlockRetrievalExtension)
				{
					// if the debug session supports IMemoryBlockExtensionRetrieval
					IMemoryBlockRetrievalExtension memRetrieval = (IMemoryBlockRetrievalExtension)standardMemRetrieval;
					
					// get extended memory block with the expression entered
					IMemoryBlockExtension memBlock = memRetrieval.getExtendedMemoryBlock(expression, elem);
					
					// add block to memory block manager
					if (memBlock != null)
					{
						fLastMemoryBlock = memBlock;
						
						IMemoryBlock[] memArray = new IMemoryBlock[]{memBlock};
						
						MemoryViewUtil.getMemoryBlockManager().addMemoryBlocks(memArray);
						if (fAddDefaultRenderings)
							addDefaultRenderings(memBlock);
					}
					else
					{
						// open error if it failed to retrieve a memory block
						MemoryViewUtil.openError(DebugUIMessages.AddMemoryBlockAction_title, DebugUIMessages.AddMemoryBlockAction_noMemoryBlock, null);
						exit = false;
					}
				}
				else
				{
					// if the debug session does not support IMemoryBlockExtensionRetrieval
					expression = expression.toUpperCase();
					String hexPrefix = "0X"; //$NON-NLS-1$
					if (expression.startsWith(hexPrefix))
					{
						expression = expression.substring(hexPrefix.length());
					}
					
					// convert the expression to an address
					BigInteger address = new BigInteger(expression, 16);

					long longAddress = address.longValue();

					// get the length of memory to block
					String strLength = dialog.getLength();
					
					long length = Long.parseLong(strLength);
					
					// must monitor at least one line
					if (length <= 0)
					{
						String message = DebugUIMessages.AddMemoryBlockAction_failed + "\n" + DebugUIMessages.AddMemoryBlockAction_input_invalid; //$NON-NLS-1$
						MemoryViewUtil.openError(DebugUIMessages.AddMemoryBlockAction_title, message, null); 
						exit = false;
						continue;
					}
					
					// get standard memory block
					IMemoryBlock memBlock = standardMemRetrieval.getMemoryBlock(longAddress, length);
					
					// make sure the memory block returned is not an instance of IMemoryBlockExtension
					if (memBlock instanceof IMemoryBlockExtension)
					{
						Status status = new Status(IStatus.WARNING, DebugUIPlugin.getUniqueIdentifier(),	0, 
							"IMemoryBlockRetrieval returns IMemoryBlockExtension.  This may result in unexpected behavior.", null); //$NON-NLS-1$
						DebugUIPlugin.log(status);
					}
					
					if (memBlock != null)
					{
						// add memory block to memory block manager
						fLastMemoryBlock = memBlock;	
						IMemoryBlock[] memArray = new IMemoryBlock[]{memBlock};
						
						MemoryViewUtil.getMemoryBlockManager().addMemoryBlocks(memArray);
						if (fAddDefaultRenderings)
							addDefaultRenderings(memBlock);
					}
					else
					{
						// otherwise open up an error doalog
						MemoryViewUtil.openError(DebugUIMessages.AddMemoryBlockAction_title, DebugUIMessages.AddMemoryBlockAction_noMemoryBlock, null);
						exit = false;
					}
				}
			} catch (DebugException e1) {
				MemoryViewUtil.openError(DebugUIMessages.AddMemoryBlockAction_title, DebugUIMessages.AddMemoryBlockAction_failed, e1);
				exit = false;
			}
			catch(NumberFormatException e2)
			{
				String message = DebugUIMessages.AddMemoryBlockAction_failed + "\n" + DebugUIMessages.AddMemoryBlockAction_input_invalid; //$NON-NLS-1$
				MemoryViewUtil.openError(DebugUIMessages.AddMemoryBlockAction_title, message, null); 
				exit = false;
			}
		}
		return exit;
	}


	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.IDebugEventSetListener#handleDebugEvents(org.eclipse.debug.core.DebugEvent[])
	 */
	public void handleDebugEvents(DebugEvent[] events) {
		for (int i=0; i < events.length; i++)
				handleDebugEvent(events[i]);
	} 
	
	private void handleDebugEvent(DebugEvent event)
	{
		// update action enablement based on debug event
		Object src = event.getSource();
		IDebugTarget srcDT = null;
		IDebugTarget selectionDT = null;
		
		if (event.getKind() == DebugEvent.TERMINATE)
		{
			if (src instanceof ITerminate && src instanceof IDebugElement)
			{
				srcDT = ((IDebugElement)src).getDebugTarget();
			}
			
			if (fCurrentContext instanceof IDebugElement)
			{
				selectionDT = ((IDebugElement)fCurrentContext).getDebugTarget();
			}

			// disable action if the debug target is terminated.
			if (srcDT == selectionDT)
			{
				setEnabled(false);
			}
		}
		// handle change event from memory block retrieval object
		// to allow non-standard debug models to update the action
		else if (event.getKind() == DebugEvent.CHANGE && event.getDetail() == DebugEvent.STATE)
		{
			Object evtSrc = event.getSource();
			if (evtSrc == MemoryViewUtil.getMemoryBlockRetrieval(fCurrentContext))
				updateAction(fCurrentContext);
		}
	}
	
	/**
	 * Return the last memory block added to memory block manager via this action.
	 * @return Returns the fLastMemoryBlock.
	 */
	public IMemoryBlock getLastMemoryBlock() {
		return fLastMemoryBlock;
	}
	
	protected void dispose() {
		
		// remove listeners
		DebugPlugin.getDefault().removeDebugEventListener(this);
		DebugUITools.getDebugContextManager().getContextService(fSite.getSite().getWorkbenchWindow()).removeDebugContextListener(this);
	}
	
	private void addDefaultRenderings(IMemoryBlock memoryBlock)
	{
		IMemoryRenderingType primaryType = DebugUITools.getMemoryRenderingManager().getPrimaryRenderingType(memoryBlock);
		IMemoryRenderingType renderingTypes[] = DebugUITools.getMemoryRenderingManager().getDefaultRenderingTypes(memoryBlock);
		
		// create primary rendering
		try {
			if (primaryType != null)
			{
				createRenderingInContainer(memoryBlock, primaryType, IDebugUIConstants.ID_RENDERING_VIEW_PANE_1);
			}
			else if (renderingTypes.length > 0)
			{
				primaryType = renderingTypes[0];
				createRenderingInContainer(memoryBlock, renderingTypes[0], IDebugUIConstants.ID_RENDERING_VIEW_PANE_1);
			}
		} catch (CoreException e1) {
			DebugUIPlugin.log(e1);	
		}
		
		for (int i = 0; i<renderingTypes.length; i++)
		{
			try {
				boolean create = true;
				if (primaryType != null)
				{
					if (primaryType.getId().equals(renderingTypes[i].getId()))
						create = false;
				}
				if (create)
					createRenderingInContainer(memoryBlock, renderingTypes[i], IDebugUIConstants.ID_RENDERING_VIEW_PANE_2);
			} catch (CoreException e) {
				DebugUIPlugin.log(e);
			}
		}
	}

	/**
	 * @param memoryBlock
	 * @param primaryType
	 * @throws CoreException
	 */
	private void createRenderingInContainer(IMemoryBlock memoryBlock, IMemoryRenderingType primaryType, String paneId) throws CoreException {
		IMemoryRendering rendering = primaryType.createRendering();
		IMemoryRenderingContainer container = fSite.getContainer(paneId);
		rendering.init(container, memoryBlock);
		container.addMemoryRendering(rendering);
	}
	
	protected MemoryView getMemoryView()
	{
		if (fSite instanceof MemoryView)
			return (MemoryView)fSite;
		return null;
	}
	
	protected void updateAction(final Object debugContext)
	{
		Job job = new Job("Update Add Memory Block Action") { //$NON-NLS-1$
			protected IStatus run(IProgressMonitor monitor) {
				setEnabled(MemoryViewUtil.isValidContext(debugContext));
				return Status.OK_STATUS;
			}};
		job.setSystem(true);
		job.schedule();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.contexts.provisional.IDebugContextListener#contextEvent(org.eclipse.debug.internal.ui.contexts.provisional.DebugContextEvent)
	 */
	public void debugContextChanged(DebugContextEvent event) {
		if ((event.getFlags() & DebugContextEvent.ACTIVATED) > 0) {
			IAdaptable context = DebugUITools.getDebugContext();
			updateAction(context);
			fCurrentContext = context;			
		}
	}
}
