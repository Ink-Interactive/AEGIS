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

    public AEGISThreadFactory(String namePrefix) {
        this(namePrefix, false, null);
    }

    public AEGISThreadFactory(String namePrefix, boolean daemon, Thread.UncaughtExceptionHandler uncaughtExceptionHandler
    ) {
        this.namePrefix = Objects.requireNonNull(namePrefix, "namePrefix cannot be null");
        this.daemon = daemon;
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

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