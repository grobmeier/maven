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
import java.util.Objects;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/**
 * An opt-in partition of one prepared project plan for one task segment.
 * List membership is immutable. Both lists retain the original configured executions, including their fork plans.
 * Each planned occurrence belongs to exactly one list. Repeated task requests remain separate occurrences.
 * This object neither changes session state nor executes the reserved goals.
 *
 * <strong>NOTE:</strong> This class is not part of any public API.
 */
public final class RootAggregatorExecutionPlan {
    private static final String AFTER_ALL = "after:all";

    private final MavenProject project;
    private final TaskSegment taskSegment;
    private final List<Task> tasks;
    private final MavenExecutionPlan completePlan;
    private final List<MojoExecution> projectExecutions;
    private final List<MojoExecution> deferredExecutions;

    private RootAggregatorExecutionPlan(
            MavenProject project,
            TaskSegment taskSegment,
            MavenExecutionPlan completePlan,
            List<MojoExecution> projectExecutions,
            List<MojoExecution> deferredExecutions) {
        this.project = project;
        this.taskSegment = taskSegment;
        this.tasks = List.copyOf(taskSegment.getTasks());
        this.completePlan = completePlan;
        this.projectExecutions = List.copyOf(projectExecutions);
        this.deferredExecutions = List.copyOf(deferredExecutions);
    }

    static RootAggregatorExecutionPlan partition(
            MavenSession session, MavenProject project, TaskSegment taskSegment, MavenExecutionPlan completePlan) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(project);
        Objects.requireNonNull(taskSegment);
        Objects.requireNonNull(completePlan);

        String builderId = session.getRequest().getBuilderId();
        int workers = session.getRequest().getDegreeOfConcurrency();
        boolean legacyBuilder = ("singlethreaded".equals(builderId) && workers == 1)
                || ("multithreaded".equals(builderId) && workers > 1);
        // A selected child can be the top-level fallback without being the invocation's execution root.
        boolean eligibleContext = project.isExecutionRoot()
                && project == session.getTopLevelProject()
                && legacyBuilder
                && !taskSegment.isAggregating()
                && !taskSegment.getTasks().isEmpty()
                && taskSegment.getTasks().stream().allMatch(LifecycleTask.class::isInstance);

        List<MojoExecution> projectExecutions = new ArrayList<>();
        List<MojoExecution> deferredExecutions = new ArrayList<>();
        for (MojoExecution execution : completePlan.getMojoExecutions()) {
            if (eligibleContext && isExplicitRootAggregator(project, execution)) {
                deferredExecutions.add(execution);
            } else {
                projectExecutions.add(execution);
            }
        }
        return new RootAggregatorExecutionPlan(
                project, taskSegment, completePlan, projectExecutions, deferredExecutions);
    }

    private static boolean isExplicitRootAggregator(MavenProject project, MojoExecution execution) {
        if (execution.getSource() != MojoExecution.Source.LIFECYCLE
                || !AFTER_ALL.equals(execution.getLifecyclePhase())) {
            return false;
        }
        if (!Objects.requireNonNull(
                        execution.getMojoDescriptor(), "The complete plan must contain prepared descriptors")
                .isAggregator()) {
            return false;
        }
        // A descriptor's default phase alone is insufficient. Require an effective-model execution binding.
        for (Plugin plugin : project.getBuildPlugins()) {
            if (plugin.getKey().equals(execution.getPlugin().getKey())) {
                return plugin.getExecutions().stream()
                        .anyMatch(binding -> Objects.equals(binding.getId(), execution.getExecutionId())
                                && AFTER_ALL.equals(binding.getPhase())
                                && binding.getGoals().contains(execution.getGoal()));
            }
        }
        return false;
    }

    public MavenProject getProject() {
        return project;
    }

    /** Returns the original segment identity for correlation with the reactor request. */
    public TaskSegment getTaskSegment() {
        return taskSegment;
    }

    /** Returns a snapshot of the requested tasks, without changing the original segment. */
    public List<Task> getTasks() {
        return tasks;
    }

    /** Returns the unchanged complete plan for inspection. Do not execute it in addition to the partition lists. */
    public MavenExecutionPlan getCompletePlan() {
        return completePlan;
    }

    /** Returns ordinary project work in its original relative order. */
    public List<MojoExecution> getProjectExecutions() {
        return projectExecutions;
    }

    /** Returns reserved root work in its original relative order. The caller owns its later execution policy. */
    public List<MojoExecution> getDeferredExecutions() {
        return deferredExecutions;
    }
}
