package me.christianrobert.orapgsync.core.job;

import me.christianrobert.orapgsync.core.job.model.JobProgress;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface Job<T> {

    String getJobId();

    String getJobType();

    String getDescription();

    CompletableFuture<T> execute(Consumer<JobProgress> progressCallback);

    default void updateProgress(Consumer<JobProgress> progressCallback, int percentage, String currentTask) {
        if (progressCallback != null) {
            progressCallback.accept(new JobProgress(percentage, currentTask));
        }
    }

    default void updateProgress(Consumer<JobProgress> progressCallback, int percentage, String currentTask, String details) {
        if (progressCallback != null) {
            progressCallback.accept(new JobProgress(percentage, currentTask, details));
        }
    }
}