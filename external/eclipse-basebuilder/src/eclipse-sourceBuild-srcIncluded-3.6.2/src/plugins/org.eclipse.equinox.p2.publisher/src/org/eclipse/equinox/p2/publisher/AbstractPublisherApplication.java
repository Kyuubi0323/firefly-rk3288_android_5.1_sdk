/*******************************************************************************
 * Copyright (c) 2007, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     EclipseSource - ongoing development
 *******************************************************************************/
package org.eclipse.equinox.p2.publisher;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository;
import org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository;
import org.eclipse.equinox.internal.p2.publisher.Activator;
import org.eclipse.equinox.internal.p2.publisher.Messages;
import org.eclipse.equinox.p2.core.*;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.artifact.ArtifactKeyQuery;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.ServiceReference;

public abstract class AbstractPublisherApplication implements IApplication {

	// The mapping rules for in-place generation need to construct paths into the structure
	// of an eclipse installation; in the future the default artifact mapping declared in
	// SimpleArtifactRepository may change, for example, to not have a 'bundles' directory
	// instead of a 'plugins' directory, so a separate constant is defined and used here.
	static final protected String[][] INPLACE_MAPPING_RULES = { {"(& (classifier=osgi.bundle) (format=packed)", "${repoUrl}/features/${id}_${version}.jar.pack.gz"}, //$NON-NLS-1$//$NON-NLS-2$
			{"(& (classifier=osgi.bundle))", "${repoUrl}/plugins/${id}_${version}.jar"}, //$NON-NLS-1$//$NON-NLS-2$
			{"(& (classifier=binary))", "${repoUrl}/binary/${id}_${version}"}, //$NON-NLS-1$//$NON-NLS-2$
			{"(& (classifier=org.eclipse.update.feature))", "${repoUrl}/features/${id}_${version}.jar"}}; //$NON-NLS-1$//$NON-NLS-2$

	static final public String PUBLISH_PACK_FILES_AS_SIBLINGS = "publishPackFilesAsSiblings"; //$NON-NLS-1$

	protected PublisherInfo info;
	protected String source;
	protected URI metadataLocation;
	protected String metadataRepoName;
	protected URI artifactLocation;
	protected String artifactRepoName;
	protected URI[] contextMetadataRepositories;
	protected URI[] contextArtifactRepositories;
	//whether repository xml files should be compressed
	protected boolean compress = false;
	protected boolean inplace = false;
	protected boolean append = false;
	protected boolean reusePackedFiles = false;
	protected String[] configurations;
	private IStatus status;

	private ServiceReference agentRef;

	protected IProvisioningAgent agent;

	/**
	 * Returns the error message for this application, or the empty string
	 * if the application terminated successfully.
	 */
	public IStatus getStatus() {
		return status;
	}

	protected void initialize(PublisherInfo publisherInfo) throws ProvisionException {
		if (inplace) {
			File location = new File(source);
			if (metadataLocation == null)
				metadataLocation = location.toURI();
			if (artifactLocation == null)
				artifactLocation = location.toURI();
			publisherInfo.setArtifactOptions(publisherInfo.getArtifactOptions() | IPublisherInfo.A_INDEX | IPublisherInfo.A_PUBLISH);
		}
		initializeRepositories(publisherInfo);
	}

	protected IStatus createConfigurationEror(String message) {
		return new Status(IStatus.ERROR, "org.eclipse.equinox.p2.publisher", message); //$NON-NLS-1$
	}

	private boolean isEmpty(IArtifactRepository repo) {
		IQueryResult<IArtifactKey> result = repo.query(QueryUtil.createLimitQuery(ArtifactKeyQuery.ALL_KEYS, 1), null);
		return result.isEmpty();
	}

	protected void initializeRepositories(PublisherInfo publisherInfo) throws ProvisionException {
		if (artifactLocation != null) {
			IArtifactRepository repo = Publisher.createArtifactRepository(agent, artifactLocation, artifactRepoName, compress, reusePackedFiles);
			if (!append && !isEmpty(repo)) {
				File repoLocation = URIUtil.toFile(artifactLocation);
				if (repoLocation != null && source != null) {
					if (repoLocation.isFile())
						repoLocation = repoLocation.getParentFile();
					if (repoLocation.equals(new File(source)))
						throw new IllegalArgumentException(NLS.bind(Messages.exception_artifactRepoNoAppendDestroysInput, URIUtil.toUnencodedString(artifactLocation)));
				}
				repo.removeAll();
			}
			publisherInfo.setArtifactRepository(repo);
		} else if ((publisherInfo.getArtifactOptions() & IPublisherInfo.A_PUBLISH) > 0)
			throw new ProvisionException(createConfigurationEror(Messages.exception_noArtifactRepo));
		if (metadataLocation == null)
			throw new ProvisionException(createConfigurationEror(Messages.exception_noMetadataRepo));
		publisherInfo.setMetadataRepository(Publisher.createMetadataRepository(agent, metadataLocation, metadataRepoName, append, compress));

		if (contextMetadataRepositories != null && contextMetadataRepositories.length > 0) {
			CompositeMetadataRepository contextMetadata = CompositeMetadataRepository.createMemoryComposite(agent);
			if (contextMetadata != null) {
				for (int i = 0; i < contextMetadataRepositories.length; i++)
					contextMetadata.addChild(contextMetadataRepositories[i]);
				if (contextMetadata.getChildren().size() > 0)
					publisherInfo.setContextMetadataRepository(contextMetadata);
			}
		}
		if (contextArtifactRepositories != null && contextArtifactRepositories.length > 0) {
			CompositeArtifactRepository contextArtifact = CompositeArtifactRepository.createMemoryComposite(agent);
			if (contextArtifact != null) {
				for (int i = 0; i < contextArtifactRepositories.length; i++)
					contextArtifact.addChild(contextArtifactRepositories[i]);

				if (contextArtifact.getChildren().size() > 0)
					publisherInfo.setContextArtifactRepository(contextArtifact);
			}
		}
	}

	protected void processCommandLineArguments(String[] args, PublisherInfo publisherInfo) throws Exception {
		if (args == null)
			return;
		for (int i = 0; i < args.length; i++) {
			// check for args without parameters (i.e., a flag arg)
			processFlag(args[i], publisherInfo);

			// check for args with parameters. If we are at the last argument or if the next one
			// has a '-' as the first character, then we can't have an arg with a parm so continue.
			if (i == args.length - 1 || args[i + 1].startsWith("-")) //$NON-NLS-1$
				continue;
			processParameter(args[i], args[++i], publisherInfo);
		}
	}

	@SuppressWarnings("unused")
	protected void processParameter(String arg, String parameter, PublisherInfo publisherInfo) throws URISyntaxException {
		try {
			if (arg.equalsIgnoreCase("-metadataRepository") || arg.equalsIgnoreCase("-mr")) //$NON-NLS-1$ //$NON-NLS-2$
				metadataLocation = URIUtil.fromString(parameter);

			if (arg.equalsIgnoreCase("-artifactRepository") || arg.equalsIgnoreCase("-ar")) //$NON-NLS-1$ //$NON-NLS-2$
				artifactLocation = URIUtil.fromString(parameter);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(NLS.bind(Messages.exception_repoMustBeURL, parameter));
		}

		if (arg.equalsIgnoreCase("-metadataRepositoryName")) //$NON-NLS-1$
			metadataRepoName = parameter;

		if (arg.equalsIgnoreCase("-source")) { //$NON-NLS-1$
			// check here to see if the location actually exists so we can fail gracefully now rather than unpredictably later
			// see bug 272956 where we would fail with an NPE if someone gave us a URL instead of a file-system path
			if (!new File(parameter).exists())
				throw new IllegalArgumentException(NLS.bind(Messages.exception_sourcePath, parameter));
			source = parameter;
		}

		if (arg.equalsIgnoreCase("-artifactRepositoryName")) //$NON-NLS-1$
			artifactRepoName = parameter;

		if (arg.equalsIgnoreCase("-configs")) //$NON-NLS-1$
			publisherInfo.setConfigurations(AbstractPublisherAction.getArrayFromString(parameter, ",")); //$NON-NLS-1$

		if (arg.equalsIgnoreCase("-contextMetadata")) //$NON-NLS-1$
			setContextRepositories(processRepositoryList(parameter), contextArtifactRepositories);

		if (arg.equalsIgnoreCase("-contextArtifacts")) //$NON-NLS-1$
			setContextRepositories(contextMetadataRepositories, processRepositoryList(parameter));
	}

	private URI[] processRepositoryList(String parameter) {
		String[] list = AbstractPublisherAction.getArrayFromString(parameter, ","); //$NON-NLS-1$
		if (list == null || list.length == 0)
			return null;

		List<URI> result = new ArrayList<URI>(list.length);
		if (result != null) {
			for (int i = 0; i < list.length; i++) {
				try {
					result.add(URIUtil.fromString(list[i]));
				} catch (URISyntaxException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		return result.toArray(new URI[result.size()]);
	}

	protected void processFlag(String arg, PublisherInfo publisherInfo) {
		if (arg.equalsIgnoreCase("-publishArtifacts") || arg.equalsIgnoreCase("-pa")) //$NON-NLS-1$ //$NON-NLS-2$
			publisherInfo.setArtifactOptions(publisherInfo.getArtifactOptions() | IPublisherInfo.A_PUBLISH);

		if (arg.equalsIgnoreCase("-publishArtifactRepository") || arg.equalsIgnoreCase("-par")) //$NON-NLS-1$ //$NON-NLS-2$
			publisherInfo.setArtifactOptions(publisherInfo.getArtifactOptions() | IPublisherInfo.A_INDEX);

		if (arg.equalsIgnoreCase("-overwriteArtifacts")) //$NON-NLS-1$ 
			publisherInfo.setArtifactOptions(publisherInfo.getArtifactOptions() | IPublisherInfo.A_OVERWRITE);

		if (arg.equalsIgnoreCase("-append")) //$NON-NLS-1$
			append = true;

		if (arg.equalsIgnoreCase("-compress")) //$NON-NLS-1$
			compress = true;

		if (arg.equalsIgnoreCase("-reusePack200Files")) //$NON-NLS-1$
			reusePackedFiles = true;

		if (arg.equalsIgnoreCase("-inplace")) //$NON-NLS-1$
			inplace = true;
	}

	private void setupAgent() throws ProvisionException {
		agentRef = Activator.getContext().getServiceReference(IProvisioningAgent.SERVICE_NAME);
		if (agentRef != null) {
			agent = (IProvisioningAgent) Activator.getContext().getService(agentRef);
			if (agent != null)
				return;
		}
		ServiceReference providerRef = Activator.getContext().getServiceReference(IProvisioningAgentProvider.SERVICE_NAME);
		if (providerRef == null)
			throw new RuntimeException("No provisioning agent provider is available"); //$NON-NLS-1$
		IProvisioningAgentProvider provider = (IProvisioningAgentProvider) Activator.getContext().getService(providerRef);
		if (provider == null)
			throw new RuntimeException("No provisioning agent provider is available"); //$NON-NLS-1$
		//obtain agent for currently running system
		agent = provider.createAgent(null);
		Activator.getContext().ungetService(providerRef);
	}

	public Object run(String args[]) throws Exception {
		try {
			info = createPublisherInfo();
			processCommandLineArguments(args, info);
			Object result = run(info);
			if (result != IApplication.EXIT_OK)
				for (int i = 0; i < args.length; i++)
					System.out.println(args[i]);
			return result;
		} catch (Exception e) {
			if (e.getMessage() != null)
				System.err.println(e.getMessage());
			else
				e.printStackTrace(System.err);
			throw e;
		}
	}

	protected PublisherInfo createPublisherInfo() {
		return new PublisherInfo();
	}

	public Object run(PublisherInfo publisherInfo) throws Exception {
		try {
			this.info = publisherInfo;
			setupAgent();
			initialize(publisherInfo);
			System.out.println(NLS.bind(Messages.message_generatingMetadata, publisherInfo.getSummary()));

			long before = System.currentTimeMillis();
			IPublisherAction[] actions = createActions();
			Publisher publisher = createPublisher(publisherInfo);
			IStatus result = publisher.publish(actions, new NullProgressMonitor());
			long after = System.currentTimeMillis();

			if (result.isOK()) {
				System.out.println(NLS.bind(Messages.message_generationCompleted, String.valueOf((after - before) / 1000)));
				return IApplication.EXIT_OK;
			}
			System.out.println(result);
		} catch (ProvisionException e) {
			status = e.getStatus();
			if (status.getSeverity() == IStatus.ERROR && status.getMessage() != null) {
				System.out.println(status.getMessage());
			}
		}
		return new Integer(1);
	}

	protected abstract IPublisherAction[] createActions();

	protected Publisher createPublisher(PublisherInfo publisherInfo) {
		return new Publisher(publisherInfo);
	}

	public Object start(IApplicationContext context) throws Exception {
		return run((String[]) context.getArguments().get("application.args")); //$NON-NLS-1$
	}

	public void stop() {
		if (agentRef != null) {
			Activator.getContext().ungetService(agentRef);
			agentRef = null;
		}
	}

	public void setArtifactLocation(URI location) {
		this.artifactLocation = location;
	}

	public void setMetadataLocation(URI location) {
		this.metadataLocation = location;
	}

	public boolean reuseExistingPack200Files() {
		return reusePackedFiles;
	}

	public void setReuseExistingPackedFiles(boolean value) {
		reusePackedFiles = value;
	}

	public void setContextRepositories(URI[] metadata, URI[] artifacts) {
		this.contextMetadataRepositories = metadata;
		this.contextArtifactRepositories = artifacts;
	}
}
