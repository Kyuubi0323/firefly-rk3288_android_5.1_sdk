/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     manklu@web.de - fix for bug 156082
 *     Bert Vingerhoets - fix for bug 169975
 *     Serge Beauchamp (Freescale Semiconductor) - [229633] Fix Concurency Exception
 *******************************************************************************/
package org.eclipse.core.internal.resources;

import org.eclipse.core.runtime.CoreException;

import java.net.URI;
import java.util.*;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.internal.events.ILifecycleListener;
import org.eclipse.core.internal.events.LifecycleEvent;
import org.eclipse.core.internal.localstore.FileSystemResourceManager;
import org.eclipse.core.internal.utils.Messages;
import org.eclipse.core.internal.utils.Policy;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.util.NLS;

/**
 * An alias is a resource that occupies the same file system location as another
 * resource in the workspace.  When a resource is modified in a way that affects
 * the file on disk, all aliases need to be updated.  This class is used to
 * maintain data structures for quickly computing the set of aliases for a given
 * resource, and for efficiently updating all aliases when a resource changes on
 * disk.
 * 
 * The approach for computing aliases is optimized for alias-free workspaces and
 * alias-free projects.  That is, if the workspace contains no aliases, then
 * updating should be very quick.  If a resource is changed in a project that
 * contains no aliases, it should also be very fast.
 * 
 * The data structures maintained by the alias manager can be seen as a cache,
 * that is, they store no information that cannot be recomputed from other
 * available information.  On shutdown, the alias manager discards all state; on
 * startup, the alias manager eagerly rebuilds its state.  The reasoning is
 * that it's better to incur this cost on startup than on the first attempt to
 * modify a resource.  After startup, the state is updated incrementally on the
 * following occasions: 
 *  -  when projects are deleted, opened, closed, or moved 
 *  - when linked resources are created, deleted, or moved.
 */
public class AliasManager implements IManager, ILifecycleListener, IResourceChangeListener {
	public class AddToCollectionDoit implements Doit {
		Collection collection;

		public void doit(IResource resource) {
			collection.add(resource);
		}

		public void setCollection(Collection collection) {
			this.collection = collection;
		}
	}

	interface Doit {
		public void doit(IResource resource);
	}

	class FindAliasesDoit implements Doit {
		private int aliasType;
		private IPath searchPath;

		public void doit(IResource match) {
			//don't record the resource we're computing aliases against as a match
			if (match.getFullPath().isPrefixOf(searchPath))
				return;
			IPath aliasPath = null;
			switch (match.getType()) {
				case IResource.PROJECT :
					//first check if there is a linked resource that blocks the project location
					if (suffix.segmentCount() > 0) {
						IResource testResource = ((IProject) match).findMember(suffix.segment(0));
						if (testResource != null && testResource.isLinked())
							return;
					}
					//there is an alias under this project
					aliasPath = match.getFullPath().append(suffix);
					break;
				case IResource.FOLDER :
					aliasPath = match.getFullPath().append(suffix);
					break;
				case IResource.FILE :
					if (suffix.segmentCount() == 0)
						aliasPath = match.getFullPath();
					break;
			}
			if (aliasPath != null)
				if (aliasType == IResource.FILE) {
					aliases.add(workspace.getRoot().getFile(aliasPath));
				} else {
					if (aliasPath.segmentCount() == 1)
						aliases.add(workspace.getRoot().getProject(aliasPath.lastSegment()));
					else
						aliases.add(workspace.getRoot().getFolder(aliasPath));
				}
		}

		/**
		 * Sets the resource that we are searching for aliases for.
		 */
		public void setSearchAlias(IResource aliasResource) {
			this.aliasType = aliasResource.getType();
			this.searchPath = aliasResource.getFullPath();
		}
	}

	/**
	 * Maintains a mapping of FileStore->IResource, such that multiple resources
	 * mapped from the same location are tolerated.
	 */
	class LocationMap {
		/**
		 * Map of FileStore->IResource OR FileStore->ArrayList of (IResource)
		 */
		private final SortedMap map = new TreeMap(getComparator());

		/**
		 * Adds the given resource to the map, keyed by the given location.
		 * Returns true if a new entry was added, and false otherwise.
		 */
		public boolean add(IFileStore location, IResource resource) {
			Object oldValue = map.get(location);
			if (oldValue == null) {
				map.put(location, resource);
				return true;
			}
			if (oldValue instanceof IResource) {
				if (resource.equals(oldValue))
					return false;//duplicate
				ArrayList newValue = new ArrayList(2);
				newValue.add(oldValue);
				newValue.add(resource);
				map.put(location, newValue);
				return true;
			}
			ArrayList list = (ArrayList) oldValue;
			if (list.contains(resource))
				return false;//duplicate
			list.add(resource);
			return true;
		}

		/**
		 * Method clear.
		 */
		public void clear() {
			map.clear();
		}

		/**
		 * Invoke the given doit for every resource whose location has the
		 * given location as a prefix.
		 */
		public void matchingPrefixDo(IFileStore prefix, Doit doit) {
			SortedMap matching;
			IFileStore prefixParent = prefix.getParent();
			if (prefixParent != null) {
				//endPoint is the smallest possible path greater than the prefix that doesn't
				//match the prefix
				IFileStore endPoint = prefixParent.getChild(prefix.getName() + "\0"); //$NON-NLS-1$
				matching = map.subMap(prefix, endPoint);
			} else {
				matching = map;
			}
			for (Iterator it = matching.values().iterator(); it.hasNext();) {
				Object value = it.next();
				if (value == null)
					return;
				if (value instanceof List) {
					Iterator duplicates = ((List) value).iterator();
					while (duplicates.hasNext())
						doit.doit((IResource) duplicates.next());
				} else {
					doit.doit((IResource) value);
				}
			}
		}

		/**
		 * Invoke the given doit for every resource that matches the given
		 * location.
		 */
		public void matchingResourcesDo(IFileStore location, Doit doit) {
			Object value = map.get(location);
			if (value == null)
				return;
			if (value instanceof List) {
				Iterator duplicates = ((List) value).iterator();
				while (duplicates.hasNext())
					doit.doit((IResource) duplicates.next());
			} else {
				doit.doit((IResource) value);
			}
		}

		/**
		 * Calls the given doit with the project of every resource in the map
		 * whose location overlaps another resource in the map.
		 */
		public void overLappingResourcesDo(Doit doit) {
			Iterator entries = map.entrySet().iterator();
			IFileStore previousStore = null;
			IResource previousResource = null;
			while (entries.hasNext()) {
				Map.Entry current = (Map.Entry) entries.next();
				//value is either single resource or List of resources
				IFileStore currentStore = (IFileStore) current.getKey();
				IResource currentResource = null;
				Object value = current.getValue();
				if (value instanceof List) {
					//if there are several then they're all overlapping
					Iterator duplicates = ((List) value).iterator();
					while (duplicates.hasNext())
						doit.doit(((IResource) duplicates.next()).getProject());
				} else {
					//value is a single resource
					currentResource = (IResource) value;
				}
				if (previousStore != null) {
					//check for overlap with previous
					//Note: previous is always shorter due to map sorting rules
					if (previousStore.isParentOf(currentStore)) {
						//resources will be null if they were in a list, in which case 
						//they've already been passed to the doit
						if (previousResource != null) {
							doit.doit(previousResource.getProject());
							//null out previous resource so we don't call doit twice with same resource
							previousResource = null;
						}
						if (currentResource != null)
							doit.doit(currentResource.getProject());
						//keep iterating with the same previous store because there may be more overlaps
						continue;
					}
				}
				previousStore = currentStore;
				previousResource = currentResource;
			}
		}

		/**
		 * Removes the given location from the map.  Returns true if anything
		 * was actually removed, and false otherwise.
		 */
		public boolean remove(IFileStore location, IResource resource) {
			Object oldValue = map.get(location);
			if (oldValue == null)
				return false;
			if (oldValue instanceof IResource) {
				if (resource.equals(oldValue)) {
					map.remove(location);
					return true;
				}
				return false;
			}
			ArrayList list = (ArrayList) oldValue;
			boolean wasRemoved = list.remove(resource);
			if (list.size() == 0)
				map.remove(location);
			return wasRemoved;
		}
	}

	/**
	 * Doit convenience class for adding items to a list
	 */
	private final AddToCollectionDoit addToCollection = new AddToCollectionDoit();

	/**
	 * The set of IProjects that have aliases.
	 */
	protected final Set aliasedProjects = new HashSet();

	/**
	 * A temporary set of aliases.  Used during computeAliases, but maintained
	 * as a field as an optimization to prevent recreating the set.
	 */
	protected final HashSet aliases = new HashSet();

	/**
	 * The set of resources that have had structure changes that might
	 * invalidate the locations map or aliased projects set.  These will be
	 * updated incrementally on the next alias request.
	 */
	private final Set changedLinks = new HashSet();

	/**
	 * This flag is true when projects have been created or deleted and the
	 * location map has not been updated accordingly.
	 */
	private boolean changedProjects = false;

	/**
	 * The Doit class used for finding aliases.
	 */
	private final FindAliasesDoit findAliases = new FindAliasesDoit();

	/**
	 * This maps IFileStore ->IResource, associating a file system location
	 * with the projects and/or linked resources that are rooted at that location.
	 */
	protected final LocationMap locationsMap = new LocationMap();
	/**
	 * The total number of resources in the workspace that are not in the default
	 * location. This includes all linked resources, including linked resources
	 * that don't currently have valid locations due to an undefined path variable.
	 * This also includes projects that are not in their default location.
	 * This value is used as a quick optimization, because a workspace with
	 * all resources in their default locations cannot have any aliases.
	 */
	private int nonDefaultResourceCount = 0;

	/**
	 * The suffix object is also used only during the computeAliases method.
	 * In this case it is a field because it is referenced from an inner class
	 * and we want to avoid creating a pointer array.  It is public to eliminate
	 * the need for synthetic accessor methods.
	 */
	public IPath suffix;

	/** the workspace */
	protected final Workspace workspace;

	public AliasManager(Workspace workspace) {
		this.workspace = workspace;
	}

	private void addToLocationsMap(IProject project) {
		IFileStore location = ((Resource) project).getStore();
		if (location != null)
			locationsMap.add(location, project);
		ProjectDescription description = ((Project) project).internalGetDescription();
		if (description == null)
			return;
		if (description.getLocationURI() != null)
			nonDefaultResourceCount++;
		HashMap links = description.getLinks();
		if (links == null)
			return;
		for (Iterator it = links.values().iterator(); it.hasNext();) {
			LinkDescription linkDesc = (LinkDescription) it.next();
			IResource link = project.findMember(linkDesc.getProjectRelativePath());
			if (link != null) {
				try {
					URI locationURI = linkDesc.getLocationURI();
					locationURI = link.getPathVariableManager().resolveURI(locationURI);
					addToLocationsMap(link, EFS.getStore(locationURI));
				} catch (CoreException e) {
					//ignore links with invalid locations
				}
			}
		}
	}

	private void addToLocationsMap(IResource link, IFileStore location) {
		if (location != null && !link.isVirtual())
			if (locationsMap.add(location, link))
				nonDefaultResourceCount++;
	}

	/**
	 * Builds the table of aliased projects from scratch.
	 */
	private void buildAliasedProjectsSet() {
		aliasedProjects.clear();
		//if there are no resources in non-default locations then there can't be any aliased projects
		if (nonDefaultResourceCount <= 0)
			return;
		//for every resource that overlaps another, marked its project as aliased
		addToCollection.setCollection(aliasedProjects);
		locationsMap.overLappingResourcesDo(addToCollection);
	}

	/**
	 * Builds the table of resource locations from scratch.  Also computes an
	 * initial value for the linked resource counter.
	 */
	private void buildLocationsMap() {
		locationsMap.clear();
		nonDefaultResourceCount = 0;
		//build table of IPath (file system location) -> IResource (project or linked resource)
		IProject[] projects = workspace.getRoot().getProjects(IContainer.INCLUDE_HIDDEN);
		for (int i = 0; i < projects.length; i++)
			if (projects[i].isAccessible())
				addToLocationsMap(projects[i]);
	}

	/**
	 * A project alias needs updating.  If the project location has been deleted,
	 * then the project should be deleted from the workspace.  This differs
	 * from the refresh local strategy, but operations performed from within
	 * the workspace must never leave a resource out of sync.
	 * @param project The project to check for deletion
	 * @param location The project location
	 * @return <code>true</code> if the project has been deleted, and <code>false</code> otherwise
	 * @exception CoreException
	 */
	private boolean checkDeletion(Project project, IFileStore location) throws CoreException {
		if (project.exists() && !location.fetchInfo().exists()) {
			//perform internal deletion of project from workspace tree because
			// it is already deleted from disk and we can't acquire a different
			//scheduling rule in this context (none is needed because we are
			//within scope of the workspace lock)
			Assert.isTrue(workspace.getWorkManager().getLock().getDepth() > 0);
			project.deleteResource(false, null);
			return true;
		}
		return false;
	}

	/**
	 * Returns all aliases of the given resource, or null if there are none.
	 */
	public IResource[] computeAliases(final IResource resource, IFileStore location) {
		//nothing to do if we are or were in an alias-free workspace or project
		if (hasNoAliases(resource))
			return null;

		aliases.clear();
		internalComputeAliases(resource, location);
		int size = aliases.size();
		if (size == 0)
			return null;
		return (IResource[]) aliases.toArray(new IResource[size]);
	}

	/**
	 * Returns all aliases of this resource, and any aliases of subtrees of this
	 * resource.  Returns null if no aliases are found.
	 */
	private void computeDeepAliases(IResource resource, IFileStore location) {
		//if the location is invalid then there won't be any aliases to update
		if (location == null)
			return;
		//get the normal aliases (resources rooted in parent locations)
		internalComputeAliases(resource, location);
		//get all resources rooted below this resource's location
		addToCollection.setCollection(aliases);
		locationsMap.matchingPrefixDo(location, addToCollection);
		//if this is a project, get all resources rooted below links in this project
		if (resource.getType() == IResource.PROJECT) {
			try {
				IResource[] members = ((IProject) resource).members();
				final FileSystemResourceManager localManager = workspace.getFileSystemManager();
				for (int i = 0; i < members.length; i++) {
					if (members[i].isLinked()) {
						IFileStore linkLocation = localManager.getStore(members[i]);
						if (linkLocation != null)
							locationsMap.matchingPrefixDo(linkLocation, addToCollection);
					}
				}
			} catch (CoreException e) {
				//skip inaccessible projects
			}
		}
	}

	/**
	 * Returns the comparator to use when sorting the locations map.  Comparison
	 * is based on segments, so that paths with the most segments in common will
	 * always be adjacent.  This is equivalent to the natural order on the path
	 * strings, with the extra condition that the path separator is ordered
	 * before all other characters. (Ex: "/foo" < "/foo/zzz" < "/fooaaa").
	 */
	private Comparator getComparator() {
		return new Comparator() {
			public int compare(Object o1, Object o2) {
				IFileStore store1 = (IFileStore) o1;
				IFileStore store2 = (IFileStore) o2;
				//scheme takes precedence over all else
				int compare = compareStringOrNull(store1.getFileSystem().getScheme(), store2.getFileSystem().getScheme());
				if (compare != 0)
					return compare;
				// compare based on URI path segment values 
				final URI uri1;
				final URI uri2;
				try {
					uri1 = store1.toURI();
					uri2 = store2.toURI();
				} catch (Exception e) {
					//protect against misbehaving 3rd party code in file system implementations
					Policy.log(e);
					return 1;
				}

				IPath path1 = new Path(uri1.getPath());
				IPath path2 = new Path(uri2.getPath());
				// compare devices
				compare = compareStringOrNull(path1.getDevice(), path2.getDevice());
				if (compare != 0)
					return compare;
				// compare segments
				int segmentCount1 = path1.segmentCount();
				int segmentCount2 = path2.segmentCount();
				for (int i = 0; (i < segmentCount1) && (i < segmentCount2); i++) {
					compare = path1.segment(i).compareTo(path2.segment(i));
					if (compare != 0)
						return compare;
				}
				//all segments are equal, so compare based on number of segments
				compare = segmentCount1 - segmentCount2;
				if (compare != 0)
					return compare;
				//same number of segments, so compare query
				return compareStringOrNull(uri1.getQuery(), uri2.getQuery());
			}

			/**
			 * Compares two strings that are possibly null.
			 */
			private int compareStringOrNull(String string1, String string2) {
				if (string1 == null) {
					if (string2 == null)
						return 0;
					return 1;
				}
				if (string2 == null)
					return -1;
				return string1.compareTo(string2);

			}
		};
	}

	public void handleEvent(LifecycleEvent event) {
		/*
		 * We can't determine the end state for most operations because they may
		 * fail after we receive pre-notification.  In these cases, we remember
		 * the invalidated resources and recompute their state lazily on the
		 * next alias request.
		 */
		switch (event.kind) {
			case LifecycleEvent.PRE_LINK_CHANGE:
			case LifecycleEvent.PRE_LINK_DELETE :
				Resource link = (Resource) event.resource;
				if (link.isLinked())
					removeFromLocationsMap(link, link.getStore());
				//fall through
			case LifecycleEvent.PRE_FILTER_ADD:
				changedLinks.add(event.resource);
				break;
			case LifecycleEvent.PRE_FILTER_REMOVE:
				changedLinks.add(event.resource);
				break;
			case LifecycleEvent.PRE_LINK_CREATE :
				changedLinks.add(event.resource);
				break;
			case LifecycleEvent.PRE_LINK_COPY :
				changedLinks.add(event.newResource);
				break;
			case LifecycleEvent.PRE_LINK_MOVE :
				link = (Resource) event.resource;
				if (link.isLinked())
					removeFromLocationsMap(link, link.getStore());
				changedLinks.add(event.newResource);
				break;
		}
	}

	/**
	 * Returns true if this resource is guaranteed to have no aliases, and false
	 * otherwise.
	 */
	private boolean hasNoAliases(final IResource resource) {
		//check if we're in an aliased project or workspace before updating structure changes.  In the 
		//deletion case, we need to know if the resource was in an aliased project *before* deletion.
		IProject project = resource.getProject();
		boolean noAliases = !aliasedProjects.contains(project);

		//now update any structure changes and check again if an update is needed
		if (hasStructureChanges()) {
			updateStructureChanges();
			noAliases &= nonDefaultResourceCount <= 0 || !aliasedProjects.contains(project);
		}
		return noAliases;
	}

	/**
	 * Returns whether there are any structure changes that we have not yet processed.
	 */
	private boolean hasStructureChanges() {
		return changedProjects || !changedLinks.isEmpty();
	}

	/**
	 * Computes the aliases of the given resource at the given location, and
	 * adds them to the "aliases" collection.
	 */
	private void internalComputeAliases(IResource resource, IFileStore location) {
		IFileStore searchLocation = location;
		if (searchLocation == null)
			searchLocation = ((Resource) resource).getStore();
		//if the location is invalid then there won't be any aliases to update
		if (searchLocation == null)
			return;

		suffix = Path.EMPTY;
		findAliases.setSearchAlias(resource);
		/*
		 * Walk up the location segments for this resource, looking for a
		 * resource with a matching location.  All matches are then added to the
		 * "aliases" set.
		 */
		do {
			locationsMap.matchingResourcesDo(searchLocation, findAliases);
			suffix = new Path(searchLocation.getName()).append(suffix);
			searchLocation = searchLocation.getParent();
		} while (searchLocation != null);
	}

	private void removeFromLocationsMap(IResource link, IFileStore location) {
		if (location != null)
			if (locationsMap.remove(location, link))
				nonDefaultResourceCount--;
	}

	public void resourceChanged(IResourceChangeEvent event) {
		final IResourceDelta delta = event.getDelta();
		if (delta == null)
			return;
		//invalidate location map if there are added or removed projects.
		if (delta.getAffectedChildren(IResourceDelta.ADDED | IResourceDelta.REMOVED).length > 0)
			changedProjects = true;
		
		// invalidate location map if any project has the description changed
		// or was closed/opened
		IResourceDelta[] changed = delta.getAffectedChildren(IResourceDelta.CHANGED);
		for (int i = 0; i < changed.length; i++) {
			if ((changed[i].getFlags() & IResourceDelta.DESCRIPTION) == IResourceDelta.DESCRIPTION 
					|| (changed[i].getFlags() & IResourceDelta.OPEN) == IResourceDelta.OPEN) {
				changedProjects = true;
				break;
			}
		}
	}

	/* (non-Javadoc)
	 * @see IManager#shutdown(IProgressMonitor)
	 */
	public void shutdown(IProgressMonitor monitor) {
		workspace.removeResourceChangeListener(this);
		locationsMap.clear();
	}

	/* (non-Javadoc)
	 * @see IManager#startup(IProgressMonitor)
	 */
	public void startup(IProgressMonitor monitor) {
		workspace.addLifecycleListener(this);
		workspace.addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
		buildLocationsMap();
		buildAliasedProjectsSet();
	}

	/**
	 * The file underlying the given resource has changed on disk.  Compute all
	 * aliases for this resource and update them.  This method will not attempt
	 * to incur any units of work on the given progress monitor, but it may
	 * update the subtask to reflect what aliases are being updated.
	 * @param resource the resource to compute aliases for
	 * @param location the file system location of the resource (passed as a
	 * parameter because in the project deletion case the resource is no longer
	 * accessible at time of update).
	 * @param depth whether to search for aliases on all children of the given
	 * resource.  Only depth ZERO and INFINITE are used.
	 */
	public void updateAliases(IResource resource, IFileStore location, int depth, IProgressMonitor monitor) throws CoreException {
		if (hasNoAliases(resource))
			return;
		aliases.clear();
		if (depth == IResource.DEPTH_ZERO)
			internalComputeAliases(resource, location);
		else
			computeDeepAliases(resource, location);
		if (aliases.size() == 0)
			return;
		FileSystemResourceManager localManager = workspace.getFileSystemManager();
		HashSet aliasesCopy = (HashSet) aliases.clone();
		for (Iterator it = aliasesCopy.iterator(); it.hasNext();) {
			IResource alias = (IResource) it.next();
			monitor.subTask(NLS.bind(Messages.links_updatingDuplicate, alias.getFullPath()));
			if (alias.getType() == IResource.PROJECT) {
				if (checkDeletion((Project) alias, location))
					continue;
				//project did not require deletion, so fall through below and refresh it
			}
			if (!((Resource)alias).isFiltered())
				localManager.refresh(alias, IResource.DEPTH_INFINITE, false, null);
		}
	}

	/**
	 * Process any structural changes that have occurred since the last alias
	 * request.
	 */
	private void updateStructureChanges() {
		boolean hadChanges = false;
		if (changedProjects) {
			//if a project is added or removed, just recompute the whole world
			changedProjects = false;
			hadChanges = true;
			buildLocationsMap();
		} else {
			//incrementally update location map for changed links
			for (Iterator it = changedLinks.iterator(); it.hasNext();) {
				IResource resource = (IResource) it.next();
				hadChanges = true;
				if (!resource.isAccessible())
					continue;
				if (resource.isLinked())
					addToLocationsMap(resource, ((Resource) resource).getStore());
			}
		}
		changedLinks.clear();
		if (hadChanges)
			buildAliasedProjectsSet();
		changedProjects = false;
	}
}
