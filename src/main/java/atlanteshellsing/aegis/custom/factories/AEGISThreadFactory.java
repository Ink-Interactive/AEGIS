package atlanteshellsing.aegis.custom.factories;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom ThreadFactory to name and configure worker threads.
 */
public class AEGISThreadFactory implements ThreadFactory {
    private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
    private final AtomicInteger counter = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable r) {
        Thread t = defaultFactory.newThread(r);
        t.setName("AEGIS-Worker-" + counter.getAndIncrement());
        t.setDaemon(true);
        return t;
    }
}