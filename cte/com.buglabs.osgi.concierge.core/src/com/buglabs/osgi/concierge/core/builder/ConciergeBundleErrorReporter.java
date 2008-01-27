/*******************************************************************************
 * Copyright (c) 2000, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.buglabs.osgi.concierge.core.builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.environments.IExecutionEnvironment;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.BundleSpecification;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.osgi.service.resolver.GenericDescription;
import org.eclipse.osgi.service.resolver.GenericSpecification;
import org.eclipse.osgi.service.resolver.HostSpecification;
import org.eclipse.osgi.service.resolver.ImportPackageSpecification;
import org.eclipse.osgi.service.resolver.ResolverError;
import org.eclipse.osgi.service.resolver.State;
import org.eclipse.osgi.service.resolver.VersionConstraint;
import org.eclipse.osgi.service.resolver.VersionRange;
import org.eclipse.osgi.util.ManifestElement;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.core.plugin.IFragmentModel;
import org.eclipse.pde.core.plugin.IPluginBase;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.core.AbstractNLModel;
import org.eclipse.pde.internal.core.ICoreConstants;
import org.eclipse.pde.internal.core.NLResourceHelper;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.PDECoreMessages;
import org.eclipse.pde.internal.core.TargetPlatformHelper;
import org.eclipse.pde.internal.core.builders.CompilerFlags;
import org.eclipse.pde.internal.core.builders.IHeader;
import org.eclipse.pde.internal.core.builders.JarManifestErrorReporter;
import org.eclipse.pde.internal.core.builders.JarManifestHeader;
import org.eclipse.pde.internal.core.builders.PDEMarkerFactory;
import org.eclipse.pde.internal.core.ibundle.IBundle;
import org.eclipse.pde.internal.core.ibundle.IBundlePluginModelBase;
import org.eclipse.pde.internal.core.ibundle.IManifestHeader;
import org.eclipse.pde.internal.core.search.PluginJavaSearchUtil;
import org.eclipse.pde.internal.core.util.IdUtil;
import org.eclipse.pde.internal.core.util.ManifestUtils;
import org.eclipse.pde.internal.core.util.PDEJavaHelper;
import org.eclipse.pde.internal.core.util.VersionUtil;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Version;

import com.buglabs.osgi.concierge.core.IPackageProvider;
import com.buglabs.osgi.concierge.core.OSGiCore;
import com.buglabs.osgi.concierge.jdt.ConciergeClasspathContainer;

public class ConciergeBundleErrorReporter extends JarManifestErrorReporter {

	private boolean fOsgiR4;
	private IPluginModelBase fModel;
	private Set fProjectPackages;
	private IPackageProvider[] packageProviders;
	private Map headers;

	public ConciergeBundleErrorReporter(IFile file) {
		super(file);
		packageProviders = OSGiCore.getPackageProviders();
	}

	public void validateContent(IProgressMonitor monitor) {
		super.validateContent(monitor);
		if (fHeaders == null || getErrorCount() > 0)
			return;

		fModel = PluginRegistry.findModel(fProject);
		// be paranoid.  something could have gone wrong reading the file etc.
		if (fModel == null || !validateBundleSymbolicName())
			return;
		setOsgiR4();

		validateFragmentHost();	
		validateRequiredHeader(Constants.BUNDLE_NAME);
		validateBundleVersion();
		validateRequiredExecutionEnvironment();

		validateEclipsePlatformFilter();
		validateBundleActivator();
		validateBundleClasspath();
		validateRequireBundle(monitor);
		validateImportPackage(monitor);
		validateExportPackage(monitor);
		validateExportPackages();
		validateAutoStart();
		validateLazyStart();
		validateExtensibleAPI();		
		validateTranslatableHeaders();
		validateImportExportServices();
		validateBundleLocalization();
	}

	private void validateExportPackages() {
		IHeader header = getHeader(Constants.EXPORT_PACKAGE);

		// check for missing exported packages
		if(fModel instanceof IBundlePluginModelBase) {
			IBundlePluginModelBase bundleModel = (IBundlePluginModelBase) fModel;
			IBundle bundle = bundleModel.getBundleModel().getBundle();
			IManifestHeader bundleClasspathheader = 
				(IManifestHeader) bundle.getManifestHeader(Constants.BUNDLE_CLASSPATH);

			IPackageFragmentRoot[] roots = ManifestUtils.findPackageFragmentRoots(bundleClasspathheader, fProject);
			// Running list of packages in the project
			//Set packages = new HashSet();
			StringBuffer packages = new StringBuffer();
			for (int i = 0; i < roots.length; i++) {
				try {
					if (ManifestUtils.isImmediateRoot(roots[i])) {
						IJavaElement[] javaElements = roots[i].getChildren();
						for (int j = 0; j < javaElements.length; j++)
							if (javaElements[j] instanceof IPackageFragment) {
								IPackageFragment fragment = (IPackageFragment) javaElements[j];
								String name = fragment.getElementName();
								if (name.length() == 0)
									name = "."; //$NON-NLS-1$
								if (fragment.containsJavaResources() || fragment.getNonJavaResources().length > 0){
									if (!containsPackage(header, name)) {
										packages.append(name);
										if(j < javaElements.length - 1)
											packages.append(","); //$NON-NLS-1$

									}
								}
							}
					}
				} catch (JavaModelException e) {}
			}

			// if we actually have packages to add
			if(packages.toString().length() > 0) {
				IMarker marker = report(PDECoreMessages.BundleErrorReporter_missingPackagesInProject, header == null ? 1 : header.getLineNumber() + 1,
						CompilerFlags.P_MISSING_EXPORT_PKGS, 
						PDEMarkerFactory.M_MISSING_EXPORT_PKGS,
						PDEMarkerFactory.CAT_OTHER);
				try {
					if (marker != null)
						marker.setAttribute("packages", packages.toString()); //$NON-NLS-1$
				} catch (CoreException e) {}
			}

		}

	}

	private void setOsgiR4() {
		IHeader header = getHeader(Constants.BUNDLE_MANIFESTVERSION);
		if (header != null) {		
			String version = header.getValue();
			try {
				fOsgiR4 = version != null && Integer.parseInt(version) > 1 ;
			}  catch (NumberFormatException e) {			
			}
		}
	}

	/**
	 * @return boolean false if fatal
	 */
	private boolean validateBundleSymbolicName() {
		IHeader header = validateRequiredHeader(Constants.BUNDLE_SYMBOLICNAME);
		if (header == null)
			return false;

		ManifestElement[] elements = header.getElements();
		String id = elements.length > 0 ? elements[0].getValue() : null;
		if (id == null || id.length() == 0) {
			report(PDECoreMessages.BundleErrorReporter_NoSymbolicName, header.getLineNumber() + 1, 
					CompilerFlags.ERROR, PDEMarkerFactory.CAT_FATAL);
			return false;
		}

		validatePluginId(header, id);
		validateSingleton(header, elements[0]);

		return true;
	}

	private boolean validatePluginId(IHeader header, String value) {
		if (!IdUtil.isValidCompositeID(value)) {
			String message = PDECoreMessages.BundleErrorReporter_InvalidSymbolicName; 
			report(message, getLine(header, value), CompilerFlags.WARNING, PDEMarkerFactory.CAT_FATAL);
			return false;
		}
		return true;
	}

	private void validateSingleton(IHeader header, ManifestElement element) {
		String singletonAttr = element.getAttribute(ICoreConstants.SINGLETON_ATTRIBUTE);
		String singletonDir = element.getDirective(Constants.SINGLETON_DIRECTIVE);
		IPluginBase base = fModel.getPluginBase();
		boolean hasExtensions = base != null && 
		(base.getExtensionPoints().length > 0
				|| base.getExtensions().length > 0);

		if (hasExtensions) {
			if (TargetPlatformHelper.getTargetVersion() >= 3.1) {
				if (!"true".equals(singletonDir)) { //$NON-NLS-1$
					if ("true".equals(singletonAttr)) { //$NON-NLS-1$
						if (isCheckDeprecated()) {
							String message = PDECoreMessages.BundleErrorReporter_deprecated_attribute_singleton;
							report(message, getLine(header, ICoreConstants.SINGLETON_ATTRIBUTE + "="), //$NON-NLS-1$
									CompilerFlags.P_DEPRECATED,	PDEMarkerFactory.M_SINGLETON_DIR_NOT_SET, PDEMarkerFactory.CAT_DEPRECATION);
							return;
						}
					} else {
						String message = NLS.bind(PDECoreMessages.BundleErrorReporter_singletonRequired, Constants.SINGLETON_DIRECTIVE); 
						report(message, header.getLineNumber() + 1,	CompilerFlags.ERROR, PDEMarkerFactory.M_SINGLETON_DIR_NOT_SET, PDEMarkerFactory.CAT_FATAL);						
						return;
					}
				}
			} else if (!"true".equals(singletonAttr)) { //$NON-NLS-1$
				String message = NLS.bind(PDECoreMessages.BundleErrorReporter_singletonAttrRequired,
						ICoreConstants.SINGLETON_ATTRIBUTE);
				report(message, header.getLineNumber() + 1, CompilerFlags.ERROR, PDEMarkerFactory.M_SINGLETON_ATT_NOT_SET, PDEMarkerFactory.CAT_OTHER);				
				return;
			}
		}

		if (TargetPlatformHelper.getTargetVersion() >= 3.1) {
			if (singletonAttr != null) {
				if (isCheckDeprecated()) {
					String message = PDECoreMessages.BundleErrorReporter_deprecated_attribute_singleton;
					report(message, getLine(header, ICoreConstants.SINGLETON_ATTRIBUTE + "="), //$NON-NLS-1$
							CompilerFlags.P_DEPRECATED,	PDEMarkerFactory.M_SINGLETON_DIR_NOT_SET, PDEMarkerFactory.CAT_OTHER);
				}			
			}
		} else if (singletonDir != null) {
			if (isCheckDeprecated()) {
				String message = PDECoreMessages.BundleErrorReporter_unsupportedSingletonDirective;
				report(message, getLine(header, Constants.SINGLETON_DIRECTIVE + ":="), //$NON-NLS-1$
						CompilerFlags.P_DEPRECATED,	PDEMarkerFactory.M_SINGLETON_DIR_NOT_SUPPORTED, PDEMarkerFactory.CAT_OTHER);

			}
		}
		validateBooleanAttributeValue(header, element, ICoreConstants.SINGLETON_ATTRIBUTE);
		validateBooleanDirectiveValue(header, element, Constants.SINGLETON_DIRECTIVE);	
	}

	private void validateFragmentHost() {
		IHeader header = getHeader(Constants.FRAGMENT_HOST);
		if (header == null) {
			if (isCheckNoRequiredAttr() && fProject.getFile("fragment.xml").exists()) {  //$NON-NLS-1$
				report(PDECoreMessages.BundleErrorReporter_HostNeeded, 1, CompilerFlags.P_NO_REQUIRED_ATT, PDEMarkerFactory.CAT_FATAL);
			}	
			return;
		}

		if (header.getElements().length == 0) {
			if (isCheckNoRequiredAttr())
				report(PDECoreMessages.BundleErrorReporter_HostNeeded, 1, CompilerFlags.P_NO_REQUIRED_ATT, PDEMarkerFactory.CAT_FATAL);
			return;
		}

		if (!isCheckUnresolvedImports())
			return;

		BundleDescription desc = fModel.getBundleDescription();	
		if (desc == null)
			return;

		HostSpecification host = desc.getHost();
		if (host == null)
			return;

		String name = host.getName();
		if (host.getSupplier() == null) {
			boolean missingHost = false;
			ResolverError[] errors = desc.getContainingState().getResolverErrors(desc);
			for (int i = 0; i < errors.length; i++) {
				if (errors[i].getType() == ResolverError.MISSING_FRAGMENT_HOST) {
					missingHost = true;
					break;
				}
			}

			if (missingHost) {
				BundleDescription[] suppliers = desc.getContainingState().getBundles(name);
				boolean resolved = true;
				for (int i = 0; i < suppliers.length; i++) {
					if (suppliers[i].getHost() != null)
						continue;
					if (suppliers[i].isResolved()) {
						Version version = suppliers[i].getVersion();
						VersionRange range = host.getVersionRange();
						if (!range.isIncluded(version)) {
							String versionRange = host.getVersionRange().toString();
							report(NLS.bind(PDECoreMessages.BundleErrorReporter_BundleRangeInvalidInBundleVersion, versionRange), 
									getLine(header, versionRange),
									CompilerFlags.P_UNRESOLVED_IMPORTS,
									PDEMarkerFactory.CAT_FATAL);
							return;
						}
					} else {
						resolved = false;
					}
				}

				if (!resolved) {
					report(NLS.bind(PDECoreMessages.BundleErrorReporter_unresolvedHost, name), 
							getLine(header, name), CompilerFlags.P_UNRESOLVED_IMPORTS, PDEMarkerFactory.CAT_FATAL);	
					return;
				}
			}
		} 

		IPluginModelBase model = PluginRegistry.findModel(name);
		if (model == null || model instanceof IFragmentModel || !model.isEnabled()) {
			report(NLS.bind(PDECoreMessages.BundleErrorReporter_HostNotExistPDE, name), 
					getLine(header, name), CompilerFlags.P_UNRESOLVED_IMPORTS, PDEMarkerFactory.CAT_FATAL);				
		}		
	}

	private void validateBundleVersion() {
		IHeader header = validateRequiredHeader(Constants.BUNDLE_VERSION);
		if (header == null)
			return;

		IStatus status = VersionUtil.validateVersion(header.getValue());		
		if(!status.isOK()){
			int line = getLine(header, header.getValue());
			report(status.getMessage(), line, CompilerFlags.ERROR, PDEMarkerFactory.CAT_FATAL);
		}
	}

	private void validateRequiredExecutionEnvironment() {
		int sev = CompilerFlags.getFlag(fProject, CompilerFlags.P_INCOMPATIBLE_ENV);
		if (sev == CompilerFlags.IGNORE)
			return;
		BundleDescription desc = fModel.getBundleDescription();
		if (desc == null)
			return;
		String[] bundleEnvs = desc.getExecutionEnvironments();
		if (bundleEnvs == null || bundleEnvs.length == 0)
			return;

		IHeader header = getHeader(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT);
		if (header == null)
			return;

		IExecutionEnvironment env = JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(bundleEnvs[0]);
		if (env != null) {
			IJavaProject jproject = JavaCore.create(fProject);
			IClasspathEntry[] entries;
			try {
				entries = jproject.getRawClasspath();
				for (int i = 0; i < entries.length; i++) {
					if (entries[i].getEntryKind() != IClasspathEntry.CPE_CONTAINER)
						continue;
					IPath currentPath = entries[i].getPath();
					if (JavaRuntime.newDefaultJREContainerPath().matchingFirstSegments(currentPath) == 0)
						continue;

					IPath validPath = JavaRuntime.newJREContainerPath(env);
					if (!validPath.equals(currentPath)) {
						report(NLS.bind(PDECoreMessages.BundleErrorReporter_reqExecEnv_conflict, bundleEnvs[0]),
								getLine(header, bundleEnvs[0]),
								sev, PDEMarkerFactory.M_MISMATCHED_EXEC_ENV,
								PDEMarkerFactory.CAT_EE);
					}
				}
			} catch (JavaModelException e) {
			}
		}
		IExecutionEnvironment[] systemEnvs = JavaRuntime.getExecutionEnvironmentsManager().getExecutionEnvironments();
		for (int i = 0; i < bundleEnvs.length; i++) {
			boolean found = false;
			for (int j = 0; j < systemEnvs.length; j++) {
				if (bundleEnvs[i].equals(systemEnvs[j].getId())) {
					found = true;
					break;
				}
			}
			if (!found) {
				report(NLS.bind(PDECoreMessages.BundleErrorReporter_reqExecEnv_unknown,
						bundleEnvs[i]), getLine(header, bundleEnvs[i]),
						sev, PDEMarkerFactory.M_UNKNOW_EXEC_ENV,
						PDEMarkerFactory.CAT_EE);
				break;
			}
		}
	}

	private void validateEclipsePlatformFilter() {
		IHeader header = getHeader(ICoreConstants.PLATFORM_FILTER);
		if (header == null)
			return;

		try {
			PDECore.getDefault().getBundleContext().createFilter(header.getValue());
			int severity = CompilerFlags.getFlag(fProject, CompilerFlags.P_INCOMPATIBLE_ENV);
			if (severity == CompilerFlags.IGNORE)
				return;
			BundleDescription desc = fModel.getBundleDescription();		
			if (desc != null && !desc.isResolved()) {
				ResolverError[] errors = desc.getContainingState().getResolverErrors(desc);
				for (int i = 0; i < errors.length; i++) {
					if (errors[i].getType() == ResolverError.PLATFORM_FILTER) {
						report(PDECoreMessages.BundleErrorReporter_badFilter, 
								header.getLineNumber() + 1, severity,
								PDEMarkerFactory.CAT_OTHER);
					}
				}
			}
		} catch (InvalidSyntaxException ise) {
			report(PDECoreMessages.BundleErrorReporter_invalidFilterSyntax, 
					header.getLineNumber() + 1, CompilerFlags.ERROR,
					PDEMarkerFactory.CAT_FATAL);
		}	
	}

	private void validateBundleActivator() {
		IHeader header = getHeader(Constants.BUNDLE_ACTIVATOR);
		if (header == null)
			return;

		String activator = header.getValue();
		BundleDescription desc = fModel.getBundleDescription();
		if (desc != null && desc.getHost() != null) {
			report(PDECoreMessages.BundleErrorReporter_fragmentActivator, 
					header.getLineNumber() + 1, CompilerFlags.ERROR,
					PDEMarkerFactory.CAT_FATAL);
			return;
		}

		if (isCheckUnknownClass()) {
			try {
				if (fProject.hasNature(JavaCore.NATURE_ID)) {
					IJavaProject javaProject = JavaCore.create(fProject);

					// Look for this activator in the project's classpath
					if (!PDEJavaHelper.isOnClasspath(activator, javaProject)) {
						report(NLS.bind(PDECoreMessages.BundleErrorReporter_NoExist, activator), 
								getLine(header, activator),
								CompilerFlags.P_UNKNOWN_CLASS,
								PDEMarkerFactory.M_UNKNOWN_ACTIVATOR,
								PDEMarkerFactory.CAT_FATAL);
					}
				}
			} catch (CoreException ce) {
			}
		}
	}

	private void validateBundleClasspath() {
		IHeader header = getHeader(Constants.BUNDLE_CLASSPATH);
		if (header != null && header.getElements().length == 0) {
			report(PDECoreMessages.BundleErrorReporter_ClasspathNotEmpty, 
					header.getLineNumber() + 1, CompilerFlags.ERROR, 
					PDEMarkerFactory.CAT_FATAL);
		}
	}

	private void validateRequireBundle(IProgressMonitor monitor) {
		if (!isCheckUnresolvedImports())
			return;

		IHeader header = getHeader(Constants.REQUIRE_BUNDLE);
		if (header == null)
			return;

		BundleDescription desc = fModel.getBundleDescription();
		if (desc == null)
			return;

		ManifestElement[] required = header.getElements();
		BundleSpecification[] specs = desc.getRequiredBundles();
		for (int i = 0; i < required.length; i++) {
			checkCanceled(monitor);

			String bundleID = required[i].getValue();

			validateBundleVersionAttribute(header, required[i]);
			validateVisibilityDirective(header, required[i]);
			validateReprovideAttribute(header, required[i]);
			validateResolutionDirective(header, required[i]);
			validateOptionalAttribute(header, required[i]);
			validateFragmentHost(header, required[i]);

			boolean optional = isOptional(required[i]);
			int severity = getRequireBundleSeverity(required[i], optional);

			if (specs[i].getSupplier() == null) {
				if (desc.getContainingState().getBundle(specs[i].getName(), null) == null) {
					IMarker marker = report(NLS.bind(PDECoreMessages.BundleErrorReporter_NotExistPDE, bundleID), 
							getPackageLine(header, required[i]),
							severity, 
							PDEMarkerFactory.M_REQ_BUNDLE_NOT_AVAILABLE,
							PDEMarkerFactory.CAT_FATAL);
					try {
						if (marker != null) {
							marker.setAttribute("bundleId", required[i].getValue()); //$NON-NLS-1$
							if (optional)
								marker.setAttribute("optional", true); //$NON-NLS-1$
						}
					} catch (CoreException e) {
					}
				} else {
					report(NLS.bind(PDECoreMessages.BundleErrorReporter_BundleRangeInvalidInBundleVersion, bundleID + ": " + specs[i].getVersionRange()),  //$NON-NLS-1$
							getPackageLine(header, required[i]), severity,
							PDEMarkerFactory.CAT_FATAL);					
				}
			}
		}
	}

	private void validateBundleVersionAttribute(IHeader header, ManifestElement element) {
		String versionRange = element.getAttribute(Constants.BUNDLE_VERSION_ATTRIBUTE);
		if (versionRange != null && !VersionUtil.validateVersionRange(versionRange).isOK()) {
			report(NLS.bind(PDECoreMessages.BundleErrorReporter_InvalidFormatInBundleVersion, 
					element.getValue()), getPackageLine(header, element),
					CompilerFlags.ERROR,
					PDEMarkerFactory.CAT_FATAL); 
		}
	}

	private void validateVisibilityDirective(IHeader header, ManifestElement element) {
		String visibility = element.getDirective(Constants.VISIBILITY_DIRECTIVE);
		if (visibility != null) {
			validateDirectiveValue(header, element,Constants.VISIBILITY_DIRECTIVE, 
					new String[] {Constants.VISIBILITY_PRIVATE, Constants.VISIBILITY_REEXPORT });
		}
	}

	private void validateReprovideAttribute(IHeader header, ManifestElement element) {
		String message;
		String rexport = element.getAttribute(ICoreConstants.REPROVIDE_ATTRIBUTE);
		if (rexport != null) {
			validateBooleanAttributeValue(header, element,ICoreConstants.REPROVIDE_ATTRIBUTE);
			if (fOsgiR4 && isCheckDeprecated()) {
				message = NLS.bind(PDECoreMessages.BundleErrorReporter_deprecated_attribute_reprovide,
						ICoreConstants.REPROVIDE_ATTRIBUTE); 
				report(message, 
						getLine(header, ICoreConstants.REPROVIDE_ATTRIBUTE + "="),  //$NON-NLS-1$
						CompilerFlags.P_DEPRECATED,
						PDEMarkerFactory.CAT_DEPRECATION);
			}
		}
	}

	private boolean isOptional(ManifestElement element) {
		return Constants.RESOLUTION_OPTIONAL.equals(element.getDirective(Constants.RESOLUTION_DIRECTIVE))
		|| "true".equals(element.getAttribute(ICoreConstants.OPTIONAL_ATTRIBUTE)); //$NON-NLS-1$
	}

	private int getRequireBundleSeverity(ManifestElement requireBundleElement, boolean optional) {
		int severity = CompilerFlags.getFlag(fProject, CompilerFlags.P_UNRESOLVED_IMPORTS);
		if (optional && severity != CompilerFlags.IGNORE) 
			severity += 1;
		return severity;
	}

	private void validateResolutionDirective(IHeader header,
			ManifestElement requireBundleElement) {
		String resolution = requireBundleElement
		.getDirective(Constants.RESOLUTION_DIRECTIVE);
		if (resolution != null) {
			validateDirectiveValue(header, requireBundleElement,
					Constants.RESOLUTION_DIRECTIVE, new String[] {
					Constants.RESOLUTION_MANDATORY,
					Constants.RESOLUTION_OPTIONAL });
		}
	}

	private void validateOptionalAttribute(IHeader header, ManifestElement element) {
		String rexport = element.getAttribute(ICoreConstants.OPTIONAL_ATTRIBUTE);
		if (rexport != null) {
			validateBooleanAttributeValue(header, element, ICoreConstants.OPTIONAL_ATTRIBUTE);
			if (fOsgiR4 && isCheckDeprecated()) {
				report(NLS.bind(PDECoreMessages.BundleErrorReporter_deprecated_attribute_optional,
						ICoreConstants.OPTIONAL_ATTRIBUTE),
						getLine(header, ICoreConstants.OPTIONAL_ATTRIBUTE + "="),  //$NON-NLS-1$
						CompilerFlags.P_DEPRECATED,
						PDEMarkerFactory.CAT_DEPRECATION); 
			}
		}
	}

	private void validateImportPackage(IProgressMonitor monitor) {
		BundleDescription desc = fModel.getBundleDescription();
		if (desc == null)
			return;

		IHeader header = getHeader(Constants.IMPORT_PACKAGE);
		if (header == null)
			return;

		boolean hasUnresolved = false;
		VersionConstraint[] constraints = desc.getContainingState().getStateHelper().getUnsatisfiedConstraints(desc);
		for (int i = 0; i < constraints.length; i++) {
			if (constraints[i] instanceof ImportPackageSpecification) {
				hasUnresolved = true;
				break;
			}
		}

		HashMap exported = null;
		try {
			exported = getAvailableExportedPackages(desc.getContainingState());
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		for(int p = 0; p < packageProviders.length; p++){
			List pp = packageProviders[p].getExportedPackages();
			createExportDescriptions(packageProviders[p].getExportedPackages(),exported);
		}

		ImportPackageSpecification[] imports = desc.getImportPackages();
		if (desc.hasDynamicImports()) {
			List staticImportsList = new ArrayList();
			for (int i=0;i < imports.length; ++i) {
				if (! imports[i].getDirective(Constants.RESOLUTION_DIRECTIVE).equals(ImportPackageSpecification.RESOLUTION_DYNAMIC))
					staticImportsList.add(imports[i]);
			}
			imports = (ImportPackageSpecification[]) staticImportsList.toArray(new ImportPackageSpecification[staticImportsList.size()]);
		}		

		ManifestElement[] elements = header.getElements();
		
		// PDE removes duplicate imports during call to desc.getImportPackages(). It does it in StateBuilder.createImportPackages. However duplicates
		// are never removed from ManifestElement[] (elements). Following code will remove duplicates from elements array.
		
		int manifestVersion = (int)Double.parseDouble((((JarManifestHeader)fHeaders.get("manifest-version")).getValue()));
		
		if(manifestVersion < 2){
			Map map = new HashMap();
			
			// move all elements to map so duplicates are not accepted
			for(int i = 0; i < elements.length; i++){
				map.put(elements[i].getValue(), elements[i]);
			}
			
			// copy all elements from map to the newly initialized elements array
			elements = new ManifestElement[map.size()];
			
			Iterator iterator = map.keySet().iterator();
			
			int cnt = 0;
			while(iterator.hasNext()){
				elements[cnt] = (ManifestElement) map.get(iterator.next());
				cnt++;
			}
		}
		
		int index = 0;
		for (int i = 0; i < elements.length; i++) {
			checkCanceled(monitor);			

			validateSpecificationVersionAttribute(header, elements[i]);
			validateResolutionDirective(header, elements[i]);
			if (!hasUnresolved)
				continue;

			validateVersionAttribute(header, elements[i], true);

			int length = elements[i].getValueComponents().length;
			for (int j = 0; j < length; j++) {
				ImportPackageSpecification importSpec = imports[index++];
				String name = importSpec.getName();
				if (name.equals("java") || name.startsWith("java.")) { //$NON-NLS-1$ //$NON-NLS-2$
					IHeader jreHeader = getHeader(ICoreConstants.ECLIPSE_JREBUNDLE);
					if (jreHeader == null || !"true".equals(jreHeader.getValue())) { //$NON-NLS-1$
						report(PDECoreMessages.BundleErrorReporter_importNoJRE, getPackageLine(header, elements[i]), CompilerFlags.ERROR, 
								PDEMarkerFactory.M_JAVA_PACKAGE__PORTED,
								PDEMarkerFactory.CAT_FATAL);
						continue;
					}
				}

				if (importSpec.isResolved() || !isCheckUnresolvedImports())
					continue;

				boolean optional = isOptional(elements[i]);
				int severity = getRequireBundleSeverity(elements[i], optional);
				ExportPackageDescription export = (ExportPackageDescription)exported.get(name);
				if (export != null) {
					if (export.getSupplier().isResolved()) {
						Version version = export.getVersion();
						VersionRange range = importSpec.getVersionRange();
						if (range != null && !range.isIncluded(version)) {
							report(NLS.bind(PDECoreMessages.BundleErrorReporter_unsatisfiedConstraint, importSpec.toString()), 
									getPackageLine(header, elements[i]), severity,
									PDEMarkerFactory.CAT_FATAL);	
						}
					} else {
						report(NLS.bind(PDECoreMessages.BundleErrorReporter_unresolvedExporter,
								new String[] {export.getSupplier().getSymbolicName(), name}), 
								getPackageLine(header, elements[i]), severity,
								PDEMarkerFactory.CAT_OTHER);
					}
				} else {
					IMarker marker = report(NLS.bind(PDECoreMessages.BundleErrorReporter_PackageNotExported, name), 
							getPackageLine(header, elements[i]),
							severity, PDEMarkerFactory.M_IMPORT_PKG_NOT_AVAILABLE,
							PDEMarkerFactory.CAT_FATAL);
					try {
						if (marker != null) {
							marker.setAttribute("packageName", name); //$NON-NLS-1$
							if (optional)
								marker.setAttribute("optional", true); //$NON-NLS-1$
						}
					} catch (CoreException e) {
					}
				}
			}
		}
	}

	private void createExportDescriptions(List exportedPackages, HashMap exported) {
		Iterator it = exportedPackages.iterator();
		while(it.hasNext()){
			String descriptor = (String)it.next();
			exported.put(descriptor, new ExportPackageDescriptionImpl(descriptor,"1.0.0"));
		}
	}

	private HashMap getAvailableExportedPackages(State state) throws IOException {
		BundleDescription[] bundles = state.getBundles();

		HashMap exported = new HashMap();

		ConciergeClasspathContainer c = new ConciergeClasspathContainer();
		IClasspathEntry[] entries = c.getClasspathEntries();

		for (int i=0; i< entries.length;++i) {
			ExportPackageDescription desc = adaptExportPackageDescription(entries[i]);
			exported.put(desc.getName(), desc);
		}

		for (int i = 0; i < bundles.length; i++) {
			ExportPackageDescription[] exports = bundles[i].getExportPackages();
			for (int j = 0; j < exports.length; j++) {
				String name = exports[j].getName();
				if (exported.containsKey(name)) {
					if (exports[j].getSupplier().isResolved()) {
						exported.put(name, exports[j]);
					}
				} else {
					exported.put(name, exports[j]);
				}
			}
		}
		return exported;
	}

	private ExportPackageDescription adaptExportPackageDescription(IClasspathEntry classpathEntry) throws ZipException, IOException {

		return new ExportPackageDescriptionImpl(classpathEntry);
	}


	protected void validateExportPackage(IProgressMonitor monitor) {
		IHeader header = getHeader(Constants.EXPORT_PACKAGE);
		if (header == null)
			return;

		String message = null;
		ManifestElement[] elements = header.getElements();

		for (int i = 0; i < elements.length; i++) {
			checkCanceled(monitor);

			validateVersionAttribute(header, elements[i], false);
			validateSpecificationVersionAttribute(header,elements[i]);
			validateX_InternalDirective(header, elements[i]);		
			validateX_FriendsDirective(header, elements[i]);

			String[] valueComps = elements[i].getValueComponents();
			for (int j = 0; j < valueComps.length; j++) {
				String name = valueComps[j];
				if (name.equals("java") || name.startsWith("java.")) { //$NON-NLS-1$ //$NON-NLS-2$
					IHeader jreHeader = getHeader(ICoreConstants.ECLIPSE_JREBUNDLE);
					if (jreHeader == null || !"true".equals(jreHeader.getValue())) { //$NON-NLS-1$
						message = PDECoreMessages.BundleErrorReporter_exportNoJRE;
						report(message, getPackageLine(header, elements[i]), 
								CompilerFlags.ERROR, 
								PDEMarkerFactory.M_JAVA_PACKAGE__PORTED,
								PDEMarkerFactory.CAT_FATAL);
					}
				} else if (".".equals(name.trim())) { //$NON-NLS-1$
					// workaround for manifest converter generating "."
					continue;
				}

				if (!isCheckUnresolvedImports()) {
					continue;
				}

				/* The exported package does not exist in the bundle.  Allow project folders to be packages (see bug 166680 comment 17)*/
				if (!getExportedPackages().contains(name)  && !(fProject.getFolder(name.replace('.', '/')).exists())) {
					message = NLS.bind(PDECoreMessages.BundleErrorReporter_NotExistInProject, name); 
					IMarker marker = report(message, getPackageLine(header, elements[i]),
							CompilerFlags.P_UNRESOLVED_IMPORTS, 
							PDEMarkerFactory.M_EXPORT_PKG_NOT_EXIST,
							PDEMarkerFactory.CAT_OTHER);
					try {
						if (marker != null)
							marker.setAttribute("packageName", name); //$NON-NLS-1$
					} catch (CoreException e) {
					}
				}		
			}
		}

	}

	private boolean containsPackage(IHeader header, String name) {
		if(header != null) {
			ManifestElement[] elements = header.getElements();
			for (int i = 0; i < elements.length; i++) {
				if(elements[i].getValue().equals(name))
					return true;
			}
		}
		return false;
	}

	private Set getExportedPackages() {
		if (fProjectPackages == null) {
			fProjectPackages = new HashSet();
			addProjectPackages(fProject);
			BundleDescription desc = fModel.getBundleDescription();
			if (desc != null) {
				HostSpecification host = desc.getHost();
				if (host != null) {
					addHostPackages(host.getName());
				} else {
					addFragmentPackages(desc.getFragments());
				}
			}
		}
		return fProjectPackages;
	}

	private void addHostPackages(String hostID) {
		IPluginModelBase model = PluginRegistry.findModel(hostID);
		if (model != null) {
			IResource resource = model.getUnderlyingResource();
			if (resource != null) {
				addProjectPackages(resource.getProject());
			} else {
				try {
					if (fProject.hasNature(JavaCore.NATURE_ID)) {
						IPackageFragment[] packages = PluginJavaSearchUtil.collectPackageFragments( new IPluginModelBase[] { model},
								JavaCore.create(fProject), false);
						for (int i = 0; i < packages.length; i++)
							fProjectPackages.add(packages[i].getElementName());
					}
				} catch (CoreException ce) {
				}
			}
		}
	}

	private void addFragmentPackages(BundleDescription[] fragments) {
		for (int i = 0; i < fragments.length; i++) {
			String id = fragments[i].getSymbolicName();
			IPluginModelBase model = PluginRegistry.findModel(id);
			IResource resource = model instanceof IFragmentModel ? model.getUnderlyingResource() : null;
			if (resource != null) {
				addProjectPackages(resource.getProject());
			}
		}
	}

	private void addProjectPackages(IProject proj) {
		try {
			if (proj.hasNature(JavaCore.NATURE_ID)) {
				IJavaProject jp = JavaCore.create(proj);
				IPackageFragmentRoot[] roots = jp.getPackageFragmentRoots();
				for (int i = 0; i < roots.length; i++) {
					if (roots[i].getKind() == IPackageFragmentRoot.K_SOURCE
							|| (roots[i].getKind() == IPackageFragmentRoot.K_BINARY && !roots[i].isExternal())) {
						IJavaElement[] children = roots[i].getChildren();
						for (int j = 0; j < children.length; j++) {
							IPackageFragment f = (IPackageFragment) children[j];
							String name = f.getElementName();
							if (name.equals("")) //$NON-NLS-1$
								name = "."; //$NON-NLS-1$
							if (f.hasChildren() || f.getNonJavaResources().length > 0)
								fProjectPackages.add(name);
						}
					}
				}
			}
		} catch (CoreException ce) {
		}
	}

	protected boolean isCheckDeprecated() {
		return CompilerFlags.getFlag(fProject, CompilerFlags.P_DEPRECATED) != CompilerFlags.IGNORE;
	}

	protected boolean isCheckNoRequiredAttr() {
		return CompilerFlags.getFlag(fProject, CompilerFlags.P_NO_REQUIRED_ATT) != CompilerFlags.IGNORE;
	}

	protected boolean isCheckUnknownClass() {
		return CompilerFlags.getFlag(fProject, CompilerFlags.P_UNKNOWN_CLASS) != CompilerFlags.IGNORE;
	}

	protected boolean isCheckUnresolvedImports() {
		return CompilerFlags.getFlag(fProject,
				CompilerFlags.P_UNRESOLVED_IMPORTS) != CompilerFlags.IGNORE;
	}
	private void validateTranslatableHeaders() {
		int severity = CompilerFlags.getFlag(fProject, CompilerFlags.P_NOT_EXTERNALIZED);
		if (severity == CompilerFlags.IGNORE)
			return;

		for (int i = 0; i < ICoreConstants.TRANSLATABLE_HEADERS.length; i++) {
			IHeader header = getHeader(ICoreConstants.TRANSLATABLE_HEADERS[i]);
			if (header != null) {
				String value = header.getValue();
				if (!value.startsWith("%")) { //$NON-NLS-1$
					report(NLS.bind(PDECoreMessages.Builders_Manifest_non_ext_attribute, header.getName()),
							getLine(header, value),
							severity,
							PDEMarkerFactory.P_UNTRANSLATED_NODE, header.getName(),
							PDEMarkerFactory.CAT_NLS); 
				} else if (fModel instanceof AbstractNLModel) {
					NLResourceHelper helper = ((AbstractNLModel)fModel).getNLResourceHelper();
					if (helper == null || !helper.resourceExists(value))
						report(NLS.bind(PDECoreMessages.Builders_Manifest_key_not_found, 
								value.substring(1)), getLine(header, value), severity,
								PDEMarkerFactory.CAT_NLS);				
				}
			}
		}
	}

	private void validateSpecificationVersionAttribute(IHeader header, ManifestElement element) {
		String version = element.getAttribute(ICoreConstants.PACKAGE_SPECIFICATION_VERSION);
		IStatus status = VersionUtil.validateVersion(version);
		if(!status.isOK()){
			report(status.getMessage(), getPackageLine(header, element), 
					CompilerFlags.ERROR,
					PDEMarkerFactory.CAT_FATAL); 
		}
		if (isCheckDeprecated()) {
			if (fOsgiR4 && version != null) {
				report(NLS.bind(PDECoreMessages.BundleErrorReporter_deprecated_attribute_specification_version,
						ICoreConstants.PACKAGE_SPECIFICATION_VERSION),
						getPackageLine(header, element), CompilerFlags.P_DEPRECATED,
						PDEMarkerFactory.CAT_DEPRECATION); 
			}
		}
	}

	private void validateVersionAttribute(IHeader header, ManifestElement element, boolean range) {
		String version = element.getAttribute(Constants.VERSION_ATTRIBUTE);
		if (version == null)
			return;
		IStatus status = range ? VersionUtil.validateVersionRange(version) 
				: VersionUtil.validateVersion(version);
		if(!status.isOK()) {
			report(status.getMessage(), getPackageLine(header, element), 
					CompilerFlags.ERROR,
					PDEMarkerFactory.CAT_FATAL); 
		}
	}

	private void validateX_InternalDirective(IHeader header, ManifestElement element) {
		String internal = element.getDirective(ICoreConstants.INTERNAL_DIRECTIVE);
		if (internal == null)
			return;

		for (int i = 0; i < BOOLEAN_VALUES.length; i++) {
			if (BOOLEAN_VALUES[i].equals(internal))
				return;
		}
		String message = NLS.bind(PDECoreMessages.BundleErrorReporter_dir_value,
				(new String[] { internal, ICoreConstants.INTERNAL_DIRECTIVE })); 
		report(message, getPackageLine(header, element), CompilerFlags.ERROR,
				PDEMarkerFactory.CAT_FATAL); 
	}

	private void validateX_FriendsDirective(IHeader header, ManifestElement element) {
		String friends = element.getDirective(ICoreConstants.FRIENDS_DIRECTIVE);
		String internal = element.getDirective(ICoreConstants.INTERNAL_DIRECTIVE);
		if (friends != null && internal != null) {
			String message = NLS.bind(
					PDECoreMessages.BundleErrorReporter_directive_hasNoEffectWith_,
					new String[] { ICoreConstants.FRIENDS_DIRECTIVE,
							ICoreConstants.INTERNAL_DIRECTIVE }); 
			IMarker marker = report(message, getPackageLine(header, element), 
					CompilerFlags.WARNING, PDEMarkerFactory.M_DIRECTIVE_HAS_NO_EFFECT,
					PDEMarkerFactory.CAT_OTHER); 
			try {
				if (marker != null)
					marker.setAttribute("packageName", element.getValue()); //$NON-NLS-1$
			} catch (CoreException e) {
			}
		}
	}

	private void validateAutoStart() {
		IHeader header = getHeader(ICoreConstants.ECLIPSE_AUTOSTART);
		if (!validateStartHeader(header))
			return; // valid start header problems already reported
		int severity = CompilerFlags.getFlag(fProject, CompilerFlags.P_DEPRECATED);
		if (severity != CompilerFlags.IGNORE && TargetPlatformHelper.getTargetVersion() >= 3.2) {
			int line = header.getLineNumber();
			report(PDECoreMessages.BundleErrorReporter_startHeader_autoStartDeprecated, line + 1, severity, 
					PDEMarkerFactory.M_DEPRECATED_AUTOSTART,
					PDEMarkerFactory.CAT_DEPRECATION);
		}
	}

	private void validateLazyStart() {
		IHeader header = getHeader(ICoreConstants.ECLIPSE_LAZYSTART);
		int severity = CompilerFlags.getFlag(fProject, CompilerFlags.P_DEPRECATED);
		if (header != null && TargetPlatformHelper.getTargetVersion() < 3.2 && severity != CompilerFlags.IGNORE) {
			report(PDECoreMessages.BundleErrorReporter_lazyStart_unsupported,
					header.getLineNumber() + 1, severity,
					PDEMarkerFactory.NO_RESOLUTION,
					PDEMarkerFactory.CAT_OTHER);
		} else {
			validateStartHeader(header);
		}
	}

	private boolean validateStartHeader(IHeader header) {
		if (header == null)
			return false;
		validateBooleanValue(header);
		return exceptionsAttributesValid(header, header.getElements());
	}

	private boolean exceptionsAttributesValid(IHeader header, ManifestElement[] elements) {
		if (elements == null || elements.length == 0) 
			return true;
		int severity = CompilerFlags.getFlag(fProject, CompilerFlags.P_UNKNOWN_ATTRIBUTE);
		if (severity == CompilerFlags.IGNORE)
			return true;
		Enumeration keys = elements[0].getKeys();
		if (keys != null && keys.hasMoreElements()) {
			String key = (String) keys.nextElement();
			if ("exceptions".equals(key)) { //$NON-NLS-1$
				String[] values = elements[0].getAttributes(key);
				for (int i = 0; i < values.length; i++) {
					StringTokenizer st = new StringTokenizer(values[i], ","); //$NON-NLS-1$
					while (st.hasMoreTokens()) {
						String name = st.nextToken().trim();
						if (!getExportedPackages().contains(name)) {
							String message = NLS.bind(PDECoreMessages.BundleErrorReporter_NotExistInProject, name); 
							report(message, getLine(header, name), CompilerFlags.P_UNRESOLVED_IMPORTS, PDEMarkerFactory.CAT_OTHER);
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	private void validateExtensibleAPI(){
		IHeader header = getHeader(ICoreConstants.EXTENSIBLE_API);
		if (header != null)
			validateBooleanValue(header);		
	}

	public void report(String message, int line, int severity, int problemID, String headerName, String category) {
		try {
			IMarker marker = report(message, line, severity, problemID, category);
			if (marker != null)
				marker.setAttribute(PDEMarkerFactory.MPK_LOCATION_PATH, headerName);
		} catch (CoreException e) {
		}
	}


	private void validateImportExportServices() {
		if(fOsgiR4) {
			IHeader importHeader = getHeader(ICoreConstants.IMPORT_SERVICE);
			IHeader exportHeader = getHeader(ICoreConstants.EXPORT_SERVICE);
			int severity = CompilerFlags.getFlag(fProject, CompilerFlags.P_DEPRECATED);

			if (severity == CompilerFlags.IGNORE)
				return;

			if(importHeader != null) {
				int line = importHeader.getLineNumber();
				report(NLS.bind(PDECoreMessages.BundleErrorReporter_importexport_servicesDeprecated,  ICoreConstants.IMPORT_SERVICE), 
						line + 1, severity, 
						PDEMarkerFactory.M_DEPRECATED_IMPORT_SERVICE,
						PDEMarkerFactory.CAT_DEPRECATION);
			}

			if (exportHeader != null) {
				int line = exportHeader.getLineNumber();
				report(NLS.bind(PDECoreMessages.BundleErrorReporter_importexport_servicesDeprecated, ICoreConstants.EXPORT_SERVICE),
						line + 1, severity, 
						PDEMarkerFactory.M_DEPRECATED_EXPORT_SERVICE,
						PDEMarkerFactory.CAT_DEPRECATION);
			}
		}
	}

	private void validateBundleLocalization() {
		IHeader header = getHeader(Constants.BUNDLE_LOCALIZATION);
		if (header == null)
			return;
		String location = header.getValue();
		String fileName = null; 
		int index = location.lastIndexOf('/');
		if (index > 0) {
			fileName = location.substring(index + 1);
			location = location.substring(0, index);
		} else {
			fileName = location;
			location = new String();
		} 

		IResource res = fProject.findMember(location);
		if (res == null || !(res instanceof IContainer)) {
			report(PDECoreMessages.BundleErrorReporter_localization_folder_not_exist, header.getLineNumber() + 1, 
					CompilerFlags.getFlag(fProject, CompilerFlags.P_UNKNOWN_RESOURCE), PDEMarkerFactory.CAT_OTHER);
			return;
		}
		IContainer folder = (IContainer)res;
		try {
			IResource[] children = folder.members();
			for (int i = 0; i < children.length; i++) {
				if (!(children[i] instanceof IFile))
					continue;
				String childName = children[i].getName();
				if (childName.endsWith(".properties") && childName.startsWith(fileName)) //$NON-NLS-1$
					return;
			}
		} catch (CoreException e) {
		}
		report(PDECoreMessages.BundleErrorReporter_localization_properties_file_not_exist, header.getLineNumber() + 1, 
				CompilerFlags.getFlag(fProject, CompilerFlags.P_UNKNOWN_RESOURCE), PDEMarkerFactory.CAT_OTHER);
	}

	private void validateFragmentHost(IHeader requireBundleHeader, ManifestElement element) {
		IHeader header = getHeader(Constants.FRAGMENT_HOST);
		if(header == null)
			return;

		ManifestElement[] elements = header.getElements();

		if (header != null && elements[0] != null && elements[0].getValue().equals(element.getValue())) {
			IMarker marker = report(NLS.bind(PDECoreMessages.BundleErrorReporter_unecessaryDependencyDueToFragmentHost, element.getValue()),
					getPackageLine(requireBundleHeader, element), CompilerFlags.WARNING, 
					PDEMarkerFactory.M_UNECESSARY_DEP,
					PDEMarkerFactory.CAT_OTHER);
			if (marker != null) {
				try {
					marker.setAttribute("bundleId", element.getValue()); //$NON-NLS-1$
				} catch (CoreException e) {} 
			}
		}
	}
}