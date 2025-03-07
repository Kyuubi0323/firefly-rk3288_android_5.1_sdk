/*******************************************************************************
 * Copyright (c) 2006, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.synchronize;

import org.eclipse.compare.*;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.team.internal.ui.*;
import org.eclipse.team.ui.mapping.SaveableComparison;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.Saveable;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;

/**
 * A saveable that wraps a compare input in which the left side is a {@link LocalResourceTypedElement}
 * and saves changes made to the file in compare when the viewers are flushed. 
 * 
 * @see LocalResourceTypedElement
 * @since 3.3
 */
public abstract class LocalResourceSaveableComparison extends SaveableComparison implements IPropertyChangeListener {

	private final ICompareInput input;
	private final CompareEditorInput editorInput;
	private boolean isSaving;
	private IContentChangeListener contentChangeListener;
    private ITypedElement fileElement;
	private IDocument document;
	
	/**
	 * Create the resource-based saveable comparison.
	 * @param input the compare input to be save
	 * @param editorInput the editor input containing the comparison
	 */
	public LocalResourceSaveableComparison(ICompareInput input, CompareEditorInput editorInput) {
		this(input, editorInput, input.getLeft());
	}
	
	/**
	 * Create the resource-based saveable comparison.
	 * @param input the compare input to be save
	 * @param editorInput the editor input containing the comparison
	 * @param fileElement the file element that handles the saving and change notification
	 */
	public LocalResourceSaveableComparison(ICompareInput input, CompareEditorInput editorInput, ITypedElement fileElement) {
		this.input = input;
		this.editorInput = editorInput;
		this.fileElement = fileElement;
		initializeContentChangeListeners();
	}
	
	protected void initializeHashing() {
		Object document= getAdapter(IDocument.class);
		if (document != null) {
			this.document = (IDocument)document;
		}
	}

	private void initializeContentChangeListeners() {
		// We need to listen to saves to the input to catch the case
		// where Save was picked from the context menu
		ITypedElement te = getFileElement();
		if (te instanceof IContentChangeNotifier) {
			if (contentChangeListener == null) {
				contentChangeListener = new IContentChangeListener() {
					public void contentChanged(IContentChangeNotifier source) {
						try {
							if(! isSaving) {
								performSave(new NullProgressMonitor());
							}
						} catch (CoreException e) {
							TeamUIPlugin.log(e);
						}
					}
				};
			}
			((IContentChangeNotifier) te).addContentChangeListener(contentChangeListener);
		}
	}
	
	/**
	 * Dispose of the saveable.
	 */
	public void dispose() {
		if (contentChangeListener != null) {
			ITypedElement te = getFileElement();
			if (te instanceof IContentChangeNotifier) {
				((IContentChangeNotifier) te).removeContentChangeListener(contentChangeListener);
			}
		}
		// Discard of the left buffer
		ITypedElement left = getFileElement();
		if (left instanceof LocalResourceTypedElement)
			 ((LocalResourceTypedElement) left).discardBuffer();
	}

	private ITypedElement getFileElement() {
		return fileElement;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.mapping.SaveableCompareModel#performSave(org.eclipse.core.runtime.IProgressMonitor)
	 */
	protected void performSave(IProgressMonitor monitor) throws CoreException {
		if (checkForUpdateConflicts()) {
			return;
		}
		try {
			isSaving = true;
			monitor.beginTask(null, 100);
			// First, we need to flush the viewers so the changes get buffered
			// in the input
			flushViewers(Policy.subMonitorFor(monitor, 40));
			// Then we tell the input to commit its changes
			// Only the left is ever saveable
			ITypedElement left = getFileElement();
			if (left instanceof LocalResourceTypedElement) {
				LocalResourceTypedElement te = (LocalResourceTypedElement) left;
				te.commit(Policy.subMonitorFor(monitor, 60));
			}
		} finally {
			// Make sure we fire a change for the compare input to update the viewers
			fireInputChange();
			setDirty(false);
			isSaving = false;
			monitor.done();
		}
	}

	/**
	 * Flush the contents of any viewers into the compare input.
	 * @param monitor a progress monitor
	 * @throws CoreException
	 */
	protected void flushViewers(IProgressMonitor monitor) throws CoreException {
		editorInput.saveChanges(monitor);
	}

	/**
	 * Fire an input change for the compare input after it has been 
	 * saved.
	 */
	protected abstract void fireInputChange();

	/**
	 * Check whether there is a conflicting save on the file.
	 * @return <code>true</code> if there was and the user chose to cancel the operation
	 */
	private boolean checkForUpdateConflicts() {
		if(hasSaveConflict()) {
			if (Utils.RUNNING_TESTS)
				return !Utils.TESTING_FLUSH_ON_COMPARE_INPUT_CHANGE;
			final MessageDialog dialog = 
				new MessageDialog(TeamUIPlugin.getStandardDisplay().getActiveShell(), 
						TeamUIMessages.SyncInfoCompareInput_0,  
						null, 
						TeamUIMessages.SyncInfoCompareInput_1,  
						MessageDialog.QUESTION,
					new String[] {
						TeamUIMessages.SyncInfoCompareInput_2, 
						IDialogConstants.CANCEL_LABEL}, 
					0);
			
			int retval = dialog.open();
			switch(retval) {
				// save
				case 0: 
					return false;
				// cancel
				case 1:
					return true;
			}
		}
		return false;
	}

	private boolean hasSaveConflict() {
		ITypedElement left = getFileElement();
		if (left instanceof LocalResourceTypedElement) {
			LocalResourceTypedElement te = (LocalResourceTypedElement) left;
			return !te.isSynchronized();
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.mapping.SaveableCompareModel#isDirty()
	 */
	public boolean isDirty() {
		// We need to get the dirty state from the compare editor input
		// since it is our only connection to the merge viewer
		return editorInput.isSaveNeeded();
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.mapping.SaveableCompareModel#setDirty(boolean)
	 */
	protected void setDirty(boolean dirty) {
		// We need to set the dirty state on the compare editor input
		// since it is our only connection to the merge viewer
		editorInput.setDirty(dirty);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.mapping.SaveableCompareModel#performRevert(org.eclipse.core.runtime.IProgressMonitor)
	 */
	protected void performRevert(IProgressMonitor monitor) {
		// Only the left is ever editable
		ITypedElement left = getFileElement();
		if (left instanceof LocalResourceTypedElement)
			 ((LocalResourceTypedElement) left).discardBuffer();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.Saveable#getName()
	 */
	public String getName() {
		return input.getName();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.Saveable#getToolTipText()
	 */
	public String getToolTipText() {
		return editorInput.getToolTipText();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.Saveable#getImageDescriptor()
	 */
	public ImageDescriptor getImageDescriptor() {
		Image image = input.getImage();
		if (image != null)
			return ImageDescriptor.createFromImage(image);
		return TeamUIPlugin.getImageDescriptor(ITeamUIImages.IMG_SYNC_VIEW);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.util.IPropertyChangeListener#propertyChange(org.eclipse.jface.util.PropertyChangeEvent)
	 */
	public void propertyChange(PropertyChangeEvent e) {
		String propertyName= e.getProperty();
		if (CompareEditorInput.DIRTY_STATE.equals(propertyName)) {
			boolean changed= false;
			Object newValue= e.getNewValue();
			if (newValue instanceof Boolean)
				changed= ((Boolean)newValue).booleanValue();
			setDirty(changed);
		}			
	}
	
	/*
	 * @see org.eclipse.ui.Saveable#hashCode()
	 */
	public int hashCode() {
		if (document != null) {
			return document.hashCode();
		}
		return input.hashCode();
	}

	/*
	 * @see org.eclipse.ui.Saveable#equals(java.lang.Object)
	 */
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		
		if (!(obj instanceof Saveable))
			return false;
		
		if (document != null) {
			Object otherDocument= ((Saveable)obj).getAdapter(IDocument.class);
			
			if (document == null && otherDocument == null)
				return false;
			
			return document != null && document.equals(otherDocument);
		}
		
		if (obj instanceof LocalResourceSaveableComparison) {
			LocalResourceSaveableComparison rscm = (LocalResourceSaveableComparison) obj;
			return rscm.input.equals(input);
		}
		return false;
	}
	
	public Object getAdapter(Class adapter) {
		if (adapter == IDocument.class) {
			if (document != null)
				return document;
			if (fileElement instanceof LocalResourceTypedElement) {
				LocalResourceTypedElement lrte = (LocalResourceTypedElement) fileElement;
				if (lrte.isConnected()) {
					ISharedDocumentAdapter sda = (ISharedDocumentAdapter)Utils.getAdapter(lrte, ISharedDocumentAdapter.class);
					if (sda != null) {
						IEditorInput input = sda.getDocumentKey(lrte);
						if (input != null) {
							IDocumentProvider provider = SharedDocumentAdapter.getDocumentProvider(input);
							if (provider != null)
								return provider.getDocument(input);
						}
					}
				}
			}
		}
		if (adapter == IEditorInput.class) {
			if (fileElement instanceof LocalResourceTypedElement) {
				LocalResourceTypedElement lrte = (LocalResourceTypedElement) fileElement;
				return new FileEditorInput((IFile)lrte.getResource());
			}
		}
		return super.getAdapter(adapter);
	}

	/**
	 * Return the compare input that is managed by this saveable.
	 * @return the compare input that is managed by this saveable
	 */
	public ICompareInput getInput() {
		return input;
	}

	public boolean isConnectedToSharedDocument() {
		if (fileElement instanceof LocalResourceTypedElement) {
			LocalResourceTypedElement lrte = (LocalResourceTypedElement) fileElement;
			return lrte.isConnected();
		}
		return false;
	}
}
