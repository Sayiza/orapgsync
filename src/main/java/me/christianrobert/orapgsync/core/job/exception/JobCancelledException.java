package me.christianrobert.orapgsync.core.job.exception;

/**
 * Exception thrown when a job is cancelled during execution.
 *
 * This exception signals cooperative cancellation - the job detected the
 * cancellation flag and is exiting early. This is NOT an error condition,
 * but a normal operational state.
 *
 * Usage:
 * <pre>
 * protected void checkCancellation() {
 *     if (cancellationToken != null && cancellationToken.isCancelled()) {
 *         throw new JobCancelledException("Job was cancelled");
 *     }
 * }
 * </pre>
 *
 * The JobService catches this exception and marks the job as CANCELLED
 * rather than FAILED.
 */
public class JobCancelledException extends RuntimeException {

    public JobCancelledException() {
        super("Job was cancelled");
    }

    public JobCancelledException(String message) {
        super(message);
    }

    public JobCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
