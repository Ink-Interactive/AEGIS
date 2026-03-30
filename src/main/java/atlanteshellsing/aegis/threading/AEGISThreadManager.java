package atlanteshellsing.aegis.threading;

import atlanteshellsing.aegis.annotations.ExcludeAsGenerated;
import atlanteshellsing.aegis.custom.factories.AEGISThreadFactory;
import atlanteshellsing.aegis.logging.AEGISLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Core thread & task manager for asynchronous operations in AEGIS.
 *
 * This manager owns multiple thread pools, task metadata, cleanup,
 * and basic metrics. Higher-level schedulers (delayed jobs, repeating jobs,
 * cron-like triggers, etc.) can be built on top of this.
 */
public class AEGISThreadManager {

    public enum TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED,
        REJECTED
    }

    public enum PoolType {
        CPU_BOUND,
        IO_BOUND
    }

    public enum QueueType {
        LINKED_BLOCKING,
        ARRAY_BLOCKING,
        SYNCHRONOUS
    }

    public enum RejectionPolicy {
        ABORT,
        CALLER_RUNS,
        DISCARD,
        DISCARD_OLDEST
    }

    public static final class PoolConfig {
        private final int corePoolSize;
        private final int maximumPoolSize;
        private final Duration keepAlive;
        private final QueueType queueType;
        private final int queueCapacity;
        private final RejectionPolicy rejectionPolicy;
        private boolean allowCoreThreadTimeOut;

        /**
         * Create a PoolConfig with the specified thread pool and queue settings.
         *
         * @param corePoolSize the number of core threads; must be greater than 0
         * @param maximumPoolSize the maximum allowed threads; must be greater than or equal to {@code corePoolSize}
         * @param keepAlive the keep-alive duration for idle threads; must not be null or negative
         * @param queueType the queue implementation to use for the pool; must not be null
         * @param queueCapacity capacity for bounded queues; required to be > 0 when {@code queueType} is {@code ARRAY_BLOCKING}
         * @param rejectionPolicy the policy to apply when the pool cannot accept a task; must not be null
         * @param allowCoreThreadTimeOut whether core threads are allowed to time out
         *
         * @throws NullPointerException if {@code keepAlive}, {@code queueType}, or {@code rejectionPolicy} is null
         * @throws IllegalArgumentException if {@code corePoolSize <= 0}, {@code maximumPoolSize < corePoolSize},
         *                                  if {@code keepAlive} is negative, or if {@code queueType} is {@code ARRAY_BLOCKING}
         *                                  and {@code queueCapacity <= 0}
         */
        public PoolConfig(int corePoolSize, int maximumPoolSize, Duration keepAlive, QueueType queueType, int queueCapacity, RejectionPolicy rejectionPolicy,  boolean allowCoreThreadTimeOut) {

            if(corePoolSize <= 0) throw new IllegalArgumentException("corePoolSize must be > 0");
            if(maximumPoolSize < corePoolSize) throw new IllegalArgumentException("maxPoolSize must be >= corePoolSize");

            this.keepAlive = Objects.requireNonNull(keepAlive, "keepAlive must not be null");
            this.queueType = Objects.requireNonNull(queueType, "queueType must not be null");
            this.rejectionPolicy = Objects.requireNonNull(rejectionPolicy, "rejectionPolicy must not be null");

            if(this.keepAlive.isNegative()) throw new IllegalArgumentException("keepAlive must not be negative");

            if(queueType == QueueType.ARRAY_BLOCKING && queueCapacity <= 0) throw new IllegalArgumentException("ARRAY_BLOCKING queue requires queueCapacity > 0");

            this.corePoolSize = corePoolSize;
            this.maximumPoolSize = maximumPoolSize;
            this.queueCapacity = queueCapacity;
            this.allowCoreThreadTimeOut = allowCoreThreadTimeOut;
        }

        /**
         * Create a PoolConfig for a fixed-size thread pool.
         *
         * @param size the number of threads for both core and maximum pool sizes (must be > 0)
         * @param queueType the type of work queue to use
         * @param queueCapacity capacity for bounded queues; ignored for unbounded or synchronous queues
         * @param rejectionPolicy the policy to apply when the queue is full and execution is rejected
         * @return a PoolConfig with corePoolSize == maximumPoolSize == size, keepAlive == Duration.ZERO, and core thread timeout disabled
         */
        public static PoolConfig fixedPool(int size, QueueType queueType, int queueCapacity, RejectionPolicy rejectionPolicy) {
            return new PoolConfig(size, size, Duration.ZERO, queueType, queueCapacity, rejectionPolicy, false);
        }

        /**
         * Creates a configurable (scaling) pool configuration with the given sizing, queue, and rejection behavior.
         *
         * @param corePoolSize the number of core threads to keep in the pool
         * @param maximumPoolSize the maximum number of threads allowed in the pool
         * @param keepAlive the keep-alive duration for idle threads above the core size
         * @param queueType the type of work queue to use for the pool
         * @param queueCapacity capacity for bounded queues; ignored for unbounded or synchronous queue types
         * @param rejectionPolicy the policy to apply when the pool cannot accept new tasks
         * @param allowCoreThreadTimeOut whether core threads are allowed to time out when idle
         * @return a new PoolConfig instance reflecting the provided settings
         */
        public static PoolConfig scalingPool(int corePoolSize, int maximumPoolSize, Duration keepAlive, QueueType queueType, int queueCapacity, RejectionPolicy rejectionPolicy, boolean allowCoreThreadTimeOut) {
            return new PoolConfig(corePoolSize, maximumPoolSize, keepAlive, queueType, queueCapacity, rejectionPolicy, allowCoreThreadTimeOut);
        }

        /**
 * The configured core number of threads for the pool.
 *
 * @return the core pool size, i.e. the minimum number of threads the pool keeps alive
 */
public int getCorePoolSize() { return corePoolSize; }
        /**
 * Maximum allowed thread count for the pool configuration.
 *
 * @return the maximum number of threads for this pool
 */
public int getMaximumPoolSize() { return maximumPoolSize; }
        /**
 * The keep-alive duration for idle threads in this pool.
 *
 * @return the duration threads are allowed to remain idle before being terminated
 */
public Duration getKeepAlive() { return keepAlive; }
        /**
 * The queue implementation type configured for this pool.
 *
 * @return the configured {@link QueueType} for the pool
 */
public QueueType getQueueType() { return queueType; }
        /**
 * The configured capacity of the pool's work queue.
 *
 * @return the queue capacity (maximum number of tasks the queue can hold); a value of
 *         0 indicates an unbounded queue for implementations that support unbounded capacity.
 */
public int getQueueCapacity() { return queueCapacity; }
        /**
 * Gets the pool's configured rejection policy.
 *
 * @return the configured {@link RejectionPolicy} for the pool
 */
public RejectionPolicy getRejectionPolicy() { return rejectionPolicy; }
        /**
 * Indicates whether core threads are allowed to time out.
 *
 * @return `true` if core threads are allowed to time out, `false` otherwise
 */
public boolean isAllowCoreThreadTimeOut() { return allowCoreThreadTimeOut; }
    }

    public static final class AEGISThreadManagerConfig {
        PoolConfig cpuPoolConfig;
        PoolConfig ioPoolConfig;
        Duration completedTaskRetention;
        Duration cleanupInterval;

        /**
         * Creates a new thread manager configuration with separate CPU and IO pool settings and cleanup timings.
         *
         * @param cpuPoolConfig           configuration for the CPU-bound thread pool; must not be null
         * @param ioPoolConfig            configuration for the IO-bound thread pool; must not be null
         * @param completedTaskRetention  duration to retain completed task metadata; must not be null and must be greater than or equal to zero
         * @param cleanupInterval         interval between periodic cleanup runs; must not be null and must be greater than zero
         * @throws NullPointerException     if any argument is null
         * @throws IllegalArgumentException if `completedTaskRetention` is negative or if `cleanupInterval` is zero or negative
         */
        public AEGISThreadManagerConfig(PoolConfig cpuPoolConfig,  PoolConfig ioPoolConfig, Duration completedTaskRetention, Duration cleanupInterval) {
            this.cpuPoolConfig = Objects.requireNonNull(cpuPoolConfig, "cpuPoolConfig must not be null");
            this.ioPoolConfig = Objects.requireNonNull(ioPoolConfig, "ioPoolConfig must not be null");
            this.completedTaskRetention = Objects.requireNonNull(completedTaskRetention, "completedTaskRetention must not be null");
            this.cleanupInterval = Objects.requireNonNull(cleanupInterval, "cleanupInterval must not be null");

            if(completedTaskRetention.isNegative()) throw new IllegalArgumentException("completedTaskRetention must not be negative");
            if(cleanupInterval.isNegative() || cleanupInterval.isZero()) throw new IllegalArgumentException("cleanupInterval must be > 0");
        }

        /**
         * Creates the default thread-manager configuration with tuned CPU and IO pools and cleanup timings.
         *
         * <p>The returned configuration uses:
         * <ul>
         *   <li>a CPU-bound fixed pool sized to the number of available processors (minimum 2) backed by an ArrayBlockingQueue with capacity 256 and a caller-runs rejection policy;</li>
         *   <li>an IO-bound scaling pool with core size at least 4, a larger maximum size, a 60-second keep-alive, a LinkedBlockingQueue with capacity 1024, caller-runs rejection policy, and core-thread timeout enabled;</li>
         *   <li>a completed-task retention of 10 minutes and a cleanup interval of 30 seconds.</li>
         * </ul>
         *
         * @return the default {@link AEGISThreadManagerConfig} populated with the CPU and IO pool configurations and retention/cleanup durations described above
         */
        public static AEGISThreadManagerConfig defaultConfig() {
            int processors = Math.max(2, Runtime.getRuntime().availableProcessors());

            PoolConfig cpu = PoolConfig.fixedPool(processors, QueueType.ARRAY_BLOCKING, 256, RejectionPolicy.CALLER_RUNS);
            PoolConfig io = PoolConfig.scalingPool(Math.max(4, processors), Math.max(8, processors * 4), Duration.ofSeconds(60), QueueType.LINKED_BLOCKING,1024, RejectionPolicy.CALLER_RUNS, true);

            return new AEGISThreadManagerConfig(cpu, io, Duration.ofMinutes(10), Duration.ofSeconds(30));
        }

        /**
 * Gets the configuration used for the CPU-bound thread pool.
 *
 * @return the immutable PoolConfig for CPU-bound tasks
 */
public PoolConfig getCpuPoolConfig() { return cpuPoolConfig; }
        /**
 * Gets the configuration used for the IO-bound thread pool.
 *
 * @return the PoolConfig applied to the IO pool
 */
public PoolConfig getIoPoolConfig() { return ioPoolConfig; }
        /**
 * How long completed tasks are retained before they become eligible for cleanup.
 *
 * @return the duration to retain completed tasks before they are eligible for purge
 */
public Duration getCompletedTaskRetention() { return completedTaskRetention; }
        /**
 * Interval between scheduled cleanup executions performed by the manager.
 *
 * @return the configured cleanup interval as a Duration
 */
public Duration getCleanupInterval() { return cleanupInterval; }
    }

    /**
     * Immutable info snapshot for a single submitted task.
     */
    public static final class AEGISTaskMetaData {
        private final UUID id;
        private final String name;
        private final String owner;
        private final PoolType poolType;
        private final Instant createdAt;
        private final Instant startedAt;
        private final Instant lastRunAt;
        private final Instant completedAt;
        private final TaskState state;

        /**
         * Constructs an AEGISTaskMetaData instance initialized with the provided identity, ownership, pool type, timestamps, and lifecycle state.
         *
         * @param id         the task's UUID identifier
         * @param name       the task's display name
         * @param owner      the task owner's identifier
         * @param poolType   the pool type (CPU_BOUND or IO_BOUND) where the task ran or will run
         * @param createdAt  the instant the task was created
         * @param startedAt  the instant the task started execution, or null if not started
         * @param lastRunAt  the instant the task last executed (success or failure), or null if never run
         * @param completedAt the instant the task reached a terminal state, or null if not completed
         * @param state      the current lifecycle state of the task
         */
        private AEGISTaskMetaData(UUID id, String name, String owner, PoolType poolType, Instant createdAt, Instant startedAt, Instant lastRunAt, Instant completedAt, TaskState state) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.poolType = poolType;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
            this.lastRunAt = lastRunAt;
            this.completedAt = completedAt;
            this.state = state;
        }

        /**
 * Unique identifier for this task submission.
 *
 * @return the UUID of the submission
 */
public UUID getId() { return id; }
        /**
 * The task's name.
 *
 * @return the task's name
 */
public String getName() { return name; }
        /**
 * Gets the owner of the task.
 *
 * @return the owner of the task, or "None" if no owner was specified
 */
public String getOwner() { return owner; }
        /**
 * The pool type assigned to this task.
 *
 * @return the {@link PoolType} indicating whether the task is CPU_BOUND or IO_BOUND
 */
public PoolType getPoolType() { return poolType; }
        /**
 * The instant when this task handle was created.
 *
 * @return the creation timestamp for the task as an Instant
 */
public Instant getCreatedAt() { return createdAt; }
        /**
 * The timestamp when the task first began execution, or null if the task has not started.
 *
 * @return the Instant when the task started, or null if it has not started
 */
public Instant getStartedAt() { return startedAt; }
        /**
 * Gets the timestamp of the task's most recent execution attempt.
 *
 * @return the Instant when the task was last executed or attempted, or null if the task has never run
 */
public Instant getLastRunAt() { return lastRunAt; }
        /**
 * Gets the timestamp when the task entered a terminal state.
 *
 * @return the Instant the task completed (completed, cancelled, failed, or rejected), or null if the task has not reached a terminal state
 */
public Instant getCompletedAt() { return completedAt; }
        /**
 * Retrieve the current lifecycle state of the task.
 *
 * @return the current {@link TaskState} of the task
 */
public TaskState getState() { return state; }
    }

    public static final class AEGISTaskSubmission<T> {
        private final UUID id;
        private final Future<T> future;

        /**
         * Creates a new submission handle associating a task id with its future.
         *
         * @param id the UUID identifying the submitted task
         * @param future the Future representing the task's execution result
         */
        private AEGISTaskSubmission(UUID id, Future<T> future) {
            this.id = id;
            this.future = future;
        }

        /**
 * Unique identifier for this task submission.
 *
 * @return the UUID of the submission
 */
public UUID getId() { return id; }
        /**
 * The Future representing this submission's scheduled computation.
 *
 * @return the task's Future, or `null` if the future has not been set yet
 */
public Future<T> getFuture() { return future; }
    }

    public static final class PoolMetrics {
        private final PoolType poolType;
        private final int corePoolSize;
        private final int maximumPoolSize;
        private final int currentPoolSize;
        private final int largestPoolSize;
        private final int activeThreadCount;
        private final int queuedTaskCount;
        private final int queueRemaningCapacity;
        private final long poolCompletedTaskCount;
        private final long poolScheduledTaskCount;

        /**
         * Creates an immutable snapshot of runtime metrics for the specified thread pool.
         *
         * @param poolType               the pool type (CPU_BOUND or IO_BOUND)
         * @param corePoolSize           configured core pool size
         * @param maximumPoolSize        configured maximum pool size
         * @param currentPoolSize        current number of threads in the pool
         * @param largestPoolSize        largest number of threads the pool has ever contained
         * @param activeThreadCount      number of threads currently executing tasks
         * @param queuedTaskCount        number of tasks currently queued
         * @param queueRemaningCapacity  remaining capacity of the pool's work queue
         * @param poolCompletedTaskCount number of tasks the pool has completed
         * @param poolScheduledTaskCount total number of tasks scheduled/submitted to the manager
         */
        private PoolMetrics(PoolType poolType, int corePoolSize, int maximumPoolSize, int currentPoolSize, int largestPoolSize, int activeThreadCount, int queuedTaskCount, int queueRemaningCapacity, long poolCompletedTaskCount, long poolScheduledTaskCount) {
            this.poolType = poolType;
            this.corePoolSize = corePoolSize;
            this.maximumPoolSize = maximumPoolSize;
            this.currentPoolSize = currentPoolSize;
            this.largestPoolSize = largestPoolSize;
            this.activeThreadCount = activeThreadCount;
            this.queuedTaskCount = queuedTaskCount;
            this.queueRemaningCapacity = queueRemaningCapacity;
            this.poolCompletedTaskCount = poolCompletedTaskCount;
            this.poolScheduledTaskCount = poolScheduledTaskCount;

        }

        /**
 * The pool type assigned to this task.
 *
 * @return the {@link PoolType} indicating whether the task is CPU_BOUND or IO_BOUND
 */
public PoolType getPoolType() { return poolType; }
        /**
 * The configured core number of threads for the pool.
 *
 * @return the core pool size, i.e. the minimum number of threads the pool keeps alive
 */
public int getCorePoolSize() { return corePoolSize; }
        /**
 * Maximum allowed thread count for the pool configuration.
 *
 * @return the maximum number of threads for this pool
 */
public int getMaximumPoolSize() { return maximumPoolSize; }
        /**
 * Retrieves the current number of threads in the pool.
 *
 * @return the current number of threads in the pool
 */
public int getCurrentPoolSize() { return currentPoolSize; }
        /**
 * The largest number of threads that have ever simultaneously existed in the pool.
 *
 * @return the largest number of threads observed in the pool
 */
public int getLargestPoolSize() { return largestPoolSize; }
        /**
 * The number of threads in the pool that are currently executing tasks.
 *
 * @return the number of threads currently executing tasks in the pool
 */
public int getActiveThreadCount() { return activeThreadCount; }
        /**
 * Number of tasks currently waiting in the pool's work queue.
 *
 * @return the number of tasks queued for execution
 */
public int getQueuedTaskCount() { return queuedTaskCount; }
        /**
 * Gets the remaining capacity of the pool's work queue.
 *
 * @return the number of additional tasks that can be accepted by the queue before it becomes full
 */
public int getQueueRemaningCapacity() { return queueRemaningCapacity; }
        /**
 * Retrieves the number of tasks completed by this pool.
 *
 * @return the total count of tasks completed by the pool
 */
public long getPoolCompletedTaskCount() { return poolCompletedTaskCount; }
        /**
 * Number of tasks that have been scheduled for this pool since the manager started.
 *
 * @return the total number of tasks submitted to this pool since manager startup
 */
public long getPoolScheduledTaskCount() { return poolScheduledTaskCount; }
    }

    public static final class ThreadManagerMetrics {
        private final PoolMetrics cpuPoolMetrics;
        private final PoolMetrics ioPoolMetrics;
        private final int trackedTaskCount;
        private final long pendingTaskCount;
        private final long runningTaskCount;
        private final long completedTaskCount;
        private final long canceledTaskCount;
        private final long failedTaskCount;
        private final long rejectedTaskCount;
        private final long totalSubmittedTaskCount;
        private final long totalCompletedTaskCount;
        private final long totalCancelledTaskCount;
        private final long totalFailedTaskCount;
        private final long totalRejectedTaskCount;
        private final long totalCleanedUpTaskCount;

        /**
         * Constructs a ThreadManagerMetrics snapshot with the provided per-pool metrics and task counters.
         *
         * @param cpuPoolMetrics                 snapshot metrics for the CPU-bound thread pool
         * @param ioPoolMetrics                  snapshot metrics for the IO-bound thread pool
         * @param trackedTaskCount               number of tasks currently tracked in the manager
         * @param pendingTaskCount               count of tasks in the pending state
         * @param runningTaskCount               count of tasks in the running state
         * @param completedTaskCount             count of tasks in the completed state
         * @param canceledTaskCount              count of tasks in the cancelled state
         * @param failedTaskCount                count of tasks in the failed state
         * @param rejectedTaskCount              count of tasks that were rejected by the executor
         * @param totalSubmittedTaskCount        cumulative number of tasks submitted since startup
         * @param totalCompletedTaskCount        cumulative number of tasks completed since startup
         * @param totalCancelledTaskCount        cumulative number of tasks cancelled since startup
         * @param totalFailedTaskCount           cumulative number of tasks that failed since startup
         * @param totalRejectedTaskCount         cumulative number of tasks rejected since startup
         * @param totalCleanedUpTaskCount        cumulative number of tasks purged/cleaned up since startup
         */
        private ThreadManagerMetrics(PoolMetrics cpuPoolMetrics,
                                     PoolMetrics ioPoolMetrics,
                                     int trackedTaskCount,
                                     long pendingTaskCount,
                                     long runningTaskCount,
                                     long completedTaskCount,
                                     long canceledTaskCount,
                                     long failedTaskCount,
                                     long rejectedTaskCount,
                                     long totalSubmittedTaskCount,
                                     long totalCompletedTaskCount,
                                     long totalCancelledTaskCount,
                                     long totalFailedTaskCount,
                                     long totalRejectedTaskCount,
                                     long totalCleanedUpTaskCount) {

            this.cpuPoolMetrics = cpuPoolMetrics;
            this.ioPoolMetrics = ioPoolMetrics;
            this.trackedTaskCount = trackedTaskCount;
            this.pendingTaskCount = pendingTaskCount;
            this.runningTaskCount = runningTaskCount;
            this.completedTaskCount = completedTaskCount;
            this.canceledTaskCount = canceledTaskCount;
            this.failedTaskCount = failedTaskCount;
            this.rejectedTaskCount = rejectedTaskCount;
            this.totalSubmittedTaskCount = totalSubmittedTaskCount;
            this.totalCompletedTaskCount = totalCompletedTaskCount;
            this.totalCancelledTaskCount = totalCancelledTaskCount;
            this.totalFailedTaskCount = totalFailedTaskCount;
            this.totalRejectedTaskCount = totalRejectedTaskCount;
            this.totalCleanedUpTaskCount = totalCleanedUpTaskCount;
        }

        /**
 * Retrieve an immutable snapshot of runtime metrics for the CPU thread pool.
 *
 * @return an immutable {@code PoolMetrics} snapshot describing the CPU pool's configuration and current runtime statistics
 */
public PoolMetrics getCpuPoolMetrics() { return cpuPoolMetrics; }
        /**
 * Gets a snapshot of the IO-bound thread pool's runtime metrics.
 *
 * @return a {@link PoolMetrics} instance containing the IO pool's configuration and current runtime statistics
 */
public PoolMetrics getIoPoolMetrics() { return ioPoolMetrics; }
        /**
 * Gets the number of tasks currently tracked by the manager.
 *
 * @return the number of tracked tasks
 */
public int getTrackedTaskCount() { return trackedTaskCount; }
        /**
 * Number of tracked tasks currently in the PENDING state.
 *
 * @return the number of tracked tasks in the PENDING state
 */
public long getPendingTaskCount() { return pendingTaskCount; }
        /**
 * Provides the current number of tasks in the RUNNING state.
 *
 * @return the current count of tasks in the RUNNING state
 */
public long getRunningTaskCount() { return runningTaskCount; }
        /**
 * Snapshot of how many tasks have completed execution in the pool.
 *
 * @return the cumulative number of completed tasks for this pool
 */
public long getCompletedTaskCount() { return completedTaskCount; }
        /**
 * Returns the total number of tasks that have been cancelled by the manager.
 *
 * @return the cumulative count of cancelled tasks
 */
public long getCanceledTaskCount() { return canceledTaskCount; }
        /**
 * Gets the total number of tasks that have transitioned to the FAILED state.
 *
 * @return the total count of failed tasks
 */
public long getFailedTaskCount() { return failedTaskCount; }
        /**
 * Retrieve the cumulative number of tasks that have been rejected by the manager.
 *
 * @return the total count of rejected tasks since the manager was initialized
 */
public long getRejectedTaskCount() { return rejectedTaskCount; }
        /**
 * Provides the total number of tasks that have been submitted to the manager since startup.
 *
 * @return the total number of submitted tasks
 */
public long getTotalSubmittedTaskCount() { return totalSubmittedTaskCount; }
        /**
 * Cumulative number of tasks that have completed execution.
 *
 * @return the cumulative count of completed tasks recorded by the manager
 */
public long getTotalCompletedTaskCount() { return totalCompletedTaskCount; }
        /**
 * Retrieve the total number of tasks that have been cancelled since the manager was initialized.
 *
 * @return the total number of cancelled tasks
 */
public long getTotalCancelledTaskCount() { return totalCancelledTaskCount; }
        /**
 * Fetches the total number of tasks that have entered the FAILED state.
 *
 * @return the total count of tasks recorded as `FAILED` since the manager started
 */
public long getTotalFailedTaskCount() { return totalFailedTaskCount; }
        /**
 * Get the total number of tasks that have been rejected by the thread manager.
 *
 * @return the total count of rejected tasks recorded since the manager started
 */
public long getTotalRejectedTaskCount() { return totalRejectedTaskCount; }
        /**
 * Gets the total number of tasks that have been cleaned up (purged) by the manager since startup.
 *
 * @return the total count of cleaned-up tasks
 */
public long getTotalCleanedUpTaskCount() { return totalCleanedUpTaskCount; }
    }

    /**
     * Internal mutable handle tracked in the registry.
     */
    private static final class AEGISTaskHandle {
        private final UUID id;
        private final String name;
        private final String owner;
        private final PoolType poolType;
        private final Instant createdAt;
        private final AtomicReference<Instant> startedAt = new AtomicReference<>();
        private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();
        private final AtomicReference<Instant> completedAt = new AtomicReference<>();
        private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.PENDING);
        private final AtomicReference<Future<?>> future = new AtomicReference<>();

        /**
         * Construct a new task handle capturing identity, ownership, pool assignment, and creation timestamp.
         *
         * @param id       the unique identifier for the task
         * @param name     the task's display name (may be null or blank for unnamed tasks)
         * @param owner    the task owner or submitter identifier (may be null or blank)
         * @param poolType the pool this task is assigned to (CPU_BOUND or IO_BOUND)
         */
        private AEGISTaskHandle(UUID id, String name, String owner, PoolType poolType) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.poolType = poolType;
            this.createdAt = Instant.now();
        }

        /**
         * Create an immutable snapshot of this task's current metadata.
         *
         * @return an {@link AEGISTaskMetaData} containing the task identity (id, name, owner), the task's pool type,
         *         timestamps (createdAt, startedAt, lastRunAt, completedAt), and the current task state
         */
        private AEGISTaskMetaData toMetaData() {
            return new AEGISTaskMetaData(id, name, owner, poolType, createdAt, startedAt.get(), lastRunAt.get(), completedAt.get(), state.get());
        }
    }

    private static final Object LIFECYCLE_LOCK = new Object();

    private static final Map<UUID, AEGISTaskHandle> TASKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final LongAdder TOTAL_SUBMITTED = new  LongAdder();
    private static final LongAdder TOTAL_COMPLETED = new  LongAdder();
    private static final LongAdder TOTAL_CANCELLED = new  LongAdder();
    private static final LongAdder TOTAL_FAILED = new  LongAdder();
    private static final LongAdder TOTAL_REJECTED = new  LongAdder();
    private static final LongAdder TOTAL_CLEANED_UP = new  LongAdder();

    private static volatile AEGISThreadManagerConfig config = AEGISThreadManagerConfig.defaultConfig();
    private static volatile ThreadPoolExecutor cpuPool;
    private static volatile ThreadPoolExecutor ioPool;
    private static volatile ScheduledExecutorService cleanupExecutor;
    private static volatile boolean intialized;

    /**
     * Prevents instantiation of this utility class.
     */
    @ExcludeAsGenerated

    private AEGISThreadManager() {}

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownGracefully(Duration.ofSeconds(10)), "AEGISThreadManager-ShutdownHook"));
    }

    /**
     * Replaces the manager configuration and (re)initializes the thread pools and cleanup scheduler using the provided configuration.
     *
     * If the manager is already initialized, this call will only reconfigure and restart internal executors when there are no active (non-terminal) tasks.
     *
     * @param newConfig the new manager configuration; must not be null
     * @throws IllegalStateException if the manager is initialized and there are active (non-terminal) tasks
     */
    public static void configure(AEGISThreadManagerConfig newConfig) {
        Objects.requireNonNull(newConfig, "config cannot be null");

        synchronized (LIFECYCLE_LOCK) {
            if (intialized && hasLiveTasks()) throw new IllegalStateException("Cannot reconfigure AEGISThreadManager while tasks are active. Shut it down or wait for idle state first.");

            if(intialized) shutdownInternal(Duration.ofSeconds(10));

            config = newConfig;
            intializedLocked();
        }
    }

    /**
     * Submit a Runnable for asynchronous execution on the CPU-bound pool and return its submission id.
     *
     * @return the UUID identifying the submitted task
     */
    public static UUID submitAsyncTask(String name, Runnable task, String owner) {
        return submitAsyncTask(name, task, owner, PoolType.CPU_BOUND);
    }

    /**
     * Submit a Runnable task for asynchronous execution on the specified pool and return its submission id.
     *
     * @param name     a human-readable task name; if blank, a default name is assigned
     * @param task     the Runnable to execute (must not be null)
     * @param owner    an identifier for the task owner; if blank, a default owner is used
     * @param poolType the pool to run the task in (CPU_BOUND or IO_BOUND)
     * @return         the UUID that identifies the submitted task
     */
    public static UUID submitAsyncTask(String name, Runnable task, String owner, PoolType poolType) {
        return submitAsyncTask(name, asCallable(task), owner, poolType).getId();
    }

    /**
     * Submits a callable for asynchronous execution using the CPU-bound thread pool.
     *
     * <p>The provided callable is scheduled for execution and tracked; a submission handle is
     * returned so callers can query the task id and its Future.</p>
     *
     * @param name  a human-readable name for the task (may be blank)
     * @param task  the callable to execute; must not be null
     * @param owner an identifier for the task owner or submitter (may be blank)
     * @param <T>   the callable result type
     * @return      an AEGISTaskSubmission containing the submitted task's UUID and Future
     */
    public static <T> AEGISTaskSubmission<T> submitAsyncTask(String name, Callable<T> task, String owner) {
        return submitAsyncTask(name, task, owner, PoolType.CPU_BOUND);
    }

    /**
     * Submits a callable task to the specified pool, registers it with the manager, and returns a submission handle.
     *
     * @param name     optional task display name; if null or blank, defaults to "Unnamed Task"
     * @param task     the callable to execute (must not be null)
     * @param owner    optional task owner identifier; if null or blank, defaults to "None"
     * @param poolType the target pool for execution (must not be null)
     * @return         an AEGISTaskSubmission containing the task's UUID and its Future
     * @throws RejectedExecutionException if the executor rejects the submission
     */
    public static <T> AEGISTaskSubmission<T> submitAsyncTask(String name, Callable<T> task, String owner, PoolType poolType) {
        Objects.requireNonNull(task, "task cannot be null");
        Objects.requireNonNull(poolType, "poolType cannot be null");

        UUID id = UUID.randomUUID();
        String effectiveName = (name == null || name.isBlank()) ? "Unnamed Task" : name;
        String effectiveOwner = (owner == null || owner.isBlank()) ? "None" : owner;

        AEGISTaskHandle handle = new AEGISTaskHandle(id, effectiveName, effectiveOwner, poolType);
        TASKS.put(id, handle);
        TOTAL_SUBMITTED.increment();

        AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                AEGISLogger.AEGISLogLevel.INFO,
                "Submitting async task: " + handle.name + " (" + id + ") owner=" + handle.owner + " pool=" + poolType);

        try {
            Future<T> future;
            synchronized (LIFECYCLE_LOCK) {
                ensureInitializedLocked();
                future = executorFor(poolType).submit(wrapCallable(handle, task));
                handle.future.set(future);
            }
            return new AEGISTaskSubmission<>(id, future);

        } catch(RejectedExecutionException e) {
            Instant rejectedAt = Instant.now();
            handle.lastRunAt.set(rejectedAt);
            handle.completedAt.set(rejectedAt);
            handle.state.set(TaskState.REJECTED);
            TOTAL_REJECTED.increment();

            AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                    AEGISLogger.AEGISLogLevel.WARNING,
                    "Task rejected by thread pool: " + handle.name + " (" + handle.id + ")"
            );
            throw e;
        }
    }

    /**
     * Attempt to cancel a tracked task identified by `id`.
     *
     * If cancellation succeeds, updates the task's `lastRunAt` and `completedAt` timestamps,
     * transitions its state to `CANCELLED`, increments the cancelled counter unless the
     * task was already in a terminal state (`CANCELLED`, `FAILED`, or `REJECTED`), and logs the event.
     *
     * @param id the UUID of the task to cancel
     * @return `true` if the task's Future was cancelled, `false` otherwise
     */
    public static boolean cancelTask(UUID id) {
        AEGISTaskHandle handle = TASKS.get(id);
        if(handle == null) return false;

        Future<?> future = handle.future.get();
        if(future == null) return false;

        boolean cancelled = future.cancel(true);
        if(cancelled) {
            Instant cancelledAt = Instant.now();
            TaskState previousState = handle.state.getAndSet(TaskState.CANCELLED);
            handle.lastRunAt.set(cancelledAt);
            handle.completedAt.compareAndSet(null, cancelledAt);

            if(previousState != TaskState.CANCELLED
            && previousState != TaskState.FAILED
            && previousState != TaskState.REJECTED) TOTAL_CANCELLED.increment();

            AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                    AEGISLogger.AEGISLogLevel.INFO,
                    "Task cancelled: " + handle.name + " (" + handle.id + ")"
            );
        }

        return cancelled;
    }

    /**
     * Retrieve a metadata snapshot for a tracked task.
     *
     * @param id the UUID of the task
     * @return an Optional containing the task's AEGISTaskMetaData if the task exists, otherwise an empty Optional
     */
    public static Optional<AEGISTaskMetaData> getTaskInfo(UUID id) {
        AEGISTaskHandle handle = TASKS.get(id);
        return handle != null ? Optional.of(handle.toMetaData()) : Optional.empty();
    }

    /**
     * Provides an unmodifiable snapshot of metadata for all currently tracked tasks.
     *
     * @return an unmodifiable List of AEGISTaskMetaData representing a point-in-time snapshot of all tracked tasks
     */
    public static List<AEGISTaskMetaData> listTasks() {
        List<AEGISTaskMetaData> allTasks = new ArrayList<>(TASKS.size());
        for(AEGISTaskHandle handle : TASKS.values()) allTasks.add(handle.toMetaData());
        return Collections.unmodifiableList(allTasks);
    }

    /**
     * Removes completed tasks that are older than the configured completed-task retention period.
     *
     * @return the number of tasks removed from the internal registry
     */
    public static int purgeCompletedTasks() {
        ensureInitialized();
        return purgeCompletedTasks(config.getCompletedTaskRetention());
    }

    /**
     * Removes tracked tasks whose completion time is older than the specified retention period.
     *
     * Iterates the manager's task registry and removes tasks that are in a terminal state and whose
     * completion timestamp is at or before Instant.now().minus(retention).
     *
     * @param retention the maximum age for completed tasks to retain; tasks completed earlier than or equal to this duration before now will be purged
     * @return the number of tasks removed
     * @throws NullPointerException if {@code retention} is null
     * @throws IllegalArgumentException if {@code retention} is negative
     */
    public static int purgeCompletedTasks(Duration retention) {
        Objects.requireNonNull(retention, "retention cannot be null");
        if(retention.isNegative()) throw new IllegalArgumentException("retention cannot be negative");

        Instant cutoff = Instant.now().minus(retention);
        int purged = 0;

        for(Map.Entry<UUID, AEGISTaskHandle> entry : TASKS.entrySet()) {
            AEGISTaskHandle handle = entry.getValue();
            Instant completedAt = handle.completedAt.get();
            TaskState state = handle.state.get();

            if(completedAt != null && isTerminalState(state) && !completedAt.isAfter(cutoff)) {
                boolean didRemove = TASKS.remove(entry.getKey(), handle);
                if(didRemove) {
                    purged++;
                    TOTAL_CLEANED_UP.increment();
                }
            }
        }

        return purged;
    }

    /**
     * Retrieve a runtime snapshot of metrics for the specified thread pool.
     *
     * @param poolType the pool to inspect (CPU_BOUND or IO_BOUND)
     * @return a PoolMetrics snapshot containing core and maximum pool sizes, current pool size,
     *         largest pool size, active thread count, queued task count, queue remaining capacity,
     *         the pool's completed task count, and the total submitted task count
     */
    public static PoolMetrics getPoolMetrics(PoolType poolType) {
        Objects.requireNonNull(poolType, "poolType cannot be null");
        ensureInitialized();

        ThreadPoolExecutor executor = executorFor(poolType);
        return new PoolMetrics(
                poolType,
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getPoolSize(),
                executor.getLargestPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount(),
                TOTAL_SUBMITTED.sum()
        );
    }

    /**
     * Collects current metrics for both thread pools and tracked tasks.
     *
     * @return a ThreadManagerMetrics containing CPU and IO PoolMetrics, the total number of tracked tasks, counts of tasks by state (pending, running, completed, cancelled, failed, rejected), and accumulated totals for submitted, completed, cancelled, failed, rejected, and cleaned-up tasks.
     */
    public static ThreadManagerMetrics getMetrics() {
        ensureInitialized();

        long pending = 0;
        long running = 0;
        long completed = 0;
        long canceled = 0;
        long failures = 0;
        long rejected = 0;

        for(AEGISTaskHandle handle : TASKS.values()) {
            switch (handle.state.get()) {
                case PENDING -> pending++;
                case RUNNING -> running++;
                case COMPLETED -> completed++;
                case CANCELLED -> canceled++;
                case FAILED -> failures++;
                case REJECTED -> rejected++;
            }
        }

        return new ThreadManagerMetrics(
                getPoolMetrics(PoolType.CPU_BOUND),
                getPoolMetrics(PoolType.IO_BOUND),
                TASKS.size(),
                pending,
                running,
                completed,
                canceled,
                failures,
                rejected,
                TOTAL_SUBMITTED.sum(),
                TOTAL_COMPLETED.sum(),
                TOTAL_CANCELLED.sum(),
                TOTAL_FAILED.sum(),
                TOTAL_REJECTED.sum(),
                TOTAL_CLEANED_UP.sum()
        );
    }

    /**
     * Initiates a graceful shutdown of the thread manager and its executors, waiting up to the given timeout for termination.
     *
     * If the manager has not been initialized this method returns immediately.
     *
     * @param timeout the maximum duration to wait for executors to terminate; must not be null
     */
    public static void shutdownGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");

        synchronized (LIFECYCLE_LOCK) {
            if(!intialized) return;
            shutdownInternal(timeout);
        }
    }

    /**
     * Stops the cleanup executor and both worker pools, waiting up to the given timeout for orderly termination
     * and forcing immediate shutdown if they do not terminate in time.
     *
     * This method:
     * - requests immediate shutdown of the cleanup executor and orderly shutdown of the CPU and IO pools,
     * - waits up to `timeout` for each pool to terminate and calls `shutdownNow()` if a pool fails to terminate in time,
     * - on interruption reasserts the interrupt status and forces immediate shutdown of both pools,
     * - marks the manager as not initialized when complete.
     *
     * @param timeout maximum time to wait for each pool to terminate before forcing shutdown; must not be null
     */
    private static void shutdownInternal(Duration timeout) {
        AEGISLogger.log(
                AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                AEGISLogger.AEGISLogLevel.INFO,
                "Shutting down AEGIS Thread Manager..."
        );

        if(cleanupExecutor != null) cleanupExecutor.shutdownNow();
        if(cpuPool != null) cpuPool.shutdown();
        if(ioPool != null) ioPool.shutdown();

        try {
            if(cpuPool != null && !cpuPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                AEGISLogger.log(
                        AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                        AEGISLogger.AEGISLogLevel.WARNING,
                        "Forcing CPU pool shutdown after timeout"
                );
                cpuPool.shutdownNow();
            }

            if(ioPool != null && !ioPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                AEGISLogger.log(
                        AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                        AEGISLogger.AEGISLogLevel.WARNING,
                        "Forcing IO pool shutdown after timeout"
                );
                ioPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if(cpuPool != null) cpuPool.shutdownNow();
            if(ioPool != null) ioPool.shutdownNow();
        } finally {
            intialized = false;
        }
    }

    /**
     * Ensures the thread manager is initialized, performing initialization while holding the lifecycle lock.
     */
    private static void ensureInitialized() {
        synchronized (LIFECYCLE_LOCK) {
            ensureInitializedLocked();
        }
    }

    /**
     * Ensures the thread manager is initialized while the lifecycle lock is held.
     *
     * If the manager is not initialized, initializes internal executors and scheduled cleanup.
     */
    private static void ensureInitializedLocked() {
        if(!intialized) intializedLocked();
    }

    /**
     * Initializes the CPU and IO thread pools and starts the scheduled cleanup executor.
     *
     * <p>The scheduled cleanup task runs at the configured cleanup interval and invokes
     * {@code purgeCompletedTasks} using the configured completed-task retention; any exceptions
     * thrown by the cleanup task are caught and logged. After creating the executors and scheduling
     * the cleanup task, this method marks the manager as initialized.
     */
    private static void intializedLocked() {
        cpuPool = createExecutor(config.getCpuPoolConfig(), "AEGIS-CPU");
        ioPool = createExecutor(config.getIoPoolConfig(), "AEGIS-IO");
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(new AEGISThreadFactory("AEGIS-Cleanup", true, null));
        cleanupExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        purgeCompletedTasks(config.getCompletedTaskRetention());
                    } catch (Exception e) {
                        AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                                AEGISLogger.AEGISLogLevel.WARNING,
                                "AEGIS cleanup task encountered an error",
                                e
                        );
                    }
                },

                config.getCleanupInterval().toMillis(),
                config.getCleanupInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );

        intialized = true;
    }

    /**
     * Creates a Callable that executes the provided delegate and updates the associated task's
     * lifecycle metadata, timestamps, and global counters based on the execution outcome.
     *
     * The returned Callable will set start/last-run/completion timestamps, transition the task
     * state (e.g., PENDING -> RUNNING, RUNNING -> COMPLETED/FAILED), and increment the
     * corresponding TOTAL_COMPLETED or TOTAL_FAILED counters as appropriate.
     *
     * @param  handle   the mutable task handle whose metadata and state will be updated during execution
     * @param  delegate the work to execute
     * @param  <T>      the delegate's return type
     * @return          a Callable that runs the delegate and returns its result while maintaining task bookkeeping
     */
    private static <T> Callable<T> wrapCallable(AEGISTaskHandle handle, Callable<T> delegate) {
        return () -> {
            handle.startedAt.compareAndSet(null, Instant.now());
            handle.state.compareAndSet(TaskState.PENDING, TaskState.RUNNING);

            try {
                T result = delegate.call();
                Instant finishedAt = Instant.now();
                handle.lastRunAt.set(finishedAt);
                handle.completedAt.compareAndSet(null, finishedAt);

                if (handle.state.compareAndSet(TaskState.RUNNING, TaskState.COMPLETED)) {
                    TOTAL_COMPLETED.increment();
                }
                return result;
            } catch (Exception e) {
                Instant failedAt = Instant.now();
                handle.lastRunAt.set(failedAt);
                handle.completedAt.compareAndSet(null, failedAt);

                if (handle.state.compareAndSet(TaskState.RUNNING, TaskState.FAILED)) {
                    TOTAL_FAILED.increment();
                    AEGISLogger.log(
                            AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                            AEGISLogger.AEGISLogLevel.SEVERE,
                            "Task failed: " + handle.name + " (" + handle.id + ")",
                            e
                    );
                } else if (handle.state.get() == TaskState.CANCELLED) {
                    AEGISLogger.log(
                            AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                            AEGISLogger.AEGISLogLevel.SEVERE,
                            "Task acknowledged cancellation: " + handle.name + " (" + handle.id + ")"
                    );
                }

                throw e;
            }
        };
    }

    /**
     * Selects the ThreadPoolExecutor associated with the given pool type.
     *
     * @param poolType the pool type whose executor should be returned
     * @return the ThreadPoolExecutor for the specified pool type
     */
    private static ThreadPoolExecutor executorFor(PoolType poolType) {
        return switch (poolType) {
            case CPU_BOUND -> cpuPool;
            case IO_BOUND -> ioPool;
        };
    }

    /**
     * Creates a ThreadPoolExecutor configured according to the provided PoolConfig and thread name prefix.
     *
     * @param poolConfig   configuration describing core/max sizes, keep-alive, queue type/capacity, and rejection policy
     * @param threadPrefix prefix used for threads created by the executor's thread factory
     * @return             a ThreadPoolExecutor configured with the settings from {@code poolConfig}
     */
    private static ThreadPoolExecutor createExecutor(PoolConfig poolConfig, String threadPrefix) {
        BlockingQueue<Runnable> queue = createQueue(poolConfig);
        RejectedExecutionHandler handler = createRejectionHandler(poolConfig.getRejectionPolicy());

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolConfig.getCorePoolSize(),
                poolConfig.getMaximumPoolSize(),
                poolConfig.getKeepAlive().toMillis(),
                TimeUnit.MILLISECONDS,
                queue,
                new AEGISThreadFactory(threadPrefix, false, null),
                handler
        );

        executor.allowCoreThreadTimeOut(poolConfig.isAllowCoreThreadTimeOut());
        return executor;
    }

    /**
     * Selects a BlockingQueue implementation based on the PoolConfig's QueueType and capacity.
     *
     * @param poolConfig configuration whose QueueType and queueCapacity determine the queue returned
     * @return a `LinkedBlockingQueue` (bounded if `queueCapacity > 0`, otherwise unbounded) when QueueType is `LINKED_BLOCKING`,
     *         an `ArrayBlockingQueue` sized to `queueCapacity` when QueueType is `ARRAY_BLOCKING`,
     *         or a `SynchronousQueue` when QueueType is `SYNCHRONOUS`
     */
    private static BlockingQueue<Runnable> createQueue(PoolConfig poolConfig) {
        return switch (poolConfig.getQueueType()) {
            case LINKED_BLOCKING -> poolConfig.getQueueCapacity() > 0
                    ? new LinkedBlockingQueue<>(poolConfig.getQueueCapacity())
                    : new LinkedBlockingQueue<>();
            case ARRAY_BLOCKING -> new ArrayBlockingQueue<>(poolConfig.getQueueCapacity());
            case SYNCHRONOUS -> new SynchronousQueue<>();
        };
    }

    /**
     * Maps the specified rejection policy to the corresponding ThreadPoolExecutor rejection handler.
     *
     * @param policy the rejection policy to convert into a handler
     * @return a RejectedExecutionHandler that implements the given rejection policy
     */
    private static RejectedExecutionHandler createRejectionHandler(RejectionPolicy policy) {
        return switch (policy) {
            case ABORT -> new ThreadPoolExecutor.AbortPolicy();
            case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
            case DISCARD -> new ThreadPoolExecutor.DiscardPolicy();
            case DISCARD_OLDEST -> new ThreadPoolExecutor.DiscardOldestPolicy();
        };
    }

    /**
     * Determines whether the manager currently has any live work to do.
     *
     * A task is considered live if either pool has active threads or queued tasks, or if any tracked task
     * is not in a terminal state.
     *
     * @return `true` if there are active or queued tasks in either pool or any tracked task is not in a terminal state, `false` otherwise.
     */
    private static boolean hasLiveTasks() {
        if(cpuPool != null && (cpuPool.getActiveCount() > 0 || !cpuPool.getQueue().isEmpty())) return true;
        if(ioPool != null && (ioPool.getActiveCount() > 0 || !ioPool.getQueue().isEmpty())) return true;

        for(AEGISTaskHandle handle : TASKS.values()) {
            if(!isTerminalState(handle.state.get())) return true;
        }

        return false;
    }

    /**
     * Determine whether the given task state is terminal.
     *
     * @param state the task state to evaluate
     * @return `true` if the state is COMPLETED, CANCELLED, FAILED, or REJECTED, `false` otherwise
     */
    private static boolean isTerminalState(TaskState state) {
        return state == TaskState.COMPLETED
                || state == TaskState.CANCELLED
                || state == TaskState.FAILED
                || state == TaskState.REJECTED;
    }

    /**
     * Converts a Runnable into a Callable that executes the Runnable.
     *
     * @param runnable the Runnable to execute
     * @return a Callable that runs the given Runnable and returns `null`
     */
    private static Callable<Void> asCallable(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable cannot be null");
        return () -> {
            runnable.run();
            return null;
        };
    }
}
