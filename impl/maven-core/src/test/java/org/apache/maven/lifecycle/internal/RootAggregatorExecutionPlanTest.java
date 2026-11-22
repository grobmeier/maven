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

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystemSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RootAggregatorExecutionPlanTest {
    private final DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
    private final MavenSession session =
            new MavenSession(mock(RepositorySystemSession.class), request, new DefaultMavenExecutionResult());
    private final MavenProject root = new MavenProject();
    private final Plugin plugin = new Plugin();
    private final TaskSegment segment = new TaskSegment(false, new LifecycleTask("package"));

    RootAggregatorExecutionPlanTest() {
        root.setExecutionRoot(true);
        root.setBuild(new Build());
        plugin.setGroupId("test");
        plugin.setArtifactId("aggregation-plugin");
        plugin.setVersion("1");
        root.getBuild().addPlugin(plugin);
        session.setProjects(List.of(root));
    }

    @Test
    void partitionsEachOccurrenceOnceWithoutChangingTheCompletePlanOrPreparedExecutions() {
        MojoExecution first = execution("first", "package", false, "package", MojoExecution.Source.LIFECYCLE);
        MojoExecution reserved = execution("aggregate", "after:all", true, "after:all", MojoExecution.Source.LIFECYCLE);
        MojoExecution middle = execution("middle", "after:all", false, "after:all", MojoExecution.Source.LIFECYCLE);
        MojoExecution repeated = execution("aggregate", "after:all", true, null, MojoExecution.Source.LIFECYCLE);
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        reserved.setConfiguration(configuration);
        reserved.setForkedExecutions("child", List.of(first));
        List<MojoExecution> all = List.of(first, reserved, middle, repeated);
        MavenExecutionPlan complete = plan(all);
        MavenProject current = session.getCurrentProject();

        RootAggregatorExecutionPlan result = RootAggregatorExecutionPlan.partition(session, root, segment, complete);

        assertEquals(List.of(first, middle), result.getProjectExecutions());
        assertEquals(List.of(reserved, repeated), result.getDeferredExecutions());
        assertEquals(all, complete.getMojoExecutions());
        assertSame(complete, result.getCompletePlan());
        assertSame(root, result.getProject());
        assertSame(segment, result.getTaskSegment());
        assertSame(current, session.getCurrentProject());
        assertSame(configuration, result.getDeferredExecutions().get(0).getConfiguration());
        assertSame(
                first,
                result.getDeferredExecutions()
                        .get(0)
                        .getForkedExecutions()
                        .get("child")
                        .get(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.getProjectExecutions().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.getDeferredExecutions().clear());
    }

    @Test
    void keepsNonAggregatorsOtherBoundariesDirectGoalsAndImplicitDescriptorBindings() {
        List<MojoExecution> all = List.of(
                execution("plain", "after:all", false, "after:all", MojoExecution.Source.LIFECYCLE),
                execution("package", "package", true, "package", MojoExecution.Source.LIFECYCLE),
                execution("before", "before:all", true, "before:all", MojoExecution.Source.LIFECYCLE),
                execution("priority", "after:all[10]", true, "after:all[10]", MojoExecution.Source.LIFECYCLE),
                execution("implicit", "after:all", true, null, MojoExecution.Source.LIFECYCLE),
                execution("cli", "after:all", true, "after:all", MojoExecution.Source.CLI));

        RootAggregatorExecutionPlan result = RootAggregatorExecutionPlan.partition(session, root, segment, plan(all));

        assertEquals(all, result.getProjectExecutions());
        assertTrue(result.getDeferredExecutions().isEmpty());
    }

    @Test
    void doesNotTreatASelectedChildAsTheInvocationRoot() {
        MojoExecution aggregate =
                execution("aggregate", "after:all", true, "after:all", MojoExecution.Source.LIFECYCLE);
        // MavenSession uses the first selected project as its top-level fallback if the root is excluded.
        root.setExecutionRoot(false);
        session.setProjects(List.of(root));

        RootAggregatorExecutionPlan result =
                RootAggregatorExecutionPlan.partition(session, root, segment, plan(List.of(aggregate)));

        assertEquals(List.of(aggregate), result.getProjectExecutions());
        assertTrue(result.getDeferredExecutions().isEmpty());
    }

    @Test
    void requiresTheActualTopLevelProjectIdentity() {
        MojoExecution aggregate =
                execution("aggregate", "after:all", true, "after:all", MojoExecution.Source.LIFECYCLE);
        MavenProject otherRoot = new MavenProject();
        otherRoot.setExecutionRoot(true);
        session.setProjects(List.of(otherRoot, root));

        RootAggregatorExecutionPlan result =
                RootAggregatorExecutionPlan.partition(session, root, segment, plan(List.of(aggregate)));

        assertEquals(List.of(aggregate), result.getProjectExecutions());
        assertTrue(result.getDeferredExecutions().isEmpty());
    }

    @Test
    void keepsAllWorkForOtherBuildersOrParallelRequests() {
        MojoExecution aggregate =
                execution("aggregate", "after:all", true, "after:all", MojoExecution.Source.LIFECYCLE);
        for (String builder : List.of("concurrent", "multithreaded", "custom")) {
            request.setBuilderId(builder);
            RootAggregatorExecutionPlan result =
                    RootAggregatorExecutionPlan.partition(session, root, segment, plan(List.of(aggregate)));
            assertEquals(List.of(aggregate), result.getProjectExecutions());
            assertTrue(result.getDeferredExecutions().isEmpty());
        }
        request.setBuilderId("singlethreaded");
        request.setDegreeOfConcurrency(2);
        RootAggregatorExecutionPlan result =
                RootAggregatorExecutionPlan.partition(session, root, segment, plan(List.of(aggregate)));
        assertEquals(List.of(aggregate), result.getProjectExecutions());
        assertTrue(result.getDeferredExecutions().isEmpty());
    }

    @Test
    void keepsWorkForAggregatingMixedAndEmptyTaskSegments() {
        MojoExecution aggregate =
                execution("aggregate", "after:all", true, "after:all", MojoExecution.Source.LIFECYCLE);
        for (TaskSegment other : List.of(
                new TaskSegment(true, new LifecycleTask("package")),
                new TaskSegment(false, new LifecycleTask("package"), new GoalTask("help:help")),
                new TaskSegment(false))) {
            RootAggregatorExecutionPlan result =
                    RootAggregatorExecutionPlan.partition(session, root, other, plan(List.of(aggregate)));
            assertEquals(List.of(aggregate), result.getProjectExecutions());
            assertTrue(result.getDeferredExecutions().isEmpty());
        }
    }

    @Test
    void retainsSeparateSegmentOwnershipAndAnImmutableTaskSnapshot() {
        MojoExecution aggregate =
                execution("aggregate", "after:all", true, "after:all", MojoExecution.Source.LIFECYCLE);
        TaskSegment later = new TaskSegment(false, new LifecycleTask("verify"));
        RootAggregatorExecutionPlan first =
                RootAggregatorExecutionPlan.partition(session, root, segment, plan(List.of(aggregate)));
        RootAggregatorExecutionPlan second =
                RootAggregatorExecutionPlan.partition(session, root, later, plan(List.of(aggregate)));
        List<Task> requested = new ArrayList<>(segment.getTasks());
        segment.getTasks().add(new LifecycleTask("verify"));

        assertEquals(requested, first.getTasks());
        assertSame(later, second.getTaskSegment());
        assertEquals(List.of(aggregate), first.getDeferredExecutions());
        assertEquals(List.of(aggregate), second.getDeferredExecutions());
        assertThrows(UnsupportedOperationException.class, () -> first.getTasks().clear());
    }

    @Test
    void rejectsAnUnpreparedCandidateRatherThanSilentlyRemovingIt() {
        MojoExecution unprepared = new MojoExecution(plugin, "collect", "aggregate");
        unprepared.setLifecyclePhase("after:all");
        MavenExecutionPlan complete = plan(List.of(unprepared));

        assertThrows(
                NullPointerException.class,
                () -> RootAggregatorExecutionPlan.partition(session, root, segment, complete));
        assertEquals(List.of(unprepared), complete.getMojoExecutions());
    }

    private MojoExecution execution(
            String id, String phase, boolean aggregator, String binding, MojoExecution.Source source) {
        if (binding != null) {
            PluginExecution declaration = new PluginExecution();
            declaration.setId(id);
            declaration.setPhase(binding);
            declaration.addGoal("collect");
            plugin.addExecution(declaration);
        }
        PluginDescriptor descriptor = new PluginDescriptor();
        descriptor.setPlugin(plugin);
        descriptor.setGroupId(plugin.getGroupId());
        descriptor.setArtifactId(plugin.getArtifactId());
        descriptor.setVersion(plugin.getVersion());
        MojoDescriptor mojo = new MojoDescriptor();
        mojo.setPluginDescriptor(descriptor);
        mojo.setGoal("collect");
        mojo.setAggregator(aggregator);
        mojo.setPhase(phase);
        MojoExecution execution = new MojoExecution(mojo, id, source);
        execution.setLifecyclePhase(phase);
        return execution;
    }

    private MavenExecutionPlan plan(List<MojoExecution> executions) {
        return new MavenExecutionPlan(ExecutionPlanItem.createExecutionPlanItems(root, executions), null);
    }
}
