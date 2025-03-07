/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.internal.ide.dialogs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.common.EventManager;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.jface.util.SafeRunnable;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ITreeViewerListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeExpansionEvent;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;

/**
 *	Workbench-level composite that combines a CheckboxTreeViewer and CheckboxListViewer.
 *	All viewer selection-driven interactions are handled within this object
 */
public class ResourceTreeAndListGroup extends EventManager implements
		ICheckStateListener, ISelectionChangedListener, ITreeViewerListener {
    private Object root;

    private Object currentTreeSelection;

    private Collection expandedTreeNodes = new HashSet();

    private Map checkedStateStore = new HashMap(9);

    private HashSet whiteCheckedTreeItems = new HashSet();

    private ITreeContentProvider treeContentProvider;

    private IStructuredContentProvider listContentProvider;

    private ILabelProvider treeLabelProvider;

    private ILabelProvider listLabelProvider;

    // widgets
    private CheckboxTreeViewer treeViewer;

    private CheckboxTableViewer listViewer;

    //height hint for viewers
    private static int PREFERRED_HEIGHT = 150;

    /**
     *	Create an instance of this class.  Use this constructor if you wish to specify
     *	the width and/or height of the combined widget (to only hardcode one of the
     *	sizing dimensions, specify the other dimension's value as -1)
     *  
     * @param parent
     * @param rootObject
     * @param treeContentProvider
     * @param treeLabelProvider
     * @param listContentProvider
     * @param listLabelProvider
     * @param style
     * @param useHeightHint If true then use the height hint
     *  to make this group big enough
     *
     */
    public ResourceTreeAndListGroup(Composite parent, Object rootObject,
            ITreeContentProvider treeContentProvider,
            ILabelProvider treeLabelProvider,
            IStructuredContentProvider listContentProvider,
            ILabelProvider listLabelProvider, int style, boolean useHeightHint) {

        root = rootObject;
        this.treeContentProvider = treeContentProvider;
        this.listContentProvider = listContentProvider;
        this.treeLabelProvider = treeLabelProvider;
        this.listLabelProvider = listLabelProvider;
        createContents(parent, style, useHeightHint);
    }

    /**
     * This method must be called just before this window becomes visible.
     */
    public void aboutToOpen() {
        determineWhiteCheckedDescendents(root);
        checkNewTreeElements(treeContentProvider.getElements(root));
        currentTreeSelection = null;

        //select the first element in the list
        Object[] elements = treeContentProvider.getElements(root);
        Object primary = elements.length > 0 ? elements[0] : null;
        if (primary != null) {
            treeViewer.setSelection(new StructuredSelection(primary));
        }
        treeViewer.getControl().setFocus();
    }

    /**
     *	Add the passed listener to self's collection of clients
     *	that listen for changes to element checked states
     *
     *	@param listener ICheckStateListener
     */
    public void addCheckStateListener(ICheckStateListener listener) {
        addListenerObject(listener);
    }

    /**
     *	Return a boolean indicating whether all children of the passed tree element
     *	are currently white-checked
     *
     *	@return boolean
     *	@param treeElement java.lang.Object
     */
    protected boolean areAllChildrenWhiteChecked(Object treeElement) {
        Object[] children = treeContentProvider.getChildren(treeElement);
        for (int i = 0; i < children.length; ++i) {
            if (!whiteCheckedTreeItems.contains(children[i])) {
				return false;
			}
        }

        return true;
    }

    /**
     *	Return a boolean indicating whether all list elements associated with
     *	the passed tree element are currently checked
     *
     *	@return boolean
     *	@param treeElement java.lang.Object
     */
    protected boolean areAllElementsChecked(Object treeElement) {
        List checkedElements = (List) checkedStateStore.get(treeElement);
        if (checkedElements == null) {
			return false;
		}

        return getListItemsSize(treeElement) == checkedElements.size();
    }

    /**
     *	Iterate through the passed elements which are being realized for the first
     *	time and check each one in the tree viewer as appropriate
     */
    protected void checkNewTreeElements(Object[] elements) {
        for (int i = 0; i < elements.length; ++i) {
            Object currentElement = elements[i];
            boolean checked = checkedStateStore.containsKey(currentElement);
            treeViewer.setChecked(currentElement, checked);
            treeViewer.setGrayed(currentElement, checked
                    && !whiteCheckedTreeItems.contains(currentElement));
        }
    }

    /**
     *	An item was checked in one of self's two views.  Determine which
     *	view this occurred in and delegate appropriately
     *
     *	@param event CheckStateChangedEvent
     */
    public void checkStateChanged(final CheckStateChangedEvent event) {

        //Potentially long operation - show a busy cursor
        BusyIndicator.showWhile(treeViewer.getControl().getDisplay(),
                new Runnable() {
                    public void run() {
                        if (event.getCheckable().equals(treeViewer)) {
							treeItemChecked(event.getElement(), event
                                    .getChecked());
						} else {
							listItemChecked(event.getElement(), event
                                    .getChecked(), true);
						}

                        notifyCheckStateChangeListeners(event);
                    }
                });
    }

    /**
     *	Lay out and initialize self's visual components.
     *
     *	@param parent org.eclipse.swt.widgets.Composite
     *  @param style the style flags for the new Composite
     *	@param useHeightHint If true yse the preferredHeight.
     */
    protected void createContents(Composite parent, int style,
            boolean useHeightHint) {
        // group pane
        Composite composite = new Composite(parent, style);
        composite.setFont(parent.getFont());
        GridLayout layout = new GridLayout();
        layout.numColumns = 2;
        layout.makeColumnsEqualWidth = true;
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(GridData.FILL_BOTH));

        createTreeViewer(composite, useHeightHint);
        createListViewer(composite, useHeightHint);

        initialize();
    }

    /**
     *	Create this group's list viewer.
     */
    protected void createListViewer(Composite parent, boolean useHeightHint) {
        listViewer = CheckboxTableViewer.newCheckList(parent, SWT.BORDER);
        GridData data = new GridData(GridData.FILL_BOTH);
        if (useHeightHint) {
			data.heightHint = PREFERRED_HEIGHT;
		}
        listViewer.getTable().setLayoutData(data);
        listViewer.getTable().setFont(parent.getFont());
        listViewer.setContentProvider(listContentProvider);
        listViewer.setLabelProvider(listLabelProvider);
        listViewer.addCheckStateListener(this);
    }

    /**
     *	Create this group's tree viewer.
     */
    protected void createTreeViewer(Composite parent, boolean useHeightHint) {
        Tree tree = new Tree(parent, SWT.CHECK | SWT.BORDER);
        GridData data = new GridData(GridData.FILL_BOTH);
        if (useHeightHint) {
			data.heightHint = PREFERRED_HEIGHT;
		}
        tree.setLayoutData(data);
        tree.setFont(parent.getFont());

        treeViewer = new CheckboxTreeViewer(tree);
        treeViewer.setContentProvider(treeContentProvider);
        treeViewer.setLabelProvider(treeLabelProvider);
        treeViewer.addTreeListener(this);
        treeViewer.addCheckStateListener(this);
        treeViewer.addSelectionChangedListener(this);
    }

    /**
     * Returns a boolean indicating whether the passed tree element should be
     * at LEAST gray-checked.  Note that this method does not consider whether
     * it should be white-checked, so a specified tree item which should be
     * white-checked will result in a <code>true</code> answer from this method.
     * To determine whether a tree item should be white-checked use method
     * #determineShouldBeWhiteChecked(Object).
     *
     * @param treeElement java.lang.Object
     * @return boolean
     * @see #determineShouldBeWhiteChecked(Object)
     */
    protected boolean determineShouldBeAtLeastGrayChecked(Object treeElement) {
        // if any list items associated with treeElement are checked then it
        // retains its gray-checked status regardless of its children
        List checked = (List) checkedStateStore.get(treeElement);
        if (checked != null && (!checked.isEmpty())) {
			return true;
		}

        // if any children of treeElement are still gray-checked then treeElement
        // must remain gray-checked as well. Only ask expanded nodes
        if (expandedTreeNodes.contains(treeElement)) {
            Object[] children = treeContentProvider.getChildren(treeElement);
            for (int i = 0; i < children.length; ++i) {
                if (checkedStateStore.containsKey(children[i])) {
					return true;
				}
            }
        }

        return false;
    }

    /**
     * Returns a boolean indicating whether the passed tree item should be
     * white-checked.
     *
     * @return boolean
     * @param treeElement java.lang.Object
     */
    protected boolean determineShouldBeWhiteChecked(Object treeElement) {
        return areAllChildrenWhiteChecked(treeElement)
                && areAllElementsChecked(treeElement);
    }

    /**
     *	Recursively add appropriate tree elements to the collection of
     *	known white-checked tree elements.
     *
     *	@param treeElement java.lang.Object
     */
    protected void determineWhiteCheckedDescendents(Object treeElement) {
        // always go through all children first since their white-checked
        // statuses will be needed to determine the white-checked status for
        // this tree element
        Object[] children = treeContentProvider.getElements(treeElement);
        for (int i = 0; i < children.length; ++i) {
			determineWhiteCheckedDescendents(children[i]);
		}

        // now determine the white-checked status for this tree element
        if (determineShouldBeWhiteChecked(treeElement)) {
			setWhiteChecked(treeElement, true);
		}
    }

    /**
     * Cause the tree viewer to expand all its items
     */
    public void expandAll() {
        treeViewer.expandAll();
    }

    /**
     *	Expand an element in a tree viewer
     */
    private void expandTreeElement(final Object item) {
        BusyIndicator.showWhile(treeViewer.getControl().getDisplay(),
                new Runnable() {
                    public void run() {

                        // First see if the children need to be given their checked state at all.  If they've
                        // already been realized then this won't be necessary
                        if (expandedTreeNodes.contains(item)) {
							checkNewTreeElements(treeContentProvider
                                    .getChildren(item));
						} else {

                            expandedTreeNodes.add(item);
                            if (whiteCheckedTreeItems.contains(item)) {
                                //If this is the first expansion and this is a white checked node then check the children
                                Object[] children = treeContentProvider
                                        .getChildren(item);
                                for (int i = 0; i < children.length; ++i) {
                                    if (!whiteCheckedTreeItems
                                            .contains(children[i])) {
                                        Object child = children[i];
                                        setWhiteChecked(child, true);
                                        treeViewer.setChecked(child, true);
                                        checkedStateStore.put(child,
                                                new ArrayList());
                                    }
                                }

                                //Now be sure to select the list of items too
                                setListForWhiteSelection(item);
                            }
                        }

                    }
                });
    }

    /**
     * Add all of the selected children of nextEntry to result recursively.
     * This does not set any values in the checked state.
     * @param treeElement The tree elements being queried
     * @param addAll a boolean to indicate if the checked state store needs to be queried
     * @param filter IElementFilter - the filter being used on the data
     * @param monitor IProgressMonitor or null that the cancel is polled for 
     */
    private void findAllSelectedListElements(Object treeElement,
            String parentLabel, boolean addAll, IElementFilter filter,
            IProgressMonitor monitor) throws InterruptedException {

        String fullLabel = null;
        if (monitor != null && monitor.isCanceled()) {
			return;
		}
        if (monitor != null) {
            fullLabel = getFullLabel(treeElement, parentLabel);
            monitor.subTask(fullLabel);
        }

        if (addAll) {
			filter.filterElements(listContentProvider.getElements(treeElement),
                    monitor);
		} else { //Add what we have stored
            if (checkedStateStore.containsKey(treeElement)) {
				filter.filterElements((Collection) checkedStateStore
                        .get(treeElement), monitor);
			}
        }

        Object[] treeChildren = treeContentProvider.getChildren(treeElement);
        for (int i = 0; i < treeChildren.length; i++) {
            Object child = treeChildren[i];
            if (addAll) {
				findAllSelectedListElements(child, fullLabel, true, filter,
                        monitor);
			} else { //Only continue for those with checked state
                if (checkedStateStore.containsKey(child)) {
					findAllSelectedListElements(child, fullLabel,
                            whiteCheckedTreeItems.contains(child), filter,
                            monitor);
				}
            }

        }
    }

    /**
     * Find all of the white checked children of the treeElement and add them to the collection.
     * If the element itself is white select add it. If not then add any selected list elements
     * and recurse down to the children.
     * @param treeElement java.lang.Object
     * @param result java.util.Collection
     */
    private void findAllWhiteCheckedItems(Object treeElement, Collection result) {

        if (whiteCheckedTreeItems.contains(treeElement)) {
			result.add(treeElement);
		} else {
            Collection listChildren = (Collection) checkedStateStore
                    .get(treeElement);
            //if it is not in the store then it and it's children are not interesting
            if (listChildren == null) {
				return;
			}
            result.addAll(listChildren);
            Object[] children = treeContentProvider.getChildren(treeElement);
            for (int i = 0; i < children.length; ++i) {
                findAllWhiteCheckedItems(children[i], result);
            }
        }
    }

    /**
	 * Returns a flat list of all of the leaf elements which are checked. Filter
	 * then based on the supplied ElementFilter. If monitor is cancelled then
	 * return null
	 * 
	 * @param filter -
	 *            the filter for the data
	 * @param monitor
	 *            IProgressMonitor or null
	 * @throws InterruptedException
	 *             If the find is interrupted.
	 */
    public void getAllCheckedListItems(IElementFilter filter,
            IProgressMonitor monitor) throws InterruptedException {

        //Iterate through the children of the root as the root is not in the store
        Object[] children = treeContentProvider.getChildren(root);
        for (int i = 0; i < children.length; ++i) {
            findAllSelectedListElements(children[i], null,
                    whiteCheckedTreeItems.contains(children[i]), filter,
                    monitor);
        }
    }

    /** Returns whether all items in the list are checked.
     * This method is required, because this widget will keep items grey
     * checked even though all children are selected (see grayUpdateHierarchy()).
     * @return true if all items in the list are checked - false if not
     */
    public boolean isEveryItemChecked() {
        //Iterate through the children of the root as the root is not in the store
    	Object[] children = treeContentProvider.getChildren(root);
        for (int i = 0; i < children.length; ++i) {
        	if (!whiteCheckedTreeItems.contains(children[i])) {
                if (!treeViewer.getGrayed(children[i]))
                	return false;
        		if (!isEveryChildrenChecked(children[i]))
        			return false;
        	}
        }
        return true;
    }

	/**Verifies of all list items of the tree element are checked, and 
	 * if all children are white checked.  If not, verify their children
	 * so that if an element is not white checked, but all its children
	 * are while checked, then, all items are considered checked.
	 * @param treeElement the treeElement which status to verify
	 * @return true if all items are checked, false otherwise.
	 */
	private boolean isEveryChildrenChecked(Object treeElement) {
        List checked = (List) checkedStateStore.get(treeElement);
        if (checked != null && (!checked.isEmpty())) {
            Object[] listItems = listContentProvider.getElements(treeElement);
        	if (listItems.length != checked.size())
        		return false;
		}
    	Object[] children = treeContentProvider.getChildren(treeElement);
        for (int i = 0; i < children.length; ++i) {
        	if (!whiteCheckedTreeItems.contains(children[i])) {
                if (!treeViewer.getGrayed(children[i]))
                	return false;
        		if (!isEveryChildrenChecked(children[i]))
        			return false;
        	}
        }
		return true;
	}

	/**
     *	Returns a flat list of all of the leaf elements which are checked.
     *
     *	@return all of the leaf elements which are checked. This API does not
     * 	return null in order to keep backwards compatibility.
     */
    public List getAllCheckedListItems() {

        final ArrayList returnValue = new ArrayList();

        IElementFilter passThroughFilter = new IElementFilter() {

            public void filterElements(Collection elements,
                    IProgressMonitor monitor) {
                returnValue.addAll(elements);
            }

            public void filterElements(Object[] elements,
                    IProgressMonitor monitor) {
                for (int i = 0; i < elements.length; i++) {
                    returnValue.add(elements[i]);
                }
            }
        };

        try {
            getAllCheckedListItems(passThroughFilter, null);
        } catch (InterruptedException exception) {
            return new ArrayList();
        }
        return returnValue;

    }

    /**
     *	Returns a flat list of all of the leaf elements.
     *
     *	@return all of the leaf elements.
     */
    public List getAllListItems() {

        final ArrayList returnValue = new ArrayList();

        IElementFilter passThroughFilter = new IElementFilter() {

            public void filterElements(Collection elements,
                    IProgressMonitor monitor) {
                returnValue.addAll(elements);
            }

            public void filterElements(Object[] elements,
                    IProgressMonitor monitor) {
                for (int i = 0; i < elements.length; i++) {
                    returnValue.add(elements[i]);
                }
            }
        };

        try {
            Object[] children = treeContentProvider.getChildren(root);
            for (int i = 0; i < children.length; ++i) {
                findAllSelectedListElements(children[i], null, true, passThroughFilter,
                        null);
            }
        } catch (InterruptedException exception) {
            return new ArrayList();
        }
        return returnValue;

    }

    /**
     *	Returns a list of all of the items that are white checked.
     * 	Any folders that are white checked are added and then any files
     *  from white checked folders are added. 
     *
     *	@return the list of all of the items that are white checked
     */
    public List getAllWhiteCheckedItems() {

        List result = new ArrayList();

        //Iterate through the children of the root as the root is not in the store
        Object[] children = treeContentProvider.getChildren(root);
        for (int i = 0; i < children.length; ++i) {
            findAllWhiteCheckedItems(children[i], result);
        }

        return result;
    }

    /**
     *	Answer the number of elements that have been checked by the
     *	user.
     *
     *	@return int
     */
    public int getCheckedElementCount() {
        return checkedStateStore.size();
    }

    /**
     * Get the full label of the treeElement (its name and its parent's name).
     * @param treeElement - the element being exported
     * @param parentLabel - the label of the parent, can be null
     * @return String
     */
    protected String getFullLabel(Object treeElement, String parentLabel) {
        String label = parentLabel;
        if (parentLabel == null){
        	label = ""; //$NON-NLS-1$
        }
        IPath parentName = new Path(label);

        String elementText = treeLabelProvider.getText(treeElement);
        if(elementText == null) {
			return parentName.toString();
		}
        return parentName.append(elementText).toString();
    }

    /**
     *	Return a count of the number of list items associated with a
     *	given tree item.
     *
     *	@return int
     *	@param treeElement java.lang.Object
     */
    protected int getListItemsSize(Object treeElement) {
        Object[] elements = listContentProvider.getElements(treeElement);
        return elements.length;
    }

    /**
     * Get the table the list viewer uses.
     * @return org.eclipse.swt.widgets.Table
     */
    public Table getListTable() {
        return this.listViewer.getTable();
    }

    /**
     *	Logically gray-check all ancestors of treeItem by ensuring that they
     *	appear in the checked table
     */
    protected void grayCheckHierarchy(Object treeElement) {

        //expand the element first to make sure we have populated for it
        expandTreeElement(treeElement);

        // if this tree element is already gray then its ancestors all are as well
        if (checkedStateStore.containsKey(treeElement)) {
			return; // no need to proceed upwards from here
		}

        checkedStateStore.put(treeElement, new ArrayList());
        Object parent = treeContentProvider.getParent(treeElement);
        if (parent != null) {
			grayCheckHierarchy(parent);
		}
    }

    /**
     *	Set the checked state of self and all ancestors appropriately. Do not white check anyone - this is
     *  only done down a hierarchy.
     */
    private void grayUpdateHierarchy(Object treeElement) {

        boolean shouldBeAtLeastGray = determineShouldBeAtLeastGrayChecked(treeElement);

        treeViewer.setGrayChecked(treeElement, shouldBeAtLeastGray);

        if (whiteCheckedTreeItems.contains(treeElement)) {
			whiteCheckedTreeItems.remove(treeElement);
		}

        // proceed up the tree element hierarchy
        Object parent = treeContentProvider.getParent(treeElement);
        if (parent != null) {
            grayUpdateHierarchy(parent);
        }
    }

    /**
     *	Set the initial checked state of the passed list element to true.
     * @param element
     */
    public void initialCheckListItem(Object element) {
        Object parent = treeContentProvider.getParent(element);
        selectAndReveal(parent);
        //Check the element in the viewer as if it had been manually checked
        listViewer.setChecked(element, true);
        //As this is not done from the UI then set the box for updating from the selection to false 
        listItemChecked(element, true, false);
        grayUpdateHierarchy(parent);
    }

    /**
     * Set the initial checked state of the passed element to true,
     * as well as to all of its children and associated list elements
     * @param element
     */
    public void initialCheckTreeItem(Object element) {
        treeItemChecked(element, true);
        selectAndReveal(element);
    }

    private void selectAndReveal(Object treeElement) {
        treeViewer.reveal(treeElement);
        IStructuredSelection selection = new StructuredSelection(treeElement);
        treeViewer.setSelection(selection);
    }

    /**
     *	Initialize this group's viewers after they have been laid out.
     */
    protected void initialize() {
        treeViewer.setInput(root);
        this.expandedTreeNodes = new ArrayList();
        this.expandedTreeNodes.add(root);

    }

    /**
     *	Callback that's invoked when the checked status of an item in the list
     *	is changed by the user. Do not try and update the hierarchy if we are building the
     *  initial list.
     */
    protected void listItemChecked(Object listElement, boolean state,
            boolean updatingFromSelection) {
        List checkedListItems = (List) checkedStateStore
                .get(currentTreeSelection);
        //If it has not been expanded do so as the selection of list items will affect gray state
        if (!expandedTreeNodes.contains(currentTreeSelection)) {
			expandTreeElement(currentTreeSelection);
		}

        if (state) {
            if (checkedListItems == null) {
                // since the associated tree item has gone from 0 -> 1 checked
                // list items, tree checking may need to be updated
                grayCheckHierarchy(currentTreeSelection);
                checkedListItems = (List) checkedStateStore
                        .get(currentTreeSelection);
            }
            checkedListItems.add(listElement);
        } else {
            checkedListItems.remove(listElement);
            if (checkedListItems.isEmpty()) {
                // since the associated tree item has gone from 1 -> 0 checked
                // list items, tree checking may need to be updated
                ungrayCheckHierarchy(currentTreeSelection);
            }
        }

        //Update the list with the selections if there are any
        if (checkedListItems.size() > 0) {
			checkedStateStore.put(currentTreeSelection, checkedListItems);
		}
        if (updatingFromSelection) {
			grayUpdateHierarchy(currentTreeSelection);
		}
    }

    /**
     *	Notify all checked state listeners that the passed element has had
     *	its checked state changed to the passed state
     */
    protected void notifyCheckStateChangeListeners(
            final CheckStateChangedEvent event) {
        Object[] array = getListeners();
        for (int i = 0; i < array.length; i++) {
            final ICheckStateListener l = (ICheckStateListener) array[i];
            SafeRunner.run(new SafeRunnable() {
                public void run() {
                    l.checkStateChanged(event);
                }
            });
        }
    }

    /**
     *Set the contents of the list viewer based upon the specified selected
     *tree element.  This also includes checking the appropriate list items.
     *
     *@param treeElement java.lang.Object
     */
    protected void populateListViewer(final Object treeElement) {
        listViewer.setInput(treeElement);

        //If the element is white checked but not expanded we have not set up all of the children yet
        if (!(expandedTreeNodes.contains(treeElement))
                && whiteCheckedTreeItems.contains(treeElement)) {

            //Potentially long operation - show a busy cursor
            BusyIndicator.showWhile(treeViewer.getControl().getDisplay(),
                    new Runnable() {
                        public void run() {
                            setListForWhiteSelection(treeElement);
                            listViewer.setAllChecked(true);
                        }
                    });

        } else {
            List listItemsToCheck = (List) checkedStateStore.get(treeElement);

            if (listItemsToCheck != null) {
                Iterator listItemsEnum = listItemsToCheck.iterator();
                while (listItemsEnum.hasNext()) {
					listViewer.setChecked(listItemsEnum.next(), true);
				}
            }
        }
    }

    /**
     *	Logically gray-check all ancestors of treeItem by ensuring that they
     *	appear in the checked table. Add any elements to the selectedNodes
     *  so we can track that has been done.
     */
    private void primeHierarchyForSelection(Object item, Set selectedNodes) {

        //Only prime it if we haven't visited yet
        if (selectedNodes.contains(item)) {
			return;
		}

        checkedStateStore.put(item, new ArrayList());

        //mark as expanded as we are going to populate it after this
        expandedTreeNodes.add(item);
        selectedNodes.add(item);

        Object parent = treeContentProvider.getParent(item);
        if (parent != null) {
			primeHierarchyForSelection(parent, selectedNodes);
		}
    }

    /**
     *	Remove the passed listener from self's collection of clients
     *	that listen for changes to element checked states
     *
     *	@param listener ICheckStateListener
     */
    public void removeCheckStateListener(ICheckStateListener listener) {
        removeListenerObject(listener);
    }

    /**
     *	Handle the selection of an item in the tree viewer
     *
     *	@param event SelectionChangedEvent
     */
    public void selectionChanged(SelectionChangedEvent event) {
        IStructuredSelection selection = (IStructuredSelection) event
                .getSelection();
        Object selectedElement = selection.getFirstElement();
        if (selectedElement == null) {
            currentTreeSelection = null;
            listViewer.setInput(currentTreeSelection);
            return;
        }

        // ie.- if not an item deselection
        if (selectedElement != currentTreeSelection) {
			populateListViewer(selectedElement);
		}

        currentTreeSelection = selectedElement;
    }

    /**
     * Select or deselect all of the elements in the tree depending on the value of the selection
     * boolean. Be sure to update the displayed files as well.
     * @param selection
     */
    public void setAllSelections(final boolean selection) {

        //If there is no root there is nothing to select
        if (root == null) {
			return;
		}

        //Potentially long operation - show a busy cursor
        BusyIndicator.showWhile(treeViewer.getControl().getDisplay(),
                new Runnable() {
                    public void run() {
                        setTreeChecked(root, selection);
                        listViewer.setAllChecked(selection);
                    }
                });
    }

    /**
     * The treeElement has been white selected. Get the list for the element and
     * set it in the checked state store.
     * @param treeElement the element being updated
     */
    private void setListForWhiteSelection(Object treeElement) {

        Object[] listItems = listContentProvider.getElements(treeElement);
        List listItemsChecked = new ArrayList();
        for (int i = 0; i < listItems.length; ++i) {
            listItemsChecked.add(listItems[i]);
        }

        checkedStateStore.put(treeElement, listItemsChecked);
    }

    /**
     *	Set the list viewer's providers to those passed
     *
     *	@param contentProvider ITreeContentProvider
     *	@param labelProvider ILabelProvider
     */
    public void setListProviders(IStructuredContentProvider contentProvider,
            ILabelProvider labelProvider) {
        listViewer.setContentProvider(contentProvider);
        listViewer.setLabelProvider(labelProvider);
    }

    /**
     * Set the comparator that is to be applied to self's list viewer
     * 
     * @param comparator the sorter for the list
     */
    public void setListComparator(ViewerComparator comparator) {
        listViewer.setComparator(comparator);
    }

    /**
     * Set the root of the widget to be new Root. Regenerate all of the tables and lists from this
     * value.
     * @param newRoot 
     */
    public void setRoot(Object newRoot) {
        this.root = newRoot;
        initialize();
    }

    /**
     *	Set the checked state of the passed tree element appropriately, and
     *	do so recursively to all of its child tree elements as well
     */
    protected void setTreeChecked(Object treeElement, boolean state) {

        if (treeElement.equals(currentTreeSelection)) {
            listViewer.setAllChecked(state);
        }

        if (state) {
            setListForWhiteSelection(treeElement);
        } else {
			checkedStateStore.remove(treeElement);
		}

        setWhiteChecked(treeElement, state);
        treeViewer.setChecked(treeElement, state);
        treeViewer.setGrayed(treeElement, false);

        // now logically check/uncheck all children as well if it has been expanded
        if (expandedTreeNodes.contains(treeElement)) {
            Object[] children = treeContentProvider.getChildren(treeElement);
            for (int i = 0; i < children.length; ++i) {
                setTreeChecked(children[i], state);
            }
        }
    }

    /**
     *	Set the tree viewer's providers to those passed
     *
     *	@param contentProvider ITreeContentProvider
     *	@param labelProvider ILabelProvider
     */
    public void setTreeProviders(ITreeContentProvider contentProvider,
            ILabelProvider labelProvider) {
        treeViewer.setContentProvider(contentProvider);
        treeViewer.setLabelProvider(labelProvider);
    }

    /**
     * Set the comparator that is to be applied to self's tree viewer
     * 
     * @param comparator the comparator for the tree
     */
    public void setTreeComparator(ViewerComparator comparator) {
        treeViewer.setComparator(comparator);
    }

    /**
     *	Adjust the collection of references to white-checked tree elements appropriately.
     *
     *	@param treeElement java.lang.Object
     *	@param isWhiteChecked boolean
     */
    protected void setWhiteChecked(Object treeElement, boolean isWhiteChecked) {
        if (isWhiteChecked) {
            if (!whiteCheckedTreeItems.contains(treeElement)) {
				whiteCheckedTreeItems.add(treeElement);
			}
        } else {
			whiteCheckedTreeItems.remove(treeElement);
		}
    }

    /**
     *	Handle the collapsing of an element in a tree viewer
     */
    public void treeCollapsed(TreeExpansionEvent event) {
        // We don't need to do anything with this
    }

    /**
     *	Handle the expansionsion of an element in a tree viewer
     */
    public void treeExpanded(TreeExpansionEvent event) {
        expandTreeElement(event.getElement());
    }

    /**
     *  Callback that's invoked when the checked status of an item in the tree
     *  is changed by the user.
     */
    protected void treeItemChecked(Object treeElement, boolean state) {

        // recursively adjust all child tree elements appropriately
        setTreeChecked(treeElement, state);

        Object parent = treeContentProvider.getParent(treeElement);
        
        // workspace root is not shown in the tree, so ignore it
        if (parent == null || parent instanceof IWorkspaceRoot) {
			return;
		}

        // now update upwards in the tree hierarchy 
        if (state) {
			grayCheckHierarchy(parent);
		} else {
			ungrayCheckHierarchy(parent);
		}

        //Update the hierarchy but do not white select the parent
        grayUpdateHierarchy(parent);
    }

    /**
     *	Logically un-gray-check all ancestors of treeItem iff appropriate.
     */
    protected void ungrayCheckHierarchy(Object treeElement) {
        if (!determineShouldBeAtLeastGrayChecked(treeElement)) {
			checkedStateStore.remove(treeElement);
		}

        Object parent = treeContentProvider.getParent(treeElement);
        if (parent != null) {
			ungrayCheckHierarchy(parent);
		}
    }

    /**
     *	Set the checked state of self and all ancestors appropriately
     */
    protected void updateHierarchy(Object treeElement) {

        boolean whiteChecked = determineShouldBeWhiteChecked(treeElement);
        boolean shouldBeAtLeastGray = determineShouldBeAtLeastGrayChecked(treeElement);

        treeViewer.setChecked(treeElement, shouldBeAtLeastGray);
        setWhiteChecked(treeElement, whiteChecked);
        if (whiteChecked) {
			treeViewer.setGrayed(treeElement, false);
		} else {
			treeViewer.setGrayed(treeElement, shouldBeAtLeastGray);
		}

        // proceed up the tree element hierarchy but gray select all of them
        Object parent = treeContentProvider.getParent(treeElement);
        if (parent != null) {
            grayUpdateHierarchy(parent);
        }
    }

    /**
     * Update the selections of the tree elements in items to reflect the new
     * selections provided.
     * @param items Map with keys of Object (the tree element) and values of List (the selected
     * list elements).  
     * NOTE: This method does not special case keys with no values (i.e., 
     * a tree element with an empty list).  If a tree element does not have any selected
     * items, do not include the element in the Map.
     */
    public void updateSelections(Map items) {
        // We are replacing all selected items with the given selected items,
        // so reinitialize everything.
        this.listViewer.setAllChecked(false);
        this.treeViewer.setCheckedElements(new Object[0]);
        this.whiteCheckedTreeItems = new HashSet();
        Set selectedNodes = new HashSet();
        checkedStateStore = new HashMap();

        //Update the store before the hierarchy to prevent updating parents before all of the children are done
        Iterator keyIterator = items.keySet().iterator();
        while (keyIterator.hasNext()) {
            Object key = keyIterator.next();
            List selections = (List) items.get(key);
            //Replace the items in the checked state store with those from the supplied items			
            checkedStateStore.put(key, selections);
            selectedNodes.add(key);
            // proceed up the tree element hierarchy
            Object parent = treeContentProvider.getParent(key);
            if (parent != null) {
                // proceed up the tree element hierarchy and make sure everything is in the table		
                primeHierarchyForSelection(parent, selectedNodes);
            }
        }

        // Update the checked tree items.  Since each tree item has a selected
        // item, all the tree items will be gray checked.
        treeViewer.setCheckedElements(checkedStateStore.keySet().toArray());
        treeViewer.setGrayedElements(checkedStateStore.keySet().toArray());

        // Update the listView of the currently selected tree item.
        if (currentTreeSelection != null) {
            Object displayItems = items.get(currentTreeSelection);
            if (displayItems != null) {
				listViewer.setCheckedElements(((List) displayItems).toArray());
			}
        }
    }

    /** 
     * Set the focus on to the list widget.
     */
    public void setFocus() {

        this.treeViewer.getTree().setFocus();
    }

}

