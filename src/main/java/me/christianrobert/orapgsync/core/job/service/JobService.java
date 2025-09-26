package me.christianrobert.orapgsync.core.job.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@ApplicationScoped
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final Map<String, JobExecution<?>> jobExecutions = new ConcurrentHashMap<>();

    public static class JobExecution<T> {
        private final Job<T> job;
        private JobStatus status;
        private JobProgress progress;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private T result;
        private Exception error;
        private CompletableFuture<T> future;

        public JobExecution(Job<T> job) {
            this.job = job;
            this.status = JobStatus.PENDING;
            this.progress = new JobProgress();
        }

        public Job<T> getJob() { return job; }
        public JobStatus getStatus() { return status; }
        public void setStatus(JobStatus status) { this.status = status; }
        public JobProgress getProgress() { return progress; }
        public void setProgress(JobProgress progress) { this.progress = progress; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        public T getResult() { return result; }
        public void setResult(T result) { this.result = result; }
        public Exception getError() { return error; }
        public void setError(Exception error) { this.error = error; }
        public CompletableFuture<T> getFuture() { return future; }
        public void setFuture(CompletableFuture<T> future) { this.future = future; }
    }

    public <T> String submitJob(Job<T> job) {
        String jobId = job.getJobId();

        log.info("Submitting job: {} ({})", jobId, job.getJobType());

        JobExecution<T> execution = new JobExecution<>(job);
        jobExecutions.put(jobId, execution);

        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            execution.setStatus(JobStatus.RUNNING);
            execution.setStartTime(LocalDateTime.now());

            log.info("Starting job execution: {}", jobId);

            try {
                T result = job.execute(progress -> {
                    execution.setProgress(progress);
                    log.debug("Job {} progress: {}%", jobId, progress.getPercentage());
                }).get();

                execution.setResult(result);
                execution.setStatus(JobStatus.COMPLETED);
                execution.setEndTime(LocalDateTime.now());

                log.info("Job completed successfully: {}", jobId);
                return result;

            } catch (Exception e) {
                execution.setError(e);
                execution.setStatus(JobStatus.FAILED);
                execution.setEndTime(LocalDateTime.now());

                log.error("Job failed: " + jobId, e);
                throw new RuntimeException("Job execution failed: " + e.getMessage(), e);
            }
        }, Executors.newCachedThreadPool());

        execution.setFuture(future);
        return jobId;
    }

    public JobExecution<?> getJobExecution(String jobId) {
        return jobExecutions.get(jobId);
    }

    public JobStatus getJobStatus(String jobId) {
        JobExecution<?> execution = jobExecutions.get(jobId);
        return execution != null ? execution.getStatus() : null;
    }

    public JobProgress getJobProgress(String jobId) {
        JobExecution<?> execution = jobExecutions.get(jobId);
        return execution != null ? execution.getProgress() : null;
    }

    public <T> T getJobResult(String jobId) {
        JobExecution<?> execution = jobExecutions.get(jobId);
        if (execution != null && execution.getStatus() == JobStatus.COMPLETED) {
            @SuppressWarnings("unchecked")
            T result = (T) execution.getResult();
            return result;
        }
        return null;
    }

    public Exception getJobError(String jobId) {
        JobExecution<?> execution = jobExecutions.get(jobId);
        if (execution != null && execution.getStatus() == JobStatus.FAILED) {
            return execution.getError();
        }
        return null;
    }

    public boolean isJobComplete(String jobId) {
        JobStatus status = getJobStatus(jobId);
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED;
    }

    public void cleanupOldJobs(int maxAgeHours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(maxAgeHours);

        jobExecutions.entrySet().removeIf(entry -> {
            JobExecution<?> execution = entry.getValue();
            LocalDateTime endTime = execution.getEndTime();

            if (endTime != null && endTime.isBefore(cutoff)) {
                log.debug("Cleaning up old job: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    public Map<String, JobExecution<?>> getAllJobExecutions() {
        return Map.copyOf(jobExecutions);
    }
}