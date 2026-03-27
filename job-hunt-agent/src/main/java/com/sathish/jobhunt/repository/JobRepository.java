package com.sathish.jobhunt.repository;

import com.sathish.jobhunt.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, String> {

    boolean existsByJobUrl(String jobUrl);

    Optional<Job> findByJobUrl(String jobUrl);

    List<Job> findByStatusOrderByMatchScoreDesc(Job.ApplicationStatus status);

    List<Job> findByMatchScoreGreaterThanEqualAndStatusOrderByMatchScoreDesc(
        int minScore, Job.ApplicationStatus status
    );

    @Query("SELECT j FROM Job j WHERE j.status = 'APPLIED' " +
           "AND j.appliedAt < :cutoff AND j.recruiterEmail IS NOT NULL")
    List<Job> findJobsRequiringFollowUp(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT j FROM Job j WHERE j.status IN ('DISCOVERED', 'ANALYZED') " +
           "AND j.matchScore >= :minScore ORDER BY j.matchScore DESC")
    List<Job> findApplicableJobs(@Param("minScore") int minScore);

    @Query("SELECT COUNT(j) FROM Job j WHERE j.status = 'APPLIED' " +
           "AND j.appliedAt >= :since")
    long countApplicationsSince(@Param("since") LocalDateTime since);

    @Query("SELECT j FROM Job j WHERE j.source = :source " +
           "ORDER BY j.discoveredAt DESC")
    List<Job> findBySource(@Param("source") String source);

    @Query("SELECT j.status, COUNT(j) FROM Job j GROUP BY j.status")
    List<Object[]> getStatusSummary();
}
