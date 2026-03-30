package atlanteshellsing.aegis.tests.threading;

import atlanteshellsing.aegis.threading.AEGISThreadManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AEGISThreadManagerTest {

    @BeforeEach
    void setUp() {
        AEGISThreadManager.shutdownGracefully(Duration.ofSeconds(1));
        AEGISThreadManager.configure(testConfig());
    }

    @AfterEach
    void tearDown() {
        AEGISThreadManager.shutdownGracefully(Duration.ofSeconds(2));
    }

    @Test
    void submitAsyncRunnable_runsTaskAndMarksCompleted() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "RunnableTask",
                latch::countDown,
                "TEST_CPU",
                AEGISThreadManager.PoolType.CPU_BOUND
        );

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Task did not complete in time");

        AEGISThreadManager.AEGISTaskMetaData info = awaitTaskInfo(id);
        assertEquals("RunnableTask", info.getName());
        assertEquals("TEST_CPU", info.getOwner());
        assertEquals(AEGISThreadManager.PoolType.CPU_BOUND, info.getPoolType());
        assertEquals(AEGISThreadManager.TaskState.COMPLETED, info.getState());
        assertNotNull(info.getCreatedAt());
        assertNotNull(info.getStartedAt());
        assertNotNull(info.getLastRunAt());
        assertNotNull(info.getCompletedAt());
        assertFalse(info.getCreatedAt().isAfter(info.getStartedAt()));
        assertFalse(info.getStartedAt().isAfter(info.getLastRunAt()));
        assertFalse(info.getLastRunAt().isAfter(info.getCompletedAt()));
    }

    @Test
    void submitAsyncCallable_returnsValueAndMarksCompleted() throws Exception {
        AEGISThreadManager.AEGISTaskSubmission<String> submission =
                AEGISThreadManager.submitAsyncTask(
                        "CallableTask",
                        () -> "RESULT",
                        "TEST_IO",
                        AEGISThreadManager.PoolType.IO_BOUND
                );

        assertEquals("RESULT", submission.getFuture().get(2, TimeUnit.SECONDS));

        AEGISThreadManager.AEGISTaskMetaData info = awaitTaskInfo(submission.getId());
        assertEquals("CallableTask", info.getName());
        assertEquals("TEST_IO", info.getOwner());
        assertEquals(AEGISThreadManager.PoolType.IO_BOUND, info.getPoolType());
        assertEquals(AEGISThreadManager.TaskState.COMPLETED, info.getState());
        assertNotNull(info.getCompletedAt());
    }

    @Test
    void listTasks_containsSubmittedTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "ListTasksTask",
                latch::countDown,
                "TEST_LIST",
                AEGISThreadManager.PoolType.CPU_BOUND
        );

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        awaitTaskInfo(id);

        List<AEGISThreadManager.AEGISTaskMetaData> tasks = AEGISThreadManager.listTasks();
        assertTrue(tasks.stream().anyMatch(task -> task.getId().equals(id)));
    }

    @Test
    void cancelTask_marksTaskCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "CancellableTask",
                () -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                },
                "TEST_CANCEL",
                AEGISThreadManager.PoolType.CPU_BOUND
        );

        assertTrue(started.await(1, TimeUnit.SECONDS), "Task did not start in time");
        assertTrue(AEGISThreadManager.cancelTask(id), "cancelTask should return true for existing task");

        release.countDown();

        AEGISThreadManager.AEGISTaskMetaData info = awaitTaskInfo(id);
        assertEquals(AEGISThreadManager.TaskState.CANCELLED, info.getState());
        assertNotNull(info.getCompletedAt());
    }

    @Test
    void submitAsync_usesDefaultsWhenNameAndOwnerBlank() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "   ",
                latch::countDown,
                "   ",
                AEGISThreadManager.PoolType.CPU_BOUND
        );

        assertTrue(latch.await(2, TimeUnit.SECONDS));

        AEGISThreadManager.AEGISTaskMetaData info = awaitTaskInfo(id);
        assertEquals("Unnamed Task", info.getName());
        assertEquals("None", info.getOwner());
    }

    @Test
    void getTaskInfo_returnsEmptyForUnknownId() {
        assertTrue(AEGISThreadManager.getTaskInfo(UUID.randomUUID()).isEmpty());
    }

    @Test
    void purgeCompletedTasks_removesCompletedTaskFromRegistry() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "PurgeTask",
                latch::countDown,
                "TEST_PURGE",
                AEGISThreadManager.PoolType.CPU_BOUND
        );

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        awaitTaskInfo(id);

        int purged = AEGISThreadManager.purgeCompletedTasks(Duration.ZERO);
        assertTrue(purged >= 1, "Expected at least one completed task to be purged");
        assertTrue(AEGISThreadManager.getTaskInfo(id).isEmpty(), "Purged task should no longer be tracked");
    }

    @Test
    void getMetrics_updatesAfterTaskCompletion() throws Exception {
        AEGISThreadManager.ThreadManagerMetrics before = AEGISThreadManager.getMetrics();

        AEGISThreadManager.AEGISTaskSubmission<String> submission =
                AEGISThreadManager.submitAsyncTask(
                        "MetricsTask",
                        () -> "done",
                        "TEST_METRICS",
                        AEGISThreadManager.PoolType.IO_BOUND
                );

        assertEquals("done", submission.getFuture().get(2, TimeUnit.SECONDS));
        awaitTaskInfo(submission.getId());

        AEGISThreadManager.ThreadManagerMetrics after = AEGISThreadManager.getMetrics();

        assertTrue(after.getTotalSubmittedTaskCount() >= before.getTotalSubmittedTaskCount() + 1);
        assertTrue(after.getTotalCompletedTaskCount() >= before.getTotalCompletedTaskCount() + 1);
        assertNotNull(after.getCpuPoolMetrics());
        assertNotNull(after.getIoPoolMetrics());
        assertTrue(after.getTrackedTaskCount() >= 1);
    }

    @Test
    void getPoolMetrics_reflectConfiguredSizes() {
        AEGISThreadManager.PoolMetrics cpuMetrics = AEGISThreadManager.getPoolMetrics(AEGISThreadManager.PoolType.CPU_BOUND);
        AEGISThreadManager.PoolMetrics ioMetrics = AEGISThreadManager.getPoolMetrics(AEGISThreadManager.PoolType.IO_BOUND);

        assertEquals(AEGISThreadManager.PoolType.CPU_BOUND, cpuMetrics.getPoolType());
        assertEquals(2, cpuMetrics.getCorePoolSize());
        assertEquals(2, cpuMetrics.getMaximumPoolSize());

        assertEquals(AEGISThreadManager.PoolType.IO_BOUND, ioMetrics.getPoolType());
        assertEquals(2, ioMetrics.getCorePoolSize());
        assertEquals(4, ioMetrics.getMaximumPoolSize());
    }

    @Test
    void configure_throwsWhenTasksAreStillActive() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        UUID id = AEGISThreadManager.submitAsyncTask(
                "BlockingTask",
                () -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                },
                "TEST_CONFIGURE",
                AEGISThreadManager.PoolType.CPU_BOUND
        );

        assertTrue(started.await(1, TimeUnit.SECONDS), "Task did not start in time");

        assertThrows(
                IllegalStateException.class,
                () -> AEGISThreadManager.configure(testConfig())
        );

        assertTrue(AEGISThreadManager.cancelTask(id));
        release.countDown();
        awaitTaskInfo(id);
    }

    private static AEGISThreadManager.AEGISThreadManagerConfig testConfig() {
        return new AEGISThreadManager.AEGISThreadManagerConfig(
                AEGISThreadManager.PoolConfig.fixedPool(
                        2,
                        AEGISThreadManager.QueueType.ARRAY_BLOCKING,
                        16,
                        AEGISThreadManager.RejectionPolicy.CALLER_RUNS
                ),
                AEGISThreadManager.PoolConfig.scalingPool(
                        2,
                        4,
                        Duration.ofSeconds(30),
                        AEGISThreadManager.QueueType.LINKED_BLOCKING,
                        32,
                        AEGISThreadManager.RejectionPolicy.CALLER_RUNS,
                        true
                ),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );
    }

    private static AEGISThreadManager.AEGISTaskMetaData awaitTaskInfo(UUID id) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Optional<AEGISThreadManager.AEGISTaskMetaData> info;

        do {
            info = AEGISThreadManager.getTaskInfo(id);
            if (info.isPresent() && isTerminal(info.get().getState())) {
                return info.get();
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadlineNanos);

        fail("Task " + id + " did not reach a terminal state in time");
        return null;
    }

    private static boolean isTerminal(AEGISThreadManager.TaskState state) {
        return state == AEGISThreadManager.TaskState.COMPLETED
                || state == AEGISThreadManager.TaskState.CANCELLED
                || state == AEGISThreadManager.TaskState.FAILED
                || state == AEGISThreadManager.TaskState.REJECTED;
    }
}
