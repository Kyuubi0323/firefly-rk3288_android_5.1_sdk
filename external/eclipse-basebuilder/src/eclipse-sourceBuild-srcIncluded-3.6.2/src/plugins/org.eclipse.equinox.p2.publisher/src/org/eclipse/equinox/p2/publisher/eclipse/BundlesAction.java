/*******************************************************************************
 * Copyright (c) 2008, 2009 Code 9 and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Code 9 - initial API and implementation
 *   IBM - ongoing development
 ******************************************************************************/
package org.eclipse.equinox.p2.publisher.eclipse;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.frameworkadmin.BundleInfo;
import org.eclipse.equinox.internal.p2.core.helpers.LogHelper;
import org.eclipse.equinox.internal.p2.core.helpers.ServiceHelper;
import org.eclipse.equinox.internal.p2.metadata.ArtifactKey;
import org.eclipse.equinox.internal.p2.publisher.Activator;
import org.eclipse.equinox.internal.p2.publisher.Messages;
import org.eclipse.equinox.internal.p2.publisher.eclipse.GeneratorBundleInfo;
import org.eclipse.equinox.p2.metadata.*;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitFragmentDescription;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.metadata.VersionRange;
import org.eclipse.equinox.p2.metadata.expression.IMatchExpression;
import org.eclipse.equinox.p2.publisher.*;
import org.eclipse.equinox.p2.publisher.actions.*;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.repository.artifact.IArtifactDescriptor;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.equinox.spi.p2.publisher.LocalizationHelper;
import org.eclipse.equinox.spi.p2.publisher.PublisherHelper;
import org.eclipse.osgi.service.pluginconversion.PluginConversionException;
import org.eclipse.osgi.service.pluginconversion.PluginConverter;
import org.eclipse.osgi.service.resolver.*;
import org.eclipse.osgi.util.ManifestElement;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.*;
import org.osgi.service.packageadmin.PackageAdmin;

/**
 * Publish IUs for all of the bundles in a given set of locations or described by a set of
 * bundle descriptions.  The locations can be actual locations of the bundles or folders
 * of bundles.
 *
 * This action consults the following types of advice:
 * </ul>
 * <li>{@link IAdditionalInstallableUnitAdvice }</li>
 * <li>{@link IBundleShapeAdvice}</li>
 * <li>{@link ICapabilityAdvice}</li>
 * <li>{@link IPropertyAdvice}</li>
 * <li>{@link ITouchpointAdvice}</li>
 * </ul>
 */
public class BundlesAction extends AbstractPublisherAction {

	// TODO reconsider the references to these specific ids in the action.  The action should be generic
	protected static final String ORG_ECLIPSE_EQUINOX_SIMPLECONFIGURATOR = "org.eclipse.equinox.simpleconfigurator"; //$NON-NLS-1$
	protected static final String ORG_ECLIPSE_UPDATE_CONFIGURATOR = "org.eclipse.update.configurator"; //$NON-NLS-1$
	/**
	 * A capability name in the {@link PublisherHelper#NAMESPACE_ECLIPSE_TYPE} namespace
	 * representing and OSGi bundle resource
	 * @see IProvidedCapability#getName()
	 */
	public static final String TYPE_ECLIPSE_BUNDLE = "bundle"; //$NON-NLS-1$

	/**
	 * A capability name in the {@link PublisherHelper#NAMESPACE_ECLIPSE_TYPE} namespace
	 * representing a source bundle
	 * @see IProvidedCapability#getName()
	 */
	public static final String TYPE_ECLIPSE_SOURCE = "source"; //$NON-NLS-1$

	public static final String OSGI_BUNDLE_CLASSIFIER = "osgi.bundle"; //$NON-NLS-1$
	private static final String CAPABILITY_NS_OSGI_BUNDLE = "osgi.bundle"; //$NON-NLS-1$
	private static final String CAPABILITY_NS_OSGI_FRAGMENT = "osgi.fragment"; //$NON-NLS-1$

	public static final IProvidedCapability BUNDLE_CAPABILITY = MetadataFactory.createProvidedCapability(PublisherHelper.NAMESPACE_ECLIPSE_TYPE, TYPE_ECLIPSE_BUNDLE, Version.createOSGi(1, 0, 0));
	public static final IProvidedCapability SOURCE_BUNDLE_CAPABILITY = MetadataFactory.createProvidedCapability(PublisherHelper.NAMESPACE_ECLIPSE_TYPE, TYPE_ECLIPSE_SOURCE, Version.createOSGi(1, 0, 0));

	static final String DEFAULT_BUNDLE_LOCALIZATION = "OSGI-INF/l10n/bundle"; //$NON-NLS-1$

	private static final String[] BUNDLE_IU_PROPERTY_MAP = {Constants.BUNDLE_NAME, IInstallableUnit.PROP_NAME, Constants.BUNDLE_DESCRIPTION, IInstallableUnit.PROP_DESCRIPTION, Constants.BUNDLE_VENDOR, IInstallableUnit.PROP_PROVIDER, Constants.BUNDLE_CONTACTADDRESS, IInstallableUnit.PROP_CONTACT, Constants.BUNDLE_DOCURL, IInstallableUnit.PROP_DOC_URL, Constants.BUNDLE_UPDATELOCATION, IInstallableUnit.PROP_BUNDLE_LOCALIZATION, Constants.BUNDLE_LOCALIZATION, IInstallableUnit.PROP_BUNDLE_LOCALIZATION};
	public static final int BUNDLE_LOCALIZATION_INDEX = PublisherHelper.BUNDLE_LOCALIZED_PROPERTIES.length - 1;
	public static final String DIR = "dir"; //$NON-NLS-1$
	public static final String JAR = "jar"; //$NON-NLS-1$
	private static final String FEATURE_FILENAME_DESCRIPTOR = "feature.xml"; //$NON-NLS-1$
	private static final String PLUGIN_FILENAME_DESCRIPTOR = "plugin.xml"; //$NON-NLS-1$
	private static final String FRAGMENT_FILENAME_DESCRIPTOR = "fragment.xml"; //$NON-NLS-1$
	public static String BUNDLE_SHAPE = "Eclipse-BundleShape"; //$NON-NLS-1$

	private File[] locations;
	private BundleDescription[] bundles;

	public static IArtifactKey createBundleArtifactKey(String bsn, String version) {
		return new ArtifactKey(OSGI_BUNDLE_CLASSIFIER, bsn, Version.parseVersion(version));
	}

	public static IInstallableUnit createBundleConfigurationUnit(String hostId, Version cuVersion, boolean isBundleFragment, GeneratorBundleInfo configInfo, String configurationFlavor, IMatchExpression<IInstallableUnit> filter) {
		if (configInfo == null)
			return null;

		InstallableUnitFragmentDescription cu = new InstallableUnitFragmentDescription();
		String configUnitId = configurationFlavor + hostId;
		cu.setId(configUnitId);
		cu.setVersion(cuVersion);

		//Indicate the IU to which this CU apply
		Version hostVersion = Version.parseVersion(configInfo.getVersion());
		VersionRange range = hostVersion == Version.emptyVersion ? VersionRange.emptyRange : new VersionRange(hostVersion, true, Version.MAX_VERSION, true);
		cu.setHost(new IRequirement[] { //
		MetadataFactory.createRequirement(CAPABILITY_NS_OSGI_BUNDLE, hostId, range, null, false, false, true), //
				MetadataFactory.createRequirement(PublisherHelper.NAMESPACE_ECLIPSE_TYPE, TYPE_ECLIPSE_BUNDLE, new VersionRange(Version.createOSGi(1, 0, 0), true, Version.createOSGi(2, 0, 0), false), null, false, false, false)});

		//Adds capabilities for fragment, self, and describing the flavor supported
		cu.setProperty(InstallableUnitDescription.PROP_TYPE_FRAGMENT, Boolean.TRUE.toString());
		cu.setCapabilities(new IProvidedCapability[] {PublisherHelper.createSelfCapability(configUnitId, cuVersion), MetadataFactory.createProvidedCapability(PublisherHelper.NAMESPACE_FLAVOR, configurationFlavor, Version.createOSGi(1, 0, 0))});

		Map<String, String> touchpointData = new HashMap<String, String>();
		touchpointData.put("install", "installBundle(bundle:${artifact})"); //$NON-NLS-1$ //$NON-NLS-2$
		touchpointData.put("uninstall", "uninstallBundle(bundle:${artifact})"); //$NON-NLS-1$ //$NON-NLS-2$
		touchpointData.put("configure", createConfigScript(configInfo, isBundleFragment)); //$NON-NLS-1$
		touchpointData.put("unconfigure", createUnconfigScript(configInfo, isBundleFragment)); //$NON-NLS-1$
		cu.addTouchpointData(MetadataFactory.createTouchpointData(touchpointData));
		cu.setFilter(filter);
		return MetadataFactory.createInstallableUnit(cu);
	}

	public static IInstallableUnit createBundleIU(BundleDescription bd, IArtifactKey key, IPublisherInfo info) {
		@SuppressWarnings("unchecked")
		Map<String, String> manifest = (Map<String, String>) bd.getUserObject();
		Map<Locale, Map<String, String>> manifestLocalizations = null;
		if (manifest != null && bd.getLocation() != null)
			manifestLocalizations = getManifestLocalizations(manifest, new File(bd.getLocation()));
		InstallableUnitDescription iu = new MetadataFactory.InstallableUnitDescription();
		iu.setSingleton(bd.isSingleton());
		iu.setId(bd.getSymbolicName());
		iu.setVersion(PublisherHelper.fromOSGiVersion(bd.getVersion()));
		iu.setFilter(bd.getPlatformFilter());
		iu.setUpdateDescriptor(MetadataFactory.createUpdateDescriptor(bd.getSymbolicName(), computeUpdateRange(bd.getVersion()), IUpdateDescriptor.NORMAL, null));
		iu.setArtifacts(new IArtifactKey[] {key});
		iu.setTouchpointType(PublisherHelper.TOUCHPOINT_OSGI);

		boolean isFragment = bd.getHost() != null;
		//		boolean requiresAFragment = isFragment ? false : requireAFragment(bd, manifest);

		//Process the required bundles
		BundleSpecification requiredBundles[] = bd.getRequiredBundles();
		ArrayList<IRequirement> reqsDeps = new ArrayList<IRequirement>();
		//		if (requiresAFragment)
		//			reqsDeps.add(MetadataFactory.createRequiredCapability(CAPABILITY_TYPE_OSGI_FRAGMENTS, bd.getSymbolicName(), VersionRange.emptyRange, null, false, false));
		if (isFragment)
			reqsDeps.add(MetadataFactory.createRequirement(CAPABILITY_NS_OSGI_BUNDLE, bd.getHost().getName(), PublisherHelper.fromOSGiVersionRange(bd.getHost().getVersionRange()), null, false, false));
		for (int j = 0; j < requiredBundles.length; j++)
			reqsDeps.add(MetadataFactory.createRequirement(CAPABILITY_NS_OSGI_BUNDLE, requiredBundles[j].getName(), PublisherHelper.fromOSGiVersionRange(requiredBundles[j].getVersionRange()), null, requiredBundles[j].isOptional(), false));

		// Process the import packages
		ImportPackageSpecification osgiImports[] = bd.getImportPackages();
		for (int i = 0; i < osgiImports.length; i++) {
			// TODO we need to sort out how we want to handle wild-carded dynamic imports - for now we ignore them
			ImportPackageSpecification importSpec = osgiImports[i];
			String importPackageName = importSpec.getName();
			if (importPackageName.indexOf('*') != -1)
				continue;
			VersionRange versionRange = PublisherHelper.fromOSGiVersionRange(importSpec.getVersionRange());
			//TODO this needs to be refined to take into account all the attribute handled by imports
			reqsDeps.add(MetadataFactory.createRequirement(PublisherHelper.CAPABILITY_NS_JAVA_PACKAGE, importPackageName, versionRange, null, isOptional(importSpec), false));
		}
		iu.setRequirements(reqsDeps.toArray(new IRequirement[reqsDeps.size()]));

		// Create set of provided capabilities
		ArrayList<IProvidedCapability> providedCapabilities = new ArrayList<IProvidedCapability>();
		providedCapabilities.add(PublisherHelper.createSelfCapability(bd.getSymbolicName(), PublisherHelper.fromOSGiVersion(bd.getVersion())));
		providedCapabilities.add(MetadataFactory.createProvidedCapability(CAPABILITY_NS_OSGI_BUNDLE, bd.getSymbolicName(), PublisherHelper.fromOSGiVersion(bd.getVersion())));

		// Process the export package
		ExportPackageDescription exports[] = bd.getExportPackages();
		for (int i = 0; i < exports.length; i++) {
			//TODO make sure that we support all the refinement on the exports
			providedCapabilities.add(MetadataFactory.createProvidedCapability(PublisherHelper.CAPABILITY_NS_JAVA_PACKAGE, exports[i].getName(), PublisherHelper.fromOSGiVersion(exports[i].getVersion())));
		}
		// Here we add a bundle capability to identify bundles
		if (manifest != null && manifest.containsKey("Eclipse-SourceBundle")) //$NON-NLS-1$
			providedCapabilities.add(SOURCE_BUNDLE_CAPABILITY);
		else
			providedCapabilities.add(BUNDLE_CAPABILITY);
		if (isFragment)
			providedCapabilities.add(MetadataFactory.createProvidedCapability(CAPABILITY_NS_OSGI_FRAGMENT, bd.getHost().getName(), PublisherHelper.fromOSGiVersion(bd.getVersion())));

		if (manifestLocalizations != null) {
			for (Entry<Locale, Map<String, String>> locEntry : manifestLocalizations.entrySet()) {
				Locale locale = locEntry.getKey();
				Map<String, String> translatedStrings = locEntry.getValue();
				for (Entry<String, String> entry : translatedStrings.entrySet()) {
					iu.setProperty(locale.toString() + '.' + entry.getKey(), entry.getValue());
				}
				providedCapabilities.add(PublisherHelper.makeTranslationCapability(bd.getSymbolicName(), locale));
			}
		}
		iu.setCapabilities(providedCapabilities.toArray(new IProvidedCapability[providedCapabilities.size()]));
		processCapabilityAdvice(iu, info);

		// Set certain properties from the manifest header attributes as IU properties.
		// The values of these attributes may be localized (strings starting with '%')
		// with the translated values appearing in the localization IU fragments
		// associated with the bundle IU.
		if (manifest != null) {
			int i = 0;
			while (i < BUNDLE_IU_PROPERTY_MAP.length) {
				if (manifest.containsKey(BUNDLE_IU_PROPERTY_MAP[i])) {
					String value = manifest.get(BUNDLE_IU_PROPERTY_MAP[i]);
					if (value != null && value.length() > 0) {
						iu.setProperty(BUNDLE_IU_PROPERTY_MAP[i + 1], value);
					}
				}
				i += 2;
			}
		}

		// Define the immutable metadata for this IU. In this case immutable means
		// that this is something that will not impact the configuration.
		Map<String, String> touchpointData = new HashMap<String, String>();
		touchpointData.put("manifest", toManifestString(manifest)); //$NON-NLS-1$
		if (isDir(bd, info))
			touchpointData.put("zipped", "true"); //$NON-NLS-1$ //$NON-NLS-2$
		processTouchpointAdvice(iu, touchpointData, info);

		processInstallableUnitPropertiesAdvice(iu, info);
		return MetadataFactory.createInstallableUnit(iu);
	}

	static VersionRange computeUpdateRange(org.osgi.framework.Version base) {
		VersionRange updateRange = null;
		if (!base.equals(org.osgi.framework.Version.emptyVersion)) {
			updateRange = new VersionRange(Version.emptyVersion, true, PublisherHelper.fromOSGiVersion(base), false);
		} else {
			updateRange = VersionRange.emptyRange;
		}
		return updateRange;
	}

	private IInstallableUnitFragment createHostLocalizationFragment(IInstallableUnit bundleIU, BundleDescription bd, String hostId, String[] hostBundleManifestValues) {
		Map<Locale, Map<String, String>> hostLocalizations = getHostLocalizations(new File(bd.getLocation()), hostBundleManifestValues);
		if (hostLocalizations == null || hostLocalizations.isEmpty())
			return null;
		return createLocalizationFragmentOfHost(bd, hostId, hostBundleManifestValues, hostLocalizations);
	}

	/*
	 * @param hostId
	 * @param bd
	 * @param locale
	 * @param localizedStrings
	 * @return installableUnitFragment
	 */
	private static IInstallableUnitFragment createLocalizationFragmentOfHost(BundleDescription bd, String hostId, String[] hostManifestValues, Map<Locale, Map<String, String>> hostLocalizations) {
		InstallableUnitFragmentDescription fragment = new MetadataFactory.InstallableUnitFragmentDescription();
		String fragmentId = makeHostLocalizationFragmentId(bd.getSymbolicName());
		fragment.setId(fragmentId);
		fragment.setVersion(PublisherHelper.fromOSGiVersion(bd.getVersion())); // TODO: is this a meaningful version?

		HostSpecification hostSpec = bd.getHost();
		IRequirement[] hostReqs = new IRequirement[] {MetadataFactory.createRequirement(IInstallableUnit.NAMESPACE_IU_ID, hostSpec.getName(), PublisherHelper.fromOSGiVersionRange(hostSpec.getVersionRange()), null, false, false, false)};
		fragment.setHost(hostReqs);

		fragment.setSingleton(true);
		fragment.setProperty(InstallableUnitDescription.PROP_TYPE_FRAGMENT, Boolean.TRUE.toString());

		// Create a provided capability for each locale and add the translated properties.
		ArrayList<IProvidedCapability> providedCapabilities = new ArrayList<IProvidedCapability>(hostLocalizations.keySet().size());
		providedCapabilities.add(PublisherHelper.createSelfCapability(fragmentId, fragment.getVersion()));
		for (Entry<Locale, Map<String, String>> localeEntry : hostLocalizations.entrySet()) {
			Locale locale = localeEntry.getKey();
			Map<String, String> translatedStrings = localeEntry.getValue();
			for (Entry<String, String> entry : translatedStrings.entrySet()) {
				fragment.setProperty(locale.toString() + '.' + entry.getKey(), entry.getValue());
			}
			providedCapabilities.add(PublisherHelper.makeTranslationCapability(hostId, locale));
		}
		fragment.setCapabilities(providedCapabilities.toArray(new IProvidedCapability[providedCapabilities.size()]));

		return MetadataFactory.createInstallableUnitFragment(fragment);
	}

	/**
	 * @param id
	 * @return the id for the iu fragment containing localized properties
	 * 		   for the fragment with the given id.
	 */
	private static String makeHostLocalizationFragmentId(String id) {
		return id + ".translated_host_properties"; //$NON-NLS-1$
	}

	private static String createConfigScript(GeneratorBundleInfo configInfo, boolean isBundleFragment) {
		if (configInfo == null)
			return ""; //$NON-NLS-1$

		String configScript = "";//$NON-NLS-1$
		if (!isBundleFragment && configInfo.getStartLevel() != BundleInfo.NO_LEVEL) {
			configScript += "setStartLevel(startLevel:" + configInfo.getStartLevel() + ");"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!isBundleFragment && configInfo.isMarkedAsStarted()) {
			configScript += "markStarted(started: true);"; //$NON-NLS-1$
		}

		if (configInfo.getSpecialConfigCommands() != null) {
			configScript += configInfo.getSpecialConfigCommands();
		}

		return configScript;
	}

	private static String createDefaultBundleConfigScript(GeneratorBundleInfo configInfo) {
		return createConfigScript(configInfo, false);
	}

	public static IInstallableUnit createDefaultBundleConfigurationUnit(GeneratorBundleInfo configInfo, GeneratorBundleInfo unconfigInfo, String configurationFlavor) {
		InstallableUnitFragmentDescription cu = new InstallableUnitFragmentDescription();
		String configUnitId = PublisherHelper.createDefaultConfigUnitId(OSGI_BUNDLE_CLASSIFIER, configurationFlavor);
		cu.setId(configUnitId);
		Version configUnitVersion = Version.createOSGi(1, 0, 0);
		cu.setVersion(configUnitVersion);

		// Add capabilities for fragment, self, and describing the flavor supported
		cu.setProperty(InstallableUnitDescription.PROP_TYPE_FRAGMENT, Boolean.TRUE.toString());
		cu.setCapabilities(new IProvidedCapability[] {PublisherHelper.createSelfCapability(configUnitId, configUnitVersion), MetadataFactory.createProvidedCapability(PublisherHelper.NAMESPACE_FLAVOR, configurationFlavor, Version.createOSGi(1, 0, 0))});

		// Create a required capability on bundles
		IRequirement[] reqs = new IRequirement[] {MetadataFactory.createRequirement(PublisherHelper.NAMESPACE_ECLIPSE_TYPE, TYPE_ECLIPSE_BUNDLE, VersionRange.emptyRange, null, false, true, false)};
		cu.setHost(reqs);
		Map<String, String> touchpointData = new HashMap<String, String>();

		touchpointData.put("install", "installBundle(bundle:${artifact})"); //$NON-NLS-1$ //$NON-NLS-2$
		touchpointData.put("uninstall", "uninstallBundle(bundle:${artifact})"); //$NON-NLS-1$ //$NON-NLS-2$
		touchpointData.put("configure", createDefaultBundleConfigScript(configInfo)); //$NON-NLS-1$
		touchpointData.put("unconfigure", createDefaultBundleUnconfigScript(unconfigInfo)); //$NON-NLS-1$

		cu.addTouchpointData(MetadataFactory.createTouchpointData(touchpointData));
		return MetadataFactory.createInstallableUnit(cu);
	}

	private static String createDefaultBundleUnconfigScript(GeneratorBundleInfo unconfigInfo) {
		return createUnconfigScript(unconfigInfo, false);
	}

	private static String createUnconfigScript(GeneratorBundleInfo unconfigInfo, boolean isBundleFragment) {
		if (unconfigInfo == null)
			return ""; //$NON-NLS-1$
		String unconfigScript = "";//$NON-NLS-1$
		if (!isBundleFragment && unconfigInfo.getStartLevel() != BundleInfo.NO_LEVEL) {
			unconfigScript += "setStartLevel(startLevel:" + BundleInfo.NO_LEVEL + ");"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!isBundleFragment && unconfigInfo.isMarkedAsStarted()) {
			unconfigScript += "markStarted(started: false);"; //$NON-NLS-1$
		}

		if (unconfigInfo.getSpecialUnconfigCommands() != null) {
			unconfigScript += unconfigInfo.getSpecialUnconfigCommands();
		}
		return unconfigScript;
	}

	private static boolean isOptional(ImportPackageSpecification importedPackage) {
		if (importedPackage.getDirective(Constants.RESOLUTION_DIRECTIVE).equals(ImportPackageSpecification.RESOLUTION_DYNAMIC) || importedPackage.getDirective(Constants.RESOLUTION_DIRECTIVE).equals(ImportPackageSpecification.RESOLUTION_OPTIONAL))
			return true;
		return false;
	}

	private static String toManifestString(Map<String, String> p) {
		if (p == null)
			return null;
		StringBuffer result = new StringBuffer();
		for (Entry<String, String> aProperty : p.entrySet()) {
			if (aProperty.getKey().equals(BUNDLE_SHAPE))
				continue;
			result.append(aProperty.getKey()).append(": ").append(aProperty.getValue()).append('\n'); //$NON-NLS-1$
		}
		return result.toString();
	}

	// Return a map from locale to property set for the manifest localizations
	// from the given bundle directory and given bundle localization path/name
	// manifest property value.
	private static Map<Locale, Map<String, String>> getManifestLocalizations(Map<String, String> manifest, File bundleLocation) {
		Map<Locale, Map<String, String>> localizations;
		Locale defaultLocale = null; // = Locale.ENGLISH; // TODO: get this from GeneratorInfo
		String[] bundleManifestValues = getManifestCachedValues(manifest);
		String bundleLocalization = bundleManifestValues[BUNDLE_LOCALIZATION_INDEX]; // Bundle localization is the last one in the list

		if ("jar".equalsIgnoreCase(new Path(bundleLocation.getName()).getFileExtension()) && //$NON-NLS-1$
				bundleLocation.isFile()) {
			localizations = LocalizationHelper.getJarPropertyLocalizations(bundleLocation, bundleLocalization, defaultLocale, bundleManifestValues);
			//localizations = getJarManifestLocalization(bundleLocation, bundleLocalization, defaultLocale, bundleManifestValues);
		} else {
			localizations = LocalizationHelper.getDirPropertyLocalizations(bundleLocation, bundleLocalization, defaultLocale, bundleManifestValues);
			// localizations = getDirManifestLocalization(bundleLocation, bundleLocalization, defaultLocale, bundleManifestValues);
		}

		return localizations;
	}

	public static String[] getExternalizedStrings(IInstallableUnit iu) {
		String[] result = new String[PublisherHelper.BUNDLE_LOCALIZED_PROPERTIES.length];
		int j = 0;
		for (int i = 1; i < BUNDLE_IU_PROPERTY_MAP.length - 1; i += 2) {
			if (iu.getProperty(BUNDLE_IU_PROPERTY_MAP[i]) != null && iu.getProperty(BUNDLE_IU_PROPERTY_MAP[i]).length() > 0 && iu.getProperty(BUNDLE_IU_PROPERTY_MAP[i]).charAt(0) == '%')
				result[j++] = iu.getProperty(BUNDLE_IU_PROPERTY_MAP[i]).substring(1);
			else
				j++;
		}
		// The last string is the location 
		result[BUNDLE_LOCALIZATION_INDEX] = iu.getProperty(IInstallableUnit.PROP_BUNDLE_LOCALIZATION);

		return result;
	}

	public static String[] getManifestCachedValues(Map<String, String> manifest) {
		String[] cachedValues = new String[PublisherHelper.BUNDLE_LOCALIZED_PROPERTIES.length];
		for (int j = 0; j < PublisherHelper.BUNDLE_LOCALIZED_PROPERTIES.length; j++) {
			String value = manifest.get(PublisherHelper.BUNDLE_LOCALIZED_PROPERTIES[j]);
			if (PublisherHelper.BUNDLE_LOCALIZED_PROPERTIES[j].equals(Constants.BUNDLE_LOCALIZATION)) {
				if (value == null)
					value = DEFAULT_BUNDLE_LOCALIZATION;
				cachedValues[j] = value;
			} else if (value != null && value.length() > 1 && value.charAt(0) == '%') {
				cachedValues[j] = value.substring(1);
			}
		}
		return cachedValues;
	}

	// Return a map from locale to property set for the manifest localizations
	// from the given bundle directory and given bundle localization path/name
	// manifest property value.
	public static Map<Locale, Map<String, String>> getHostLocalizations(File bundleLocation, String[] hostBundleManifestValues) {
		Map<Locale, Map<String, String>> localizations;
		Locale defaultLocale = null; // = Locale.ENGLISH; // TODO: get this from GeneratorInfo
		String hostBundleLocalization = hostBundleManifestValues[BUNDLE_LOCALIZATION_INDEX];
		if (hostBundleLocalization == null)
			return null;

		if ("jar".equalsIgnoreCase(new Path(bundleLocation.getName()).getFileExtension()) && //$NON-NLS-1$
				bundleLocation.isFile()) {
			localizations = LocalizationHelper.getJarPropertyLocalizations(bundleLocation, hostBundleLocalization, defaultLocale, hostBundleManifestValues);
			//localizations = getJarManifestLocalization(bundleLocation, hostBundleLocalization, defaultLocale, hostBundleManifestValues);
		} else {
			localizations = LocalizationHelper.getDirPropertyLocalizations(bundleLocation, hostBundleLocalization, defaultLocale, hostBundleManifestValues);
			// localizations = getDirManifestLocalization(bundleLocation, hostBundleLocalization, defaultLocale, hostBundleManifestValues);
		}

		return localizations;
	}

	private static PluginConverter acquirePluginConverter() {
		return (PluginConverter) ServiceHelper.getService(Activator.getContext(), PluginConverter.class.getName());
	}

	@SuppressWarnings("unchecked")
	private static Dictionary<String, String> convertPluginManifest(File bundleLocation, boolean logConversionException) {
		PluginConverter converter;
		try {
			converter = acquirePluginConverter();
			if (converter == null) {
				String message = NLS.bind(Messages.exception_noPluginConverter, bundleLocation);
				LogHelper.log(new Status(IStatus.ERROR, Activator.ID, message));
				return null;
			}
			return converter.convertManifest(bundleLocation, false, null, true, null);
		} catch (PluginConversionException convertException) {
			// only log the exception if we had a plugin.xml or fragment.xml and we failed conversion
			if (bundleLocation.getName().equals(FEATURE_FILENAME_DESCRIPTOR))
				return null;
			if (!new File(bundleLocation, PLUGIN_FILENAME_DESCRIPTOR).exists() && !new File(bundleLocation, FRAGMENT_FILENAME_DESCRIPTOR).exists())
				return null;
			if (logConversionException) {
				IStatus status = new Status(IStatus.WARNING, Activator.ID, 0, NLS.bind(Messages.exception_errorConverting, bundleLocation.getAbsolutePath()), convertException);
				LogHelper.log(status);
			}
			return null;
		}
	}

	public static BundleDescription createBundleDescription(Dictionary<String, String> enhancedManifest, File bundleLocation) {
		try {
			BundleDescription descriptor = StateObjectFactory.defaultFactory.createBundleDescription(null, enhancedManifest, bundleLocation == null ? null : bundleLocation.getAbsolutePath(), 1); //TODO Do we need to have a real bundle id
			descriptor.setUserObject(enhancedManifest);
			return descriptor;
		} catch (BundleException e) {
			String message = NLS.bind(Messages.exception_stateAddition, bundleLocation == null ? null : bundleLocation.getAbsoluteFile());
			IStatus status = new Status(IStatus.WARNING, Activator.ID, message, e);
			LogHelper.log(status);
			return null;
		}
	}

	public static BundleDescription createBundleDescription(File bundleLocation) {
		Dictionary<String, String> manifest = loadManifest(bundleLocation);
		if (manifest == null)
			return null;
		return createBundleDescription(manifest, bundleLocation);
	}

	public static Dictionary<String, String> loadManifest(File bundleLocation) {
		Dictionary<String, String> manifest = basicLoadManifest(bundleLocation);
		if (manifest == null)
			return null;
		// if the bundle itself does not define its shape, infer the shape from the current form
		if (manifest.get(BUNDLE_SHAPE) == null)
			manifest.put(BUNDLE_SHAPE, bundleLocation.isDirectory() ? DIR : JAR);
		return manifest;
	}

	public static Dictionary<String, String> basicLoadManifest(File bundleLocation) {
		InputStream manifestStream = null;
		ZipFile jarFile = null;
		try {
			if ("jar".equalsIgnoreCase(new Path(bundleLocation.getName()).getFileExtension()) && bundleLocation.isFile()) { //$NON-NLS-1$
				jarFile = new ZipFile(bundleLocation, ZipFile.OPEN_READ);
				ZipEntry manifestEntry = jarFile.getEntry(JarFile.MANIFEST_NAME);
				if (manifestEntry != null) {
					manifestStream = jarFile.getInputStream(manifestEntry);
				}
			} else {
				File manifestFile = new File(bundleLocation, JarFile.MANIFEST_NAME);
				if (manifestFile.exists())
					manifestStream = new BufferedInputStream(new FileInputStream(manifestFile));
			}
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_errorLoadingManifest, bundleLocation);
			LogHelper.log(new Status(IStatus.WARNING, Activator.ID, message, e));
		}
		Dictionary<String, String> manifest = null;
		try {
			if (manifestStream != null) {
				try {
					@SuppressWarnings("unchecked")
					Map<String, String> manifestMap = ManifestElement.parseBundleManifest(manifestStream, null);
					// TODO temporary hack.  We are reading a Map but everyone wants a Dictionary so convert.
					// real answer is to have people expect a Map but that is a wider change.
					manifest = new Hashtable<String, String>(manifestMap);
				} catch (IOException e) {
					String message = NLS.bind(Messages.exception_errorReadingManifest, bundleLocation, e.getMessage());
					LogHelper.log(new Status(IStatus.ERROR, Activator.ID, message, e));
					return null;
				} catch (BundleException e) {
					String message = NLS.bind(Messages.exception_errorReadingManifest, bundleLocation, e.getMessage());
					LogHelper.log(new Status(IStatus.ERROR, Activator.ID, message, e));
					return null;
				}
			} else {
				manifest = convertPluginManifest(bundleLocation, true);
			}
		} finally {
			try {
				if (jarFile != null)
					jarFile.close();
			} catch (IOException e2) {
				//Ignore
			}
		}

		if (manifest == null)
			return null;

		//Deal with the pre-3.0 plug-in shape who have a default jar manifest.mf
		if (manifest.get(org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME) == null)
			manifest = convertPluginManifest(bundleLocation, true);
		return manifest;

	}

	public BundlesAction(File[] locations) {
		this.locations = locations;
	}

	public BundlesAction(BundleDescription[] bundles) {
		this.bundles = bundles;
	}

	public IStatus perform(IPublisherInfo publisherInfo, IPublisherResult results, IProgressMonitor monitor) {
		if (bundles == null && locations == null)
			throw new IllegalStateException(Messages.exception_noBundlesOrLocations);

		setPublisherInfo(publisherInfo);

		try {
			if (bundles == null)
				bundles = getBundleDescriptions(expandLocations(locations), monitor);
			generateBundleIUs(bundles, publisherInfo, results, monitor);
			bundles = null;
		} catch (OperationCanceledException e) {
			return Status.CANCEL_STATUS;
		}
		return Status.OK_STATUS;
	}

	protected void publishArtifact(IArtifactDescriptor descriptor, File base, File[] inclusions, IPublisherInfo publisherInfo) {
		IArtifactRepository destination = publisherInfo.getArtifactRepository();
		if (descriptor == null || destination == null)
			return;

		// publish the given files
		publishArtifact(descriptor, inclusions, null, publisherInfo, createRootPrefixComputer(base));
	}

	protected void publishArtifact(IArtifactDescriptor descriptor, File jarFile, IPublisherInfo publisherInfo) {
		// no files to publish so this is done.
		if (jarFile == null || publisherInfo == null)
			return;

		// if the destination already contains the descriptor, there is nothing to do.
		IArtifactRepository destination = publisherInfo.getArtifactRepository();
		if (destination == null || destination.contains(descriptor))
			return;

		super.publishArtifact(descriptor, jarFile, publisherInfo);

		// if we are assimilating pack200 files then add the packed descriptor
		// into the repo assuming it does not already exist.
		boolean reuse = "true".equals(destination.getProperties().get(AbstractPublisherApplication.PUBLISH_PACK_FILES_AS_SIBLINGS)); //$NON-NLS-1$
		if (reuse && (publisherInfo.getArtifactOptions() & IPublisherInfo.A_PUBLISH) > 0) {
			File packFile = new Path(jarFile.getAbsolutePath()).addFileExtension("pack.gz").toFile(); //$NON-NLS-1$
			if (packFile.exists()) {
				IArtifactDescriptor ad200 = createPack200ArtifactDescriptor(descriptor.getArtifactKey(), packFile, descriptor.getProperty(IArtifactDescriptor.ARTIFACT_SIZE));
				publishArtifact(ad200, packFile, publisherInfo);
			}
		}
	}

	private File[] expandLocations(File[] list) {
		ArrayList<File> result = new ArrayList<File>();
		expandLocations(list, result);
		return result.toArray(new File[result.size()]);
	}

	private void expandLocations(File[] list, ArrayList<File> result) {
		if (list == null)
			return;
		for (int i = 0; i < list.length; i++) {
			File location = list[i];
			if (location.isDirectory()) {
				// if the location is itself a bundle, just add it.  Otherwise r down
				if (new File(location, JarFile.MANIFEST_NAME).exists())
					result.add(location);
				else if (new File(location, "plugin.xml").exists() || new File(location, "fragment.xml").exists()) //$NON-NLS-1$ //$NON-NLS-2$
					result.add(location); //old style bundle without manifest
				else
					expandLocations(location.listFiles(), result);
			} else {
				result.add(location);
			}
		}
	}

	//TODO remove this method
	protected void generateBundleIUs(BundleDescription[] bundleDescriptions, IPublisherResult result, IProgressMonitor monitor) {
		generateBundleIUs(bundleDescriptions, null, result, monitor);
	}

	protected void generateBundleIUs(BundleDescription[] bundleDescriptions, IPublisherInfo info, IPublisherResult result, IProgressMonitor monitor) {

		// This assumes that hosts are processed before fragments because for each fragment the host
		// is queried for the strings that should be translated.
		for (int i = 0; i < bundleDescriptions.length; i++) {
			if (monitor.isCanceled())
				throw new OperationCanceledException();

			BundleDescription bd = bundleDescriptions[i];
			if (bd != null && bd.getSymbolicName() != null && bd.getVersion() != null) {
				//First check to see if there is already an IU around for this
				IInstallableUnit bundleIU = queryForIU(result, bundleDescriptions[i].getSymbolicName(), PublisherHelper.fromOSGiVersion(bd.getVersion()));
				IArtifactKey key = createBundleArtifactKey(bd.getSymbolicName(), bd.getVersion().toString());
				if (bundleIU == null) {
					createAdviceFileAdvice(bundleDescriptions[i], info);
					// Create the bundle IU according to any shape advice we have
					bundleIU = createBundleIU(bd, key, info);
				}

				File location = new File(bd.getLocation());
				IArtifactDescriptor ad = PublisherHelper.createArtifactDescriptor(info, key, location);
				processArtifactPropertiesAdvice(bundleIU, ad, info);

				// Publish according to the shape on disk
				File bundleLocation = new File(bd.getLocation());
				if (bundleLocation.isDirectory())
					publishArtifact(ad, bundleLocation, bundleLocation.listFiles(), info);
				else
					publishArtifact(ad, bundleLocation, info);

				IInstallableUnit fragment = null;
				if (isFragment(bd)) {
					// TODO: Need a test case for multiple hosts
					String hostId = bd.getHost().getName();
					VersionRange hostVersionRange = PublisherHelper.fromOSGiVersionRange(bd.getHost().getVersionRange());
					IQueryResult<IInstallableUnit> hosts = queryForIUs(result, hostId, hostVersionRange);

					for (Iterator<IInstallableUnit> itor = hosts.iterator(); itor.hasNext();) {
						IInstallableUnit host = itor.next();
						String fragmentId = makeHostLocalizationFragmentId(bd.getSymbolicName());
						fragment = queryForIU(result, fragmentId, PublisherHelper.fromOSGiVersion(bd.getVersion()));
						if (fragment == null) {
							String[] externalizedStrings = getExternalizedStrings(host);
							fragment = createHostLocalizationFragment(bundleIU, bd, hostId, externalizedStrings);
						}
					}

				}

				result.addIU(bundleIU, IPublisherResult.ROOT);
				if (fragment != null)
					result.addIU(fragment, IPublisherResult.NON_ROOT);

				InstallableUnitDescription[] others = processAdditionalInstallableUnitsAdvice(bundleIU, info);
				for (int iuIndex = 0; others != null && iuIndex < others.length; iuIndex++) {
					result.addIU(MetadataFactory.createInstallableUnit(others[iuIndex]), IPublisherResult.ROOT);
				}
			}
		}
	}

	/**
	 * Adds advice for any p2.inf file found in this bundle.
	 */
	private void createAdviceFileAdvice(BundleDescription bundleDescription, IPublisherInfo publisherInfo) {
		String location = bundleDescription.getLocation();
		if (location == null)
			return;

		AdviceFileAdvice advice = new AdviceFileAdvice(bundleDescription.getSymbolicName(), PublisherHelper.fromOSGiVersion(bundleDescription.getVersion()), new Path(location), AdviceFileAdvice.BUNDLE_ADVICE_FILE);
		if (advice.containsAdvice())
			publisherInfo.addAdvice(advice);

	}

	private static boolean isDir(BundleDescription bundle, IPublisherInfo info) {
		Collection<IBundleShapeAdvice> advice = info.getAdvice(null, true, bundle.getSymbolicName(), PublisherHelper.fromOSGiVersion(bundle.getVersion()), IBundleShapeAdvice.class);
		// if the advice has a shape, use it
		if (advice != null && !advice.isEmpty()) {
			// we know there is some advice but if there is more than one, take the first.
			String shape = advice.iterator().next().getShape();
			if (shape != null)
				return shape.equals(IBundleShapeAdvice.DIR);
		}
		// otherwise go with whatever we figured out from the manifest or the shape on disk
		@SuppressWarnings("unchecked")
		Map<String, String> manifest = (Map<String, String>) bundle.getUserObject();
		String format = manifest.get(BUNDLE_SHAPE);
		return DIR.equals(format);
	}

	private boolean isFragment(BundleDescription bd) {
		return (bd.getHost() != null ? true : false);
	}

	// TODO reconsider the special cases here for the configurators.  Perhaps these should be in their own actions.
	protected BundleDescription[] getBundleDescriptions(File[] bundleLocations, IProgressMonitor monitor) {
		if (bundleLocations == null)
			return new BundleDescription[0];
		boolean addSimpleConfigurator = false;
		boolean scIn = false;
		for (int i = 0; i < bundleLocations.length; i++) {
			if (!addSimpleConfigurator)
				addSimpleConfigurator = bundleLocations[i].toString().indexOf(ORG_ECLIPSE_UPDATE_CONFIGURATOR) > 0;
			if (!scIn) {
				scIn = bundleLocations[i].toString().indexOf(ORG_ECLIPSE_EQUINOX_SIMPLECONFIGURATOR) > 0;
				if (scIn)
					break;
			}
		}
		if (scIn)
			addSimpleConfigurator = false;
		BundleDescription[] result = new BundleDescription[bundleLocations.length + (addSimpleConfigurator ? 1 : 0)];
		for (int i = 0; i < bundleLocations.length; i++) {
			if (monitor.isCanceled())
				throw new OperationCanceledException();
			result[i] = createBundleDescription(bundleLocations[i]);
		}
		if (addSimpleConfigurator) {
			// Add simple configurator to the list of bundles
			try {
				Bundle simpleConfigBundle = getBundle(ORG_ECLIPSE_EQUINOX_SIMPLECONFIGURATOR);
				if (simpleConfigBundle == null)
					LogHelper.log(new Status(IStatus.INFO, Activator.ID, Messages.message_noSimpleconfigurator));
				else {
					File location = FileLocator.getBundleFile(simpleConfigBundle);
					result[result.length - 1] = createBundleDescription(location);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return result;
	}

	// This method is based on core.runtime's InternalPlatform.getBundle(...) with a difference just in how we get PackageAdmin
	private static Bundle getBundle(String symbolicName) {
		PackageAdmin packageAdmin = (PackageAdmin) ServiceHelper.getService(Activator.getContext(), PackageAdmin.class.getName());
		if (packageAdmin == null)
			return null;
		Bundle[] matchingBundles = packageAdmin.getBundles(symbolicName, null);
		if (matchingBundles == null)
			return null;
		//Return the first bundle that is not installed or uninstalled
		for (int i = 0; i < matchingBundles.length; i++) {
			if ((matchingBundles[i].getState() & (Bundle.INSTALLED | Bundle.UNINSTALLED)) == 0) {
				return matchingBundles[i];
			}
		}
		return null;
	}
}
