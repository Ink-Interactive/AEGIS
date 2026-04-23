package atlanteshellsing.aegis.fileplayground.service;

import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundState;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    private Path playgroundRoot() {
        return tempDir.resolve("AEGIS/temp/file-playground");
    }

    private AEGISTemporaryFilePlaygroundManager createManager() {
        return new AEGISTemporaryFilePlaygroundManager(playgroundRoot());
    }

    private AEGISTemporaryFilePlaygroundManager createManager(
            AEGISTemporaryFilePlaygroundManager.PlaygroundOSIntegration osIntegration) {
        return new AEGISTemporaryFilePlaygroundManager(playgroundRoot(), osIntegration);
    }

    @Nested
    class SessionTest {

        @Test
        void SessionTest_001_createSessionShouldCreateUniqueSessionFoldersAndTrackMetadata() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();

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

            assertEquals(playgroundRoot().toAbsolutePath().normalize(), first.workspacePath().getParent());
            assertEquals(playgroundRoot().toAbsolutePath().normalize(), second.workspacePath().getParent());

            assertEquals(2, manager.getAllSessions().size());
            assertEquals(2, manager.getOpenSessions().size());

            assertEquals(TemporaryFilePlaygroundState.OPEN, manager.getAllSessions().get(first.id()).state());
            assertEquals(TemporaryFilePlaygroundState.OPEN, manager.getAllSessions().get(second.id()).state());
        }

        @Test
        void SessionTest_002_getSessionShouldReturnTrackedSession() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();

            TemporaryFilePlaygroundSession created = manager.createSession();
            TemporaryFilePlaygroundSession retrieved = manager.getSession(created.id());

            assertNotNull(retrieved);
            assertEquals(created.id(), retrieved.id());
            assertEquals(created.workspacePath(), retrieved.workspacePath());
            assertEquals(created.createdAt(), retrieved.createdAt());
            assertEquals(created.state(), retrieved.state());
        }

        @Test
        void SessionTest_003_getAllSessionsShouldReturnEmptyMapBeforeAnySessionIsCreated() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();

            assertTrue(manager.getAllSessions().isEmpty());
            assertTrue(manager.getOpenSessions().isEmpty());
        }

        @Test
        void SessionTest_004_getAllSessionsShouldReturnUnmodifiableMap() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            manager.createSession();

            Map<UUID, TemporaryFilePlaygroundSession> sessions = manager.getAllSessions();

            assertThrows(UnsupportedOperationException.class, sessions::clear);
        }

        @Test
        void SessionTest_005_getOpenSessionsShouldReturnUnmodifiableMap() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            manager.createSession();

            Map<UUID, TemporaryFilePlaygroundSession> sessions = manager.getOpenSessions();

            assertThrows(UnsupportedOperationException.class, sessions::clear);
        }

        @Test
        void SessionTest_006_constructorShouldNormalizePlaygroundRoot() {
            Path root = tempDir.resolve("AEGIS")
                    .resolve("temp")
                    .resolve("..")
                    .resolve("temp")
                    .resolve("file-playground");

            AEGISTemporaryFilePlaygroundManager manager = new AEGISTemporaryFilePlaygroundManager(root);

            assertEquals(root.toAbsolutePath().normalize(), manager.getPlaygroundRoot());
        }
    }

    @Nested
    class FileAndFolderTest {

        @Test
        void FileAndFolderTest_001_createFileShouldCreateFileInSessionWorkspace() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();

            Path created = manager.createFile(session.id(), "notes.txt");

            assertEquals(session.workspacePath().resolve("notes.txt"), created);
            assertTrue(Files.isRegularFile(created));
        }

        @Test
        void FileAndFolderTest_002_createFolderShouldCreateFolderInSessionWorkspace() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();

            Path created = manager.createFolder(session.id(), "docs");

            assertEquals(session.workspacePath().resolve("docs"), created);
            assertTrue(Files.isDirectory(created));
        }

        @Test
        void FileAndFolderTest_003_createFileShouldCreateInsideProvidedFolder() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path folder = manager.createFolder(session.id(), "docs");

            Path created = manager.createFile(session.id(), folder, "nested.txt");

            assertEquals(folder.resolve("nested.txt"), created);
            assertTrue(Files.isRegularFile(created));
        }

        @Test
        void FileAndFolderTest_004_createFileShouldRejectBlankName() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.createFile(session.id(), "   ")
            );

            assertEquals("File name cannot be blank", exception.getMessage());
        }

        @Test
        void FileAndFolderTest_005_createFileShouldRejectDuplicateName() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            manager.createFile(session.id(), "notes.txt");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.createFile(session.id(), "notes.txt")
            );

            assertEquals("'notes.txt' already exists", exception.getMessage());
        }

        @Test
        void FileAndFolderTest_006_createFileShouldRejectNameContainingPathSeparator() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.createFile(session.id(), "foo/bar.txt")
            );

            assertEquals("File name cannot contain path separators", exception.getMessage());
        }

        @Test
        void FileAndFolderTest_007_createFileShouldRejectUnknownSessionId() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.createFile(UUID.randomUUID(), "notes.txt")
            );

            assertTrue(exception.getMessage().startsWith("Temporary playground session not found:"));
        }

        @Test
        void FileAndFolderTest_008_createFolderShouldRejectDirectoryOutsideWorkspace() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path outside = tempDir.resolve("outside");
            Files.createDirectories(outside);

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.createFolder(session.id(), outside, "docs")
            );

            assertEquals("Target directory must be inside the workspace", exception.getMessage());
        }
    }

    @Nested
    class ImportPathsTest {

        @Test
        void ImportPathsTest_001_importPathsShouldImportSingleFileSuccessfully() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path source = tempDir.resolve("single.txt");
            Files.writeString(source, "hello");

            manager.importPaths(session.id(), List.of(source));

            Path imported = session.workspacePath().resolve("single.txt");
            assertTrue(Files.isRegularFile(imported));
            assertEquals("hello", Files.readString(imported));
        }

        @Test
        void ImportPathsTest_002_importPathsShouldImportMultipleFilesSuccessfully() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path first = tempDir.resolve("one.txt");
            Path second = tempDir.resolve("two.txt");
            Files.writeString(first, "first");
            Files.writeString(second, "second");

            manager.importPaths(session.id(), List.of(first, second));

            assertEquals("first", Files.readString(session.workspacePath().resolve("one.txt")));
            assertEquals("second", Files.readString(session.workspacePath().resolve("two.txt")));
        }

        @Test
        void ImportPathsTest_003_importPathsShouldImportFolderRecursivelySuccessfully() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path sourceFolder = tempDir.resolve("folderA");
            Path nested = sourceFolder.resolve("nested");
            Files.createDirectories(nested);
            Files.writeString(sourceFolder.resolve("root.txt"), "root");
            Files.writeString(nested.resolve("deep.txt"), "deep");

            manager.importPaths(session.id(), List.of(sourceFolder));

            Path importedFolder = session.workspacePath().resolve("folderA");
            assertTrue(Files.isDirectory(importedFolder));
            assertEquals("root", Files.readString(importedFolder.resolve("root.txt")));
            assertEquals("deep", Files.readString(importedFolder.resolve("nested/deep.txt")));
        }

        @Test
        void ImportPathsTest_004_importPathsShouldRejectDuplicateTargetName() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            Files.writeString(session.workspacePath().resolve("dupe.txt"), "existing");
            Path source = tempDir.resolve("dupe.txt");
            Files.writeString(source, "new");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.importPaths(session.id(), List.of(source))
            );

            Path expectedTarget = session.workspacePath().resolve("dupe.txt").normalize();
            assertEquals("Dropped Item already exists: " + expectedTarget, exception.getMessage());
        }

        @Test
        void ImportPathsTest_005_importPathsShouldRejectUnknownSessionId() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            Path source = tempDir.resolve("single.txt");
            Files.writeString(source, "hello");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.importPaths(UUID.randomUUID(), List.of(source))
            );

            assertTrue(exception.getMessage().startsWith("Temporary playground session not found:"));
        }

        @Test
        void ImportPathsTest_006_importPathsShouldRejectEmptyImportList() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.importPaths(session.id(), List.of())
            );

            assertEquals("No Files or Folders to import.", exception.getMessage());
        }
    }

    @Nested
    class OpenIntegrationTest {

        @Test
        void OpenIntegrationTest_001_openWorkspaceShouldRejectUnknownSessionId() {
            AEGISTemporaryFilePlaygroundManager.PlaygroundOSIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.openWorkspace(UUID.randomUUID())
            );

            assertTrue(exception.getMessage().startsWith("Temporary playground session not found:"));
        }

        @Test
        void OpenIntegrationTest_002_openSelectedFileShouldRejectNullSelectedPath() {
            AEGISTemporaryFilePlaygroundManager.PlaygroundOSIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.openSelectedFile(session.id(), null)
            );

            assertEquals("Selected path cannot be null", exception.getMessage());
        }

        @Test
        void OpenIntegrationTest_003_openSelectedFileShouldRejectPathOutsideWorkspace() throws Exception {
            AEGISTemporaryFilePlaygroundManager.PlaygroundOSIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path outsideFile = tempDir.resolve("outside.txt");
            Files.writeString(outsideFile, "outside");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.openSelectedFile(session.id(), outsideFile)
            );

            assertEquals(
                    "Selected path must be inside the session workspace: " + outsideFile.toAbsolutePath().normalize(),
                    exception.getMessage()
            );
        }

        @Test
        void OpenIntegrationTest_004_openSelectedFileShouldRejectDirectorySelection() {
            AEGISTemporaryFilePlaygroundManager.PlaygroundOSIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.openSelectedFile(session.id(), session.workspacePath())
            );

            assertEquals(
                    "Selected path must be a file: " + session.workspacePath().toAbsolutePath().normalize(),
                    exception.getMessage()
            );
        }

        @Test
        void OpenIntegrationTest_005_openSelectedFileShouldRejectMissingPath() {
            AEGISTemporaryFilePlaygroundManager.PlaygroundOSIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path missing = session.workspacePath().resolve("missing.txt");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.openSelectedFile(session.id(), missing)
            );

            assertEquals(
                    "Selected path does not exist: " + missing.toAbsolutePath().normalize(),
                    exception.getMessage()
            );
        }

        @Test
        void OpenIntegrationTest_006_openSelectedFileShouldWrapOsFailures() {
            StubOsIntegration osIntegration = new StubOsIntegration();
            osIntegration.failOpen = true;
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path file = manager.createFile(session.id(), "notes.txt");

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> manager.openSelectedFile(session.id(), file)
            );

            assertEquals("Failed to open selected file: " + file.toAbsolutePath().normalize(), exception.getMessage());
        }

        @Test
        void OpenIntegrationTest_007_openSelectedInExplorerShouldOpenParentDirectoryWhenSelectionIsAFile() throws Exception {
            StubOsIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path folder = manager.createFolder(session.id(), "docs");
            Path file = manager.createFile(session.id(), folder, "nested.txt");

            manager.openSelectedInExplorer(session.id(), file);

            assertEquals(folder.toAbsolutePath().normalize(), osIntegration.lastOpenedPath);
        }

        @Test
        void OpenIntegrationTest_008_openSelectedInExplorerShouldOpenDirectoryWhenSelectionIsADirectory() {
            StubOsIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path folder = manager.createFolder(session.id(), "docs");

            manager.openSelectedInExplorer(session.id(), folder);

            assertEquals(folder.toAbsolutePath().normalize(), osIntegration.lastOpenedPath);
        }

        @Test
        void OpenIntegrationTest_009_openSelectedInExplorerShouldRejectPathOutsideWorkspace() throws Exception {
            StubOsIntegration osIntegration = new StubOsIntegration();
            AEGISTemporaryFilePlaygroundManager manager = createManager(osIntegration);
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path outside = tempDir.resolve("outside.txt");
            Files.writeString(outside, "outside");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.openSelectedInExplorer(session.id(), outside)
            );

            assertEquals(
                    "Selected path must be inside the session workspace: " + outside.toAbsolutePath().normalize(),
                    exception.getMessage()
            );
        }
    }

    @Nested
    class CloseSessionTest {

        @Test
        void CloseSessionTest_001_closeSessionShouldDeleteWorkspaceRecursively() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            Path nestedFolder = Files.createDirectories(session.workspacePath().resolve("docs/nested"));
            Path nestedFile = Files.writeString(nestedFolder.resolve("notes.txt"), "hello");

            manager.closeSession(session.id());

            assertTrue(Files.notExists(nestedFile));
            assertTrue(Files.notExists(nestedFolder));
            assertTrue(Files.notExists(session.workspacePath()));
        }

        @Test
        void CloseSessionTest_002_closeSessionShouldRemoveSessionFromTrackingOnSuccess() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();

            manager.closeSession(session.id());

            assertTrue(manager.getAllSessions().isEmpty());
            assertTrue(manager.getOpenSessions().isEmpty());
        }

        @Test
        void CloseSessionTest_003_closeSessionShouldRejectUnknownSessionId() {
            AEGISTemporaryFilePlaygroundManager manager = createManager();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.closeSession(UUID.randomUUID())
            );

            assertTrue(exception.getMessage().startsWith("Temporary playground session not found:"));
        }

        @Test
        void CloseSessionTest_004_closeSessionShouldLeaveSessionTrackedWhenDeletionFails() throws Exception {
            AEGISTemporaryFilePlaygroundManager manager = createManager();
            TemporaryFilePlaygroundSession session = manager.createSession();
            assertTrue(Files.deleteIfExists(session.workspacePath()));

            assertThrows(
                    IllegalStateException.class,
                    () -> manager.closeSession(session.id())
            );

            assertEquals(1, manager.getAllSessions().size());
            assertEquals(session, manager.getSession(session.id()));
        }
    }

    private static final class StubOsIntegration implements AEGISTemporaryFilePlaygroundManager.PlaygroundOSIntegration {
        private boolean failOpen;
        private Path lastOpenedPath;

        @Override
        public void openPath(Path path) throws IOException {
            if (failOpen) {
                throw new IOException("open failed");
            }
            lastOpenedPath = path.toAbsolutePath().normalize();
        }
    }
}