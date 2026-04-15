package atlanteshellsing.aegis.fileplayground.service;

import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AEGISTemporaryFilePlaygroundManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createSessionShouldCreateUniqueSessionFoldersAndTrackMetadata() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

        TemporaryFilePlaygroundSession first = manager.createSession();
        TemporaryFilePlaygroundSession second = manager.createSession();

        assertNotNull(first);
        assertNotNull(second);

        assertNotNull(first.id());
        assertNotNull(second.id());
        assertNotNull(first.workspacePath());
        assertNotNull(second.workspacePath());
        assertNotNull(first.createdAt());
        assertNotNull(second.createdAt());

        assertEquals(TemporaryFilePlaygroundState.OPEN, first.state());
        assertEquals(TemporaryFilePlaygroundState.OPEN, second.state());

        assertTrue(first.isOpen());
        assertTrue(second.isOpen());

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.workspacePath(), second.workspacePath());

        assertTrue(Files.isDirectory(first.workspacePath()));
        assertTrue(Files.isDirectory(second.workspacePath()));

        assertEquals(root.toAbsolutePath().normalize(), first.workspacePath().getParent());
        assertEquals(root.toAbsolutePath().normalize(), second.workspacePath().getParent());

        assertEquals(2, manager.getAllSessions().size());
        assertEquals(2, manager.getOpenSessions().size());

        assertEquals(TemporaryFilePlaygroundState.OPEN, manager.getAllSessions().get(first.id()).state());
        assertEquals(TemporaryFilePlaygroundState.OPEN, manager.getAllSessions().get(second.id()).state());
    }

    @Test
    void getSessionShouldReturnTrackedSession() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

        TemporaryFilePlaygroundSession created = manager.createSession();
        TemporaryFilePlaygroundSession retrieved = manager.getSession(created.id());

        assertNotNull(retrieved);
        assertEquals(created.id(), retrieved.id());
        assertEquals(created.workspacePath(), retrieved.workspacePath());
        assertEquals(created.createdAt(), retrieved.createdAt());
        assertEquals(created.state(), retrieved.state());
    }

    @Test
    void getAllSessionsShouldReturnEmptyMapBeforeAnySessionIsCreated() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

        assertTrue(manager.getAllSessions().isEmpty());
        assertTrue(manager.getOpenSessions().isEmpty());
    }

    @Test
    void getAllSessionsShouldReturnUnmodifiableMap() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

        manager.createSession();

        Map<java.util.UUID, TemporaryFilePlaygroundSession> sessions = manager.getAllSessions();

        assertThrows(UnsupportedOperationException.class, sessions::clear);
    }

    @Test
    void getOpenSessionsShouldReturnUnmodifiableMap() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

        manager.createSession();

        Map<java.util.UUID, TemporaryFilePlaygroundSession> sessions = manager.getOpenSessions();

        assertThrows(UnsupportedOperationException.class, sessions::clear);
    }

    @Test
    void constructorShouldNormalizePlaygroundRoot() {
        Path root = tempDir.resolve("AEGIS")
                .resolve("temp")
                .resolve("..")
                .resolve("temp")
                .resolve("file-playground");

        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

        assertEquals(root.toAbsolutePath().normalize(), manager.getPlaygroundRoot());
    }

    @Test
    void createFileShouldCreateFileInSessionWorkspace() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);
        TemporaryFilePlaygroundSession session = manager.createSession();

        Path created = manager.createFile(session.id(), "notes.txt");

        assertEquals(session.workspacePath().resolve("notes.txt"), created);
        assertTrue(Files.isRegularFile(created));
    }

    @Test
    void createFolderShouldCreateFolderInSessionWorkspace() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);
        TemporaryFilePlaygroundSession session = manager.createSession();

        Path created = manager.createFolder(session.id(), "docs");

        assertEquals(session.workspacePath().resolve("docs"), created);
        assertTrue(Files.isDirectory(created));
    }

    @Test
    void createFileShouldCreateInsideProvidedFolder() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);
        TemporaryFilePlaygroundSession session = manager.createSession();
        Path folder = manager.createFolder(session.id(), "docs");

        Path created = manager.createFile(session.id(), folder, "nested.txt");

        assertEquals(folder.resolve("nested.txt"), created);
        assertTrue(Files.isRegularFile(created));
    }

    @Test
    void createFileShouldRejectBlankName() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);
        TemporaryFilePlaygroundSession session = manager.createSession();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> manager.createFile(session.id(), "   "));

        assertEquals("File name cannot be blank", exception.getMessage());
    }

    @Test
    void createFileShouldRejectDuplicateName() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);
        TemporaryFilePlaygroundSession session = manager.createSession();
        manager.createFile(session.id(), "notes.txt");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> manager.createFile(session.id(), "notes.txt"));

        assertEquals("'notes.txt' already exists", exception.getMessage());
    }

    @Test
    void createFileShouldRejectNameContainingPathSeparator() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);
        TemporaryFilePlaygroundSession session = manager.createSession();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> manager.createFile(session.id(), "foo/bar.txt"));

        assertEquals("File name cannot contain path separators", exception.getMessage());
    }

    @Test
    void createFileShouldRejectUnknownSessionId() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> manager.createFile(UUID.randomUUID(), "notes.txt"));

        assertTrue(exception.getMessage().startsWith("Temporary playground session not found:"));
    }

    @Test
    void createFolderShouldRejectDirectoryOutsideWorkspace() {
        Path root = tempDir.resolve("AEGIS/temp/file-playground");
        AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);
        TemporaryFilePlaygroundSession session = manager.createSession();
        Path outside = tempDir.resolve("outside");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> manager.createFolder(session.id(), outside, "docs"));

        assertEquals("Target directory must be inside the workspace", exception.getMessage());
    }
}