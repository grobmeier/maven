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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.execution.BuildFailure;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.execution.ProjectExecutionListener;
import org.apache.maven.internal.transformation.TransformerManager;
import org.apache.maven.lifecycle.DefaultLifecycles;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.lifecycle.internal.builder.BuilderCommon;
import org.apache.maven.lifecycle.internal.builder.singlethreaded.SingleThreadedBuilder;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystemSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultLifecycleStarterAggregationTest {
    @Test
    void buildsChildrenBeforeRootAggregationWithOneRootLifecycleAndPreparedContext() throws Exception {
        try (Fixture f = new Fixture("after:all", true)) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            f.root.setClassRealm(new ClassRealm(new ClassWorld(), "root", original));
            f.starter.execute(f.session);

            assertEquals(
                    List.of("root:ordinary", "root:cleanup", "a:package", "b:package", "root:collect"), f.executed);
            assertEquals(1, f.rootPlanCalculations.get());
            assertEquals(1, f.rootLifecycleStarts);
            assertEquals(List.of("a", "b", "root"), f.successes);
            assertTrue(f.result.getBuildSummary(f.root) instanceof BuildSuccess);
            assertFalse(f.result.hasExceptions());
            assertNull(f.session.getCurrentProject());
            assertSame(original, Thread.currentThread().getContextClassLoader());
            verify(f.catapult, times(1)).fire(ExecutionEvent.Type.SessionEnded, f.session, null);
            assertEquals(
                    1,
                    f.events.stream()
                            .filter(e -> e.equals("ProjectStarted:root"))
                            .count());
            assertEquals(
                    1,
                    f.events.stream()
                            .filter(e -> e.equals("ProjectSucceeded:root"))
                            .count());
            assertTrue(f.events.indexOf("ProjectSucceeded:a") < f.events.indexOf("ProjectSucceeded:root"));
            assertSame(f.collect, f.successPlan.get(f.successPlan.size() - 1));
        }
    }

    @Test
    void skipsTheCollectorAfterChildFailureUnderEveryFailurePolicy() throws Exception {
        for (String policy : List.of(
                MavenExecutionRequest.REACTOR_FAIL_FAST,
                MavenExecutionRequest.REACTOR_FAIL_AT_END,
                MavenExecutionRequest.REACTOR_FAIL_NEVER)) {
            try (Fixture f = new Fixture("after:all", true)) {
                f.request.setReactorFailureBehavior(policy);
                f.failAt = "a:package";
                f.clearFailureExceptions = MavenExecutionRequest.REACTOR_FAIL_NEVER.equals(policy);
                f.starter.execute(f.session);

                assertFalse(f.executed.contains("root:collect"), policy);
                assertTrue(f.executed.contains("root:cleanup"), "Non-aggregating after:all work remains ordinary");
                assertTrue(f.result.getBuildSummary(f.a) instanceof BuildFailure, policy);
                assertTrue(f.result.getBuildSummary(f.root) instanceof BuildFailure, policy);
                assertFalse(f.successes.contains("root"));
                assertEquals(
                        1,
                        f.events.stream()
                                .filter(e -> e.equals("ProjectFailed:root"))
                                .count());
                assertTrue(((BuildFailure) f.result.getBuildSummary(f.root))
                        .getCause()
                        .getMessage()
                        .contains("were skipped"));
                if (!MavenExecutionRequest.REACTOR_FAIL_FAST.equals(policy)) {
                    assertTrue(f.executed.contains("b:package"), policy);
                }
            }
        }
    }

    @Test
    void recordsADeferredGoalFailureOnceWithoutEarlierRootSuccess() throws Exception {
        try (Fixture f = new Fixture("after:all", true)) {
            f.failAt = "root:collect";
            f.starter.execute(f.session);

            assertEquals(
                    1, f.executed.stream().filter(e -> e.equals("root:collect")).count());
            assertTrue(f.result.getBuildSummary(f.root) instanceof BuildFailure);
            assertEquals(1, f.result.getExceptions().size());
            assertEquals(List.of("a", "b"), f.successes);
            assertEquals(
                    1,
                    f.events.stream()
                            .filter(e -> e.equals("ProjectFailed:root"))
                            .count());
            assertFalse(f.events.contains("ProjectSucceeded:root"));
            assertNull(f.session.getCurrentProject());
        }
    }

    @Test
    void recordsAnUnexpectedDeferredRuntimeFailureWithoutAddingItTwice() throws Exception {
        try (Fixture f = new Fixture("after:all", true)) {
            f.failAt = "root:collect";
            f.runtimeFailure = true;
            f.starter.execute(f.session);

            assertEquals(
                    1, f.executed.stream().filter(e -> e.equals("root:collect")).count());
            assertTrue(f.result.getBuildSummary(f.root) instanceof BuildFailure);
            assertEquals(1, f.result.getExceptions().size());
            assertTrue(f.result.getExceptions().get(0) instanceof IllegalStateException);
            assertFalse(f.successes.contains("root"));
            assertNull(f.session.getCurrentProject());
        }
    }

    @Test
    void doesNotBuildChildrenOrRunDeferredGoalsAfterOrdinaryRootFailure() throws Exception {
        try (Fixture f = new Fixture("after:all", true)) {
            f.failAt = "root:ordinary";
            f.starter.execute(f.session);
            assertEquals(List.of("root:ordinary"), f.executed);
            assertTrue(f.result.getBuildSummary(f.root) instanceof BuildFailure);
            assertNull(f.result.getBuildSummary(f.a));
        }
    }

    @Test
    void supportsASelectedSingleProjectWithoutDuplicateExecution() throws Exception {
        try (Fixture f = new Fixture("after:all", true)) {
            f.session.setProjects(List.of(f.root));
            f.starter.execute(f.session);
            assertEquals(List.of("root:ordinary", "root:cleanup", "root:collect"), f.executed);
            assertEquals(List.of("root"), f.successes);
        }
    }

    @Test
    void keepsPackageBoundAggregatorsOnTheOriginalPath() throws Exception {
        try (Fixture f = new Fixture("package", true)) {
            f.starter.execute(f.session);
            assertEquals(
                    List.of("root:ordinary", "root:cleanup", "root:collect", "a:package", "b:package"), f.executed);
            assertEquals(List.of("root", "a", "b"), f.successes);
            assertEquals(1, f.rootPlanCalculations.get());
        }
    }

    @Test
    void keepsNonAggregatorAfterAllExecutionsOnTheOriginalPath() throws Exception {
        try (Fixture f = new Fixture("after:all", false)) {
            f.starter.execute(f.session);
            assertEquals(
                    List.of("root:ordinary", "root:cleanup", "root:collect", "a:package", "b:package"), f.executed);
            assertEquals(List.of("root", "a", "b"), f.successes);
            assertEquals(1, f.rootPlanCalculations.get());
        }
    }

    @Test
    void leavesExcludedRootsAndMultiTaskOrCliRequestsUnchanged() throws Exception {
        try (Fixture f = new Fixture("after:all", true)) {
            f.session.setProjects(List.of(f.a, f.b));
            f.starter.execute(f.session);
            assertEquals(List.of("a:package", "b:package"), f.executed);
            assertEquals(0, f.rootPlanCalculations.get());
        }
        try (Fixture f = new Fixture("after:all", true)) {
            f.segment.getTasks().add(new LifecycleTask("verify"));
            f.starter.execute(f.session);
            assertTrue(f.executed.indexOf("root:collect") < f.executed.indexOf("a:package"));
        }
        try (Fixture f = new Fixture("after:all", true)) {
            f.taskSegments = List.of(f.segment, new TaskSegment(true, new GoalTask("help:help")));
            f.starter.execute(f.session);
            assertTrue(f.executed.indexOf("root:collect") < f.executed.indexOf("a:package"));
        }
    }

    @Test
    void leavesParallelAndConcurrentBuildersUntouched() throws Exception {
        for (String builderId : List.of("multithreaded", "concurrent")) {
            try (Fixture f = new Fixture("after:all", true)) {
                Builder other = mock(Builder.class);
                f.builders.put(builderId, other);
                f.request.setBuilderId(builderId);
                f.starter.execute(f.session);
                verify(other).build(eq(f.session), any(), any(), eq(List.of(f.segment)), any());
                assertTrue(f.executed.isEmpty());
                assertEquals(0, f.rootPlanCalculations.get());
            }
        }
        try (Fixture f = new Fixture("after:all", true)) {
            f.request.setDegreeOfConcurrency(2);
            f.starter.execute(f.session);
            assertTrue(f.executed.indexOf("root:collect") < f.executed.indexOf("a:package"));
        }
    }

    @Test
    void completesAReservationOnlyOnce() throws Exception {
        try (Fixture f = new Fixture("after:all", true)) {
            ReactorContext context = new ReactorContext(
                    f.result,
                    Thread.currentThread().getContextClassLoader(),
                    new ReactorBuildStatus(f.session.getProjectDependencyGraph()));
            LifecycleModuleBuilder.DeferredRootBuild deferred =
                    f.moduleBuilder.buildProjectWithDeferredRootAggregators(f.session, context, f.root, f.segment);
            assertNotNull(deferred);
            f.moduleBuilder.completeDeferredRootProject(f.session, context, deferred, true);
            assertThrows(
                    IllegalStateException.class,
                    () -> f.moduleBuilder.completeDeferredRootProject(f.session, context, deferred, true));
            assertEquals(
                    1, f.executed.stream().filter(e -> e.equals("root:collect")).count());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        final DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
        final MavenSession session = new MavenSession(mock(RepositorySystemSession.class), request, result);
        final MavenProject root = project("root", true);
        final MavenProject a = project("a", false);
        final MavenProject b = project("b", false);
        final TaskSegment segment = new TaskSegment(false, new LifecycleTask("package"));
        List<TaskSegment> taskSegments = List.of(segment);
        final ExecutionEventCatapult catapult = mock(ExecutionEventCatapult.class);
        final LifecycleTaskSegmentCalculator segments = mock(LifecycleTaskSegmentCalculator.class);
        final Map<String, Builder> builders = new java.util.HashMap<>();
        final LifecycleExecutionPlanCalculator calculator = mock(LifecycleExecutionPlanCalculator.class);
        final List<String> executed = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> successes = new ArrayList<>();
        final AtomicInteger rootPlanCalculations = new AtomicInteger();
        final Map<MavenProject, List<MojoExecution>> plans = new IdentityHashMap<>();
        final MojoExecution collect;
        final LifecycleModuleBuilder moduleBuilder;
        final DefaultLifecycleStarter starter;
        List<MojoExecution> successPlan;
        int rootLifecycleStarts;
        String failAt;
        boolean clearFailureExceptions;
        boolean runtimeFailure;

        Fixture(String collectorPhase, boolean aggregator) throws Exception {
            session.setProjects(List.of(root, a, b));
            session.setProjectDependencyGraph(mock(ProjectDependencyGraph.class));
            request.setReactorFailureBehavior(MavenExecutionRequest.REACTOR_FAIL_FAST);
            request.setProjectPresent(true);
            MojoExecution ordinary = mojo(root, "ordinary", "package", false);
            MojoExecution cleanup = mojo(root, "cleanup", "after:all", false);
            collect = mojo(root, "collect", collectorPhase, aggregator);
            plans.put(root, List.of(ordinary, cleanup, collect));
            plans.put(a, List.of(mojo(a, "package", "package", false)));
            plans.put(b, List.of(mojo(b, "package", "package", false)));
            when(segments.calculateTaskSegments(session)).thenAnswer(call -> taskSegments);
            when(calculator.calculateExecutionPlan(eq(session), any(MavenProject.class), anyList()))
                    .thenAnswer(call -> {
                        MavenProject project = call.getArgument(1);
                        assertSame(project, session.getCurrentProject());
                        if (project == root) {
                            rootPlanCalculations.incrementAndGet();
                        }
                        return new MavenExecutionPlan(
                                ExecutionPlanItem.createExecutionPlanItems(project, plans.get(project)), null);
                    });
            MojoExecutor executor = mock(MojoExecutor.class);
            doAnswer(call -> {
                        MavenProject project = session.getCurrentProject();
                        assertNotNull(project);
                        if (project.getClassRealm() != null) {
                            assertSame(
                                    project.getClassRealm(),
                                    Thread.currentThread().getContextClassLoader());
                        }
                        for (MojoExecution execution : (List<MojoExecution>) call.getArgument(1)) {
                            assertNotNull(execution.getConfiguration());
                            String key = project.getArtifactId() + ":" + execution.getExecutionId();
                            executed.add(key);
                            if (key.equals(failAt)) {
                                if (runtimeFailure) {
                                    throw new IllegalStateException("deliberate runtime failure");
                                }
                                throw new LifecycleExecutionException("deliberate failure", execution, project);
                            }
                            if (execution == collect
                                    && "after:all".equals(collectorPhase)
                                    && aggregator
                                    && taskSegments.size() == 1
                                    && segment.getTasks().size() == 1
                                    && request.getDegreeOfConcurrency() == 1) {
                                assertSame(root, project);
                            }
                        }
                        return null;
                    })
                    .when(executor)
                    .execute(eq(session), anyList());
            doAnswer(call -> {
                        ExecutionEvent.Type type = call.getArgument(0);
                        MavenProject project = session.getCurrentProject();
                        events.add(type + ":" + (project == null ? "session" : project.getArtifactId()));
                        return null;
                    })
                    .when(catapult)
                    .fire(any(ExecutionEvent.Type.class), eq(session), isNull());
            // Error events include their cause and use a separate overload.
            doAnswer(call -> {
                        events.add(call.getArgument(0) + ":"
                                + session.getCurrentProject().getArtifactId());
                        return null;
                    })
                    .when(catapult)
                    .fire(any(ExecutionEvent.Type.class), eq(session), isNull(), any(Exception.class));
            ProjectExecutionListener listener = new ProjectExecutionListener() {
                @Override
                public void beforeProjectExecution(ProjectExecutionEvent event) {}

                @Override
                public void beforeProjectLifecycleExecution(ProjectExecutionEvent event) {
                    if (event.getProject() == root) {
                        rootLifecycleStarts++;
                        assertTrue(event.getExecutionPlan().contains(collect));
                    }
                }

                @Override
                public void afterProjectExecutionSuccess(ProjectExecutionEvent event) {
                    successes.add(event.getProject().getArtifactId());
                    if (event.getProject() == root) {
                        successPlan = event.getExecutionPlan();
                    }
                }

                @Override
                public void afterProjectExecutionFailure(ProjectExecutionEvent event) {
                    if (clearFailureExceptions) {
                        result.getExceptions().clear();
                    }
                }
            };
            BuilderCommon common = new BuilderCommon(new LifecycleDebugLogger(), calculator, catapult);
            moduleBuilder = new LifecycleModuleBuilder(
                    executor, common, catapult, List.of(listener), mock(TransformerManager.class));
            builders.put("singlethreaded", new SingleThreadedBuilder(moduleBuilder));
            starter = new DefaultLifecycleStarter(
                    catapult,
                    mock(DefaultLifecycles.class),
                    new BuildListCalculator(),
                    new LifecycleDebugLogger(),
                    segments,
                    builders,
                    new SequentialRootAggregationCoordinator(moduleBuilder));
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

        private static MojoExecution mojo(MavenProject project, String id, String phase, boolean aggregator) {
            Plugin plugin = new Plugin();
            plugin.setGroupId("test");
            plugin.setArtifactId(id + "-plugin");
            plugin.setVersion("1");
            PluginExecution binding = new PluginExecution();
            binding.setId(id);
            binding.setPhase(phase);
            binding.addGoal(id);
            plugin.addExecution(binding);
            project.getBuild().addPlugin(plugin);
            PluginDescriptor descriptor = new PluginDescriptor();
            descriptor.setPlugin(plugin);
            descriptor.setGroupId(plugin.getGroupId());
            descriptor.setArtifactId(plugin.getArtifactId());
            descriptor.setVersion(plugin.getVersion());
            MojoDescriptor mojo = new MojoDescriptor();
            mojo.setPluginDescriptor(descriptor);
            mojo.setGoal(id);
            mojo.setAggregator(aggregator);
            MojoExecution execution = new MojoExecution(mojo, id);
            execution.setLifecyclePhase(phase);
            execution.setConfiguration(new Xpp3Dom("configuration"));
            return execution;
        }

        @Override
        public void close() throws Exception {
            if (root.getClassRealm() != null) {
                root.getClassRealm().close();
            }
        }
    }
}
