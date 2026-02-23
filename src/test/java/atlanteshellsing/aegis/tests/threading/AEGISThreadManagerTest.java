package atlanteshellsing.aegis.tests.threading;

import atlanteshellsing.aegis.threading.AEGISThreadManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AEGISThreadManagerTest {

    @AfterAll
    static void tearDown() {
        // Give the manager a chance to clean up between test runs
        AEGISThreadManager.shutdownGracefully(Duration.ofSeconds(2));
    }

    @Test
    void submitAsync_runsTaskAndMarksCompleted() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "TestTask",
                latch::countDown,
                "TEST"
        );

        // Wait for the task to run
        boolean finished = latch.await(2, TimeUnit.SECONDS);
        assertTrue(finished, "Task did not complete in time");

        Optional<AEGISThreadManager.AEGISTaskMetaData> infoOpt = AEGISThreadManager.getTaskInfo(id);
        assertTrue(infoOpt.isPresent(), "TaskInfo should be present");

        AEGISThreadManager.AEGISTaskMetaData info = infoOpt.get();
        assertEquals("TestTask", info.getName());
        assertEquals("TEST", info.getOwner());
        assertEquals(AEGISThreadManager.TaskState.COMPLETED, info.getState());
        assertNotNull(info.getCreatedAt(), "createdAt should not be null");
        assertNotNull(info.getStartedAt(), "startedAt should not be null");
        assertNotNull(info.getLastRunAt(), "lastRunAt should not be null");
        assertTrue(!info.getCreatedAt().isAfter(info.getStartedAt()),
                "createdAt should be before or equal to startedAt");
        assertTrue(!info.getStartedAt().isAfter(info.getLastRunAt()),
                "startedAt should be before or equal to lastRunAt");
    }

    @Test
    void listTasks_containsSubmittedTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "ListTasksTask",
                latch::countDown,
                "TEST_LIST"
        );

        latch.await(2, TimeUnit.SECONDS);

        List<AEGISThreadManager.AEGISTaskMetaData> tasks = AEGISThreadManager.listTasks();
        assertFalse(tasks.isEmpty(), "Task list should not be empty");

        boolean found = tasks.stream().anyMatch(t -> t.getId().equals(id));
        assertTrue(found, "Submitted task should appear in listTasks()");
    }

    @Test
    void cancelTask_requestsCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "CancellableTask",
                () -> {
                    started.countDown();
                    // Simulate longer work so we have time to cancel
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException _) {
                        // Ignore, cancellation will interrupt this sleep
                        Thread.currentThread().interrupt();
                    }
                },
                "TEST_CANCEL"
        );

        // Wait until task starts
        assertTrue(started.await(1, TimeUnit.SECONDS), "Task did not start in time");

        boolean cancelled = AEGISThreadManager.cancelTask(id);
        assertTrue(cancelled, "cancelTask should return true for existing task");

        // Give some time for cancellation to propagate
        Thread.sleep(200);

        Optional<AEGISThreadManager.AEGISTaskMetaData> infoOpt = AEGISThreadManager.getTaskInfo(id);
        assertTrue(infoOpt.isPresent(), "TaskInfo should still be present after cancellation");

        AEGISThreadManager.AEGISTaskMetaData info = infoOpt.get();

        // Depending on timing, the task may end up CANCELLED or FAILED (if an interrupt causes an exception),
        // but it should definitely not still be PENDING.
        assertNotEquals(AEGISThreadManager.TaskState.PENDING, info.getState(),
                "Cancelled task should not remain in PENDING state");
    }

    @Test
    void submitAsync_usesDefaultsWhenNameAndOwnerBlank() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "   ", // blank name
                latch::countDown,
                "   " // blank owner
        );

        latch.await(2, TimeUnit.SECONDS);

        Optional<AEGISThreadManager.AEGISTaskMetaData> infoOpt = AEGISThreadManager.getTaskInfo(id);
        assertTrue(infoOpt.isPresent());

        AEGISThreadManager.AEGISTaskMetaData info = infoOpt.get();
        assertEquals("Unnamed Task", info.getName());
        assertEquals("NONE", info.getOwner());
    }

    @Test
    void getTaskInfo_returnsEmptyForUnknownId() {
        UUID randomId = UUID.randomUUID();
        Optional<AEGISThreadManager.AEGISTaskMetaData> infoOpt = AEGISThreadManager.getTaskInfo(randomId);
        assertTrue(infoOpt.isEmpty(), "Unknown task id should return empty Optional");
    }
}
