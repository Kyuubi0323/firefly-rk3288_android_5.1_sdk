/*******************************************************************************
 * Copyright (c) 2006, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.debug.ui.actions;

import org.eclipse.debug.core.IRequest;
import org.eclipse.debug.internal.ui.commands.actions.DebugCommandService;
import org.eclipse.debug.internal.ui.commands.actions.ICommandParticipant;
import org.eclipse.debug.internal.ui.commands.actions.IEnabledTarget;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.contexts.DebugContextEvent;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.debug.ui.contexts.IDebugContextService;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Abstract base class for re-targeting actions which delegate execution to 
 * {@link org.eclipse.debug.core.commands.IDebugCommandHandler} handlers.  
 * The specific type of <code>IDebugCommandHandler</code> is determined by the 
 * abstract {@link #getCommandType()} method.    
 * <p>
 * This base class is an action which can be instantiated directly by views, 
 * etc.  In order to contribute an action using an extension point, a class 
 * implementing {@link org.eclipse.ui.IActionDelegate} should be created first.
 * The delegate should then use a <code>DebugCommandAction</code> to implement 
 * the needed functionality. The IActionDelegate must use {@link #setActionProxy(IAction)}
 * specifying the workbench's action that is a proxy to the action delegate. This
 * way, the workbench action can be updated visually as needed.<br>
 * Note: <code>IDebugCommandHandler</code> command typically act on the active
 * debug context as opposed to the active selection in view or window.  The 
 * action delegate should ignore the active window selection, and instead allow 
 * the <code>DebugCommandAction</code> to update itself based on the active 
 * debug context. 
 * </p>
 * <p>
 * Clients may subclass this class.
 * </p>
 * @see org.eclipse.debug.core.commands.IDebugCommandHandler
 * @since 3.6
 */
public abstract class DebugCommandAction extends Action implements IDebugContextListener {

    private boolean fInitialized = false;
    
	/**
	 * The window this action is working for.
	 */
    private IWorkbenchWindow fWindow;
    
    /**
     * The part this action is working for, or <code>null</code> if global to
     * a window.
     */
    private IWorkbenchPart fPart;
    
    /**
     * Command service.
     */
    private DebugCommandService fUpdateService;
    
    /**
     * Delegate this action is working for or <code>null</code> if none.
     */
    private IAction fAction;

    private IEnabledTarget fEnabledTarget = new IEnabledTarget() {
        public void setEnabled(boolean enabled) {
            DebugCommandAction.this.setEnabled(enabled);
        }
    };
    
    /**
     * Constructor
     */
    public DebugCommandAction() {
        super();
        String helpContextId = getHelpContextId();
        if (helpContextId != null)
            PlatformUI.getWorkbench().getHelpSystem().setHelp(this, helpContextId);
        setEnabled(false);
    }

	/**
     * Sets the current workbench action that is a proxy to an {@link org.eclipse.ui.IActionDelegate}
     * that is using this action to perform its actual work. This only needs to be called when
     * an {@link org.eclipse.ui.IActionDelegate} is using one of these actions to perform its
     * function.
     * 
     * @param action workbench proxy action
     */
    public void setActionProxy(IAction action) {
        fAction = action;
        fAction.setEnabled(isEnabled());
    }

    /**
     * Executes this action on the given target object
     * 
     * @param target the target to perform the action on
     */
    private boolean execute(final Object[] targets) {
    	return fUpdateService.executeCommand(
    	    getCommandType(), targets, 
    	    new ICommandParticipant() {
    	        public void requestDone(org.eclipse.debug.core.IRequest request) {
    	            DebugCommandAction.this.postExecute(request, targets);
    	        }    	      
    	    });
    }
        
    /**
     * This method is called after the completion of the execution of this 
     * command.  Extending classes may override this method to perform additional
     * operation after command execution. 
     * 
     * @param request The completed request object which was given to the 
     * debug command handler.
     * @param targets Objects which were the targets of this action
     */
    protected void postExecute(IRequest request, Object[] targets) {
        // do nothing by default
    }
    
    /**
     * Returns the {@link org.eclipse.debug.core.commands.IDebugCommandHandler} 
     * command handler that type this action executes.
     * 
     * @return command class.
     * 
     * @see org.eclipse.debug.core.commands.IDebugCommandHandler
     */
    abstract protected Class getCommandType();  

    /**
     * @see org.eclipse.debug.ui.contexts.IDebugContextListener#debugContextChanged(org.eclipse.debug.ui.contexts.DebugContextEvent)
     */
    public void debugContextChanged(DebugContextEvent event) {
    	fUpdateService.postUpdateCommand(getCommandType(), fEnabledTarget);
	}

    /**
     * @see org.eclipse.jface.action.Action#setEnabled(boolean)
     */
    public void setEnabled(boolean enabled) {
        synchronized (this) {
            if (!fInitialized) {
                fInitialized = true;
                notifyAll();
            }
        }        
        super.setEnabled(enabled);
        if (fAction != null) {
            fAction.setEnabled(enabled);
        }
    }

    /**
     * Initializes this action for a specific part.
     * 
     * @param part workbench part
     */
    public void init(IWorkbenchPart part) {
    	fInitialized = false;
        fPart = part;
        fWindow = part.getSite().getWorkbenchWindow();
        fUpdateService = DebugCommandService.getService(fWindow);
        IDebugContextService service = getDebugContextService();
		String partId = part.getSite().getId();
		service.addDebugContextListener(this, partId);
        ISelection activeContext = service.getActiveContext(partId);
        if (activeContext != null) {
        	fUpdateService.updateCommand(getCommandType(), fEnabledTarget);
        } else {
        	setEnabled(getInitialEnablement());
        }
    }
    
    /**
     * Initializes this action for a workbench window.
     * 
     * @param window the window
     */
    public void init(IWorkbenchWindow window) {
    	fInitialized = false;
        fWindow = window;
        fUpdateService = DebugCommandService.getService(fWindow);
        IDebugContextService contextService = getDebugContextService();
		contextService.addDebugContextListener(this);
        ISelection activeContext = contextService.getActiveContext();
        if (activeContext != null) {
        	fUpdateService.updateCommand(getCommandType(), fEnabledTarget);
        } else {
        	setEnabled(getInitialEnablement());
        }
    }
    
    /**
     * Returns whether this action should be enabled when initialized
     * and there is no active debug context. By default, <code>false</code>
     * is returned.
     * 
     * @return initial enabled state when there is no active context.
     */
    protected boolean getInitialEnablement() {
    	return false;
    }

    /**
     * Returns the context (selection) this action operates on. By default
     * the active debug context in this action's associated part or window is used,
     * but subclasses may override as required.
     * 
     * @return the context this action operates on
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    protected ISelection getContext() {
		if (fPart != null) {
			getDebugContextService().getActiveContext(fPart.getSite().getId());
    	}
        return getDebugContextService().getActiveContext();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#run()
     */
    public void run() {
        synchronized (this) {
            if (!fInitialized) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }           
        }        
        
        ISelection selection = getContext();
        if (selection instanceof IStructuredSelection && isEnabled()) {
            IStructuredSelection ss = (IStructuredSelection) selection;
            boolean enabled = execute(ss.toArray());
            // disable the action according to the command
            setEnabled(enabled);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#runWithEvent(org.eclipse.swt.widgets.Event)
     */
    public void runWithEvent(Event event) {
        run();
    }

    /**
     * Clean up when removing
     */
    public void dispose() {
        IDebugContextService service = getDebugContextService();
        if (fPart != null) {
        	service.removeDebugContextListener(this, fPart.getSite().getId());
        } else {
            service.removeDebugContextListener(this);
        }
        fWindow = null;
        fPart = null;
    }
    
    /**
     * Returns the context service this action linked to. By default, this actions is
     * associated with the context service for the window this action is operating in.
     * 
     * @return associated context service
     */
    protected IDebugContextService getDebugContextService() {
    	return DebugUITools.getDebugContextManager().getContextService(fWindow);
    }

    /**
     * Returns the help context id for this action or <code>null</code> if none.
     * 
     * @return The help context id for this action or <code>null</code>
     */ 
    public abstract String getHelpContextId();

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#getId()
     */
    public abstract String getId();

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#getText()
     */
    public abstract String getText();

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#getToolTipText()
     */
    public abstract String getToolTipText();

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#getDisabledImageDescriptor()
     */
    public abstract ImageDescriptor getDisabledImageDescriptor();

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#getHoverImageDescriptor()
     */
    public abstract ImageDescriptor getHoverImageDescriptor();

    /*
     * (non-Javadoc)
     * @see org.eclipse.jface.action.Action#getImageDescriptor()
     */
    public abstract ImageDescriptor getImageDescriptor();
    
    /**
     * Returns the workbench proxy associated with this action or <code>null</code>
     * if none. This is the workbench proxy to an {@link org.eclipse.ui.IActionDelegate}
     * that is using this action to perform its actual work. This is only used when
     * an {@link org.eclipse.ui.IActionDelegate} is using one of these actions to perform its
     * function.
     * 
     * @return workbench proxy action or <code>null</code>
     */
    protected IAction getActionProxy() {
    	return fAction;
    }
}
