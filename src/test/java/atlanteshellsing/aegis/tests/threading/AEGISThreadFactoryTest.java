package atlanteshellsing.aegis.tests.threading;

import atlanteshellsing.aegis.custom.factories.AEGISThreadFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AEGISThreadFactoryTest {

    @Test
    void newThread_appliesPrefixAndIncrementsNames() {
        AEGISThreadFactory factory = new AEGISThreadFactory("AEGIS-Test");

        Thread first = factory.newThread(() -> {});
        Thread second = factory.newThread(() -> {});

        assertEquals("AEGIS-Test-1", first.getName());
        assertEquals("AEGIS-Test-2", second.getName());
        assertFalse(first.isDaemon());
        assertFalse(second.isDaemon());
    }

    @Test
    void newThread_appliesDaemonFlag() {
        AEGISThreadFactory factory = new AEGISThreadFactory("AEGIS-Daemon", true, null);

        Thread thread = factory.newThread(() -> {});

        assertEquals("AEGIS-Daemon-1", thread.getName());
        assertTrue(thread.isDaemon());
    }

    @Test
    void newThread_invokesConfiguredUncaughtExceptionHandler() throws Exception {
        AtomicReference<Throwable> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            captured.set(throwable);
            latch.countDown();
        };

        AEGISThreadFactory factory = new AEGISThreadFactory("AEGIS-Handler", false, handler);
        Thread thread = factory.newThread(() -> {
            throw new IllegalStateException("boom");
        });

        thread.start();
        thread.join(1000);

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Uncaught exception handler was not invoked");
        assertNotNull(captured.get());
        assertEquals(IllegalStateException.class, captured.get().getClass());
        assertEquals("boom", captured.get().getMessage());
    }

    @Test
    void constructor_rejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> new AEGISThreadFactory(null));
    }
}
