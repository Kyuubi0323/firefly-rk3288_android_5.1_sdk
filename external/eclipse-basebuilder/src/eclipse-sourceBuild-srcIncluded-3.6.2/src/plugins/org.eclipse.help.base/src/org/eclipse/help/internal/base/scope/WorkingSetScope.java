/*******************************************************************************
 * Copyright (c) 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.help.internal.base.scope;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.help.ICriteria;
import org.eclipse.help.IIndexEntry;
import org.eclipse.help.IIndexSee;
import org.eclipse.help.IToc;
import org.eclipse.help.ITopic;
import org.eclipse.help.base.AbstractHelpScope;
import org.eclipse.help.internal.HelpPlugin;
import org.eclipse.help.internal.UAElement;
import org.eclipse.help.internal.base.util.CriteriaUtilities;
import org.eclipse.help.internal.criteria.CriteriaProviderRegistry;
import org.eclipse.help.internal.criteria.CriterionResource;
import org.eclipse.help.internal.workingset.AdaptableHelpResource;
import org.eclipse.help.internal.workingset.IHelpWorkingSetManager;
import org.eclipse.help.internal.workingset.WorkingSet;

public class WorkingSetScope extends AbstractHelpScope {
	
	private static final String UNCATEGORIZED = "Uncategorized"; //$NON-NLS-1$
	
	private IHelpWorkingSetManager wSetManager;
	private WorkingSet workingSet;
	private AdaptableHelpResource[] elements;
	private CriterionResource[] criteria;
	private String name;
	
	public WorkingSetScope(String scope, IHelpWorkingSetManager manager, String name) {
		wSetManager = manager;
		workingSet = wSetManager.getWorkingSet(scope); 
		elements = workingSet.getElements();
		criteria = workingSet.getCriteria();
		this.name = name;
	}

	public boolean inScope(IToc toc) {
		if(HelpPlugin.getCriteriaManager().isCriteriaEnabled()) {
			return inContentScope(toc) && inCriteriaScope(toc);
		} else {
			return inContentScope(toc);
		}
	}
	
	private boolean inContentScope(IToc toc) {
		for (int i = 0; i < elements.length; i++) {	
			for (AdaptableHelpResource adaptable = elements[i]; adaptable != null; ) {
				Object itoc = adaptable.getAdapter(IToc.class); 
				if (toc == itoc) {
					return true;	
				}
				IAdaptable parent= adaptable.getParent();
				if (parent instanceof AdaptableHelpResource) {
					adaptable = (AdaptableHelpResource) parent;
				} else {
				    adaptable = null;
				}
			}		
		}
		return false;
	}
	
	private boolean inCriteriaScope(IToc toc) {
		if(null == toc){
			if(null == criteria || 0 == criteria.length){
				return true;
			}
			return false;
		}
		ICriteria[] criteriaOfToc = CriteriaProviderRegistry.getInstance().getAllCriteria(toc);
		return isCriteriaInScope(criteriaOfToc);
	}
	
	private boolean isCriteriaInScope(ICriteria[] criteriaOfTopic) {
		if(null ==criteria){
			return true;
		}
		Map ownCriteria = getCriteriaInfo(criteriaOfTopic);
		Map scope = getCriteriaInfo(criteria);
		outer: for (Iterator keyIterator = scope.keySet().iterator(); keyIterator.hasNext();) {
			String key = String.valueOf(keyIterator.next());
			for (Iterator valueIterator = ((Set)scope.get(key)).iterator(); valueIterator.hasNext();) {
				String value = String.valueOf(valueIterator.next());
				if (value.equals(UNCATEGORIZED)) {
					if (!ownCriteria.containsKey(key)) {
						continue outer;						
					}
				} else {
					if (null != ownCriteria.get(key) && ((Set)ownCriteria.get(key)).contains(value))
						continue outer;					
				}
			}
			return false;
		}
		return true;
	}
	
	private Map getCriteriaInfo(CriterionResource[] criteria) {
		Map criteriaMap = new HashMap();
		CriteriaUtilities.addCriteriaToMap(criteriaMap, criteria);
		return criteriaMap;
	}
	
	private Map getCriteriaInfo(ICriteria[] criteria) {
		Map criteriaMap = new HashMap();
		CriteriaUtilities.addCriteriaToMap(criteriaMap, criteria);
	    return criteriaMap;
	}

	public boolean inScope(ITopic topic) {
		if(HelpPlugin.getCriteriaManager().isCriteriaEnabled()) {
			return inContentScope(topic) && inCriteriaScope(topic);
		} else {
			return inContentScope(topic);
		}
	}
	
	private boolean inContentScope(ITopic topic) {
		Set topics = new HashSet();
		IToc toc = null;
		topics.add(topic);
		if (topic instanceof UAElement) {
			for (UAElement uae = (UAElement) topic; uae != null; ) {
				if (uae instanceof IToc)  {
					toc = (IToc) uae;
					uae = null;
				} else if (uae instanceof IIndexEntry) {
					for (int i = 0; i < elements.length; i++) {
						AdaptableHelpResource adaptable = elements[i];
						if (adaptable.getTopic(topic.getHref()) != null) {
							return true;
						}
					}
					return false;
				} else {					
					if (uae instanceof ITopic) {
					    topics.add(uae);
					}
					uae = uae.getParentElement();
				}
			}
		}
		for (int i = 0; i < elements.length; i++) {
			AdaptableHelpResource adaptable = elements[i];
			if (toc != null) {
				Object itoc = adaptable.getAdapter(IToc.class);
				if (toc == itoc) {
					return true;
				}
			}
			Object itopic = adaptable.getAdapter(ITopic.class);
			if (topic != null && topics.contains(itopic)) {
				return true;
			}
			IAdaptable parent = adaptable.getParent();
			if (parent instanceof AdaptableHelpResource) {
				adaptable = (AdaptableHelpResource) parent;
			} else {
				adaptable = null;
			}
		}
		return false;
	}
	
	private boolean inCriteriaScope(ITopic topic) {
		if(null == topic){
			if(null == criteria || 0 == criteria.length){
				return true;
			}
			return false;
		}
		ICriteria[] criteriaOfTopic = CriteriaProviderRegistry.getInstance().getAllCriteria(topic);
		return isCriteriaInScope(criteriaOfTopic);
	}

	public boolean inScope(IIndexEntry entry) {
		return hasInScopeChildren(entry);
	}

	public boolean inScope(IIndexSee see) {
		return hasInScopeChildren(see);
	}

	public String getName(Locale locale) {
		return name;
	}

}
