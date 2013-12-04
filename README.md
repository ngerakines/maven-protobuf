# About

This project provides a lightweight maven plugin that can be integrated into
maven projects to compile protocol buffers .proto files.

# Usage

Consider the following configuration added to a pom.xml file:

	<properties>
		<protoc>/usr/bin/protoc</protoc>
	</properties>

	<profiles>
		<profile>
			<id>protobuf-build</id>
			<build>
				<plugins>
					<plugin>
						<groupId>com.socklabs</groupId>
						<artifactId>maven-protobuff</artifactId>
						<version>develop-SNAPSHOT</version>
						<executions>
							<execution>
								<id>generate-sources</id>
								<phase>generate-sources</phase>
								<configuration>
									<protoSources>
										<protoSource>src/main/resources/com/socklabs/elasticservices/core/service.proto</protoSource>
									</protoSources>
								</configuration>
								<goals>
									<goal>compile</goal>
								</goals>
							</execution>
						</executions>
					</plugin>
				</plugins>
			</build>
		</profile>
	</profiles>

What we've done is created a profile that, when enabled, will automatically
compile the listed .proto sources into the src/main/java directory relative
to the pom file. This plugin is configurable and can be extended to include
additional source files and include files as well as set the location of
generated sources.

To change the location that generated source files are placed, set the
*protoOutput* configuration value. The default value is **src/main/java**.

	<protoOutput>target/generated-sources/proto</protoOutput>

## Multi Modules

**Note:** If you are building a multi-module project, you may need to make
includes explicit. You must include the current include path as well as any
additional include paths used.

For example, if I've got two modules, foo and bar, that both have .proto
files and the bar module's proto files depend on the foo module's proto files
the directory structure would look something like this:

	parent/foo/src/main/resources/com/socklabs/foo/foo.proto
	parent/bar/src/main/resources/com/socklabs/bar/bar.proto

In the bar.proto, the import directive needs to include the full path
starting at the base parent/foo/src/main/resources directory.

	import "com/socklabs/foo/foo.proto";

    package com.socklabs.bar;
	option java_package = "com.socklabs.bar";
	option java_outer_classname = "BarProto";

In the pom.xml of the bar module, the configuration needs to reflect the
include paths of both the current module and the imported modules's proto
files.

	<configuration>
		<protoSources>
			<protoSource>src/main/resources/com/socklabs/bar/bar.proto</protoSource>
		</protoSources>
		<includePaths>
			<includePath>src/main/resources</includePath>
			<includePath>../foo/src/main/resources</includePath>
		</includePaths>
    </configuration>

# License

Copyright (c) 2013 Nick Gerakines <nick@gerakines.net>

This project and its contents are open source under the MIT license.
