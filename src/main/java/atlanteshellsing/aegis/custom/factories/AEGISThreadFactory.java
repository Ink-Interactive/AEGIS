package atlanteshellsing.aegis.custom.factories;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class AEGISThreadFactory implements ThreadFactory {

    private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
    private final AtomicInteger counter = new AtomicInteger(1);
    private final String namePrefix;
    private final boolean daemon;
    private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    /**
     * Creates a thread factory that names threads using the given prefix and does not assign an uncaught-exception handler.
     *
     * @param namePrefix the prefix to use for created thread names; must not be null
     */
    public AEGISThreadFactory(String namePrefix) {
        this(namePrefix, false, null);
    }

    /**
     * Creates a thread factory that names threads using the given prefix and configures their daemon status
     * and uncaught-exception handler.
     *
     * @param namePrefix the prefix to use for created thread names; must not be null
     * @param daemon if true, created threads will be marked as daemon
     * @param uncaughtExceptionHandler an optional handler to assign to newly created threads, or {@code null} to keep the default
     * @throws NullPointerException if {@code namePrefix} is {@code null}
     */
    public AEGISThreadFactory(String namePrefix, boolean daemon, Thread.UncaughtExceptionHandler uncaughtExceptionHandler
    ) {
        this.namePrefix = Objects.requireNonNull(namePrefix, "namePrefix cannot be null");
        this.daemon = daemon;
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

    /**
     * Creates a new Thread configured with the factory's name prefix, daemon flag, and optional uncaught-exception handler.
     *
     * @param runnable the task to execute in the created thread
     * @return the created Thread whose name uses the factory's prefix plus a unique counter, whose daemon status reflects the factory setting, and which has the factory's uncaught-exception handler assigned when provided
     */
    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = defaultFactory.newThread(runnable);
        thread.setName(namePrefix + "-" + counter.getAndIncrement());
        thread.setDaemon(daemon);

        if (uncaughtExceptionHandler != null) {
            thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        }

        return thread;
    }
}