package atlanteshellsing.aegis.fileplayground.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryFilePlaygroundSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void isOpenShouldReturnTrueWhenStateIsOpen() {
        TemporaryFilePlaygroundSession session = new TemporaryFilePlaygroundSession(
                UUID.randomUUID(),
                tempDir.resolve("workspace"),
                Instant.now(),
                TemporaryFilePlaygroundState.OPEN
        );

        assertTrue(session.isOpen());
    }

    @Test
    void isOpenShouldReturnFalseWhenStateIsClosed() {
        TemporaryFilePlaygroundSession session = new TemporaryFilePlaygroundSession(
                UUID.randomUUID(),
                tempDir.resolve("workspace"),
                Instant.now(),
                TemporaryFilePlaygroundState.CLOSED
        );

        assertFalse(session.isOpen());
    }

    @Test
    void asClosedShouldReturnClosedCopy() {
        TemporaryFilePlaygroundSession session = new TemporaryFilePlaygroundSession(
                UUID.randomUUID(),
                tempDir.resolve("workspace"),
                Instant.now(),
                TemporaryFilePlaygroundState.OPEN
        );

        TemporaryFilePlaygroundSession closed = session.asClosed();

        assertEquals(session.id(), closed.id());
        assertEquals(session.workspacePath(), closed.workspacePath());
        assertEquals(session.createdAt(), closed.createdAt());
        assertEquals(TemporaryFilePlaygroundState.CLOSED, closed.state());
        assertFalse(closed.isOpen());
    }

    @Test
    void constructorShouldRejectNullId() {
        assertThrows(NullPointerException.class, () -> new TemporaryFilePlaygroundSession(
                null,
                tempDir.resolve("workspace"),
                Instant.now(),
                TemporaryFilePlaygroundState.OPEN
        ));
    }

    @Test
    void constructorShouldRejectNullWorkspacePath() {
        assertThrows(NullPointerException.class, () -> new TemporaryFilePlaygroundSession(
                UUID.randomUUID(),
                null,
                Instant.now(),
                TemporaryFilePlaygroundState.OPEN
        ));
    }

    @Test
    void constructorShouldRejectNullCreatedAt() {
        assertThrows(NullPointerException.class, () -> new TemporaryFilePlaygroundSession(
                UUID.randomUUID(),
                tempDir.resolve("workspace"),
                null,
                TemporaryFilePlaygroundState.OPEN
        ));
    }

    @Test
    void constructorShouldRejectNullState() {
        assertThrows(NullPointerException.class, () -> new TemporaryFilePlaygroundSession(
                UUID.randomUUID(),
                tempDir.resolve("workspace"),
                Instant.now(),
                null
        ));
    }
}