package me.christianrobert.orapgsync.core.job.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final Map<String, JobExecution<?>> jobExecutions = new ConcurrentHashMap<>();

    /**
     * Shared thread pool for all job executions.
     * Using a single pool prevents unbounded thread creation and memory leaks.
     *
     * CRITICAL FIX: Previously, each job created a new ExecutorService, leading to
     * 100+ thread pools after 100 jobs, consuming 500MB-1.5GB memory.
     *
     * Now: Single shared pool, properly managed lifecycle.
     */
    private ExecutorService executorService;

    /**
     * Initializes the shared thread pool on application startup.
     */
    @PostConstruct
    public void init() {
        log.info("Initializing shared ExecutorService for job execution");
        executorService = Executors.newCachedThreadPool();
    }

    /**
     * Shuts down the thread pool on application shutdown.
     * Waits up to 30 seconds for running jobs to complete.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ExecutorService");
        shutdownExecutorService(executorService, 30);
    }

    /**
     * Shuts down an ExecutorService gracefully.
     *
     * @param executor ExecutorService to shut down
     * @param timeoutSeconds Maximum time to wait for shutdown
     */
    private void shutdownExecutorService(ExecutorService executor, int timeoutSeconds) {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        try {
            log.debug("Shutting down executor service (timeout: {}s)", timeoutSeconds);
            executor.shutdown();

            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in {}s, forcing shutdown", timeoutSeconds);
                executor.shutdownNow();

                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Executor did not terminate after forced shutdown");
                }
            }

            log.debug("Executor service shut down successfully");
        } catch (InterruptedException e) {
            log.error("Interrupted while shutting down executor service", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

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
        }, executorService);

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
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
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

    /**
     * Clears all job executions from memory and resets the thread pool.
     * This should be called when resetting application state to prevent memory leaks.
     *
     * CRITICAL FIX: Now properly shuts down the old ExecutorService and creates a new one.
     * This prevents thread pool accumulation that was causing 500MB-1.5GB memory leaks.
     *
     * ENHANCED FIX: Explicitly cancels all running jobs before shutdown to ensure proper cleanup.
     * This prevents orphaned CompletableFutures and provides clear audit trail of cancelled jobs.
     *
     * Warning: This will clear job history and results. Only use when intentionally
     * resetting the application state (e.g., before starting a new migration run).
     */
    public void resetJobs() {
        int count = jobExecutions.size();
        log.info("Clearing {} job executions from memory and resetting thread pool", count);

        // Cancel all running jobs explicitly
        int cancelledCount = 0;
        for (JobExecution<?> execution : jobExecutions.values()) {
            if (execution.getStatus() == JobStatus.RUNNING) {
                CompletableFuture<?> future = execution.getFuture();
                if (future != null && !future.isDone()) {
                    log.info("Cancelling running job: {} ({})",
                        execution.getJob().getJobId(),
                        execution.getJob().getJobType());

                    future.cancel(true);  // Interrupt if running
                    execution.setStatus(JobStatus.CANCELLED);
                    execution.setError(new Exception("Job cancelled during state reset"));
                    execution.setEndTime(LocalDateTime.now());
                    cancelledCount++;
                }
            }
        }

        if (cancelledCount > 0) {
            log.warn("Cancelled {} running jobs during state reset", cancelledCount);
        } else {
            log.info("No running jobs to cancel");
        }

        // Shut down old executor service
        shutdownExecutorService(executorService, 30);

        // Clear job history
        jobExecutions.clear();

        // Create new executor service
        executorService = Executors.newCachedThreadPool();

        log.info("Job execution history cleared and thread pool reset successfully");
    }
}