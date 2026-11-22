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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.maven.execution.BuildFailure;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.lifecycle.internal.builder.singlethreaded.SingleThreadedBuilder;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystemSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SequentialRootAggregationCoordinatorTest {
    @Test
    void requiresTheActualSequentialBuilderWithOneWorker() {
        Fixture f = new Fixture();
        assertTrue(f.eligible());
        assertFalse(f.coordinator.canDefer(f.session, mock(Builder.class), f.builds, f.segments));
        f.request.setBuilderId("concurrent");
        assertFalse(f.eligible());
        f.request.setBuilderId("singlethreaded");
        f.request.setDegreeOfConcurrency(2);
        assertFalse(f.eligible());
        verifyNoInteractions(f.moduleBuilder);
    }

    @Test
    void requiresOneLifecycleTaskAndTheSelectedRootSegment() {
        Fixture f = new Fixture();
        for (TaskSegment segment : List.of(
                new TaskSegment(false),
                new TaskSegment(true, new LifecycleTask("package")),
                new TaskSegment(false, new GoalTask("help:help")),
                new TaskSegment(false, new LifecycleTask("clean"), new LifecycleTask("package")))) {
            assertFalse(f.coordinator.canDefer(f.session, f.builder, f.buildsFor(segment), List.of(segment)));
        }
        assertFalse(f.coordinator.canDefer(
                f.session, f.builder, f.builds, List.of(f.segment, new TaskSegment(true, new GoalTask("help:help")))));
        assertFalse(f.coordinator.canDefer(
                f.session, f.builder, f.buildsFor(new TaskSegment(false, new LifecycleTask("package"))), f.segments));
    }

    @Test
    void requiresAnExplicitAfterAllBindingAtTheExecutionRoot() {
        Fixture f = new Fixture();
        f.root.setExecutionRoot(false);
        assertFalse(f.eligible());
        f.root.setExecutionRoot(true);
        assertFalse(f.coordinator.canDefer(
                f.session,
                f.builder,
                new ProjectBuildList(List.of(f.builds.get(1), f.builds.get(0), f.builds.get(2))),
                f.segments));
        f.binding.setPhase("package");
        assertFalse(f.eligible());
        f.binding.setPhase("after:all");
        f.binding.getGoals().clear();
        assertFalse(f.eligible());
        verifyNoInteractions(f.moduleBuilder);
    }

    @Test
    void buildsRemainingProjectsInOrderBeforeCompletingTheRoot() throws Exception {
        Fixture f = new Fixture();
        doAnswer(call -> {
                    f.succeed(f.a);
                    f.succeed(f.b);
                    return null;
                })
                .when(f.builder)
                .build(eq(f.session), eq(f.context), any(), eq(f.segments), eq(f.status));

        f.coordinator.build(f.session, f.context, f.builds, f.segments, f.builder);

        ArgumentCaptor<ProjectBuildList> remaining = ArgumentCaptor.forClass(ProjectBuildList.class);
        InOrder order = inOrder(f.moduleBuilder, f.builder);
        order.verify(f.moduleBuilder).buildProjectWithDeferredRootAggregators(f.session, f.context, f.root, f.segment);
        order.verify(f.builder).build(eq(f.session), eq(f.context), remaining.capture(), eq(f.segments), eq(f.status));
        order.verify(f.moduleBuilder).completeDeferredRootProject(f.session, f.context, f.deferred, true);
        assertEquals(2, remaining.getValue().size());
        assertSame(f.builds.get(1), remaining.getValue().get(0));
        assertSame(f.builds.get(2), remaining.getValue().get(1));
        assertEquals(3, f.builds.size());
        assertSame(f.root, f.builds.get(0).getProject());
    }

    @Test
    void requiresSuccessfulSummariesForEveryRemainingProject() throws Exception {
        for (boolean failedSummary : List.of(false, true)) {
            Fixture f = new Fixture();
            doAnswer(call -> {
                        f.succeed(f.a);
                        if (failedSummary) {
                            f.result.addBuildSummary(
                                    new BuildFailure(f.b, Duration.ZERO, new Exception("child failed")));
                        }
                        return null;
                    })
                    .when(f.builder)
                    .build(eq(f.session), eq(f.context), any(), eq(f.segments), eq(f.status));

            f.coordinator.build(f.session, f.context, f.builds, f.segments, f.builder);

            assertFalse(f.result.hasExceptions());
            verify(f.moduleBuilder).completeDeferredRootProject(f.session, f.context, f.deferred, false);
        }
    }

    @Test
    void doesNotCompleteSuccessfullyWhileExceptionsRemain() throws Exception {
        Fixture f = new Fixture();
        doAnswer(call -> {
                    f.succeed(f.a);
                    f.succeed(f.b);
                    f.result.addException(new Exception("reactor failed"));
                    return null;
                })
                .when(f.builder)
                .build(eq(f.session), eq(f.context), any(), eq(f.segments), eq(f.status));

        f.coordinator.build(f.session, f.context, f.builds, f.segments, f.builder);

        verify(f.moduleBuilder).completeDeferredRootProject(f.session, f.context, f.deferred, false);
    }

    @Test
    void doesNotRepeatAnAlreadyRecordedRootFailure() throws Exception {
        Fixture f = new Fixture();
        RuntimeException failure = new IllegalStateException("root failed");
        when(f.moduleBuilder.buildProjectWithDeferredRootAggregators(f.session, f.context, f.root, f.segment))
                .thenAnswer(call -> {
                    f.result.addException(failure);
                    throw failure;
                });

        f.coordinator.build(f.session, f.context, f.builds, f.segments, f.builder);

        assertEquals(List.of(failure), f.result.getExceptions());
        verifyNoInteractions(f.builder);
        verify(f.moduleBuilder, never()).completeDeferredRootProject(any(), any(), any(), eq(true));
        verify(f.moduleBuilder, never()).completeDeferredRootProject(any(), any(), any(), eq(false));
    }

    @Test
    void propagatesBuilderFailuresToTheStarter() throws Exception {
        for (Exception failure :
                List.of(new ExecutionException(new Exception("builder failed")), new InterruptedException())) {
            Fixture f = new Fixture();
            doThrow(failure).when(f.builder).build(eq(f.session), eq(f.context), any(), eq(f.segments), eq(f.status));

            Exception observed = assertThrows(
                    Exception.class, () -> f.coordinator.build(f.session, f.context, f.builds, f.segments, f.builder));

            assertSame(failure, observed);
            verify(f.moduleBuilder, never()).completeDeferredRootProject(any(), any(), any(), eq(true));
            verify(f.moduleBuilder, never()).completeDeferredRootProject(any(), any(), any(), eq(false));
        }
    }

    private static final class Fixture {
        final DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        final DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
        final MavenSession session = new MavenSession(mock(RepositorySystemSession.class), request, result);
        final MavenProject root = project("root", true);
        final MavenProject a = project("a", false);
        final MavenProject b = project("b", false);
        final TaskSegment segment = new TaskSegment(false, new LifecycleTask("package"));
        final List<TaskSegment> segments = List.of(segment);
        final LifecycleModuleBuilder moduleBuilder = mock(LifecycleModuleBuilder.class);
        final Builder builder = mock(SingleThreadedBuilder.class);
        final SequentialRootAggregationCoordinator coordinator =
                new SequentialRootAggregationCoordinator(moduleBuilder);
        final LifecycleModuleBuilder.DeferredRootBuild deferred = mock(LifecycleModuleBuilder.DeferredRootBuild.class);
        final ReactorBuildStatus status;
        final ReactorContext context;
        final ProjectBuildList builds;
        final PluginExecution binding = new PluginExecution();

        Fixture() {
            request.setBuilderId("singlethreaded");
            request.setDegreeOfConcurrency(1);
            session.setProjects(List.of(root, a, b));
            session.setProjectDependencyGraph(mock(ProjectDependencyGraph.class));
            status = new ReactorBuildStatus(session.getProjectDependencyGraph());
            context = new ReactorContext(result, Thread.currentThread().getContextClassLoader(), status);
            builds = buildsFor(segment);
            Plugin plugin = new Plugin();
            plugin.setGroupId("test");
            plugin.setArtifactId("collect-plugin");
            binding.setPhase("after:all");
            binding.addGoal("collect");
            plugin.addExecution(binding);
            root.getBuild().addPlugin(plugin);
            when(moduleBuilder.buildProjectWithDeferredRootAggregators(session, context, root, segment))
                    .thenReturn(deferred);
        }

        ProjectBuildList buildsFor(TaskSegment segment) {
            return new ProjectBuildList(List.of(
                    new ProjectSegment(root, segment, session),
                    new ProjectSegment(a, segment, session),
                    new ProjectSegment(b, segment, session)));
        }

        boolean eligible() {
            return coordinator.canDefer(session, builder, builds, segments);
        }

        void succeed(MavenProject project) {
            result.addBuildSummary(new BuildSuccess(project, Duration.ZERO));
        }

        private static MavenProject project(String id, boolean executionRoot) {
            MavenProject project = new MavenProject();
            project.setGroupId("test");
            project.setArtifactId(id);
            project.setVersion("1");
            project.setExecutionRoot(executionRoot);
            project.setBuild(new Build());
            return project;
        }
    }
}
