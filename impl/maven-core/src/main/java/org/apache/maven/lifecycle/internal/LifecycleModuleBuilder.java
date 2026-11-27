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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import org.apache.maven.api.MonotonicClock;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.execution.ProjectExecutionListener;
import org.apache.maven.internal.transformation.TransformerManager;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.lifecycle.internal.builder.BuilderCommon;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/**
 * <p>
 * Builds one or more lifecycles for a full module
 * </p>
 * <strong>NOTE:</strong> This class is not part of any public api and can be changed or deleted without prior notice.
 *
 * @since 3.0
 */
@Named
@Singleton
public class LifecycleModuleBuilder {

    private final MojoExecutor mojoExecutor;
    private final BuilderCommon builderCommon;
    private final ExecutionEventCatapult eventCatapult;
    private final ProjectExecutionListener projectExecutionListener;
    private final TransformerManager transformerManager;

    @Inject
    public LifecycleModuleBuilder(
            MojoExecutor mojoExecutor,
            BuilderCommon builderCommon,
            ExecutionEventCatapult eventCatapult,
            List<ProjectExecutionListener> listeners,
            TransformerManager transformerManager) {
        this.mojoExecutor = mojoExecutor;
        this.builderCommon = builderCommon;
        this.eventCatapult = eventCatapult;
        this.projectExecutionListener = new CompoundProjectExecutionListener(listeners);
        this.transformerManager = transformerManager;
    }

    public void buildProject(
            MavenSession session, ReactorContext reactorContext, MavenProject currentProject, TaskSegment taskSegment) {
        buildProject(session, session, reactorContext, currentProject, taskSegment);
    }

    public void buildProject(
            MavenSession session,
            MavenSession rootSession,
            ReactorContext reactorContext,
            MavenProject currentProject,
            TaskSegment taskSegment) {
        buildProject(session, rootSession, reactorContext, currentProject, taskSegment, false);
    }

    DeferredRootBuild buildProjectWithDeferredRootAggregators(
            MavenSession session, ReactorContext reactorContext, MavenProject project, TaskSegment taskSegment) {
        return buildProjectWithDeferredRootAggregators(session, session, reactorContext, project, taskSegment);
    }

    public DeferredRootBuild buildProjectWithDeferredRootAggregators(
            MavenSession session,
            MavenSession rootSession,
            ReactorContext reactorContext,
            MavenProject project,
            TaskSegment taskSegment) {
        return buildProject(session, rootSession, reactorContext, project, taskSegment, true);
    }

    private DeferredRootBuild buildProject(
            MavenSession session,
            MavenSession rootSession,
            ReactorContext reactorContext,
            MavenProject currentProject,
            TaskSegment taskSegment,
            boolean reserveRootAggregators) {
        session.setCurrentProject(currentProject);

        Instant buildStartTime = MonotonicClock.now();
        boolean successReported = false;

        try {

            if (reactorContext.getReactorBuildStatus().isHaltedOrBlacklisted(currentProject)) {
                eventCatapult.fire(ExecutionEvent.Type.ProjectSkipped, session, null);
                return null;
            }

            transformerManager.injectTransformedArtifacts(session.getRepositorySession(), currentProject);

            BuilderCommon.attachToThread(currentProject);

            projectExecutionListener.beforeProjectExecution(new ProjectExecutionEvent(session, currentProject));

            eventCatapult.fire(ExecutionEvent.Type.ProjectStarted, session, null);

            MavenExecutionPlan executionPlan =
                    builderCommon.resolveBuildPlan(session, currentProject, taskSegment, new HashSet<>());
            List<MojoExecution> mojoExecutions = executionPlan.getMojoExecutions();
            RootAggregatorExecutionPlan partition = reserveRootAggregators
                    ? RootAggregatorExecutionPlan.partition(session, currentProject, taskSegment, executionPlan)
                    : null;

            projectExecutionListener.beforeProjectLifecycleExecution(
                    new ProjectExecutionEvent(session, currentProject, mojoExecutions));
            boolean hasDeferredExecutions =
                    partition != null && !partition.getDeferredExecutions().isEmpty();
            mojoExecutor.execute(session, hasDeferredExecutions ? partition.getProjectExecutions() : mojoExecutions);

            if (hasDeferredExecutions) {
                return new DeferredRootBuild(partition, buildStartTime);
            }

            Instant buildEndTime = MonotonicClock.now();

            successReported = true;
            projectExecutionListener.afterProjectExecutionSuccess(
                    new ProjectExecutionEvent(session, currentProject, mojoExecutions));

            reactorContext
                    .getResult()
                    .addBuildSummary(new BuildSuccess(currentProject, Duration.between(buildStartTime, buildEndTime)));

            eventCatapult.fire(ExecutionEvent.Type.ProjectSucceeded, session, null);
        } catch (Throwable t) {
            builderCommon.handleBuildError(reactorContext, rootSession, session, currentProject, t, buildStartTime);

            if (!successReported) {
                projectExecutionListener.afterProjectExecutionFailure(
                        new ProjectExecutionEvent(session, currentProject, t));
            }

            // rethrow original errors and runtime exceptions
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (t instanceof Error error) {
                throw error;
            }
        } finally {
            session.setCurrentProject(null);

            Thread.currentThread().setContextClassLoader(reactorContext.getOriginalContextClassLoader());
        }
        return null;
    }

    void completeDeferredRootProject(
            MavenSession session,
            ReactorContext reactorContext,
            DeferredRootBuild deferred,
            boolean requiredWorkSucceeded) {
        completeDeferredRootProject(session, session, reactorContext, deferred, requiredWorkSucceeded);
    }

    public void completeDeferredRootProject(
            MavenSession session,
            MavenSession rootSession,
            ReactorContext reactorContext,
            DeferredRootBuild deferred,
            boolean requiredWorkSucceeded) {
        if (deferred.completed) {
            throw new IllegalStateException("The deferred root build is already complete");
        }
        deferred.completed = true;
        MavenProject project = deferred.plan.getProject();
        MavenProject previousProject = session.getCurrentProject();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        session.setCurrentProject(project);
        try {
            BuilderCommon.attachToThread(project);
            if (!requiredWorkSucceeded) {
                throw new LifecycleExecutionException(
                        "Deferred root aggregators were skipped because selected reactor work did not succeed.",
                        null,
                        project);
            }
            mojoExecutor.execute(session, deferred.plan.getDeferredExecutions());
            projectExecutionListener.afterProjectExecutionSuccess(new ProjectExecutionEvent(
                    session, project, deferred.plan.getCompletePlan().getMojoExecutions()));
            reactorContext
                    .getResult()
                    .addBuildSummary(
                            new BuildSuccess(project, Duration.between(deferred.buildStartTime, MonotonicClock.now())));
            eventCatapult.fire(ExecutionEvent.Type.ProjectSucceeded, session, null);
        } catch (Throwable t) {
            builderCommon.handleBuildError(reactorContext, rootSession, session, project, t, deferred.buildStartTime);
            projectExecutionListener.afterProjectExecutionFailure(new ProjectExecutionEvent(session, project, t));
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (t instanceof Error error) {
                throw error;
            }
        } finally {
            session.setCurrentProject(previousProject);
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    public static final class DeferredRootBuild {
        private final RootAggregatorExecutionPlan plan;
        private final Instant buildStartTime;
        private boolean completed;

        private DeferredRootBuild(RootAggregatorExecutionPlan plan, Instant buildStartTime) {
            this.plan = plan;
            this.buildStartTime = buildStartTime;
        }
    }
}
