// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.java;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.SkylarkProvider.SkylarkKey;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.OutputJar;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests Skylark API for Java rules.
 */
@RunWith(JUnit4.class)
public class JavaSkylarkApiTest extends BuildViewTestCase {
  private static final String HOST_JAVA_RUNTIME_LABEL = TestConstants.TOOLS_REPOSITORY
      + "//tools/jdk:current_host_java_runtime";

  @Test
  public void testJavaRuntimeProviderJavaExecutableAbsolute() throws Exception {
    scratch.file("a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "java_runtime(name='jvm', srcs=[], java_home='/foo/bar/')",
        "java_runtime_suite(name='suite', default=':jvm')",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')");

    scratch.file(
        "a/rule.bzl",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return struct(",
        "    java_executable = provider.java_executable_exec_path,",
        "    java_runfiles = provider.java_executable_runfiles_path,",
        ")",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--javabase=//a:suite");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked") PathFragment javaExecutable =
        (PathFragment) ct.get("java_executable");
    assertThat(javaExecutable.getPathString()).startsWith("/foo/bar/bin/java");
    @SuppressWarnings("unchecked") PathFragment javaRunfiles =
        (PathFragment) ct.get("java_runfiles");
    assertThat(javaRunfiles.getPathString()).startsWith("/foo/bar/bin/java");
  }

  @Test
  public void testJavaRuntimeProviderJavaExecutableHermetic() throws Exception {
    scratch.file("a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "java_runtime(name='jvm', srcs=[], java_home='foo/bar')",
        "java_runtime_suite(name='suite', default=':jvm')",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')");

    scratch.file(
        "a/rule.bzl",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return struct(",
        "    java_executable = provider.java_executable_exec_path,",
        "    java_runfiles = provider.java_executable_runfiles_path,",
        ")",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--javabase=//a:suite");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked") PathFragment javaExecutable =
        (PathFragment) ct.get("java_executable");
    assertThat(javaExecutable.getPathString()).startsWith("a/foo/bar/bin/java");
    @SuppressWarnings("unchecked") PathFragment javaRunfiles =
        (PathFragment) ct.get("java_runfiles");
    assertThat(javaRunfiles.getPathString()).startsWith("a/foo/bar/bin/java");
  }

  @Test
  public void testJavaRuntimeProviderJavaHome() throws Exception {
    scratch.file("a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "java_runtime(name='jvm', srcs=[], java_home='/foo/bar/')",
        "java_runtime_suite(name='suite', default=':jvm')",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')");

    scratch.file(
        "a/rule.bzl",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return struct(",
        "    java_home = provider.java_home",
        ")",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--javabase=//a:suite");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked") PathFragment javaHome =
        (PathFragment) ct.get("java_home");
    assertThat(javaHome.getPathString()).isEqualTo("/foo/bar");
  }

  @Test
  public void testInvalidHostJavabase() throws Exception {
    writeBuildFileForJavaToolchain();

    scratch.file("a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "filegroup(name='fg')",
        "jrule(name='r', srcs=['S.java'])");

    scratch.file("a/rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  return struct()",
        "jrule = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "    '_host_javabase': attr.label(default = Label('//a:fg'))",
        "  },",
        "  fragments = ['java'])");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:r");
    assertContainsEvent("must point to a Java runtime");
  }

  @Test
  public void testExposesJavaCommonProvider() throws Exception {
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")",
        "my_rule(",
        "  name = 'my',",
        "  dep = ':dep',",
        ")");
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep[java_common.provider]",
        "   return [result(",
        "             transitive_runtime_jars = depj.transitive_runtime_jars,",
        "             transitive_compile_time_jars = depj.transitive_compile_time_jars,",
        "             compile_jars = depj.compile_jars,",
        "             full_compile_jars = depj.full_compile_jars,",
        "             source_jars = depj.source_jars,",
        "             outputs = depj.outputs,",
        "          )]",
        "my_rule = rule(impl, attrs = { 'dep' : attr.label() })");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    Info info =
        configuredTarget.get(
            new SkylarkKey(Label.parseAbsolute("//java/test:extension.bzl"), "result"));

    SkylarkNestedSet transitiveRuntimeJars =
        ((SkylarkNestedSet) info.getValue("transitive_runtime_jars"));
    SkylarkNestedSet transitiveCompileTimeJars =
        ((SkylarkNestedSet) info.getValue("transitive_compile_time_jars"));
    SkylarkNestedSet compileJars = ((SkylarkNestedSet) info.getValue("compile_jars"));
    SkylarkNestedSet fullCompileJars = ((SkylarkNestedSet) info.getValue("full_compile_jars"));
    SkylarkList<Artifact> sourceJars = ((SkylarkList<Artifact>) info.getValue("source_jars"));
    JavaRuleOutputJarsProvider outputs = ((JavaRuleOutputJarsProvider) info.getValue("outputs"));

    assertThat(artifactFilesNames(transitiveRuntimeJars.toCollection(Artifact.class)))
        .containsExactly("libdep.jar");
    assertThat(artifactFilesNames(transitiveCompileTimeJars.toCollection(Artifact.class)))
        .containsExactly("libdep-hjar.jar");
    assertThat(transitiveCompileTimeJars.toCollection()).isEqualTo(compileJars.toCollection());
    assertThat(artifactFilesNames(fullCompileJars.toCollection(Artifact.class)))
        .containsExactly("libdep.jar");
    assertThat(artifactFilesNames(sourceJars)).containsExactly("libdep-src.jar");

    assertThat(outputs.getOutputJars()).hasSize(1);
    OutputJar output = outputs.getOutputJars().get(0);
    assertThat(output.getClassJar().getFilename()).isEqualTo("libdep.jar");
    assertThat(output.getIJar().getFilename()).isEqualTo("libdep-hjar.jar");
    assertThat(artifactFilesNames(output.getSrcJars())).containsExactly("libdep-src.jar");
    assertThat(outputs.getJdeps().getFilename()).isEqualTo("libdep.jdeps");
  }

  @Test
  public void testJavaCommonCompileExposesOutputJarProvider() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file("java/test/B.jar");
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "name = 'dep',",
        "srcs = ['Main.java'],",
        "sourcepath = [':B.jar']",
        ")",
        "my_rule(",
        "  name = 'my',",
        "  dep = ':dep',",
        ")");
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep[java_common.provider]",
        "   return [result(",
        "             transitive_runtime_jars = depj.transitive_runtime_jars,",
        "             transitive_compile_time_jars = depj.transitive_compile_time_jars,",
        "             compile_jars = depj.compile_jars,",
        "             full_compile_jars = depj.full_compile_jars,",
        "             source_jars = depj.source_jars,",
        "             outputs = depj.outputs,",
        "          )]",
        "my_rule = rule(impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    javac_opts = java_common.default_javac_opts(",
        "        ctx, java_toolchain_attr = '_java_toolchain'),",
        "    deps = [],",
        "    sourcepath = ctx.files.sourcepath,",
        "    strict_deps = 'ERROR',",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  return struct(",
        "    files = depset([output_jar]),",
        "    providers = [compilation_provider]",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'sourcepath': attr.label_list(allow_files=['.jar']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "    '_host_javabase': attr.label(",
        "        default = Label('" + HOST_JAVA_RUNTIME_LABEL + "'))",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    Info info =
        configuredTarget.get(
            new SkylarkKey(Label.parseAbsolute("//java/test:extension.bzl"), "result"));

    JavaRuleOutputJarsProvider outputs = ((JavaRuleOutputJarsProvider) info.getValue("outputs"));
    assertThat(outputs.getOutputJars()).hasSize(1);

    OutputJar outputJar = outputs.getOutputJars().get(0);
    assertThat(outputJar.getClassJar().getFilename()).isEqualTo("libdep.jar");
    assertThat(outputJar.getIJar().getFilename()).isEqualTo("libdep-hjar.jar");
    assertThat(prettyArtifactNames(outputJar.getSrcJars()))
        .containsExactly("java/test/libdep-src.jar");
    assertThat(outputs.getJdeps().getFilename()).isEqualTo("libdep.jdeps");
  }

  @Test
  public void testJavaCommonCompileTransitiveSourceJars() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['Main.java'],",
        "  deps = [':dep']",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  deps = [dep[java_common.provider] for dep in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    javac_opts = java_common.default_javac_opts(",
        "        ctx, java_toolchain_attr = '_java_toolchain'),",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  return struct(",
        "    files = depset([output_jar]),",
        "    providers = [compilation_provider]",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "    '_host_javabase': attr.label(",
        "        default = Label('" + HOST_JAVA_RUNTIME_LABEL + "'))",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    SkylarkList<Artifact> sourceJars = info.getSourceJars();
    NestedSet<Artifact> transitiveSourceJars = info.getTransitiveSourceJars();
    assertThat(artifactFilesNames(sourceJars)).containsExactly("libcustom-src.jar");
    assertThat(artifactFilesNames(transitiveSourceJars))
        .containsExactly("libdep-src.jar", "libcustom-src.jar");
  }

  @Test
  public void testJavaCommonCompileSourceJarName() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['Main.java'],",
        "  deps = [':dep']",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('amazing.jar')",
        "  other_output_jar = ctx.actions.declare_file('wonderful.jar')",
        "  deps = [dep[java_common.provider] for dep in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    javac_opts = java_common.default_javac_opts(",
        "        ctx, java_toolchain_attr = '_java_toolchain'),",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  other_compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = other_output_jar,",
        "    javac_opts = java_common.default_javac_opts(",
        "        ctx, java_toolchain_attr = '_java_toolchain'),",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  result_provider = java_common.merge([compilation_provider, other_compilation_provider])",
        "  return struct(",
        "    files = depset([output_jar]),",
        "    providers = [result_provider]",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'amazing.jar',",
        "    'my_second_output': 'wonderful.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "    '_host_javabase': attr.label(",
        "        default = Label('" + HOST_JAVA_RUNTIME_LABEL + "'))",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    SkylarkList<Artifact> sourceJars = info.getSourceJars();
    NestedSet<Artifact> transitiveSourceJars = info.getTransitiveSourceJars();
    assertThat(artifactFilesNames(sourceJars)).containsExactly(
        "amazing-src.jar", "wonderful-src.jar");
    assertThat(artifactFilesNames(transitiveSourceJars))
        .containsExactly("libdep-src.jar", "amazing-src.jar", "wonderful-src.jar");
  }

  @Test
  public void testJavaCommonCompileWithOnlyOneSourceJar() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['myjar-src.jar'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_jars = ctx.files.srcs,",
        "    output = output_jar,",
        "    javac_opts = java_common.default_javac_opts(",
        "        ctx, java_toolchain_attr = '_java_toolchain'),",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  return struct(",
        "    files = depset([output_jar]),",
        "    providers = [compilation_provider]",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.jar']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "    '_host_javabase': attr.label(",
        "        default = Label('" + HOST_JAVA_RUNTIME_LABEL + "'))",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    SkylarkList<Artifact> sourceJars = info.getSourceJars();
    assertThat(artifactFilesNames(sourceJars)).containsExactly("myjar-src.jar");
    JavaRuleOutputJarsProvider outputJars = info.getOutputJars();
    assertThat(outputJars.getOutputJars()).hasSize(1);
    OutputJar outputJar = outputJars.getOutputJars().get(0);
    assertThat((outputJar.getClassJar().getFilename())).isEqualTo("libcustom.jar");
    assertThat((outputJar.getSrcJar().getFilename())).isEqualTo("myjar-src.jar");
    assertThat((outputJar.getIJar().getFilename())).isEqualTo("libcustom-hjar.jar");
    assertThat(outputJars.getJdeps().getFilename()).isEqualTo("libcustom.jdeps");
  }

  @Test
  public void testJavaCommonCompileWithNoSources() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    javac_opts = java_common.default_javac_opts(",
        "        ctx, java_toolchain_attr = '_java_toolchain'),",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  return struct(",
        "    files = depset([output_jar]),",
        "    providers = [compilation_provider]",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "    '_host_javabase': attr.label(",
        "        default = Label('" + HOST_JAVA_RUNTIME_LABEL + "'))",
        "  },",
        "  fragments = ['java']",
        ")");
    try {
      getConfiguredTarget("//java/test:custom");
    } catch (AssertionError e) {
      assertThat(e.getMessage())
          .contains("source_jars, sources and exports cannot be simultaneous empty");
    }

  }

  @Test
  public void testExposesJavaSkylarkApiProvider() throws Exception {
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")",
        "my_rule(",
        "  name = 'my',",
        "  dep = ':dep',",
        ")");
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep.java",
        "   return [result(",
        "             source_jars = depj.source_jars,",
        "             transitive_deps = depj.transitive_deps,",
        "             transitive_runtime_deps = depj.transitive_runtime_deps,",
        "             transitive_source_jars = depj.transitive_source_jars,",
        "             outputs = depj.outputs.jars,",
        "          )]",
        "my_rule = rule(impl, attrs = { 'dep' : attr.label() })");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    Info info =
        configuredTarget.get(
            new SkylarkKey(Label.parseAbsolute("//java/test:extension.bzl"), "result"));

    SkylarkNestedSet sourceJars = ((SkylarkNestedSet) info.getValue("source_jars"));
    SkylarkNestedSet transitiveDeps = ((SkylarkNestedSet) info.getValue("transitive_deps"));
    SkylarkNestedSet transitiveRuntimeDeps =
        ((SkylarkNestedSet) info.getValue("transitive_runtime_deps"));
    SkylarkNestedSet transitiveSourceJars =
        ((SkylarkNestedSet) info.getValue("transitive_source_jars"));
    SkylarkList<OutputJar> outputJars = ((SkylarkList<OutputJar>) info.getValue("outputs"));

    assertThat(artifactFilesNames(sourceJars.toCollection(Artifact.class)))
        .containsExactly("libdep-src.jar");
    assertThat(artifactFilesNames(transitiveDeps.toCollection(Artifact.class)))
        .containsExactly("libdep-hjar.jar");
    assertThat(artifactFilesNames(transitiveRuntimeDeps.toCollection(Artifact.class)))
        .containsExactly("libdep.jar");
    assertThat(artifactFilesNames(transitiveSourceJars.toCollection(Artifact.class)))
        .containsExactly("libdep-src.jar");
    assertThat(outputJars).hasSize(1);
    assertThat(outputJars.get(0).getClassJar().getFilename()).isEqualTo("libdep.jar");
  }

  private static Collection<String> artifactFilesNames(Iterable<Artifact> artifacts) {
    List<String> result = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      result.add(artifact.getFilename());
    }
    return result;
  }

  @Test
  public void testJavaPlugin() throws Exception {
    scratch.file(
      "java/test/extension.bzl",
      "result = provider()",
      "def impl(ctx):",
      "   depj = ctx.attr.dep.java",
      "   return [result(",
      "             processor_classpath = depj.annotation_processing.processor_classpath,",
      "             processor_classnames = depj.annotation_processing.processor_classnames,",
      "          )]",
      "my_rule = rule(impl, attrs = { 'dep' : attr.label() })"
    );
    scratch.file(
      "java/test/BUILD",
      "load(':extension.bzl', 'my_rule')",
      "java_library(name = 'plugin_dep',",
      "    srcs = [ 'ProcessorDep.java'])",
      "java_plugin(name = 'plugin',",
      "    srcs = ['AnnotationProcessor.java'],",
      "    processor_class = 'com.google.process.stuff',",
      "    deps = [ ':plugin_dep' ])",
      "java_library(name = 'to_be_processed',",
      "    plugins = [':plugin'],",
      "    srcs = ['ToBeProcessed.java'])",
      "my_rule(name = 'my', dep = ':to_be_processed')");
    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    Info info =
        configuredTarget.get(
            new SkylarkKey(Label.parseAbsolute("//java/test:extension.bzl"), "result"));

    assertThat((List<?>) info.getValue("processor_classnames"))
        .containsExactly("com.google.process.stuff");
    assertThat(
            Iterables.transform(
                ((SkylarkNestedSet) info.getValue("processor_classpath")).toCollection(),
                new Function<Object, String>() {
                  @Override
                  public String apply(Object o) {
                    return ((Artifact) o).getFilename();
                  }
                }))
        .containsExactly("libplugin.jar", "libplugin_dep.jar");
  }

  @Test
  public void testJavaProviderFieldsAreSkylarkAccessible() throws Exception {
    // The Skylark evaluation itself will test that compile_jars and
    // transitive_runtime_jars are returning a list readable by Skylark with
    // the expected number of entries.
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   java_provider = ctx.attr.dep[JavaInfo]",
        "   return [result(",
        "             compile_jars = java_provider.compile_jars,",
        "             transitive_runtime_jars = java_provider.transitive_runtime_jars,",
        "             transitive_compile_time_jars = java_provider.transitive_compile_time_jars,",
        "          )]",
        "my_rule = rule(impl, attrs = { ",
        "  'dep' : attr.label(), ",
        "  'cnt_cjar' : attr.int(), ",
        "  'cnt_rjar' : attr.int(), ",
        "})");
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'parent',",
        "    srcs = [ 'Parent.java'])",
        "java_library(name = 'jl',",
        "    srcs = ['Jl.java'],",
        "    deps = [ ':parent' ])",
        "my_rule(name = 'my', dep = ':jl', cnt_cjar = 1, cnt_rjar = 2)");
    // Now, get that information and ensure it is equal to what the jl java_library
    // was presenting
    ConfiguredTarget myConfiguredTarget = getConfiguredTarget("//java/test:my");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//java/test:jl");

    // Extract out the information from skylark rule
    Info info =
        myConfiguredTarget.get(
            new SkylarkKey(Label.parseAbsolute("//java/test:extension.bzl"), "result"));

    SkylarkNestedSet rawMyCompileJars = (SkylarkNestedSet) (info.getValue("compile_jars"));
    SkylarkNestedSet rawMyTransitiveRuntimeJars =
        (SkylarkNestedSet) (info.getValue("transitive_runtime_jars"));
    SkylarkNestedSet rawMyTransitiveCompileTimeJars =
        (SkylarkNestedSet) (info.getValue("transitive_compile_time_jars"));

    NestedSet<Artifact> myCompileJars = rawMyCompileJars.getSet(Artifact.class);
    NestedSet<Artifact> myTransitiveRuntimeJars = rawMyTransitiveRuntimeJars.getSet(Artifact.class);
    NestedSet<Artifact> myTransitiveCompileTimeJars =
        rawMyTransitiveCompileTimeJars.getSet(Artifact.class);

    // Extract out information from native rule
    JavaCompilationArgsProvider jlJavaCompilationArgsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, javaLibraryTarget);
    NestedSet<Artifact> jlCompileJars =
        jlJavaCompilationArgsProvider.getJavaCompilationArgs().getCompileTimeJars();
    NestedSet<Artifact> jlTransitiveRuntimeJars =
        jlJavaCompilationArgsProvider.getRecursiveJavaCompilationArgs().getRuntimeJars();
    NestedSet<Artifact> jlTransitiveCompileTimeJars =
        jlJavaCompilationArgsProvider.getRecursiveJavaCompilationArgs().getCompileTimeJars();

    // Using reference equality since should be precisely identical
    assertThat(myCompileJars == jlCompileJars).isTrue();
    assertThat(myTransitiveRuntimeJars == jlTransitiveRuntimeJars).isTrue();
    assertThat(myTransitiveCompileTimeJars).isEqualTo(jlTransitiveCompileTimeJars);
  }

  @Test
  public void testSkylarkApiProviderReexported() throws Exception {
    scratch.file(
        "java/test/extension.bzl",
        "def impl(ctx):",
        "   dep_java = ctx.attr.dep.java",
        "   return struct(java = dep_java)",
        "my_rule = rule(impl, attrs = { ",
        "  'dep' : attr.label(), ",
        "})");
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl', srcs = ['Jl.java'])",
        "my_rule(name = 'my', dep = ':jl')");
    // Now, get that information and ensure it is equal to what the jl java_library
    // was presenting
    ConfiguredTarget myConfiguredTarget = getConfiguredTarget("//java/test:my");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//java/test:jl");

    assertThat(myConfiguredTarget.get("java")).isSameAs(
        javaLibraryTarget.get("java")
    );
  }


  @Test
  public void javaProviderFieldsAreCorrectAfterCreatingProvider() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  my_provider = java_common.create_provider(",
        "        compile_time_jars = ctx.files.compile_time_jars,",
        "        use_ijar = False,",
        "        runtime_jars = ctx.files.runtime_jars,",
        "        transitive_compile_time_jars = ctx.files.transitive_compile_time_jars,",
        "        transitive_runtime_jars = ctx.files.transitive_runtime_jars,",
        "        source_jars = depset(ctx.files.source_jars))",
        "  return [my_provider]",
        "my_rule = rule(_impl, ",
        "    attrs = { ",
        "        'compile_time_jars' : attr.label_list(allow_files=['.jar']),",
        "        'full_compile_time_jars' : attr.label_list(allow_files=['.jar']),",
        "        'runtime_jars': attr.label_list(allow_files=['.jar']),",
        "        'transitive_compile_time_jars': attr.label_list(allow_files=['.jar']),",
        "        'transitive_runtime_jars': attr.label_list(allow_files=['.jar']),",
        "        'source_jars': attr.label_list(allow_files=['.jar'])",
        "})");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'myrule',",
        "    compile_time_jars = ['liba.jar'],",
        "    runtime_jars = ['libb.jar'],",
        "    transitive_compile_time_jars = ['libc.jar'],",
        "    transitive_runtime_jars = ['libd.jar'],",
        ")");
    ConfiguredTarget target = getConfiguredTarget("//foo:myrule");
    JavaInfo info = target.get(JavaInfo.PROVIDER);

    SkylarkNestedSet compileJars = info.getCompileTimeJars();
    assertThat(prettyArtifactNames(compileJars.getSet(Artifact.class)))
        .containsExactly("foo/liba.jar");

    SkylarkNestedSet fullCompileJars = info.getFullCompileTimeJars();
    assertThat(prettyArtifactNames(fullCompileJars.getSet(Artifact.class)))
        .containsExactly("foo/liba.jar");

    SkylarkNestedSet transitiveCompileTimeJars = info.getTransitiveCompileTimeJars();
    assertThat(prettyArtifactNames(transitiveCompileTimeJars.getSet(Artifact.class)))
        .containsExactly("foo/libc.jar");

    SkylarkNestedSet transitiveRuntimeJars = info.getTransitiveRuntimeJars();
    assertThat(prettyArtifactNames(transitiveRuntimeJars.getSet(Artifact.class)))
        .containsExactly("foo/libd.jar");
  }

  @Test
  public void javaProviderFieldsAreCorrectAfterCreatingProviderSomeEmptyFields() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  my_provider = java_common.create_provider(",
        "        compile_time_jars = ctx.files.compile_time_jars,",
        "        use_ijar = False,",
        "        runtime_jars = [],",
        "        transitive_compile_time_jars = [],",
        "        transitive_runtime_jars = ctx.files.transitive_runtime_jars)",
        "  return [my_provider]",
        "my_rule = rule(_impl, ",
        "    attrs = { ",
        "        'compile_time_jars' : attr.label_list(allow_files=['.jar']),",
        "        'transitive_runtime_jars': attr.label_list(allow_files=['.jar']),",
        "})");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'myrule',",
        "    compile_time_jars = ['liba.jar'],",
        "    transitive_runtime_jars = ['libd.jar'],",
        ")");
    ConfiguredTarget target = getConfiguredTarget("//foo:myrule");
    JavaInfo info = target.get(JavaInfo.PROVIDER);

    SkylarkNestedSet compileJars = info.getCompileTimeJars();
    assertThat(prettyArtifactNames(compileJars.getSet(Artifact.class)))
        .containsExactly("foo/liba.jar");

    SkylarkNestedSet transitiveCompileTimeJars = info.getTransitiveCompileTimeJars();
    assertThat(prettyArtifactNames(transitiveCompileTimeJars.getSet(Artifact.class)))
        .containsExactly("foo/liba.jar");

    SkylarkNestedSet transitiveRuntimeJars = info.getTransitiveRuntimeJars();
    assertThat(prettyArtifactNames(transitiveRuntimeJars.getSet(Artifact.class)))
        .containsExactly("foo/libd.jar");
  }

  @Test
  public void constructJavaProvider() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  my_provider = java_common.create_provider(",
        "        compile_time_jars = depset(ctx.files.compile_time_jars),",
        "        use_ijar = False,",
        "        runtime_jars = depset(ctx.files.runtime_jars),",
        "        transitive_compile_time_jars = depset(ctx.files.transitive_compile_time_jars),",
        "        transitive_runtime_jars = depset(ctx.files.transitive_runtime_jars),",
        "        source_jars = depset(ctx.files.source_jars))",
        "  return [my_provider]",
        "my_rule = rule(_impl, ",
        "    attrs = { ",
        "        'compile_time_jars' : attr.label_list(allow_files=['.jar']),",
        "        'runtime_jars': attr.label_list(allow_files=['.jar']),",
        "        'transitive_compile_time_jars': attr.label_list(allow_files=['.jar']),",
        "        'transitive_runtime_jars': attr.label_list(allow_files=['.jar']),",
        "        'source_jars': attr.label_list(allow_files=['.jar'])",
        "})");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'myrule',",
        "    compile_time_jars = ['liba.jar'],",
        "    runtime_jars = ['libb.jar'],",
        "    transitive_compile_time_jars = ['libc.jar'],",
        "    transitive_runtime_jars = ['libd.jar'],",
        "    source_jars = ['liba-src.jar'],",
        ")");
    ConfiguredTarget target = getConfiguredTarget("//foo:myrule");
    JavaCompilationArgsProvider provider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, target);
    assertThat(provider).isNotNull();
    List<String> compileTimeJars =
        prettyArtifactNames(provider.getJavaCompilationArgs().getCompileTimeJars());
    assertThat(compileTimeJars).containsExactly("foo/liba.jar");
    List<String> runtimeJars =
        prettyArtifactNames(provider.getJavaCompilationArgs().getRuntimeJars());
    assertThat(runtimeJars).containsExactly("foo/libb.jar");

    List<String> transitiveCompileTimeJars =
        prettyArtifactNames(provider.getRecursiveJavaCompilationArgs().getCompileTimeJars());
    assertThat(transitiveCompileTimeJars).containsExactly("foo/libc.jar");
    List<String> transitiveRuntimeJars =
        prettyArtifactNames(provider.getRecursiveJavaCompilationArgs().getRuntimeJars());
    assertThat(transitiveRuntimeJars).containsExactly("foo/libd.jar");

    JavaSourceJarsProvider sourcesProvider =
        JavaInfo.getProvider(JavaSourceJarsProvider.class, target);
    List<String> sourceJars = prettyArtifactNames(sourcesProvider.getSourceJars());
    assertThat(sourceJars).containsExactly("foo/liba-src.jar");
  }

  @Test
  public void constructJavaProviderWithAnotherJavaProvider() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  transitive_provider = java_common.merge(",
        "      [dep[JavaInfo] for dep in ctx.attr.deps])",
        "  my_provider = java_common.create_provider(",
        "        compile_time_jars = depset(ctx.files.compile_time_jars),",
        "        use_ijar = False,",
        "        runtime_jars = depset(ctx.files.runtime_jars))",
        "  return [java_common.merge([my_provider, transitive_provider])]",
        "my_rule = rule(_impl, ",
        "    attrs = { ",
        "        'compile_time_jars' : attr.label_list(allow_files=['.jar']),",
        "        'runtime_jars': attr.label_list(allow_files=['.jar']),",
        "        'deps': attr.label_list()",
        "})");
    scratch.file("foo/liba.jar");
    scratch.file("foo/libb.jar");
    scratch.file("foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'java_dep',",
        "    srcs = ['A.java'])",
        "my_rule(name = 'myrule',",
        "    compile_time_jars = ['liba.jar'],",
        "    runtime_jars = ['libb.jar'],",
        "    deps = [':java_dep']",
        ")"
    );
    ConfiguredTarget target = getConfiguredTarget("//foo:myrule");
    JavaCompilationArgsProvider provider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, target);
    assertThat(provider).isNotNull();
    List<String> compileTimeJars =
        prettyArtifactNames(provider.getJavaCompilationArgs().getCompileTimeJars());
    assertThat(compileTimeJars).containsExactly("foo/liba.jar", "foo/libjava_dep-hjar.jar");

    List<String> runtimeJars =
        prettyArtifactNames(provider.getJavaCompilationArgs().getRuntimeJars());
    assertThat(runtimeJars).containsExactly("foo/libb.jar", "foo/libjava_dep.jar");
  }

  @Test
  public void constructJavaProviderJavaLibrary() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  my_provider = java_common.create_provider(",
        "        transitive_compile_time_jars = depset(ctx.files.transitive_compile_time_jars),",
        "        transitive_runtime_jars = depset(ctx.files.transitive_runtime_jars))",
        "  return [my_provider]",
        "my_rule = rule(_impl, ",
        "    attrs = { ",
        "        'transitive_compile_time_jars' : attr.label_list(allow_files=['.jar']),",
        "        'transitive_runtime_jars': attr.label_list(allow_files=['.jar'])",
        "})");
    scratch.file("foo/liba.jar");
    scratch.file("foo/libb.jar");
    scratch.file("foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'myrule',",
        "    transitive_compile_time_jars = ['liba.jar'],",
        "    transitive_runtime_jars = ['libb.jar']",
        ")",
        "java_library(name = 'java_lib',",
        "    srcs = ['C.java'],",
        "    deps = [':myrule']",
        ")"
    );
    ConfiguredTarget target = getConfiguredTarget("//foo:java_lib");
    JavaCompilationArgsProvider provider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, target);
    List<String> compileTimeJars =
        prettyArtifactNames(provider.getRecursiveJavaCompilationArgs().getCompileTimeJars());
    assertThat(compileTimeJars).containsExactly("foo/libjava_lib-hjar.jar", "foo/liba.jar");

    List<String> runtimeJars =
        prettyArtifactNames(provider.getRecursiveJavaCompilationArgs().getRuntimeJars());
    assertThat(runtimeJars).containsExactly("foo/libjava_lib.jar", "foo/libb.jar");
  }

  @Test
  public void javaProviderExposedOnJavaLibrary() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "my_provider = provider()",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return [my_provider(p = dep_params)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl', srcs = ['java/A.java'])",
        "my_rule(name = 'r', dep = ':jl')");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:r");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//foo:jl");
    SkylarkKey myProviderKey =
        new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "my_provider");
    Info declaredProvider = myRuleTarget.get(myProviderKey);
    Object javaProvider = declaredProvider.getValue("p");
    assertThat(javaProvider).isInstanceOf(JavaInfo.class);
    assertThat(javaLibraryTarget.get(JavaInfo.PROVIDER)).isEqualTo(javaProvider);
  }

  @Test
  public void javaProviderPropagation() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return [dep_params]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl', srcs = ['java/A.java'])",
        "my_rule(name = 'r', dep = ':jl')",
        "java_library(name = 'jl_top', srcs = ['java/C.java'], deps = [':r'])");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:r");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//foo:jl");
    ConfiguredTarget topJavaLibraryTarget = getConfiguredTarget("//foo:jl_top");

    Object javaProvider = myRuleTarget.get(JavaInfo.PROVIDER.getKey());
    assertThat(javaProvider).isInstanceOf(JavaInfo.class);

    JavaInfo jlJavaInfo = javaLibraryTarget.get(JavaInfo.PROVIDER);

    assertThat(jlJavaInfo == javaProvider).isTrue();

    JavaInfo jlTopJavaInfo = topJavaLibraryTarget.get(JavaInfo.PROVIDER);

    javaCompilationArgsHaveTheSameParent(
        jlJavaInfo.getProvider(JavaCompilationArgsProvider.class).getJavaCompilationArgs(),
        jlTopJavaInfo.getProvider(JavaCompilationArgsProvider.class).getJavaCompilationArgs());
  }

  @Test
  public void skylarkJavaToJavaLibraryAttributes() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return struct(providers = [dep_params])",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl_bottom_for_deps', srcs = ['java/A.java'])",
        "java_library(name = 'jl_bottom_for_exports', srcs = ['java/A2.java'])",
        "java_library(name = 'jl_bottom_for_runtime_deps', srcs = ['java/A2.java'])",
        "my_rule(name = 'mya', dep = ':jl_bottom_for_deps')",
        "my_rule(name = 'myb', dep = ':jl_bottom_for_exports')",
        "my_rule(name = 'myc', dep = ':jl_bottom_for_runtime_deps')",
        "java_library(name = 'lib_exports', srcs = ['java/B.java'], deps = [':mya'],",
        "  exports = [':myb'], runtime_deps = [':myc'])",
        "java_library(name = 'lib_interm', srcs = ['java/C.java'], deps = [':lib_exports'])",
        "java_library(name = 'lib_top', srcs = ['java/D.java'], deps = [':lib_interm'])");
    assertNoEvents();

    // Test that all bottom jars are on the runtime classpath of lib_exports.
    ConfiguredTarget jlExports = getConfiguredTarget("//foo:lib_exports");
    JavaCompilationArgsProvider jlExportsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, jlExports);
    assertThat(
            prettyArtifactNames(
                jlExportsProvider.getRecursiveJavaCompilationArgs().getRuntimeJars()))
        .containsAllOf(
            "foo/libjl_bottom_for_deps.jar",
            "foo/libjl_bottom_for_runtime_deps.jar",
            "foo/libjl_bottom_for_exports.jar");

    // Test that libjl_bottom_for_exports.jar is in the recursive java compilation args of lib_top.
    ConfiguredTarget jlTop = getConfiguredTarget("//foo:lib_interm");
    JavaCompilationArgsProvider jlTopProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, jlTop);
    assertThat(
            prettyArtifactNames(jlTopProvider.getRecursiveJavaCompilationArgs().getRuntimeJars()))
        .contains("foo/libjl_bottom_for_exports.jar");
  }

  @Test
  public void skylarkJavaToJavaBinaryAttributes() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return struct(providers = [dep_params])",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl_bottom_for_deps', srcs = ['java/A.java'])",
        "java_library(name = 'jl_bottom_for_runtime_deps', srcs = ['java/A2.java'])",
        "my_rule(name = 'mya', dep = ':jl_bottom_for_deps')",
        "my_rule(name = 'myb', dep = ':jl_bottom_for_runtime_deps')",
        "java_binary(name = 'binary', srcs = ['java/B.java'], main_class = 'foo.A',",
        "  deps = [':mya'], runtime_deps = [':myb'])");
    assertNoEvents();

    // Test that all bottom jars are on the runtime classpath.
    ConfiguredTarget binary = getConfiguredTarget("//foo:binary");
    assertThat(
            prettyArtifactNames(
                binary.getProvider(JavaRuntimeClasspathProvider.class).getRuntimeClasspath()))
        .containsAllOf("foo/libjl_bottom_for_deps.jar", "foo/libjl_bottom_for_runtime_deps.jar");
  }

  @Test
  public void skylarkJavaToJavaImportAttributes() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return struct(providers = [dep_params])",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl_bottom_for_deps', srcs = ['java/A.java'])",
        "java_library(name = 'jl_bottom_for_runtime_deps', srcs = ['java/A2.java'])",
        "my_rule(name = 'mya', dep = ':jl_bottom_for_deps')",
        "my_rule(name = 'myb', dep = ':jl_bottom_for_runtime_deps')",
        "java_import(name = 'import', jars = ['B.jar'], deps = [':mya'], runtime_deps = [':myb'])");
    assertNoEvents();

    // Test that all bottom jars are on the runtime classpath.
    ConfiguredTarget importTarget = getConfiguredTarget("//foo:import");
    JavaCompilationArgsProvider compilationProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, importTarget);
    assertThat(
            prettyArtifactNames(
                compilationProvider.getRecursiveJavaCompilationArgs().getRuntimeJars()))
        .containsAllOf("foo/libjl_bottom_for_deps.jar", "foo/libjl_bottom_for_runtime_deps.jar");
  }

  @Test
  public void javaInfoSourceJarsExposed() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(source_jars = ctx.attr.dep[JavaInfo].source_jars)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'] , deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_skylark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    Info info = myRuleTarget.get(
        new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "result"));
    @SuppressWarnings("unchecked") SkylarkList<Artifact> sourceJars =
        (SkylarkList<Artifact>) (info.getValue("source_jars"));
    assertThat(prettyArtifactNames(sourceJars)).containsExactly("foo/libmy_java_lib_a-src.jar");

    assertThat(prettyArtifactNames(sourceJars)).doesNotContain("foo/libmy_java_lib_b-src.jar");
  }

  @Test
  public void testJavaInfoGetTransitiveSourceJars() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_source_jars)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'], deps = [':my_java_lib_c'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_skylark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    Info info =
        myRuleTarget.get(new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "result"));

    @SuppressWarnings("unchecked")
    SkylarkNestedSet sourceJars = (SkylarkNestedSet) info.getValue("property");

    assertThat(prettyArtifactNames(sourceJars.getSet(Artifact.class)))
        .containsExactly(
            "foo/libmy_java_lib_a-src.jar",
            "foo/libmy_java_lib_b-src.jar",
            "foo/libmy_java_lib_c-src.jar");
  }

  @Test
  public void testJavaInfoGetTransitiveDeps() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_deps)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'], deps = [':my_java_lib_c'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_skylark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    Info info =
        myRuleTarget.get(new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "result"));

    @SuppressWarnings("unchecked")
    SkylarkNestedSet sourceJars = (SkylarkNestedSet) info.getValue("property");

    assertThat(prettyArtifactNames(sourceJars.getSet(Artifact.class)))
        .containsExactly(
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar");
  }

  @Test
  public void testJavaInfoGetTransitiveRuntimeDeps() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_runtime_deps)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'], deps = [':my_java_lib_c'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_skylark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    Info info =
        myRuleTarget.get(new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "result"));

    @SuppressWarnings("unchecked")
    SkylarkNestedSet sourceJars = (SkylarkNestedSet) info.getValue("property");

    assertThat(prettyArtifactNames(sourceJars.getSet(Artifact.class)))
        .containsExactly(
            "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar", "foo/libmy_java_lib_c.jar");
  }


  @Test
  public void testJavaInfoGetTransitiveExports() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_exports)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], ",
        "             deps = [':my_java_lib_b', ':my_java_lib_c'], ",
        "             exports = [':my_java_lib_b']) ",
        "my_rule(name = 'my_skylark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    Info info = myRuleTarget.get(
        new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "result"));

    @SuppressWarnings("unchecked") SkylarkNestedSet exports =
        (SkylarkNestedSet) (info.getValue("property"));

    assertThat(exports.getSet(Label.class))
        .containsExactly(Label.parseAbsolute("//foo:my_java_lib_b"));
  }


  @Test
  public void testJavaInfoGetGenJarsProvider() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].annotation_processing)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], ",
        "             javacopts = ['-processor com.google.process.Processor'])",
        "my_rule(name = 'my_skylark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    Info info = myRuleTarget.get(
        new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "result"));

    JavaGenJarsProvider javaGenJarsProvider = (JavaGenJarsProvider) info.getValue("property");

    assertThat(javaGenJarsProvider.getGenClassJar().getFilename())
        .isEqualTo("libmy_java_lib_a-gen.jar");
    assertThat(javaGenJarsProvider.getGenSourceJar().getFilename())
        .isEqualTo("libmy_java_lib_a-gensrc.jar");
  }


  @Test
  public void javaInfoGetCompilationInfoProvider() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].compilation_info)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'])",
        "my_rule(name = 'my_skylark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    Info info = myRuleTarget.get(
        new SkylarkKey(Label.parseAbsolute("//foo:extension.bzl"), "result"));

    JavaCompilationInfoProvider javaCompilationInfoProvider =
        (JavaCompilationInfoProvider) info.getValue("property");

    assertThat(prettyArtifactNames(javaCompilationInfoProvider.getRuntimeClasspath()))
        .containsExactly("foo/libmy_java_lib_a.jar");
  }

  /* Test inspired by {@link AbstractJavaLibraryConfiguredTargetTest#testNeverlink}.*/
  @Test
  public void javaCommonCompileNeverlink() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_binary(name = 'plugin',",
        "    deps = [ ':somedep'],",
        "    srcs = [ 'Plugin.java'],",
        "    main_class = 'plugin.start')",
        "java_custom_library(name = 'somedep',",
        "    srcs = ['Dependency.java'],",
        "    deps = [ ':eclipse' ])",
        "java_custom_library(name = 'eclipse',",
        "    neverlink = 1,",
        "    srcs = ['EclipseDependency.java'])");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  deps = [dep[java_common.provider] for dep in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    javac_opts = java_common.default_javac_opts(",
        "        ctx, java_toolchain_attr = '_java_toolchain'),",
        "    neverlink = ctx.attr.neverlink,",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "    host_javabase = ctx.attr._host_javabase",
        "  )",
        "  return struct(",
        "    files = depset([output_jar]),",
        "    providers = [compilation_provider]",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'neverlink': attr.bool(),",
        "     'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "    '_host_javabase': attr.label(default = Label('" + HOST_JAVA_RUNTIME_LABEL + "'))",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget target = getConfiguredTarget("//java/test:plugin");
    assertThat(
            actionsTestUtil()
                .predecessorClosureAsCollection(getFilesToBuild(target), JavaSemantics.JAVA_SOURCE))
        .containsExactly("Plugin.java", "Dependency.java", "EclipseDependency.java");
    assertThat(
            ActionsTestUtil.baseNamesOf(
                FileType.filter(
                    getRunfilesSupport(target).getRunfilesSymlinkTargets(), JavaSemantics.JAR)))
        .isEqualTo("libsomedep.jar plugin.jar");
  }

  @Test
  public void strictDepsEnabled() throws Exception {
    scratch.file(
        "foo/custom_library.bzl",
        "def _impl(ctx):",
        "  java_provider = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])",
        "  if not ctx.attr.strict_deps:",
        "    java_provider = java_common.make_non_strict(java_provider)",
        "  return [java_provider]",
        "custom_library = rule(",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'strict_deps': attr.bool()",
        "  },",
        "  implementation = _impl",
        ")"
    );
    scratch.file(
        "foo/BUILD",
        "load(':custom_library.bzl', 'custom_library')",
        "custom_library(name = 'custom', deps = [':a'], strict_deps = True)",
        "java_library(name = 'a', srcs = ['java/A.java'], deps = [':b'])",
        "java_library(name = 'b', srcs = ['java/B.java'])"
    );

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:custom");
    JavaCompilationArgsProvider javaCompilationArgsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, myRuleTarget);
    List<String> directJars =
        prettyArtifactNames(javaCompilationArgsProvider.getJavaCompilationArgs().getRuntimeJars());
    assertThat(directJars).containsExactly("foo/liba.jar");
  }

  @Test
  public void strictDepsDisabled() throws Exception {
    scratch.file(
        "foo/custom_library.bzl",
        "def _impl(ctx):",
        "  java_provider = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])",
        "  if not ctx.attr.strict_deps:",
        "    java_provider = java_common.make_non_strict(java_provider)",
        "  return [java_provider]",
        "custom_library = rule(",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'strict_deps': attr.bool()",
        "  },",
        "  implementation = _impl",
        ")"
    );
    scratch.file(
        "foo/BUILD",
        "load(':custom_library.bzl', 'custom_library')",
        "custom_library(name = 'custom', deps = [':a'], strict_deps = False)",
        "java_library(name = 'a', srcs = ['java/A.java'], deps = [':b'])",
        "java_library(name = 'b', srcs = ['java/B.java'])"
    );

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:custom");
    JavaCompilationArgsProvider javaCompilationArgsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, myRuleTarget);
    List<String> directJars =
        prettyArtifactNames(javaCompilationArgsProvider.getJavaCompilationArgs().getRuntimeJars());
    assertThat(directJars).containsExactly("foo/liba.jar", "foo/libb.jar");
  }

  @Test
  public void strictJavaDepsFlagExposed_default() throws Exception {
    scratch.file(
      "foo/rule.bzl",
      "result = provider()",
      "def _impl(ctx):",
      "  return [result(strict_java_deps=ctx.fragments.java.strict_java_deps)]",
      "myrule = rule(",
      "  implementation=_impl,",
      "  fragments = ['java']",
      ")"
    );
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'myrule')",
        "myrule(name='myrule')"
    );
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    Info info =
        configuredTarget.get(new SkylarkKey(Label.parseAbsolute("//foo:rule.bzl"), "result"));
    assertThat(((String) info.getValue("strict_java_deps"))).isEqualTo("default");
  }

  @Test
  public void strictJavaDepsFlagExposed_error() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(strict_java_deps=ctx.fragments.java.strict_java_deps)]",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java']",
        ")"
    );
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'myrule')",
        "myrule(name='myrule')"
    );
    useConfiguration("--strict_java_deps=ERROR");
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    Info info =
        configuredTarget.get(new SkylarkKey(Label.parseAbsolute("//foo:rule.bzl"), "result"));
    assertThat(((String) info.getValue("strict_java_deps"))).isEqualTo("error");
  }

  @Test
  public void javaToolchainFlag_default() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(java_toolchain_label=ctx.attr._java_toolchain.label)]",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java'],",
        "  attrs = { '_java_toolchain': attr.label(default=Label('//foo:alias')) }",
        ")"
    );
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'myrule')",
        "java_toolchain_alias(name='alias')",
        "myrule(name='myrule')"
    );
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    Info info =
        configuredTarget.get(new SkylarkKey(Label.parseAbsolute("//foo:rule.bzl"), "result"));
    Label javaToolchainLabel = ((Label) info.getValue("java_toolchain_label"));
    assertThat(javaToolchainLabel.toString()).endsWith("jdk:toolchain");
  }

  @Test
  public void javaToolchainFlag_set() throws Exception {
    writeBuildFileForJavaToolchain();
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(java_toolchain_label=ctx.attr._java_toolchain.label)]",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java'],",
        "  attrs = { '_java_toolchain': attr.label(default=Label('//foo:alias')) }",
        ")"
    );
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'myrule')",
        "java_toolchain_alias(name='alias')",
        "myrule(name='myrule')"
    );
    useConfiguration("--java_toolchain=//java/com/google/test:toolchain");
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    Info info =
        configuredTarget.get(new SkylarkKey(Label.parseAbsolute("//foo:rule.bzl"), "result"));
    Label javaToolchainLabel = ((Label) info.getValue("java_toolchain_label"));
    assertThat(javaToolchainLabel.toString()).isEqualTo("//java/com/google/test:toolchain");
  }

  private static boolean javaCompilationArgsHaveTheSameParent(
      JavaCompilationArgs args, JavaCompilationArgs otherArgs) {
    if (!nestedSetsOfArtifactHaveTheSameParent(
        args.getCompileTimeJars(), otherArgs.getCompileTimeJars())) {
      return false;
    }
    if (!nestedSetsOfArtifactHaveTheSameParent(
        args.getInstrumentationMetadata(), otherArgs.getInstrumentationMetadata())) {
      return false;
    }
    if (!nestedSetsOfArtifactHaveTheSameParent(args.getRuntimeJars(), otherArgs.getRuntimeJars())) {
      return false;
    }
    return true;
  }

  private static boolean nestedSetsOfArtifactHaveTheSameParent(
      NestedSet<Artifact> artifacts, NestedSet<Artifact> otherArtifacts) {
    Iterator<Artifact> iterator = artifacts.iterator();
    Iterator<Artifact> otherIterator = otherArtifacts.iterator();
    while (iterator.hasNext() && otherIterator.hasNext()) {
      Artifact artifact = (Artifact) iterator.next();
      Artifact otherArtifact = (Artifact) otherIterator.next();
      if (!artifact
          .getPath()
          .getParentDirectory()
          .equals(otherArtifact.getPath().getParentDirectory())) {
        return false;
      }
    }
    if (iterator.hasNext() || otherIterator.hasNext()) {
      return false;
    }
    return true;
  }
}

