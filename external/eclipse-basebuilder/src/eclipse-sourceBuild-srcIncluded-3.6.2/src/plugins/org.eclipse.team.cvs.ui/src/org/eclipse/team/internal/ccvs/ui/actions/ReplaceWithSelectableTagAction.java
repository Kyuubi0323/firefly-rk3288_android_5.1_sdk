/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ccvs.ui.actions;

import org.eclipse.jface.window.Window;
import org.eclipse.team.internal.ccvs.core.CVSTag;
import org.eclipse.team.internal.ccvs.ui.CVSUIMessages;
import org.eclipse.team.internal.ccvs.ui.IHelpContextIds;
import org.eclipse.team.internal.ccvs.ui.operations.ReplaceOperation;
import org.eclipse.team.internal.ccvs.ui.tags.TagSelectionDialog;
import org.eclipse.team.internal.ccvs.ui.tags.TagSource;

public class ReplaceWithSelectableTagAction extends ReplaceWithTagAction {
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.internal.ccvs.ui.actions.ReplaceWithTagAction.getTag(ReplaceOperation)
	 */
	protected CVSTag getTag(final ReplaceOperation replaceOperation) {
		TagSelectionDialog dialog = new TagSelectionDialog(getShell(), TagSource.create(replaceOperation.getScope().getMappings()), 
			CVSUIMessages.ReplaceWithTagAction_message, 
			CVSUIMessages.TagSelectionDialog_Select_a_Tag_1, 
			TagSelectionDialog.INCLUDE_ALL_TAGS, 
			false, /*show recurse*/
			IHelpContextIds.REPLACE_TAG_SELECTION_DIALOG); 
		dialog.setBlockOnOpen(true);
		if (dialog.open() == Window.CANCEL) {
			return null;
		}
		return dialog.getResult();
	}
	
}
