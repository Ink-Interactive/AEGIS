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

        public static PoolConfig fixedPool(int size, QueueType queueType, int queueCapacity, RejectionPolicy rejectionPolicy) {
            return new PoolConfig(size, size, Duration.ZERO, queueType, queueCapacity, rejectionPolicy, false);
        }

        public static PoolConfig scalingPool(int corePoolSize, int maximumPoolSize, Duration keepAlive, QueueType queueType, int queueCapacity, RejectionPolicy rejectionPolicy, boolean allowCoreThreadTimeOut) {
            return new PoolConfig(corePoolSize, maximumPoolSize, keepAlive, queueType, queueCapacity, rejectionPolicy, allowCoreThreadTimeOut);
        }

        public int getCorePoolSize() { return corePoolSize; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public Duration getKeepAlive() { return keepAlive; }
        public QueueType getQueueType() { return queueType; }
        public int getQueueCapacity() { return queueCapacity; }
        public RejectionPolicy getRejectionPolicy() { return rejectionPolicy; }
        public boolean isAllowCoreThreadTimeOut() { return allowCoreThreadTimeOut; }
    }

    public static final class AEGISThreadManagerConfig {
        PoolConfig cpuPoolConfig;
        PoolConfig ioPoolConfig;
        Duration completedTaskRetention;
        Duration cleanupInterval;

        public AEGISThreadManagerConfig(PoolConfig cpuPoolConfig,  PoolConfig ioPoolConfig, Duration completedTaskRetention, Duration cleanupInterval) {
            this.cpuPoolConfig = Objects.requireNonNull(cpuPoolConfig, "cpuPoolConfig must not be null");
            this.ioPoolConfig = Objects.requireNonNull(ioPoolConfig, "ioPoolConfig must not be null");
            this.completedTaskRetention = Objects.requireNonNull(completedTaskRetention, "completedTaskRetention must not be null");
            this.cleanupInterval = Objects.requireNonNull(cleanupInterval, "cleanupInterval must not be null");

            if(completedTaskRetention.isNegative()) throw new IllegalArgumentException("completedTaskRetention must not be negative");
            if(cleanupInterval.isNegative() || cleanupInterval.isZero()) throw new IllegalArgumentException("cleanupInterval must be > 0");
        }

        public static AEGISThreadManagerConfig defaultConfig() {
            int processors = Math.max(2, Runtime.getRuntime().availableProcessors());

            PoolConfig cpu = PoolConfig.fixedPool(processors, QueueType.ARRAY_BLOCKING, 256, RejectionPolicy.CALLER_RUNS);
            PoolConfig io = PoolConfig.scalingPool(Math.max(4, processors), Math.max(8, processors * 4), Duration.ofSeconds(60), QueueType.LINKED_BLOCKING,1024, RejectionPolicy.CALLER_RUNS, true);

            return new AEGISThreadManagerConfig(cpu, io, Duration.ofMinutes(10), Duration.ofSeconds(30));
        }

        public PoolConfig getCpuPoolConfig() { return cpuPoolConfig; }
        public PoolConfig getIoPoolConfig() { return ioPoolConfig; }
        public Duration getCompletedTaskRetention() { return completedTaskRetention; }
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

        public UUID getId() { return id; }
        public String getName() { return name; }
        public String getOwner() { return owner; }
        public PoolType getPoolType() { return poolType; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getLastRunAt() { return lastRunAt; }
        public Instant getCompletedAt() { return completedAt; }
        public TaskState getState() { return state; }
    }

    public static final class AEGISTaskSubmission<T> {
        private final UUID id;
        private final Future<T> future;

        private AEGISTaskSubmission(UUID id, Future<T> future) {
            this.id = id;
            this.future = future;
        }

        public UUID getId() { return id; }
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

        public PoolType getPoolType() { return poolType; }
        public int getCorePoolSize() { return corePoolSize; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public int getCurrentPoolSize() { return currentPoolSize; }
        public int getLargestPoolSize() { return largestPoolSize; }
        public int getActiveThreadCount() { return activeThreadCount; }
        public int getQueuedTaskCount() { return queuedTaskCount; }
        public int getQueueRemaningCapacity() { return queueRemaningCapacity; }
        public long getPoolCompletedTaskCount() { return poolCompletedTaskCount; }
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

        public PoolMetrics getCpuPoolMetrics() { return cpuPoolMetrics; }
        public PoolMetrics getIoPoolMetrics() { return ioPoolMetrics; }
        public int getTrackedTaskCount() { return trackedTaskCount; }
        public long getPendingTaskCount() { return pendingTaskCount; }
        public long getRunningTaskCount() { return runningTaskCount; }
        public long getCompletedTaskCount() { return completedTaskCount; }
        public long getCanceledTaskCount() { return canceledTaskCount; }
        public long getFailedTaskCount() { return failedTaskCount; }
        public long getRejectedTaskCount() { return rejectedTaskCount; }
        public long getTotalSubmittedTaskCount() { return totalSubmittedTaskCount; }
        public long getTotalCompletedTaskCount() { return totalCompletedTaskCount; }
        public long getTotalCancelledTaskCount() { return totalCancelledTaskCount; }
        public long getTotalFailedTaskCount() { return totalFailedTaskCount; }
        public long getTotalRejectedTaskCount() { return totalRejectedTaskCount; }
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

        private AEGISTaskHandle(UUID id, String name, String owner, PoolType poolType) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.poolType = poolType;
            this.createdAt = Instant.now();
        }

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

    @ExcludeAsGenerated

    private AEGISThreadManager() {}

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownGracefully(Duration.ofSeconds(10)), "AEGISThreadManager-ShutdownHook"));
    }

    public static void configure(AEGISThreadManagerConfig newConfig) {
        Objects.requireNonNull(newConfig, "config cannot be null");

        synchronized (LIFECYCLE_LOCK) {
            if (intialized && hasLiveTasks()) throw new IllegalStateException("Cannot reconfigure AEGISThreadManager while tasks are active. Shut it down or wait for idle state first.");

            if(intialized) shutdownInternal(Duration.ofSeconds(10));

            config = newConfig;
            intializedLocked();
        }
    }

    public static UUID submitAsyncTask(String name, Runnable task, String owner) {
        return submitAsyncTask(name, task, owner, PoolType.CPU_BOUND);
    }

    public static UUID submitAsyncTask(String name, Runnable task, String owner, PoolType poolType) {
        return submitAsyncTask(name, asCallable(task), owner, poolType).getId();
    }

    public static <T> AEGISTaskSubmission<T> submitAsyncTask(String name, Callable<T> task, String owner) {
        return submitAsyncTask(name, task, owner, PoolType.CPU_BOUND);
    }

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

    public static Optional<AEGISTaskMetaData> getTaskInfo(UUID id) {
        AEGISTaskHandle handle = TASKS.get(id);
        return handle != null ? Optional.of(handle.toMetaData()) : Optional.empty();
    }

    public static List<AEGISTaskMetaData> listTasks() {
        List<AEGISTaskMetaData> allTasks = new ArrayList<>(TASKS.size());
        for(AEGISTaskHandle handle : TASKS.values()) allTasks.add(handle.toMetaData());
        return Collections.unmodifiableList(allTasks);
    }

    public static int purgeCompletedTasks() {
        ensureInitialized();
        return purgeCompletedTasks(config.getCompletedTaskRetention());
    }

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

    public static void shutdownGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");

        synchronized (LIFECYCLE_LOCK) {
            if(!intialized) return;
            shutdownInternal(timeout);
        }
    }

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

    private static void ensureInitialized() {
        synchronized (LIFECYCLE_LOCK) {
            ensureInitializedLocked();
        }
    }

    private static void ensureInitializedLocked() {
        if(!intialized) intializedLocked();
    }

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

    private static ThreadPoolExecutor executorFor(PoolType poolType) {
        return switch (poolType) {
            case CPU_BOUND -> cpuPool;
            case IO_BOUND -> ioPool;
        };
    }

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

    private static BlockingQueue<Runnable> createQueue(PoolConfig poolConfig) {
        return switch (poolConfig.getQueueType()) {
            case LINKED_BLOCKING -> poolConfig.getQueueCapacity() > 0
                    ? new LinkedBlockingQueue<>(poolConfig.getQueueCapacity())
                    : new LinkedBlockingQueue<>();
            case ARRAY_BLOCKING -> new ArrayBlockingQueue<>(poolConfig.getQueueCapacity());
            case SYNCHRONOUS -> new SynchronousQueue<>();
        };
    }

    private static RejectedExecutionHandler createRejectionHandler(RejectionPolicy policy) {
        return switch (policy) {
            case ABORT -> new ThreadPoolExecutor.AbortPolicy();
            case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
            case DISCARD -> new ThreadPoolExecutor.DiscardPolicy();
            case DISCARD_OLDEST -> new ThreadPoolExecutor.DiscardOldestPolicy();
        };
    }

    private static boolean hasLiveTasks() {
        if(cpuPool != null && (cpuPool.getActiveCount() > 0 || !cpuPool.getQueue().isEmpty())) return true;
        if(ioPool != null && (ioPool.getActiveCount() > 0 || !ioPool.getQueue().isEmpty())) return true;

        for(AEGISTaskHandle handle : TASKS.values()) {
            if(!isTerminalState(handle.state.get())) return true;
        }

        return false;
    }

    private static boolean isTerminalState(TaskState state) {
        return state == TaskState.COMPLETED
                || state == TaskState.CANCELLED
                || state == TaskState.FAILED
                || state == TaskState.REJECTED;
    }

    private static Callable<Void> asCallable(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable cannot be null");
        return () -> {
            runnable.run();
            return null;
        };
    }
}
