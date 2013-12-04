package com.socklabs.maven.plugin.protobuf;


import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.io.RawInputStreamFacade;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Extract and compile protocol buffer from maven dependencies.
 *
 * @phase generate-sources
 * @goal compile
 * @requiresDependencyResolution compile
 */
public class ProtobuffMojo extends AbstractMojo {

	/**
	 * @parameter default-value="true"
	 */
	private Boolean clear;

	/**
	 * @parameter default-value="false"
	 */
	private Boolean verbose;

	/**
	 * @parameter default-value="false"
	 */
	private Boolean buildAnyway;

	/**
	 * @parameter default-value="src/main/java/"
	 */
	private String protoOutput;

	/**
	 * @parameter default-value="protoc"
	 */
	private String protoc;

	/**
	 * @parameter
	 */
	private String[] protoSources;

	/**
	 * @parameter
	 */
	private String[] includePaths;

	/**
	 * @parameter expression="${project}"
	 */
	private MavenProject project;

	public void execute() throws MojoExecutionException, MojoFailureException {
		final Iterable<File> classpathElementFiles = getDependencyArtifactFiles();
		final String tempDirPath = tempDirectory();
		final File tempDirFile = new File(tempDirPath);
		boolean hasExtractedFiles = false;

		final boolean shouldExtract = shouldBuild();
		if (shouldExtract) {
			final List<Pair<JarFile, JarEntry>> protoJarEntries = findExtractableProtos(classpathElementFiles);
			for (final Pair<JarFile, JarEntry> pair : protoJarEntries) {
				clearTempDir(hasExtractedFiles, tempDirFile);
				extractProtos(hasExtractedFiles, tempDirFile, pair.getSecond(), pair.getFirst());
				hasExtractedFiles = true;
			}
		}

		if (protoSources != null && protoSources.length > 0) {
			final String[] processedIncludePaths = getDefaultPaths(includePaths, hasExtractedFiles);
			final List<String> protocCommandParams = buildProtocCommand(
					processIncludePaths(processedIncludePaths),
					processProtos(protoSources),
					processOutputDirectory(getPath(protoOutput)));
			try {
				compile(protoc, protocCommandParams);
			} catch (final CommandLineException e) {
				getLog().error("Error executing command.", e);
			}
		} else {
			if (verbose) {
				getLog().warn("No proto files were configured to be compiled.");
			}
		}
	}

	/**
	 * Compose a path to the temporary directory that protofiles are extracted to.
	 */
	private String tempDirectory() {
		return getPath("target" + File.separator + "protos");
	}

	/**
	 * For each of the project's dependencies, iterate through them and find any jars that may contain proto files.
	 */
	private List<Pair<JarFile, JarEntry>> findExtractableProtos(final Iterable<File> classpathElementFiles) {
		final List<Pair<JarFile, JarEntry>> jarEntries = new ArrayList<>();
		for (final File classpathElementFile : classpathElementFiles) {
			if (isValidClasspathElementFile(classpathElementFile)) {
				try {
					final JarFile classpathJar = new JarFile(classpathElementFile);
					jarEntries.addAll(collectProtosFromJarFile(classpathJar));
				} catch (final IOException e) {
					getLog().error("Could not processed classpath file " + classpathElementFile.getPath());
				}
			}
		}
		return jarEntries;
	}

	/**
	 * For each of the jars given, find an proto files.
	 */
	private List<Pair<JarFile, JarEntry>> collectProtosFromJarFile(final JarFile classpathJar) {
		final List<Pair<JarFile, JarEntry>> jarEntries = new ArrayList<>();
		for (final JarEntry jarEntry : Collections.list(classpathJar.entries())) {
			if (isProto(jarEntry.getName())) {
				jarEntries.add(new Pair<>(classpathJar, jarEntry));
			}
		}
		return jarEntries;
	}

	private void extractProtos(
			final boolean hasExtractedFiles,
			final File tempDirFile,
			final JarEntry jarEntry,
			final JarFile classpathJar) {
		if (verbose) {
			getLog().info("Found proto file " + jarEntry.getName());
		}
		final String jarEntryName = jarEntry.getName();
		final File uncompressedCopy = new File(tempDirFile, jarEntryName);
		uncompressedCopy.getParentFile().mkdirs();
		try {
			FileUtils.copyStreamToFile(
					new RawInputStreamFacade(classpathJar.getInputStream(jarEntry)),
					uncompressedCopy);
		} catch (final IOException e) {
			getLog().error(e);
		}
	}

	/**
	 * Attempt to delete the temporary directory on first run if the plugin is configured to do so.
	 */
	private void clearTempDir(final boolean hasExtractedFiles, final File tempDirFile) {
		if (hasExtractedFiles && clear) {
			if (verbose) {
				getLog().info("Deleting temp dir " + tempDirFile.getPath());
			}
			tempDirFile.delete();
		}
	}

	/**
	 * Determine if we should extract files by looking at the number of proto sources given and or the buildAnyway
	 * parameter.
	 */
	private boolean shouldBuild() {
		if (buildAnyway) {
			return true;
		}
		if (protoSources != null && protoSources.length > 0) {
			return true;
		}
		return false;
	}

	/**
	 * Compose a list of include paths to provide to the compiler.
	 * <p>
	 * If the user provides a list of includes, that list is used as-is. If no list is manually configured, a default
	 * list is used that includes the src/main/resources directory as well as the target/protos directory.
	 * </p>
	 */
	private String[] getDefaultPaths(final String[] providedIncludePaths, final boolean hasExtractedFiles) {
		if (providedIncludePaths != null && providedIncludePaths.length > 0) {
			return providedIncludePaths;
		}

		final List<String> defaultPaths = new ArrayList<>();
		defaultPaths.add("src/main/resources/");
		if (hasExtractedFiles) {
			defaultPaths.add("target/protos/");
		}
		if (verbose) {
			getLog().info("Setting default include paths: " + defaultPaths.toString());
		}
		return defaultPaths.toArray(new String[defaultPaths.size()]);
	}

	/**
	 * Determine if a given classpath element can be used to find proto files.
	 */
	private boolean isValidClasspathElementFile(final File classpathElementFile) {
		if (classpathElementFile.isFile() && classpathElementFile.canRead()) {
			if (!classpathElementFile.getName().endsWith(".xml")) {
				return true;
			}
		}
		return false;
	}

	private boolean isProto(final String jarEntryName) {
		return jarEntryName.endsWith(".proto");
	}

	private Set<File> getDependencyArtifactFiles() {
		final Set<File> dependencyArtifactFiles = new HashSet<>();
		for (final Artifact artifact : (List<Artifact>)project.getCompileArtifacts()) {
			dependencyArtifactFiles.add(artifact.getFile());
		}
		return dependencyArtifactFiles;
	}

	/**
	 * Create a command line object, populate it with configured and obtained information and attempt to execute it.
	 */
	private int compile(final String executable, final List<String> commandParams) throws CommandLineException {
		final CommandLineUtils.StringStreamConsumer output = new CommandLineUtils.StringStreamConsumer();
		final CommandLineUtils.StringStreamConsumer error = new CommandLineUtils.StringStreamConsumer();
		final Commandline cl = new Commandline();
		cl.setExecutable(executable);
		cl.addArguments(commandParams.toArray(new String[commandParams.size()]));
		if (verbose) {
			getLog().info(cl.toString());
		}
		final int ret = CommandLineUtils.executeCommandLine(cl, null, output, error);
		if (verbose) {
			getLog().info(output.getOutput());
		}
		if (ret != 0) {
			getLog().error(error.getOutput());
		}
		return ret;
	}

	/**
	 * Build a list of command line parameters out of a list of provided include directories, proto files and a output
	 * directory.
	 */
	private List<String> buildProtocCommand(
			final Set<File> protoPathElements,
			final Set<File> protoFiles,
			final File javaOutputDirectory) {
		final List<String> command = new ArrayList<>();
		for (final File protoPathElement : protoPathElements) {
			command.add("--proto_path=" + protoPathElement);
		}
		command.add("--java_out=" + javaOutputDirectory);
		for (final File protoFile : protoFiles) {
			command.add(protoFile.toString());
		}
		return command;
	}

	/**
	 * For a list of paths, determine if they can be used as protobuf compiler include paths and return the valided
	 * list.
	 */
	private Set<File> processIncludePaths(final String[] paths) {
		final Set<File> filePaths = new HashSet<>();
		for (final String path : paths) {
			final String fullPath = getPath(path);
			final File includePathFile = new File(fullPath);
			if (includePathFile.exists() && includePathFile.isDirectory()) {
				filePaths.add(includePathFile);
			} else {
				if (verbose) {
					getLog().error("Could not find path " + fullPath);
				}
			}
		}
		return filePaths;
	}

	/**
	 * For a list of paths, determine if they are files that can be given to the protobuf compiler as protobuf source
	 * files and return the list.
	 */
	private Set<File> processProtos(final String[] paths) {
		final Set<File> filePaths = new HashSet<>();
		for (final String path : paths) {
			final String fullPath = getPath(path);
			final File protoFile = new File(fullPath);
			if (protoFile.exists() && protoFile.isFile()) {
				filePaths.add(protoFile);
			} else {
				if (verbose) {
					getLog().error("Can't find file " + fullPath);
				}
			}
		}
		return filePaths;
	}

	/**
	 * For a given path, determine if it exists and if not, create it.
	 */
	private File processOutputDirectory(final String path) {
		final File outputDirectory = new File(path);
		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs();
		}
		return outputDirectory;
	}

	private String getPath(final String path) {
		return project.getBasedir().getPath() + File.separator + path;
	}

	/**
	 * A small pair object used to create list of jar and jar entry objects within the parent class.
	 */
	private static class Pair<A, B> {

		private final A first;
		private final B second;

		public Pair(final A firstParam, final B secondParam) {
			this.first = firstParam;
			this.second = secondParam;
		}

		public A getFirst() {
			return first;
		}

		public B getSecond() {
			return second;
		}
	}

}
