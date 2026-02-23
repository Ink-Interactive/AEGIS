package atlanteshellsing.aegis.threading;

import atlanteshellsing.aegis.custom.factories.AEGISThreadFactory;
import atlanteshellsing.aegis.logging.AEGISLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core Thread & Task manager for asynchronous operations in AEGIS.
 * This is the low-level owner of thread pools and task metadata.
 * Higher-level schedulers (delayed/repeating jobs, cron, etc.)
 * can be built on top of this.
 */
public class AEGISThreadManager {

    public enum TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /**
     * Immutable info snapshot for a single submitted task.
     */
    public static final class AEGISTaskMetaData {
        private final UUID id;
        private final String name;
        private final String owner;
        private final Instant createdAt;
        private final Instant startedAt;
        private final Instant lastRunAt;
        private final TaskState state;

        private AEGISTaskMetaData(UUID id, String name, String owner, Instant createdAt, Instant startedAt, Instant lastRunAt, TaskState state) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
            this.lastRunAt = lastRunAt;
            this.state = state;
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        public String getOwner() { return owner; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getLastRunAt() { return lastRunAt; }
        public TaskState getState() { return state; }
    }

    /**
     * Internal mutable handle tracked in the registry.
     */
    private static final class AEGISTaskHandle {
        final UUID id;
        final String name;
        final String owner;
        final Instant createdAt;
        final AtomicReference<Instant> startedAt = new AtomicReference<>();
        final AtomicReference<Instant> lastRunAt = new AtomicReference<>();
        final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.PENDING);
        final AtomicReference<Future<?>> future = new AtomicReference<>();

        AEGISTaskHandle(UUID id, String name, String owner) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.createdAt = Instant.now();
        }

        AEGISTaskMetaData toMetaData() {
            return new AEGISTaskMetaData(
                id,
                name,
                owner,
                createdAt,
                startedAt.get(),
                lastRunAt.get(),
                state.get()
            );
        }
    }

    // --- Thread Pool & Registry ---

    private static final int POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());

    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(POOL_SIZE, new AEGISThreadFactory());

    private static final ConcurrentMap<UUID, AEGISTaskHandle> TASKS = new ConcurrentHashMap<>();

    private AEGISThreadManager() { }

    static {
        //Ensure graceful shutdown on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdownGracefully(Duration.ofSeconds(10)),
                "AEGISThreadManager-ShutdownHook"
        ));
    }

    // --- Public API ---

    /**
     * Submit an asynchronous task to the worker pool.
     *
     * @param name  optional human-readable task name
     * @param task  the runnable to execute
     * @param owner logical owner, e.g. "CORE" or "PLUGIN:MyPlugin"
     * @return UUID that can be used to query / cancel this task
     */
    public static UUID submitAsyncTask(String name, Runnable task, String owner) {
        Objects.requireNonNull(task, "Task runnable cannot be null");

        UUID id = UUID.randomUUID();
        String effectiveName = (name == null || name.isBlank()) ? "Unnamed Task" : name;
        String effectiveOwner = (owner == null || owner.isBlank()) ? "NONE" : owner;

        AEGISTaskHandle handle = new AEGISTaskHandle(id, effectiveName, effectiveOwner);
        TASKS.put(id, handle);

        AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                AEGISLogger.AEGISLogLevel.INFO,
                "Submitting async task: " + handle.name + " (" + id + ") owner=" + handle.owner);

        Future<?> future = WORKER_POOL.submit(wrapRunnable(handle, task));
        handle.future.set(future);
        return id;
    }

    /**
     * Attempt to cancel a running or pending task.
     *
     * @param id task UUID
     * @return true if cancellation was requested, false if not found
     */
    public static boolean cancelTask(UUID id) {
        AEGISTaskHandle handle = TASKS.get(id);
        if(handle == null) {
            return false;
        }

        Future<?> future = handle.future.get();
        if(future != null) {
            boolean cancelled = future.cancel(true);
            if(cancelled) {
                handle.state.set(TaskState.CANCELLED);
                AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                        AEGISLogger.AEGISLogLevel.INFO,
                        "Cancelled task: " + handle.name + " (" + id + ")");
            }
            return cancelled;
        }
        return false;
    }

    /**
     * Get a snapshot of info for a single task.
     */
    public static Optional<AEGISTaskMetaData> getTaskInfo(UUID id) {
        AEGISTaskHandle handle = TASKS.get(id);
        return handle != null ? Optional.of(handle.toMetaData()) : Optional.empty();
    }

    /**
     * List snapshots of all known tasks.
     */
    public static List<AEGISTaskMetaData> listTasks() {
        List<AEGISTaskMetaData> metaDataList = new ArrayList<>(TASKS.size());
        for(AEGISTaskHandle handle : TASKS.values()) {
            metaDataList.add(handle.toMetaData());
        }
        return Collections.unmodifiableList(metaDataList);
    }

    /**
     * Shutdown the thread manager gracefully, waiting for tasks to finish.
     */
    public static void shutdownGracefully(Duration timeout) {
        AEGISLogger.log(
                AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                AEGISLogger.AEGISLogLevel.INFO,
                "Shutting down AEGIS Thread Manager..."
        );

        WORKER_POOL.shutdown();
        try {
            if(!WORKER_POOL.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                AEGISLogger.log(
                        AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                        AEGISLogger.AEGISLogLevel.WARNING,
                        "Forcing worker pool shutdown after timeout"
                );
                WORKER_POOL.shutdownNow();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            WORKER_POOL.shutdownNow();
        }
    }

    // --- Internal Helpers ---

    private static Runnable wrapRunnable(AEGISTaskHandle handle, Runnable delegate) {
        return () -> {
            handle.startedAt.compareAndSet(null, Instant.now());
            handle.state.set(TaskState.RUNNING);
            try {
                delegate.run();
                handle.lastRunAt.set(Instant.now());
                handle.state.set(TaskState.COMPLETED);
            } catch (Exception e) {
                handle.state.set(TaskState.FAILED);
                AEGISLogger.log(
                        AEGISLogger.AEGISLogKey.AEGIS_MAIN,
                        AEGISLogger.AEGISLogLevel.SEVERE,
                        "Task failed: " + handle.name + " (" + handle.id + ")", e
                );
            }
        };
    }
}
