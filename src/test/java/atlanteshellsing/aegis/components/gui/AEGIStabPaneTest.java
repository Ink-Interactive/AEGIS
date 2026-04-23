package atlanteshellsing.aegis.components.gui;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AEGISTabPaneTest {

    private static final AtomicBoolean JAVAFX_INITIALIZED = new AtomicBoolean(false);

    @BeforeAll
    static void initializeJavaFX() throws Exception {
        if (JAVAFX_INITIALIZED.compareAndSet(false, true)) {
            CountDownLatch startupLatch = new CountDownLatch(1);
            Platform.startup(startupLatch::countDown);
            assertTrue(startupLatch.await(5, TimeUnit.SECONDS), "JavaFX platform failed to initialize");
        }
    }

    @Test
    void addTabDefaultShouldCreateClosableTab() throws Exception {
        runOnFxThreadAndWait(() -> {
            AEGISTabPane tabPane = new AEGISTabPane();

            tabPane.addTab("home", "Home", new Label("Home"));

            assertEquals(1, tabPane.getTabs().size());
            Tab tab = tabPane.getTabs().getFirst();
            assertTrue(tab.isClosable());
            assertEquals("Home", tab.getText());
        });
    }

    @Test
    void addTabShouldRespectExplicitClosableFlag() throws Exception {
        runOnFxThreadAndWait(() -> {
            AEGISTabPane tabPane = new AEGISTabPane();

            tabPane.addTab("playground-1", "Playground", new Label("Playground"), false);

            assertEquals(1, tabPane.getTabs().size());
            Tab tab = tabPane.getTabs().getFirst();
            assertFalse(tab.isClosable());
        });
    }

    @Test
    void addTabShouldReuseExistingTabForDuplicateKey() throws Exception {
        runOnFxThreadAndWait(() -> {
            AEGISTabPane tabPane = new AEGISTabPane();
            Label firstContent = new Label("First");
            Label secondContent = new Label("Second");

            tabPane.addTab("playground-1", "Playground One", firstContent, false);
            tabPane.addTab("playground-1", "Playground Two", secondContent, true);

            assertEquals(1, tabPane.getTabs().size());
            Tab tab = tabPane.getTabs().getFirst();
            assertSame(firstContent, tab.getContent());
            assertEquals("Playground One", tab.getText());
            assertFalse(tab.isClosable());
            assertSame(tab, tabPane.getSelectionModel().getSelectedItem());
        });
    }

    private void runOnFxThreadAndWait(FxAssertion assertion) throws Exception {
        if (Platform.isFxApplicationThread()) {
            assertion.run();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                assertion.run();
                completed.set(true);
            } catch (Throwable t) {
                failed.set(true);
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for JavaFX assertions");
        assertTrue(completed.get() || failed.get(), "JavaFX assertion did not execute");
        if (failed.get()) {
            throw new AssertionError("JavaFX assertion failed", thrown.get());
        }
    }

    @FunctionalInterface
    private interface FxAssertion {
        void run();
    }
}