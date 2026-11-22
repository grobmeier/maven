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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.lifecycle.internal.builder.singlethreaded.SingleThreadedBuilder;
import org.apache.maven.project.MavenProject;

/** Coordinates ordinary root work, selected projects, and deferred root aggregators in the single-threaded builder. */
@Named
@Singleton
public class SequentialRootAggregationCoordinator {
    private final LifecycleModuleBuilder lifecycleModuleBuilder;

    @Inject
    public SequentialRootAggregationCoordinator(LifecycleModuleBuilder lifecycleModuleBuilder) {
        this.lifecycleModuleBuilder = lifecycleModuleBuilder;
    }

    boolean canDefer(
            MavenSession session, Builder builder, ProjectBuildList projectBuilds, List<TaskSegment> taskSegments) {
        if (!(builder instanceof SingleThreadedBuilder)
                || !"singlethreaded".equals(session.getRequest().getBuilderId())
                || session.getRequest().getDegreeOfConcurrency() != 1
                || taskSegments.size() != 1) {
            return false;
        }

        TaskSegment segment = taskSegments.get(0);
        // Multiple lifecycle tasks and CLI segments retain their existing execution order.
        if (segment.isAggregating()
                || segment.getTasks().size() != 1
                || !(segment.getTasks().get(0) instanceof LifecycleTask)) {
            return false;
        }

        MavenProject root = session.getTopLevelProject();
        return root != null
                && root.isExecutionRoot()
                && projectBuilds.get(0).getProject() == root
                && projectBuilds.get(0).getTaskSegment() == segment
                && root.getBuildPlugins().stream()
                        .flatMap(plugin -> plugin.getExecutions().stream())
                        .anyMatch(execution -> "after:all".equals(execution.getPhase())
                                && !execution.getGoals().isEmpty());
    }

    void build(
            MavenSession session,
            ReactorContext reactorContext,
            ProjectBuildList projectBuilds,
            List<TaskSegment> taskSegments,
            Builder builder)
            throws ExecutionException, InterruptedException {
        ProjectSegment root = projectBuilds.get(0);
        LifecycleModuleBuilder.DeferredRootBuild deferred;
        try {
            deferred = lifecycleModuleBuilder.buildProjectWithDeferredRootAggregators(
                    session, reactorContext, root.getProject(), root.getTaskSegment());
        } catch (RuntimeException e) {
            // The module builder already records the failure and halts the reactor.
            return;
        }

        List<ProjectSegment> remaining = new ArrayList<>();
        for (int i = 1; i < projectBuilds.size(); i++) {
            remaining.add(projectBuilds.get(i));
        }
        if (!reactorContext.getReactorBuildStatus().isHalted()) {
            builder.build(
                    session,
                    reactorContext,
                    new ProjectBuildList(remaining),
                    taskSegments,
                    reactorContext.getReactorBuildStatus());
        }
        if (deferred != null) {
            // Build summaries also catch failed or skipped work when fail-never masks exceptions.
            boolean requiredWorkSucceeded = !session.getResult().hasExceptions()
                    && remaining.stream()
                            .allMatch(project ->
                                    session.getResult().getBuildSummary(project.getProject()) instanceof BuildSuccess);
            try {
                lifecycleModuleBuilder.completeDeferredRootProject(
                        session, reactorContext, deferred, requiredWorkSucceeded);
            } catch (RuntimeException e) {
                // Preserve normal module failure handling without adding the same exception twice.
            }
        }
    }
}
