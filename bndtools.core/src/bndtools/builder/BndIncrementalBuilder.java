/*******************************************************************************
 * Copyright (c) 2010 Neil Bartlett.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Neil Bartlett - initial API and implementation
 *******************************************************************************/
package bndtools.builder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.IResourceProxyVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.lib.osgi.Builder;
import bndtools.Plugin;
import bndtools.classpath.BndContainer;
import bndtools.classpath.BndContainerInitializer;
import bndtools.utils.FileUtils;
import bndtools.utils.ResourceDeltaAccumulator;

public class BndIncrementalBuilder extends IncrementalProjectBuilder {

	public static final String BUILDER_ID = Plugin.PLUGIN_ID + ".bndbuilder";
	public static final String MARKER_BND_PROBLEM = Plugin.PLUGIN_ID + ".bndproblem";
	public static final String MARKER_BND_CLASSPATH_PROBLEM = Plugin.PLUGIN_ID + ".bnd_classpath_problem";

	private static final String BND_SUFFIX = ".bnd";

	private static final long NEVER = -1;

	private final Map<String, Long> projectLastBuildTimes = new HashMap<String, Long>();
	private final Map<File, Container> bndsToDeliverables = new HashMap<File, Container>();

	@Override protected IProject[] build(int kind, @SuppressWarnings("rawtypes") Map args, IProgressMonitor monitor)
			throws CoreException {

        IProject project = getProject();

		ensureBndBndExists(project);

		if (getLastBuildTime(project) == -1 || kind == FULL_BUILD) {
			rebuildBndProject(project, monitor);
		} else {
			IResourceDelta delta = getDelta(project);
			if(delta == null)
				rebuildBndProject(project, monitor);
			else
				incrementalRebuild(delta, project, monitor);
		}
		setLastBuildTime(project, System.currentTimeMillis());
		return new IProject[]{ project.getWorkspace().getRoot().getProject(Project.BNDCNF)};
	}
	private void setLastBuildTime(IProject project, long time) {
		projectLastBuildTimes.put(project.getName(), time);
	}
	private long getLastBuildTime(IProject project) {
		Long time = projectLastBuildTimes.get(project.getName());
		return time != null ? time.longValue() : NEVER;
	}
	Collection<IPath> enumerateBndFiles(IProject project) throws CoreException {
		final Collection<IPath> paths = new LinkedList<IPath>();
		project.accept(new IResourceProxyVisitor() {
			public boolean visit(IResourceProxy proxy) throws CoreException {
				if(proxy.getType() == IResource.FOLDER || proxy.getType() == IResource.PROJECT)
					return true;

				String name = proxy.getName();
				if(name.toLowerCase().endsWith(BND_SUFFIX)) {
					IPath path = proxy.requestFullPath();
					paths.add(path);
				}
				return false;
			}
		}, 0);
		return paths;
	}
	void ensureBndBndExists(IProject project) throws CoreException {
		IFile bndFile = project.getFile(Project.BNDFILE);
		bndFile.refreshLocal(0, null);
		if(!bndFile.exists()) {
			bndFile.create(new ByteArrayInputStream(new byte[0]), 0, null);
		}
	}
	@Override protected void clean(IProgressMonitor monitor)
			throws CoreException {
		// Clear markers
		getProject().deleteMarkers(MARKER_BND_PROBLEM, true,
				IResource.DEPTH_INFINITE);

		// Delete target files
		Project model = Plugin.getDefault().getCentral().getModel(JavaCore.create(getProject()));
		try {
			model.clean();
		} catch (Exception e) {
			throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error cleaning project outputs.", e));
		}
	}
	void incrementalRebuild(IResourceDelta delta, IProject project, IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor);
		Project model = Plugin.getDefault().getCentral().getModel(JavaCore.create(project));
		// model.refresh();

		try {
			List<File> affectedFiles = new ArrayList<File>();
			final File targetDir = model.getTarget();
			FileFilter generatedFilter = new FileFilter() {
				public boolean accept(File pathname) {
					return !FileUtils.isAncestor(targetDir, pathname);
				}
			};
			ResourceDeltaAccumulator visitor = new ResourceDeltaAccumulator(IResourceDelta.ADDED | IResourceDelta.CHANGED | IResourceDelta.REMOVED, affectedFiles, generatedFilter);
			delta.accept(visitor);

			progress.setWorkRemaining(affectedFiles.size() + 10);

			boolean rebuild = false;
			List<File> deletedBnds = new LinkedList<File>();

			// Check if any affected file is a bnd file
			for (File file : affectedFiles) {
				if(file.getName().toLowerCase().endsWith(BND_SUFFIX)) {
					rebuild = true;
					int deltaKind = visitor.queryDeltaKind(file);
					if((deltaKind & IResourceDelta.REMOVED) > 0) {
						deletedBnds.add(file);
					}
					break;
				}
			}
			if(!rebuild && !affectedFiles.isEmpty()) {
				// Check if any of the affected files are members of bundles built by a sub builder
                Collection<? extends Builder> builders = model.getSubBuilders();
				for (Builder builder : builders) {
					File buildPropsFile = builder.getPropertiesFile();
					if(affectedFiles.contains(buildPropsFile)) {
						rebuild = true;
						break;
					} else if(builder.isInScope(affectedFiles)) {
						rebuild = true;
						break;
					}
					progress.worked(1);
				}
			}

			// Delete corresponding bundles for deleted Bnds
			for (File bndFile : deletedBnds) {
				Container container = bndsToDeliverables.get(bndFile);
				if(container != null) {
					IResource resource = FileUtils.toWorkspaceResource(container.getFile());
					resource.delete(false, null);
				}
			}

			if(rebuild)
				rebuildBndProject(project, monitor);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		model.refresh();
	}
	void rebuildBndProject(IProject project, IProgressMonitor monitor) throws CoreException {
		IJavaProject javaProject = JavaCore.create(project);

        Project model = Plugin.getDefault().getCentral().getModel(javaProject);
		model.refresh();
		model.setChanged();

		// Get or create the build model for this bnd file
		IFile bndFile = project.getFile(Project.BNDFILE);

		// Clear markers
		if (bndFile.exists()) {
			bndFile.deleteMarkers(MARKER_BND_PROBLEM, true, IResource.DEPTH_INFINITE);
		}

		// Update classpath
		JavaCore.setClasspathContainer(BndContainerInitializer.ID, new IJavaProject[] { javaProject } , new IClasspathContainer[] { new BndContainer(javaProject, BndContainerInitializer.calculateEntries(model)) }, null);

		// Build
		try {
		    final Set<File> deliverableJars = new HashSet<File>();
			bndsToDeliverables.clear();
            Collection<? extends Builder> builders = model.getSubBuilders();
			for (Builder builder : builders) {
				File subBndFile = builder.getPropertiesFile();
				String bsn = builder.getBsn();
				Container deliverable = model.getDeliverable(bsn, null);
				bndsToDeliverables.put(subBndFile, deliverable);
				deliverableJars.add(deliverable.getFile());
			}

			BndBuildJob.scheduleBuild(bndFile, model, deliverableJars);
		} catch (Exception e) {
			throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error building project.", e));
		}

	}
}
