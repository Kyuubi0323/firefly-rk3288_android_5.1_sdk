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
package org.eclipse.help.search;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.help.internal.util.ResourceLocator;
import org.osgi.framework.Bundle;

/**
 * Participant in the help search. A plug-in can contribute instance of LuceneSearchParticipant to
 * <code>"org.eclipse.help.search.luceneSearchParticipant"</code> extension point. Search
 * participant is responsible for adding the content of documents it is responsible for into the
 * help system's search index. Once in the index, the document becomes searchable and can produce
 * search hits. There are two ways of using the participant:
 * <ol>
 * <li> For adding documents that are part of the help's TOC that have content formats not known to
 * the default indexer (which are essentially all documents that are not of HTML format). In this
 * case, the help system knows about the documents because they are in TOC but does not know how to
 * index them. Because of the non-HTML format, help search participants are normally accompanied by
 * the help content producers that are responsible for transforming the unknown format into HTML on
 * the fly. <br>
 * <br>
 * When used in this mode, search participants must be registered with file extensions they handle.
 * Based on the file extension mapping, they will be the first to get a chance at indexing the
 * document with the matching extension.<br>
 * <br>
 * </li>
 * <li> For adding documents that are outside of the help's TOC. In this case, the documents are
 * completely unknown to the help system. Search participants are responsible for providing a set of
 * documents they know about and are not supposed to declare file extensions they can handle. They
 * are also responsible for opening these documents when asked because the help system will not be
 * able to open them in any meaningful way. </li>
 * </ol>
 * 
 * @since 3.2
 * @deprecated 
 * This class is deprecated because it exposes Lucene classes, 
 * which are not binary compatible between major release. The 
 * extension point org.eclipse.help.base.searchParticipant 
 * and the class SearchParticipant should be used instead.
 * 
 * In Eclipse 3.6 the announcement was made that this API class will be removed 
 * in a future release.
 */
public abstract class LuceneSearchParticipant {

	private static final HashSet EMPTY_SET = new HashSet();

	private String id;

	/**
	 * Initializes the participant with the unique identifier from the registry. The method is
	 * called by the help system - subclasses are not supposed to call it.
	 * 
	 * @param id
	 *            the unique identifier of this participant
	 */

	public final void init(String id) {
			this.id = id;
	}

	/**
	 * Returns the unique identifier of this participant.
	 * 
	 * @return the unique id
	 */
	public String getId() {
		return id;
	}

	/**
	 * Adds the document to the search index.
	 * 
	 * @param index
	 *            the abstract representation of the help index that is currently running. Indexing
	 *            known file types in participants that manage documents outside the TOC can be
	 *            delegated to the index.
	 * @param pluginId
	 *            the plug-in that owns the document
	 * @param name
	 *            the name of the document to index
	 * @param url
	 *            the url of the document to index
	 * @param id
	 *            the unique id associated with this document
	 * @param doc
	 *            the Lucene document to add searchable content to
	 * @return the status of the indexing operation. A successful operation should return
	 *         <code>Status.OK</code>.
	 */
	public abstract IStatus addDocument(ISearchIndex index, String pluginId, String name, URL url, String id,
			Document doc);

	/**
	 * Returns all the documents that this participant knows about. This method is only used for
	 * participants that handle documents outside of the help system's TOC.
	 * 
	 * @param locale
	 *            the index locale
	 * 
	 * @return a set of hrefs for documents managed by this participant.
	 */
	public Set getAllDocuments(String locale) {
		return EMPTY_SET;
	}

	/**
	 * Returns a set of identifiers of plug-ins that contribute indexable documents. This method is
	 * only used for participants that handle documents outside of the help system's TOC.
	 * 
	 * @return a set of contributing plug-in ids
	 */

	public Set getContributingPlugins() {
		return EMPTY_SET;
	}

	/**
	 * A utility method that resolves a file name that contains '$'-based substitution variables.
	 * 
	 * @param pluginId
	 *            the identifier of the originating plug-in
	 * @param fileName
	 *            the source file name
	 * @param locale
	 *            the locale to use when resolving nl variable
	 * @return the plug-in relative file name with resolved variables
	 */

	protected static String resolveVariables(String pluginId, String fileName, String locale) {
		if (fileName.indexOf('$') == -1)
			return fileName;
		ArrayList prefix = ResourceLocator.getPathPrefix(locale);
		Bundle bundle = Platform.getBundle(pluginId);
		if (bundle == null)
			return fileName;
		URL url = ResourceLocator.find(bundle, new Path(fileName), prefix);
		URL root = FileLocator.find(bundle, new Path(""), null); //$NON-NLS-1$
		return url.toString().substring(root.toString().length());
	}

	/**
	 * A utility method that adds a document title to the Lucene document.
	 * 
	 * @param title
	 *            the title string
	 * @param doc
	 *            the Lucene document
	 */

	protected void addTitle(String title, Document doc) {
		doc.add(new Field("title", title, Field.Store.NO, Field.Index.TOKENIZED)); //$NON-NLS-1$
		doc.add(new Field("exact_title", title, Field.Store.NO, Field.Index.TOKENIZED)); //$NON-NLS-1$
		doc.add(new Field("raw_title", title, Field.Store.YES, Field.Index.NO)); //$NON-NLS-1$
	}

	/**
	 * Help system does not know how to open documents outside of the system's TOC. Global search
	 * participants that bring additional documents into the index when
	 * <code>getAllDocuments(String)</code> have a chance to open the document when it is part of
	 * the search results. The default implementation returns <code>false</code> indicating that
	 * the help system should open the document. In most cases this is wrong for most of XML files
	 * that are in some interesting way.
	 * 
	 * @param id
	 *            a participant-specific identifier that completely represents a search result
	 * 
	 * @return <code>true</code> if the file has been opened correctly or <code>false</code> to
	 *         allow the help system to try to open the document.
	 */

	public boolean open(String id) {
		return false;
	}

	/**
	 * Signals to the participant that the indexing operation has finished and that cached resources
	 * can be disposed to free up memory. The participant itself is still kept around (hence this is
	 * semantically different from <code>dispose</code>).
	 */
	public void clear() {
	}
}
