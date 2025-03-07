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
package org.eclipse.help.internal.search;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.help.IToc;
import org.eclipse.help.ITopic;
import org.eclipse.help.base.AbstractHelpScope;
import org.eclipse.help.internal.HelpPlugin;
import org.eclipse.help.internal.base.scope.CriteriaHelpScope;
import org.eclipse.help.internal.criteria.CriterionResource;
import org.eclipse.help.internal.util.URLCoder;
import org.eclipse.help.internal.workingset.AdaptableHelpResource;
import org.eclipse.help.internal.workingset.AdaptableSelectedToc;
import org.eclipse.help.internal.workingset.AdaptableSelectedTopic;
import org.eclipse.help.internal.workingset.AdaptableToc;
import org.eclipse.help.internal.workingset.AdaptableTopic;
import org.eclipse.help.internal.workingset.WorkingSet;

/**
 * Search result collector. Performs filtering and collects hits into an array
 * of SearchHit
 */
public class SearchResults implements ISearchHitCollector {
	// Collection of AdaptableHelpResource[]
	private ArrayList scopes;
	private int maxHits;
	private String locale;
	private AbstractHelpScope filter;
	private CriteriaHelpScope criteriaScope;
	protected SearchHit[] searchHits = new SearchHit[0];
	private QueryTooComplexException searchException = null;
	private boolean isQuickSearch;

	public SearchResults(WorkingSet[] workingSets, int maxHits, String locale) {
		this(workingSets, maxHits, locale, false);
	}
	
	/**
	 * Constructor
	 * 
	 * @param workingSets
	 *            working sets or null if no filtering
	 */
	public SearchResults(WorkingSet[] workingSets, int maxHits, String locale, boolean isQuickSearch) {
		this.maxHits = maxHits;
		this.locale = locale;
		this.scopes = getScopes(workingSets);
		this.criteriaScope = new CriteriaHelpScope(getCriteriaScopes(workingSets));
		this.isQuickSearch = isQuickSearch;
	}
	
	public void setFilter(AbstractHelpScope filter) {
		this.filter = filter;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.help.internal.search.ISearchHitCollector#addHits(List, String)
	 */
	public void addHits(List hits, String highlightTerms) {	
		String urlEncodedWords = URLCoder.encode(highlightTerms);
		List searchHitList = new ArrayList();
		float scoreScale = 1.0f;
		boolean scoreScaleSet = false;
		
		Iterator iter = hits.iterator();
		for (int filteredHits = 0; filteredHits < maxHits && iter.hasNext(); ) {
			SearchHit rawHit = (SearchHit)iter.next();
			String href = rawHit.getHref();
			IToc toc = null; // the TOC containing the topic
			AdaptableHelpResource scope = null;
			// the scope for the topic, if any
			if (scopes == null) {
				toc = getTocForTopic(href, locale);
				if (toc == null && !rawHit.canOpen()) {
					continue;
				}
			} else {
				scope = getScopeForTopic(href);
				if (scope == null) {
					// topic outside of scope
					continue;
				} else if ((scope instanceof AdaptableToc) || (scope instanceof AdaptableSelectedToc)) {
					toc = (IToc) scope.getAdapter(IToc.class);
				} else if((scope instanceof AdaptableTopic) || (scope instanceof AdaptableSelectedTopic)){ // scope is AdaptableTopic or AdaptableSelectedTopic
					toc = (IToc) scope.getParent().getAdapter(IToc.class);
				}
			}

			// adjust score
			float score = rawHit.getScore();
			if (!scoreScaleSet) {
				if (score > 0) {
					scoreScale = 0.99f / score;
					score = 1;
				}
				scoreScaleSet = true;
			} else {
				score = score * scoreScale + 0.01f;
			}

			// Set the document label
			String label = rawHit.getLabel();
			if ("".equals(label) && toc != null) { //$NON-NLS-1$
				ITopic t;
				if (scope != null) {
					t = scope.getTopic(href);
				} else {
					t = toc.getTopic(href);
				}
				if (t != null) {
					label = t.getLabel();
				}
			}
			if (label == null || "".equals(label)) { //$NON-NLS-1$
				label = href;
			}
			
			// Set document href
			if (urlEncodedWords.length() > 0) {
				href += "?resultof=" + urlEncodedWords; //$NON-NLS-1$
			}
			filteredHits ++;
			searchHitList.add(new SearchHit(href, label, rawHit.getSummary(), score, toc, rawHit.getRawId(), rawHit.getParticipantId(), rawHit.isPotentialHit()));
		}
		searchHits = (SearchHit[]) searchHitList
				.toArray(new SearchHit[searchHitList.size()]);

	}
	/**
	 * Finds a topic within a scope
	 */
	private AdaptableHelpResource getScopeForTopic(String href) {
		boolean enabled = HelpPlugin.getCriteriaManager().isCriteriaEnabled();
		for (int i = 0; i < scopes.size(); i++) {
			AdaptableHelpResource scope = (AdaptableHelpResource) scopes.get(i);
			ITopic inScopeTopic = scope.getTopic(href);
			if (inScopeTopic != null){
				if (filter == null || filter.inScope(inScopeTopic)) {
					if(!enabled || (enabled && criteriaScope.inScope(inScopeTopic))){
						return scope;
					}
				}
			}	
		
			// add root toc's extradir topics to search scope
			if (!isQuickSearch) {
				IToc tocRoot = getTocForScope(scope, locale);
				if (tocRoot != null) {
					IToc toc = HelpPlugin.getTocManager().getOwningToc(href);
					if (toc != null) {
						String owningTocHref = toc.getHref();
						if (owningTocHref == tocRoot.getHref()) {
							if (filter == null || filter.inScope(inScopeTopic)) {
								if(!enabled || (enabled && criteriaScope.inScope(inScopeTopic))){
									return scope;
								}
							}
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Finds a scope in a toc
	 */
	private IToc getTocForScope(AdaptableHelpResource scope, String locale) {
		if (scope == null) {
			return null;
		}
        String href = scope.getHref();
		if(scope.getAdapter(IToc.class) instanceof IToc){
			IToc toc=(IToc)scope.getAdapter(IToc.class);
			href=toc.getTopic(null).getHref();
		}
		
		if (href != null && href.length() > 0) {
			return getTocForTopic(href, locale);
		} else {
			AdaptableHelpResource[] childrenScopes = scope.getChildren();
			if (childrenScopes != null) {
				for (int i = 0; i < childrenScopes.length; i++) {
					// To find the target toc recursively because scope.getHref
					// may be null.
					IToc toc = getTocForScope(childrenScopes[i], locale);
					if (toc != null)
						return toc;
				}
			}
		}
		return null;
	}

	/**
	 * Finds a topic in a toc or within a scope if specified
	 */
	private IToc getTocForTopic(String href, String locale) {
		IToc[] tocs = HelpPlugin.getTocManager().getTocs(locale);
		boolean foundInToc = false;
		for (int i = 0; i < tocs.length; i++) {
			ITopic topic = tocs[i].getTopic(href);
			if (topic != null) {
				foundInToc = true;
				if (filter == null || filter.inScope(topic)) {
					return tocs[i];
				}
			} 
		}
		if (!foundInToc) {
			// test to pick up files in extradirs
			IToc toc = HelpPlugin.getTocManager().getOwningToc(href);
			if (toc != null) {
				foundInToc = true;
				if (filter == null || filter.inScope(toc)) {
				    return toc;
				}
			}
		}
		return null;
	}

	/**
	 * Gets the searchHits.
	 * 
	 * @return Returns a SearchHit[]
	 */
	public SearchHit[] getSearchHits() {
		return searchHits;
	}
	
	public QueryTooComplexException getException() {
		return searchException;
	}

	/**
	 * Returns a collection of adaptable help resources that are roots for
	 * filtering.
	 * 
	 * @return Collection
	 */
	private ArrayList getScopes(WorkingSet[] wSets) {
		if (wSets == null)
			return null;

		scopes = new ArrayList(wSets.length);
		for (int w = 0; w < wSets.length; w++) {
			AdaptableHelpResource[] elements = wSets[w].getElements();
			for (int i = 0; i < elements.length; i++)
				scopes.add(elements[i]);
		}
		return scopes;
	}
	
	private ArrayList getCriteriaScopes(WorkingSet[] wSets){
		if (wSets == null)
			return null;
		
		ArrayList criteriaScopes = new ArrayList(wSets.length);
		for (int w = 0; w < wSets.length; w++) {
			CriterionResource[] elements = wSets[w].getCriteria();
			for (int i = 0; i < elements.length; i++)
				criteriaScopes.add(elements[i]);
		}
		return criteriaScopes;		
	}

	public void addQTCException(QueryTooComplexException exception) throws QueryTooComplexException {
		this.searchException = exception;
	}
}
