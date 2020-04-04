/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.web.selectors.impl;

import com.google.common.collect.Sets;
import com.netflix.genie.common.external.dtos.v4.Cluster;
import com.netflix.genie.common.external.dtos.v4.JobRequest;
import com.netflix.genie.web.dtos.ResourceSelectionResult;
import com.netflix.genie.web.exceptions.checked.ResourceSelectionException;
import com.netflix.genie.web.scripts.ClusterSelectorManagedScript;
import com.netflix.genie.web.scripts.ResourceSelectorScriptResult;
import com.netflix.genie.web.selectors.ClusterSelector;
import com.netflix.genie.web.util.MetricsConstants;
import com.netflix.genie.web.util.MetricsUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * An implementation of the {@link ClusterSelector} interface which uses user-provided script to make decisions
 * based on the list of clusters and the job request supplied.
 *
 * @author tgianos
 * @since 3.1.0
 */
@Slf4j
public class ScriptClusterSelectorImpl implements ClusterSelector {

    static final String SELECT_TIMER_NAME = "genie.jobs.clusters.selectors.script.select.timer";
    private static final String NULL_TAG = "null";
    private static final String NULL_RATIONALE = "Script returned null, no preference";

    private final MeterRegistry registry;
    private final ClusterSelectorManagedScript clusterSelectorManagedScript;

    /**
     * Constructor.
     *
     * @param clusterSelectorManagedScript the cluster selector script
     * @param registry                     the metrics registry
     */
    public ScriptClusterSelectorImpl(
        final ClusterSelectorManagedScript clusterSelectorManagedScript,
        final MeterRegistry registry
    ) {
        this.clusterSelectorManagedScript = clusterSelectorManagedScript;
        this.registry = registry;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResourceSelectionResult<Cluster> select(
        @NotEmpty final Set<@Valid Cluster> clusters,
        @Valid final JobRequest jobRequest,
        @NotBlank final String jobId
    ) throws ResourceSelectionException {
        final long selectStart = System.nanoTime();
        log.debug("Called to select cluster from {} for job {}", clusters, jobId);
        final Set<Tag> tags = Sets.newHashSet();
        final ResourceSelectionResult.Builder<Cluster> builder = new ResourceSelectionResult.Builder<>(this.getClass());

        try {
            final ResourceSelectorScriptResult<Cluster> result
                = this.clusterSelectorManagedScript.selectResource(clusters, jobRequest, jobId);
            MetricsUtils.addSuccessTags(tags);

            final Optional<Cluster> clusterOptional = result.getResource();
            if (!clusterOptional.isPresent()) {
                final String rationale = result.getRationale().orElse(NULL_RATIONALE);
                log.debug("No cluster selected due to: {}", rationale);
                tags.add(Tag.of(MetricsConstants.TagKeys.CLUSTER_ID, NULL_TAG));
                builder.withSelectionRationale(rationale);
                return builder.build();
            }

            final Cluster selectedCluster = clusterOptional.get();
            tags.add(Tag.of(MetricsConstants.TagKeys.CLUSTER_ID, selectedCluster.getId()));
            tags.add(Tag.of(MetricsConstants.TagKeys.CLUSTER_NAME, selectedCluster.getMetadata().getName()));

            return builder
                .withSelectionRationale(result.getRationale().orElse(null))
                .withSelectedResource(selectedCluster)
                .build();
        } catch (final Throwable e) {
            final String errorMessage = "Cluster selection error: " + e.getMessage();
            log.error(errorMessage, e);
            MetricsUtils.addFailureTagsWithException(tags, e);
            if (e instanceof ResourceSelectionException) {
                throw e;
            } else {
                throw new ResourceSelectionException(e);
            }
        } finally {
            this.registry
                .timer(SELECT_TIMER_NAME, tags)
                .record(System.nanoTime() - selectStart, TimeUnit.NANOSECONDS);
        }
    }
}