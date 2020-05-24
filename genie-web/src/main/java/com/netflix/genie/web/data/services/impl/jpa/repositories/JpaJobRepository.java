/*
 * Copyright 2015 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.genie.web.data.services.impl.jpa.repositories;

import com.netflix.genie.common.dto.JobStatus;
import com.netflix.genie.web.data.services.impl.jpa.entities.JobEntity;
import com.netflix.genie.web.data.services.impl.jpa.queries.aggregates.UserJobResourcesAggregate;
import com.netflix.genie.web.data.services.impl.jpa.queries.projections.JobApplicationsProjection;
import com.netflix.genie.web.data.services.impl.jpa.queries.projections.JobClusterProjection;
import com.netflix.genie.web.data.services.impl.jpa.queries.projections.JobCommandProjection;
import com.netflix.genie.web.data.services.impl.jpa.queries.projections.JobProjection;
import com.netflix.genie.web.data.services.impl.jpa.queries.projections.JobRequestProjection;
import com.netflix.genie.web.data.services.impl.jpa.queries.projections.v4.JobSpecificationProjection;
import com.netflix.genie.web.data.services.impl.jpa.queries.projections.v4.V4JobRequestProjection;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Job repository.
 *
 * @author tgianos
 */
public interface JpaJobRepository extends JpaBaseRepository<JobEntity> {

    /**
     * The query used to find batches of jobs before a certain time.
     */
    String FIND_OLD_JOBS_QUERY =
        "SELECT id"
            + " FROM jobs"
            + " WHERE created < :createdThreshold AND status NOT IN (:excludedStatuses)"
            + " LIMIT :batchSize"; // JPQL doesn't support limit so this needs to be native query

    // TODO: Make interfaces generic but be aware of https://jira.spring.io/browse/DATAJPA-1185

    /**
     * Find jobs by host name and status.
     *
     * @param agentHostname The host name to search for
     * @param statuses      The job statuses to filter by
     * @return The jobs
     */
    @EntityGraph(value = JobEntity.V3_JOB_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Set<JobProjection> findByAgentHostnameAndStatusIn(String agentHostname, Set<String> statuses);

    /**
     * Get the number of agent jobs on a given host with the given statuses.
     *
     * @param agentHostname The hostname of the agent
     * @param statuses      The statuses the job should be in e.g. {@link JobStatus#getActiveStatuses()}
     * @return The number of jobs in the given statuses on that node
     */
    long countByAgentHostnameAndStatusIn(String agentHostname, Set<String> statuses);

    /**
     * Given the hostname that agents are running on return the total memory their jobs are currently using.
     *
     * @param agentHostname The agent hostname
     * @param statuses      The job statuses to filter by e.g. {@link JobStatus#getActiveStatuses()}
     * @return The total memory used in MB
     */
    @Query(
        "SELECT COALESCE(SUM(j.memoryUsed), 0)"
            + " FROM JobEntity j"
            + " WHERE j.agentHostname = :agentHostname AND j.status IN (:statuses)"
    )
    long getTotalMemoryUsedOnHost(
        @Param("agentHostname") String agentHostname,
        @Param("statuses") Set<String> statuses
    );

    /**
     * Find the jobs with one of the statuses entered.
     *
     * @param statuses The statuses to search
     * @return The {@link Set} of hostname's running V3 (embedded) jobs
     * @deprecated Only used for old v3 jobs
     */
    @Query("SELECT DISTINCT(j.agentHostname) FROM JobEntity j WHERE j.v4 = false AND j.status IN (:statuses)")
    @Deprecated
    Set<String> getV3Hosts(@Param("statuses") Set<String> statuses);

    /**
     * Count all jobs that belong to a given user and are in any of the given states.
     *
     * @param user     the user name
     * @param statuses the set of statuses
     * @return the count of jobs matching the search criteria
     */
    Long countJobsByUserAndStatusIn(@NotBlank String user, @NotEmpty Set<String> statuses);

    /**
     * Find a batch of jobs that were created before the given time.
     *
     * @param createdThreshold The time before which the jobs were submitted. Exclusive
     * @param excludeStatuses  The set of statuses which should be excluded from the results
     * @param limit            The maximum number of jobs to to find
     * @return The number of deleted jobs
     */
    @Query(value = FIND_OLD_JOBS_QUERY, nativeQuery = true)
    Set<Long> findJobsCreatedBefore(
        @Param("createdThreshold") Instant createdThreshold,
        @Param("excludedStatuses") Set<String> excludeStatuses,
        @Param("batchSize") int limit
    );

    /**
     * Returns resources usage for each user that has a running job.
     * Only jobs running on Genie servers are considered (i.e. no Agent jobs)
     *
     * @return The user resource aggregates
     */
    @Query(
        "SELECT j.user AS user, COUNT(j) as runningJobsCount, SUM(j.memoryUsed) as usedMemory"
            + " FROM JobEntity j"
            + " WHERE j.status = 'RUNNING' AND j.v4 = FALSE"
            + " GROUP BY j.user"
    )
    Set<UserJobResourcesAggregate> getUserJobResourcesAggregates();

    /**
     * Find agent jobs in the given set of states.
     *
     * @param statuses the job statuses filter
     * @return a set of job projections
     */
    @Query(
        "SELECT j.uniqueId"
            + " FROM JobEntity j"
            + " WHERE j.status IN (:statuses)"
            + " AND j.v4 = TRUE"
    )
    Set<String> getAgentJobIdsWithStatusIn(@Param("statuses") @NotEmpty Set<String> statuses);

    /**
     * Get only the status of a job.
     *
     * @param id The id of the job to get the status for
     * @return The job status string or {@link Optional#empty()} if no job with the given id exists
     */
    @Query("SELECT j.status FROM JobEntity j WHERE j.uniqueId = :id")
    Optional<String> getJobStatus(@Param("id") String id);

    /**
     * Return whether the job is running with the V4 agent or the older V3 embedded mechanism.
     *
     * @param id The unique id of the job
     * @return {@literal true} if the job is running with the agent. {@link Optional#empty()} if the job doesn't exist
     * @deprecated Only here during v3 to v4 agent migration
     */
    @Query("SELECT j.v4 FROM JobEntity j WHERE j.uniqueId = :id")
    @Deprecated
    Optional<Boolean> isV4(@Param("id") String id);

    /**
     * Return whether the job was submitted via the API or the Agent CLI.
     *
     * @param id The unique id of the job
     * @return {@literal true} if the job was submitted via the API. {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j.api FROM JobEntity j WHERE j.uniqueId = :id")
    Optional<Boolean> isAPI(@Param("id") String id);

    /**
     * Get only the hostname of a job.
     *
     * @param id The id of the job to get the hostname for
     * @return The job hostname or {@link Optional#empty()} if no job with the given id exists
     */
    @Query("SELECT j.agentHostname FROM JobEntity j WHERE j.uniqueId = :id")
    Optional<String> getJobHostname(@Param("id") String id);

    /**
     * Get the data needed to create a V3 Job DTO.
     *
     * @param id The unique id of the job
     * @return The {@link JobProjection} data or {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j FROM JobEntity j WHERE j.uniqueId = :id")
    @EntityGraph(value = JobEntity.V3_JOB_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Optional<JobProjection> getV3Job(@Param("id") String id);

    /**
     * Get the data needed to create a V3 Job Request DTO.
     *
     * @param id The unique id of the job
     * @return The {@link JobRequestProjection} data or {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j FROM JobEntity j WHERE j.uniqueId = :id")
    @EntityGraph(value = JobEntity.V3_JOB_REQUEST_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Optional<JobRequestProjection> getV3JobRequest(@Param("id") String id);

    /**
     * Get the data needed to create a V4 Job Request DTO.
     *
     * @param id The unique id of the job
     * @return The {@link V4JobRequestProjection} data or {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j FROM JobEntity j WHERE j.uniqueId = :id")
    @EntityGraph(value = JobEntity.V4_JOB_REQUEST_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Optional<V4JobRequestProjection> getV4JobRequest(@Param("id") String id);

    /**
     * Get the data needed to create a V4 Job Specification DTO.
     *
     * @param id The unique id of the job
     * @return The {@link JobSpecificationProjection} data or {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j FROM JobEntity j WHERE j.uniqueId = :id")
    @EntityGraph(value = JobEntity.V4_JOB_SPECIFICATION_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Optional<JobSpecificationProjection> getV4JobSpecification(@Param("id") String id);

    /**
     * Get the applications for a job.
     *
     * @param id The unique id of the job
     * @return The {@link JobApplicationsProjection} data or {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j FROM JobEntity j WHERE j.uniqueId = :id")
    @EntityGraph(value = JobEntity.JOB_APPLICATIONS_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Optional<JobApplicationsProjection> getJobApplications(@Param("id") String id);

    /**
     * Get the cluster for a job.
     *
     * @param id The unique id of the job
     * @return The {@link JobClusterProjection} data or {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j FROM JobEntity j WHERE j.uniqueId = :id")
    @EntityGraph(value = JobEntity.JOB_CLUSTER_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Optional<JobClusterProjection> getJobCluster(@Param("id") String id);

    /**
     * Get the command for a job.
     *
     * @param id The unique id of the job
     * @return The {@link JobCommandProjection} data or {@link Optional#empty()} if the job doesn't exist
     */
    @Query("SELECT j FROM JobEntity j WHERE j.uniqueId = :id")
    @EntityGraph(value = JobEntity.JOB_COMMAND_DTO_ENTITY_GRAPH, type = EntityGraph.EntityGraphType.LOAD)
    Optional<JobCommandProjection> getJobCommand(@Param("id") String id);
}