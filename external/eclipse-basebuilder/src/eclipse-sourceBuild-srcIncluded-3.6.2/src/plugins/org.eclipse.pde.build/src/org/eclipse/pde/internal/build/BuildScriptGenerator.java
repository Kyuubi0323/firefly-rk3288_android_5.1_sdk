/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      IBM Corporation - initial API and implementation
 *      Ben Pryor - Bug 148288
 *******************************************************************************/
package org.eclipse.pde.internal.build;

import java.io.*;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.internal.build.builder.*;
import org.eclipse.pde.internal.build.packager.PackageScriptGenerator;
import org.eclipse.pde.internal.build.site.*;
import org.eclipse.pde.internal.build.site.compatibility.Feature;
import org.osgi.framework.Version;

public class BuildScriptGenerator extends AbstractScriptGenerator {
	/**
	 * Indicates whether the assemble script should contain the archive
	 * generation statement.
	 */
	protected boolean generateArchive = true;
	/**
	 * Indicates whether scripts for a feature's children should be generated.
	 */
	protected boolean children = true;

	/**
	 * Indicates whether the resulting archive will contain a group of all the configurations
	 */
	protected boolean groupConfigs = false;

	/**
	 * Source elements for script generation.
	 */
	protected String[] elements;

	/**
	 * Additional dev entries for the compile classpath.
	 */
	protected DevClassPathHelper devEntries;

	protected boolean recursiveGeneration = true;
	protected boolean generateBuildScript = true;
	protected boolean includePlatformIndependent = true;
	protected boolean signJars = false;
	protected boolean generateJnlp = false;
	protected boolean generateFeatureVersionSuffix = false;
	protected boolean parallel = false;
	protected boolean workspaceBinaries = false;
	protected int threadCount = -1;
	protected int threadsPerProcessor = -1;
	protected String[] eeSources = null;

	protected String product;
	//Map configuration with the expected output format: key: Config, value: string
	private HashMap archivesFormat;

	private String archivesFormatAsString;

	/**
	 * flag indicating if the assemble script should be generated
	 */
	private boolean generateAssembleScript = true;

	/** flag indicating if missing properties file should be logged */
	private boolean ignoreMissingPropertiesFile = true;

	/** flag indicating if we should generate the plugin & feature versions lists */
	protected boolean generateVersionsList = false;

	private Properties antProperties = null;
	private BundleDescription[] bundlesToBuild;
	private boolean flatten = false;
	private boolean sourceReferences = false;

	private static final String PROPERTY_ARCHIVESFORMAT = "archivesFormat"; //$NON-NLS-1$

	/**
	 * 
	 * @throws CoreException
	 */
	public void generate() throws CoreException {
		if (archivesFormatAsString != null) {
			realSetArchivesFormat(archivesFormatAsString);
			archivesFormatAsString = null;
		}

		List plugins = new ArrayList(5);
		List features = new ArrayList(5);
		try {
			AbstractScriptGenerator.setStaticAntProperties(antProperties);

			sortElements(features, plugins);
			pluginsForFilterRoots = plugins;
			featuresForFilterRoots = features;
			getSite(true); //This forces the creation of the siteFactory which contains all the parameters necessary to initialize.
			//TODO To avoid this. What would be necessary is use the BuildTimeSiteFactory to store the values that are stored in the AbstractScriptGenerator and to pass the parameters to a new BuildTimeSiteFacotry when created.
			//More over this would allow us to remove some of the setters when creating a new featurebuildscriptgenerator.

			// It is not required to filter in the two first generateModels, since
			// it is only for the building of a single plugin
			generateModels(plugins);
			generateFeatures(features);
			flushState();
		} finally {
			AbstractScriptGenerator.setStaticAntProperties(null);
		}
	}

	/**
	 * Separate elements by kind.
	 */
	protected void sortElements(List features, List plugins) {
		if (elements == null)
			return;
		for (int i = 0; i < elements.length; i++) {
			int index = elements[i].indexOf('@');
			String type = elements[i].substring(0, index);
			String element = elements[i].substring(index + 1);
			if (type.equals(PLUGIN) || type.equals(FRAGMENT))
				plugins.add(element);
			else if (type.equals(FEATURE))
				features.add(element);
		}
	}

	/**
	 * 
	 * @param models
	 * @throws CoreException
	 */
	protected void generateModels(List models) throws CoreException {
		ModelBuildScriptGenerator generator = null;
		try {
			for (Iterator iterator = models.iterator(); iterator.hasNext();) {
				generator = new ModelBuildScriptGenerator();
				generator.setReportResolutionErrors(reportResolutionErrors);
				generator.setIgnoreMissingPropertiesFile(ignoreMissingPropertiesFile);
				//Filtering is not required here, since we are only generating the build for a plugin or a fragment
				String[] modelInfo = getNameAndVersion((String) iterator.next());
				generator.setBuildSiteFactory(siteFactory);
				generator.setModelId(modelInfo[0], modelInfo[1]);

				generator.setPluginPath(pluginPath);
				generator.setDevEntries(devEntries);
				generator.setCompiledElements(generator.getCompiledElements());
				generator.setBuildingOSGi(isBuildingOSGi());
				generator.setSignJars(signJars);
				generator.setGenerateSourceReferences(sourceReferences);
				generator.generate();
			}
			if (bundlesToBuild != null)
				for (int i = 0; i < bundlesToBuild.length; i++) {
					generator = new ModelBuildScriptGenerator();
					generator.setReportResolutionErrors(reportResolutionErrors);
					generator.setIgnoreMissingPropertiesFile(ignoreMissingPropertiesFile);
					//Filtering is not required here, since we are only generating the build for a plugin or a fragment
					generator.setBuildSiteFactory(siteFactory);
					generator.setModel(bundlesToBuild[i]);

					generator.setPluginPath(pluginPath);
					generator.setDevEntries(devEntries);
					generator.setCompiledElements(generator.getCompiledElements());
					generator.setBuildingOSGi(isBuildingOSGi());
					generator.setSignJars(signJars);
					generator.setGenerateSourceReferences(sourceReferences);
					generator.generate();
				}
		} finally {
			if (generator != null)
				generator.getSite(false).getRegistry().cleanupOriginalState();
		}
	}

	private String[] getNameAndVersion(String id) {
		int versionPosition = id.indexOf(":"); //$NON-NLS-1$
		String[] result = new String[2];
		if (versionPosition != -1) {
			result[1] = id.substring(versionPosition + 1);
			result[0] = id.substring(0, versionPosition);
		} else
			result[0] = id;
		return result;
	}

	protected void generateFeatures(List features) throws CoreException {
		AssemblyInformation assemblageInformation = null;
		BuildDirector generator = null;

		if (product != null) {
			String replacement = QualifierReplacer.replaceQualifierInVersion("1.0.0.qualifier", "", null, null); //$NON-NLS-1$ //$NON-NLS-2$
			productQualifier = new Version(replacement).getQualifier();
		}

		if (features.size() > 0) {
			assemblageInformation = new AssemblyInformation();

			generator = new BuildDirector(assemblageInformation);
			generator.setGenerateIncludedFeatures(this.recursiveGeneration);
			generator.setAnalyseChildren(this.children);
			generator.setBinaryFeatureGeneration(true);
			generator.setScriptGeneration(generateBuildScript);
			generator.setPluginPath(pluginPath);
			generator.setBuildSiteFactory(siteFactory);
			generator.setDevEntries(devEntries);
			generator.setSourceToGather(new SourceFeatureInformation());//
			generator.setCompiledElements(generator.getCompiledElements());
			generator.setBuildingOSGi(isBuildingOSGi());
			generator.includePlatformIndependent(includePlatformIndependent);
			generator.setReportResolutionErrors(reportResolutionErrors);
			generator.setIgnoreMissingPropertiesFile(ignoreMissingPropertiesFile);
			generator.setSignJars(signJars);
			generator.setGenerateJnlp(generateJnlp);
			generator.setGenerateVersionSuffix(generateFeatureVersionSuffix);
			generator.setProduct(product);
			generator.setProductQualifier(productQualifier);
			generator.setUseWorkspaceBinaries(workspaceBinaries);
			generator.setContextMetadata(contextMetadata);
			generator.setContextArtifacts(contextArtifacts);
			generator.setGenerateSourceReferences(sourceReferences);
		}

		if (generator != null) {
			try {
				String[] featureInfo = null;
				for (Iterator i = features.iterator(); i.hasNext();) {
					featureInfo = getNameAndVersion((String) i.next());
					BuildTimeFeature feature = getSite(false).findFeature(featureInfo[0], featureInfo[1], true);
					generator.generate(feature);
				}

				if (features.size() != 1)
					featureInfo = new String[] {"all"}; //$NON-NLS-1$

				if (flatten)
					generateCompileScript(assemblageInformation, featureInfo);

				if (generateAssembleScript == true) {
					generateAssembleScripts(assemblageInformation, featureInfo, generator.siteFactory);

					if (features.size() != 1)
						featureInfo = new String[] {""}; //$NON-NLS-1$

					generatePackageScripts(assemblageInformation, featureInfo, generator.siteFactory);
				}
				if (generateVersionsList)
					generateVersionsLists(assemblageInformation);
			} finally {
				getSite(false).getRegistry().cleanupOriginalState();
			}
		}
	}

	protected void generateVersionsLists(AssemblyInformation assemblageInformation) throws CoreException {
		if (assemblageInformation == null)
			return;
		List configs = getConfigInfos();
		Set features = new HashSet();
		Set plugins = new HashSet();
		Properties versions = new Properties();

		//For each configuration, save the version of all the features in a file 
		//and save the version of all the plug-ins in another file
		for (Iterator iter = configs.iterator(); iter.hasNext();) {
			Config config = (Config) iter.next();
			String configString = config.toStringReplacingAny("_", ANY_STRING); //$NON-NLS-1$

			//Features
			Collection list = assemblageInformation.getFeatures(config);
			versions.clear();
			features.addAll(list);
			String featureFile = DEFAULT_FEATURE_VERSION_FILENAME_PREFIX + '.' + configString + PROPERTIES_FILE_SUFFIX;
			readVersions(versions, featureFile);
			for (Iterator i = list.iterator(); i.hasNext();) {
				Feature feature = (Feature) i.next();
				recordVersion(feature.getId(), new Version(feature.getVersion()), versions);
			}
			saveVersions(versions, featureFile);

			//Plugins
			list = assemblageInformation.getPlugins(config);
			versions.clear();
			plugins.addAll(list);
			String pluginFile = DEFAULT_PLUGIN_VERSION_FILENAME_PREFIX + '.' + configString + PROPERTIES_FILE_SUFFIX;
			readVersions(versions, pluginFile);
			for (Iterator i = list.iterator(); i.hasNext();) {
				BundleDescription bundle = (BundleDescription) i.next();
				recordVersion(bundle.getSymbolicName(), bundle.getVersion(), versions);
			}
			saveVersions(versions, pluginFile);
		}

		//Create a file containing all the feature versions  
		versions.clear();
		String featureFile = DEFAULT_FEATURE_VERSION_FILENAME_PREFIX + PROPERTIES_FILE_SUFFIX;
		readVersions(versions, featureFile);
		for (Iterator i = features.iterator(); i.hasNext();) {
			Feature feature = (Feature) i.next();
			recordVersion(feature.getId(), new Version(feature.getVersion()), versions);
		}
		saveVersions(versions, featureFile);

		//Create a file containing all the plugin versions
		versions.clear();
		String pluginVersion = DEFAULT_PLUGIN_VERSION_FILENAME_PREFIX + PROPERTIES_FILE_SUFFIX;
		readVersions(versions, pluginVersion);
		for (Iterator i = plugins.iterator(); i.hasNext();) {
			BundleDescription bundle = (BundleDescription) i.next();
			recordVersion(bundle.getSymbolicName(), bundle.getVersion(), versions);
		}
		saveVersions(versions, pluginVersion);
	}

	protected void recordVersion(String name, Version version, Properties properties) {
		String versionString = version.toString();
		if (properties.containsKey(name)) {
			Version existing = new Version((String) properties.get(name));
			if (version.compareTo(existing) >= 0) {
				properties.put(name, versionString);
			}
		} else {
			properties.put(name, versionString);
		}
		String suffix = '_' + String.valueOf(version.getMajor()) + '.' + String.valueOf(version.getMinor()) + '.' + String.valueOf(version.getMicro());
		properties.put(name + suffix, versionString);
	}

	private String getFilePath(String fileName) {
		return workingDirectory + '/' + fileName;
	}

	protected void readVersions(Properties properties, String fileName) {
		String location = getFilePath(fileName);
		try {
			InputStream is = new BufferedInputStream(new FileInputStream(location));
			try {
				properties.load(is);
			} finally {
				is.close();
			}
		} catch (IOException e) {
			//Ignore
		}
	}

	protected void saveVersions(Properties properties, String fileName) throws CoreException {
		String location = getFilePath(fileName);
		try {
			OutputStream os = new BufferedOutputStream(new FileOutputStream(location));
			try {
				properties.store(os, null);
			} finally {
				os.close();
			}
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_writingFile, location);
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, null));
		}
	}

	protected void generatePackageScripts(AssemblyInformation assemblageInformation, String[] featureInfo, BuildTimeSiteFactory factory) throws CoreException {
		PackageScriptGenerator assembler = null;
		assembler = new PackageScriptGenerator(workingDirectory, assemblageInformation, featureInfo[0]);
		assembler.setSignJars(signJars);
		assembler.setGenerateJnlp(generateJnlp);
		assembler.setArchivesFormat(getArchivesFormat());
		assembler.setProduct(product);
		assembler.setProductQualifier(productQualifier);
		assembler.setBuildSiteFactory(factory);
		assembler.setGroupConfigs(groupConfigs);
		assembler.setVersionsList(generateVersionsList);
		assembler.setContextMetadata(contextMetadata);
		assembler.setContextArtifacts(contextArtifacts);
		assembler.generate();
	}

	private void generateAssembleScripts(AssemblyInformation assemblageInformation, String[] featureInfo, BuildTimeSiteFactory factory) throws CoreException {
		AssembleScriptGenerator assembler = new AssembleScriptGenerator(workingDirectory, assemblageInformation, featureInfo[0]);
		assembler.setSignJars(signJars);
		assembler.setGenerateJnlp(generateJnlp);
		assembler.setArchivesFormat(getArchivesFormat());
		assembler.setProduct(product);
		assembler.setProductQualifier(productQualifier);
		assembler.setBuildSiteFactory(factory);
		assembler.setGroupConfigs(groupConfigs);
		assembler.setVersionsList(generateVersionsList);
		assembler.setContextMetadata(contextMetadata);
		assembler.setContextArtifacts(contextArtifacts);
		assembler.generate();
	}

	private void generateCompileScript(AssemblyInformation assemblageInformation, String[] featureInfo) throws CoreException {
		CompilationScriptGenerator generator = new CompilationScriptGenerator();
		generator.setBuildSiteFactory(siteFactory);
		generator.setWorkingDirectory(workingDirectory);
		generator.setAssemblyData(assemblageInformation);
		generator.setFeatureId(featureInfo[0]);
		generator.setParallel(parallel);
		generator.setThreadCount(threadCount);
		generator.setThreadsPerProcessor(threadsPerProcessor);
		generator.generate();
	}

	public void setGenerateArchive(boolean generateArchive) {
		this.generateArchive = generateArchive;
	}

	/**
	 * 
	 * @param children
	 */
	public void setChildren(boolean children) {
		this.children = children;
	}

	/**
	 * 
	 * @param devEntries
	 */
	public void setDevEntries(String devEntries) {
		if (devEntries != null)
			this.devEntries = new DevClassPathHelper(devEntries);
	}

	/**
	 * 
	 * @param elements
	 */
	public void setElements(String[] elements) {
		this.elements = elements;
	}

	/**
	 * Sets the recursiveGeneration.
	 * 
	 * @param recursiveGeneration
	 *            The recursiveGeneration to set
	 */
	public void setRecursiveGeneration(boolean recursiveGeneration) {
		this.recursiveGeneration = recursiveGeneration;
	}

	/**
	 * @param generateAssembleScript
	 *            The generateAssembleScript to set.
	 */
	public void setGenerateAssembleScript(boolean generateAssembleScript) {
		this.generateAssembleScript = generateAssembleScript;
	}

	/**
	 * Whether or not to generate plugin & feature versions lists
	 * @param generateVersionsList
	 */
	public void setGenerateVersionsList(boolean generateVersionsList) {
		this.generateVersionsList = generateVersionsList;
	}

	/**
	 * @param value The reportResolutionErrors to set.
	 */
	public void setReportResolutionErrors(boolean value) {
		this.reportResolutionErrors = value;
	}

	public void setP2Gathering(boolean value) {
		BuildDirector.p2Gathering = value;
	}

	/**
	 * @param value The ignoreMissingPropertiesFile to set.
	 */
	public void setIgnoreMissingPropertiesFile(boolean value) {
		ignoreMissingPropertiesFile = value;
	}

	public void setProduct(String value) {
		product = value;
	}

	public void setSignJars(boolean value) {
		signJars = value;
	}

	public void setGenerateJnlp(boolean value) {
		generateJnlp = value;
	}

	public void setGenerateFeatureVersionSuffix(boolean value) {
		generateFeatureVersionSuffix = value;
	}

	private static class ArchiveTable extends HashMap {
		private static final long serialVersionUID = -3063402400461435816L;

		public ArchiveTable(int size) {
			super(size);
		}

		public Object get(Object arg0) {
			Object result = super.get(arg0);
			if (result == null)
				result = IXMLConstants.FORMAT_ANTZIP;
			return result;
		}
	}

	public void setArchivesFormat(String archivesFormatAsString) {
		this.archivesFormatAsString = archivesFormatAsString;
	}

	public void realSetArchivesFormat(String formatString) throws CoreException {
		if (Utils.getPropertyFormat(PROPERTY_ARCHIVESFORMAT).equalsIgnoreCase(formatString)) {
			archivesFormat = new ArchiveTable(0);
			return;
		}

		archivesFormat = new ArchiveTable(getConfigInfos().size() + 1);
		String[] configs = Utils.getArrayFromStringWithBlank(formatString, "&"); //$NON-NLS-1$
		for (int i = 0; i < configs.length; i++) {
			String[] configElements = Utils.getArrayFromStringWithBlank(configs[i], ","); //$NON-NLS-1$
			if (configElements.length != 3) {
				IStatus error = new Status(IStatus.ERROR, IPDEBuildConstants.PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_CONFIG_FORMAT, NLS.bind(Messages.error_configWrongFormat, configs[i]), null);
				throw new CoreException(error);
			}
			String[] archAndFormat = Utils.getArrayFromStringWithBlank(configElements[2], "-"); //$NON-NLS-1$
			if (archAndFormat.length != 2) {
				String message = NLS.bind(Messages.invalid_archivesFormat, formatString);
				IStatus status = new Status(IStatus.ERROR, IPDEBuildConstants.PI_PDEBUILD, message);
				throw new CoreException(status);
			}

			Config aConfig = new Config(configElements[0], configElements[1], archAndFormat[0]);
			if (getConfigInfos().contains(aConfig) || configElements[0].equals("group")) { //$NON-NLS-1$
				archivesFormat.put(aConfig, archAndFormat[1]);
			}
		}
	}

	protected HashMap getArchivesFormat() {
		if (archivesFormat == null) {
			try {
				//If not set, pass in the empty property to trigger the default value to be loaded
				realSetArchivesFormat(Utils.getPropertyFormat(PROPERTY_ARCHIVESFORMAT));
			} catch (CoreException e) {
				//ignore
			}
		}
		return archivesFormat;
	}

	public void includePlatformIndependent(boolean b) {
		includePlatformIndependent = b;
	}

	public void setGroupConfigs(boolean value) {
		groupConfigs = value;
	}

	public void setImmutableAntProperties(Properties properties) {
		antProperties = properties;
	}

	public void setBundles(BundleDescription[] bundles) {
		bundlesToBuild = bundles;
	}

	public void setFlattenDependencies(boolean flatten) {
		this.flatten = flatten;
	}

	public void setParallel(boolean parallel) {
		this.parallel = parallel;
	}

	public void setThreadCount(int threadCount) {
		this.threadCount = threadCount;
	}

	public void setThreadsPerProcessor(int threadsPerProcessor) {
		this.threadsPerProcessor = threadsPerProcessor;
	}

	public void setEESources(String[] eeSources) {
		this.eeSources = eeSources;
	}

	public String[] getEESources() {
		return eeSources;
	}

	public void setUseWorkspaceBinaries(boolean workspaceBinaries) {
		this.workspaceBinaries = workspaceBinaries;
	}

	public void setGenerateSourceReferences(boolean generateSourceRef) {
		this.sourceReferences = generateSourceRef;
	}
}
