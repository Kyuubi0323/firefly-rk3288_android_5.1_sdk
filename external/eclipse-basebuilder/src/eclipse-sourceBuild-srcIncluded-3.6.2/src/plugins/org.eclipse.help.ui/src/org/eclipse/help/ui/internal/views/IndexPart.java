/*******************************************************************************
 * Copyright (c) 2006, 2009 Intel Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Intel Corporation - initial API and implementation
 *     IBM Corporation - 163558 Dynamic content support for all UA
 *     IBM Corporation - Support for see elements
 *******************************************************************************/
package org.eclipse.help.ui.internal.views;

import org.eclipse.help.HelpSystem;
import org.eclipse.help.IHelpResource;
import org.eclipse.help.IIndexEntry;
import org.eclipse.help.IIndexEntry2;
import org.eclipse.help.IIndexSee;
import org.eclipse.help.UAContentFilter;
import org.eclipse.help.internal.base.HelpBasePlugin;
import org.eclipse.help.internal.base.HelpEvaluationContext;
import org.eclipse.help.internal.base.util.IndexUtils;
import org.eclipse.help.internal.index.IndexSee;
import org.eclipse.help.ui.internal.IHelpUIConstants;
import org.eclipse.help.ui.internal.Messages;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.forms.widgets.FormToolkit;

public class IndexPart extends HyperlinkTreePart implements IHelpUIConstants {
	private RoleFilter roleFilter;

	class IndexProvider implements ITreeContentProvider {
		public Object[] getChildren(Object parentElement) {
			if (parentElement == IndexPart.this) {
				return HelpSystem.getIndex().getEntries();
			}
			if (parentElement instanceof IIndexEntry) {
				return IndexPart.this.getChildren((IIndexEntry) parentElement);
			}
			return new Object[0];
		}

		public Object getParent(Object element) {
			return null;
		}

		public boolean hasChildren(Object element) {
			return getChildren(element).length > 0;
		}

		public Object[] getElements(Object inputElement) {
			return getChildren(inputElement);
		}

		public void dispose() {}
		public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {}
	}

	class IndexLabelProvider extends LabelProvider {
		public String getText(Object obj) {
			if (obj instanceof IIndexEntry)
				return ((IIndexEntry) obj).getKeyword();
			if (obj instanceof IHelpResource)
				return ((IHelpResource) obj).getLabel();
			if (obj instanceof IndexSee) {
				IndexSee see = (IndexSee) obj;
				return getSeeString(see);
			} 
			return super.getText(obj);
		}

		public Image getImage(Object obj) {
			return super.getImage(obj);
		}
	}
	
	public String getSeeString(IIndexSee see) {
		String seeText = see.isSeeAlso() ? Messages.SeeAlso : Messages.See;
		String message = NLS.bind(seeText, see.getKeyword());
		String[] path = IndexUtils.getPath(see);;
		for (int i = 1; i < path.length; i++) {
			message = NLS.bind(Messages.SeeList, message,path[i]);
		}
		return message;
	}

	class RoleFilter extends ViewerFilter {
		public boolean select(Viewer viewer, Object parentElement,
				Object element) {
			if (element instanceof IIndexEntry) {
				return isEnabled((IIndexEntry) element);
			} else if (element instanceof IHelpResource) {
				return isEnabled((IHelpResource) element);
			} else if (element instanceof IIndexSee) {
				return isEnabled((IIndexSee) element);
			}
			return false;
		}

		private boolean isEnabled(IIndexEntry entry) {
			if (!UAContentFilter.isFiltered(entry, HelpEvaluationContext.getContext())) {
				IHelpResource[] topics = entry.getTopics();
				for (int i = 0; i < topics.length; i++) {
					if (isEnabled(topics[i]))
						return true;
				}
				IIndexEntry[] subentries = entry.getSubentries();
				for (int i = 0; i < subentries.length; i++) {
					if (isEnabled(subentries[i]))
						return true;
				}
				if (entry instanceof IIndexEntry2) {
					IIndexSee[] sees = ((IIndexEntry2)entry).getSees();
					for (int i = 0; i < sees.length; i++) {
						if (isEnabled(sees[i]))
							return true;
					}
				}
			}
			return false;
		}

		private boolean isEnabled(Object obj) {
			return !UAContentFilter.isFiltered(obj, HelpEvaluationContext.getContext());
		}

		private boolean isEnabled(IHelpResource topic) {
			return isEnabled((Object)topic) && HelpBasePlugin.getActivitySupport().isEnabled(topic.getHref());
		}
	}

	public IndexPart(Composite parent, FormToolkit toolkit, IToolBarManager tbm) {
		super(parent, toolkit, tbm);
		roleFilter = new RoleFilter();
	}

	protected void configureTreeViewer() {
		treeViewer.setContentProvider(new IndexProvider());
		treeViewer.setLabelProvider(new IndexLabelProvider());
	}

	public void init(ReusableHelpPart parent, String id, IMemento memento) {
		super.init(parent, id, memento);
		if (parent.isFilteredByRoles())
			treeViewer.addFilter(roleFilter);
	}

	protected void doOpen(Object obj) {
		if (obj instanceof IHelpResource) {
			parent.showURL(((IHelpResource) obj).getHref());
		} else if (obj instanceof IIndexEntry) {
			IIndexEntry entry = (IIndexEntry) obj;
			if (getChildren(entry).length > 0) {
				treeViewer.setExpandedState(obj, !treeViewer.getExpandedState(obj));
			}
			IHelpResource[] topics = entry.getTopics();
			if (topics.length == 1) {
				parent.showURL(topics[0].getHref());
			}
		} else if (obj instanceof IIndexSee) {
			IIndexSee see = (IIndexSee)obj;
			IIndexEntry[] entrys = IndexUtils.findSeeTargets(HelpSystem.getIndex(), see, 0);
			for (int i = 0; i < entrys.length; i++) {
				treeViewer.setExpandedState(entrys[i], true);
				treeViewer.setSelection(new StructuredSelection(entrys[i]), true);
			}
		}
	}

	protected boolean canAddBookmarks() {
		return true;
	}

	public void saveState(IMemento memento) {
	}

	public void toggleRoleFilter() {
		if (parent.isFilteredByRoles())
			treeViewer.addFilter(roleFilter);
		else
			treeViewer.removeFilter(roleFilter);
	}

	public void refilter() {
		treeViewer.refresh();
	}

	private Object[] getChildren(IIndexEntry entry) {
		/*
		 * Index entry has two types of children: topics and subentries.
		 * 
		 * The method returns topics among children only if number of the topics
		 * more than 1.
		 * 
		 * In case when the entry owns only one topic, this topic is not returned
		 * as child because the entry will represent this topic by its keyword.
		 */
		IHelpResource[] topics = entry.getTopics();
		IIndexEntry[] subentries = entry.getSubentries();
		IIndexSee[] sees = entry instanceof IIndexEntry2 ? ((IIndexEntry2)entry).getSees() : 
			               new IIndexSee[0];

		if (topics.length <= 1 && subentries.length == 0 && sees.length == 0) {
			// Entries with only one topic do not show children
			return new Object[0];
		}

		Object[] children = new Object[topics.length + subentries.length + sees.length];
		System.arraycopy(topics, 0, children, 0, topics.length);
		System.arraycopy(subentries, 0, children, topics.length, subentries.length);
		System.arraycopy(sees, 0, children, topics.length + subentries.length, sees.length);

		return children; 
	}
	

	protected Tree getTreeWidget() {
		return treeViewer.getTree();
	}
}
