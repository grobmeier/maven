/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.lifecycle.internal;

import java.util.List;
import java.util.Map;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.DefaultLifecycles;
import org.apache.maven.lifecycle.Lifecycle;
import org.apache.maven.lifecycle.LifecycleMappingDelegate;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultLifecycleExecutionPlanCalculatorTest {
    @Test
    void reservesRootAggregatorsOnlyThroughTheExplicitApiAfterNormalPreparation() throws Exception {
        MavenSession session = mock(MavenSession.class);
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        MavenProject project = new MavenProject();
        project.setExecutionRoot(true);
        project.setBuild(new Build());
        when(session.getRequest()).thenReturn(request);
        when(session.getTopLevelProject()).thenReturn(project);

        Plugin plugin = new Plugin();
        plugin.setGroupId("test");
        plugin.setArtifactId("aggregation-plugin");
        plugin.setVersion("1");
        PluginExecution binding = new PluginExecution();
        binding.setId("collect");
        binding.setPhase("after:all");
        binding.addGoal("collect");
        plugin.addExecution(binding);
        project.getBuild().addPlugin(plugin);

        PluginDescriptor descriptor = new PluginDescriptor();
        descriptor.setPlugin(plugin);
        descriptor.setGroupId(plugin.getGroupId());
        descriptor.setArtifactId(plugin.getArtifactId());
        descriptor.setVersion(plugin.getVersion());
        MojoDescriptor mojo = new MojoDescriptor();
        mojo.setPluginDescriptor(descriptor);
        mojo.setGoal("collect");
        mojo.setAggregator(true);
        MojoExecution execution = new MojoExecution(plugin, "collect", "collect");
        execution.setLifecyclePhase("after:all");
        BuildPluginManager pluginManager = mock(BuildPluginManager.class);
        when(pluginManager.getMojoDescriptor(
                        plugin, "collect", project.getRemotePluginRepositories(), session.getRepositorySession()))
                .thenReturn(mojo);

        DefaultLifecycles lifecycles = mock(DefaultLifecycles.class);
        Lifecycle lifecycle = new Lifecycle("default", List.of("package", "after:all"), Map.of());
        when(lifecycles.get("package")).thenReturn(lifecycle);
        LifecycleMappingDelegate mappings = mock(LifecycleMappingDelegate.class);
        when(mappings.calculateLifecycleMappings(session, project, lifecycle, "package"))
                .thenReturn(Map.of("after:all", List.of(execution)));
        LifecycleExecutionPlanCalculator calculator = new DefaultLifecycleExecutionPlanCalculator(
                pluginManager,
                lifecycles,
                mock(MojoDescriptorCreator.class),
                mock(LifecyclePluginResolver.class),
                mappings,
                Map.of(),
                Map.of("default", new DefaultMojoExecutionConfigurator()));
        TaskSegment segment = new TaskSegment(false, new LifecycleTask("package"));

        MavenExecutionPlan ordinary = calculator.calculateExecutionPlan(session, project, segment.getTasks());
        assertEquals(List.of(execution), ordinary.getMojoExecutions());
        assertNotNull(execution.getConfiguration());

        // Require the opt-in call to prepare its input rather than relying on the earlier calculation.
        execution.setMojoDescriptor(null);
        execution.setConfiguration((Xpp3Dom) null);
        RootAggregatorExecutionPlan reserved =
                calculator.calculateExecutionPlanWithDeferredRootAggregators(session, project, segment);

        assertTrue(reserved.getProjectExecutions().isEmpty());
        assertEquals(List.of(execution), reserved.getDeferredExecutions());
        assertSame(mojo, reserved.getDeferredExecutions().get(0).getMojoDescriptor());
        assertNotNull(reserved.getDeferredExecutions().get(0).getConfiguration());
        assertEquals(List.of(execution), ordinary.getMojoExecutions());
        assertEquals(List.of(execution), reserved.getCompletePlan().getMojoExecutions());
    }

    @Test
    void doesNotResolveProjectPluginsForDirectGoal() throws Exception {
        MojoDescriptorCreator mojoDescriptorCreator = mock(MojoDescriptorCreator.class);
        LifecyclePluginResolver lifecyclePluginResolver = mock(LifecyclePluginResolver.class);
        MavenSession session = mock(MavenSession.class);
        MavenProject project = new MavenProject();

        PluginDescriptor pluginDescriptor = new PluginDescriptor();
        pluginDescriptor.setGroupId("org.apache.maven.plugins");
        pluginDescriptor.setArtifactId("maven-help-plugin");
        pluginDescriptor.setVersion("1.0");
        MojoDescriptor mojoDescriptor = new MojoDescriptor();
        mojoDescriptor.setPluginDescriptor(pluginDescriptor);
        mojoDescriptor.setGoal("help");
        when(mojoDescriptorCreator.getMojoDescriptor("help:help", session, project))
                .thenReturn(mojoDescriptor);

        DefaultLifecycleExecutionPlanCalculator calculator = new DefaultLifecycleExecutionPlanCalculator(
                mock(BuildPluginManager.class),
                mock(DefaultLifecycles.class),
                mojoDescriptorCreator,
                lifecyclePluginResolver);

        calculator.calculateExecutionPlan(session, project, List.of(new GoalTask("help:help")), false);

        verify(lifecyclePluginResolver, never()).resolveMissingPluginVersions(project, session);
    }

    @Test
    void resolvesProjectPluginsForLifecycleTask() throws Exception {
        LifecyclePluginResolver lifecyclePluginResolver = mock(LifecyclePluginResolver.class);
        MavenSession session = mock(MavenSession.class);
        MavenProject project = new MavenProject();
        DefaultLifecycles defaultLifecycles = mock(DefaultLifecycles.class);
        Lifecycle lifecycle = new Lifecycle("default", List.of("validate"), Map.of());
        LifecycleMappingDelegate lifecycleMappingDelegate = mock(LifecycleMappingDelegate.class);

        when(defaultLifecycles.get("validate")).thenReturn(lifecycle);
        when(lifecycleMappingDelegate.calculateLifecycleMappings(session, project, lifecycle, "validate"))
                .thenReturn(Map.of());

        DefaultLifecycleExecutionPlanCalculator calculator = new DefaultLifecycleExecutionPlanCalculator(
                mock(BuildPluginManager.class),
                defaultLifecycles,
                mock(MojoDescriptorCreator.class),
                lifecyclePluginResolver,
                lifecycleMappingDelegate,
                Map.of(),
                Map.of());

        calculator.calculateExecutionPlan(session, project, List.of(new LifecycleTask("validate")), false);

        verify(lifecyclePluginResolver).resolveMissingPluginVersions(project, session);
    }
}
