package atlanteshellsing.aegis.fileplayground.service;

import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundState;

import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages Temporary File Playground sessions for the current application run.
 *
 * Current behavior:
 * - Creates session folders under the configured playground root
 * - Tracks session metadata in memory
 * - Allows multiple playground sessions to remain open at once
 */
public class AEGISTemporaryFilePlaygroundManager {

    private final Path playgroundRoot;
    private final Map<UUID, TemporaryFilePlaygroundSession> sessions;
    private final PlaygroundOSIntegration osIntegration;
    private final PlaygroundDeletion deletion;

    /**
     * Creates a manager using the provided playground root.
     *
     * @param playgroundRoot root directory for all Temporary File Playground sessions
     */
    public AEGISTemporaryFilePlaygroundManager(Path playgroundRoot) {
        this(playgroundRoot, new DesktopPlaygroundOSIntegration());
    }

    public AEGISTemporaryFilePlaygroundManager(Path playgroundRoot, PlaygroundOSIntegration osIntegration) {
        this(playgroundRoot, osIntegration, AEGISTemporaryFilePlaygroundManager::deletePathRecursively);
    }

    AEGISTemporaryFilePlaygroundManager(Path playgroundRoot, PlaygroundOSIntegration osIntegration, PlaygroundDeletion deletion) {
        this.playgroundRoot = Objects.requireNonNull(playgroundRoot, "playgroundRoot cannot be null")
                .toAbsolutePath()
                .normalize();
        this.osIntegration = Objects.requireNonNull(osIntegration, "osIntegration cannot be null");
        this.deletion = Objects.requireNonNull(deletion, "deletion cannot be null");
        this.sessions = new LinkedHashMap<>();
    }

    /**
     * Creates a new Temporary File Playground session.
     *
     * @return metadata for the created session
     * @throws IllegalStateException if the session directory cannot be created
     */
    public synchronized TemporaryFilePlaygroundSession createSession() {
        try {
            Files.createDirectories(playgroundRoot);

            UUID sessionId = UUID.randomUUID();
            Path sessionPath = Files.createTempDirectory(playgroundRoot, sessionId + "-")
                    .toAbsolutePath()
                    .normalize();

            TemporaryFilePlaygroundSession session = new TemporaryFilePlaygroundSession(
                    sessionId,
                    sessionPath,
                    Instant.now(),
                    TemporaryFilePlaygroundState.OPEN
            );

            sessions.put(session.id(), session);
            return session;

        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temporary file playground session", e);
        }
    }

    /**
     * Opens the current session workspace with the OS file manager.
     *
     * @param sessionId session ID
     */
    public synchronized void openWorkspace(UUID sessionId) {
        TemporaryFilePlaygroundSession session = getOpenSession(sessionId);
        Path workspacePath = session.workspacePath();

        if(!Files.isDirectory(workspacePath)) throw new IllegalStateException("Workspace path does not exist or is not a directory: " + workspacePath);

        try {
            osIntegration.openPath(workspacePath);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to open workspace in Explorer: " + workspacePath, e);
        }
    }

    /**
     * Opens a selected file using the default associated OS application.
     *
     * @param sessionId session ID
     * @param selectedPath selected file
     */
    public synchronized void openSelectedFile(UUID sessionId, Path selectedPath) {
        Path resolvedPath = validateSelectedPath(sessionId, selectedPath, true);
        try {
            osIntegration.openPath(resolvedPath);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to open selected file: " + resolvedPath, e);
        }
    }

    /**
     * Opens Explorer / file manager for the selected workspace item.
     * - if selection is a directory, opens that directory
     * - if selection is a file, opens the parent directory
     *
     * @param sessionId session ID
     * @param selectedPath selected path inside workspace
     */
    public synchronized void openSelectedInExplorer(UUID sessionId, Path selectedPath) {
        Path resolvedPath = validateSelectedPath(sessionId, selectedPath, false);
        Path targetDirectory = Files.isDirectory(resolvedPath) ? resolvedPath : resolvedPath.getParent();

        if(targetDirectory == null) throw new IllegalArgumentException("Selected path has no parent directory: " + resolvedPath);

        try {
            osIntegration.openPath(targetDirectory);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to open selected path in Explorer: " + targetDirectory, e);
        }
    }

    /**
     * Closes and deletes a tracked Temporary File Playground session workspace.
     *
     * @param sessionId session ID
     */
    public synchronized void closeSession(UUID sessionId) {
        TemporaryFilePlaygroundSession session = getOpenSession(sessionId);
        Path workspacePath = session.workspacePath();

        try {
            deletion.delete(workspacePath);
        } catch (IOException e) {
            TemporaryFilePlaygroundSession pendingCleanup = session.asPendingCleanup();
            sessions.put(sessionId, pendingCleanup);
            throw new IllegalStateException(
                    "Failed to delete playground workspace; session is pending cleanup: " + workspacePath,
                    e
            );
        }

        sessions.remove(sessionId);
    }

    private Path validateSelectedPath(UUID sessionId, Path selectedPath, boolean mustBeFile) {
        TemporaryFilePlaygroundSession session = getOpenSession(sessionId);
        Path workspacePath = session.workspacePath().toAbsolutePath().normalize();

        if(selectedPath == null) throw new IllegalArgumentException("Selected path cannot be null");

        Path normalizedSelection = selectedPath.toAbsolutePath().normalize();
        if(!normalizedSelection.startsWith(workspacePath)) throw new IllegalArgumentException("Selected path must be inside the session workspace: " + normalizedSelection);

        if(!Files.exists(normalizedSelection)) throw new IllegalArgumentException("Selected path does not exist: " + normalizedSelection);

        if(mustBeFile && !Files.isRegularFile(normalizedSelection)) throw new IllegalArgumentException("Selected path must be a file: " + normalizedSelection);

        return normalizedSelection;
    }

    /**
     * Returns a tracked session by ID, or throws an IllegalArgumentException if not found.
     *
     * @param sessionId the session ID
     * @return the session or an IllegalArgumentException
     */
    public synchronized TemporaryFilePlaygroundSession getSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        TemporaryFilePlaygroundSession session = sessions.get(sessionId);
        if(session == null) {
            throw new IllegalArgumentException("Temporary playground session not found: " + sessionId);
        }
        return session;
    }

    private TemporaryFilePlaygroundSession getOpenSession(UUID sessionId) {
        TemporaryFilePlaygroundSession session = getSession(sessionId);
        if(!session.isOpen()) {
            throw new IllegalStateException(
                    "Temporary playground session is not open (state=" + session.state() + "): " + sessionId
            );
        }

        return session;
    }

    /**
     * Returns all sessions tracked during the current run.
     *
     * @return unmodifiable snapshot of tracked sessions
     */
    public synchronized Map<UUID, TemporaryFilePlaygroundSession> getAllSessions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sessions));
    }

    /**
     * Returns all currently open sessions tracked during the current run.
     *
     * @return unmodifiable snapshot of open sessions
     */
    public synchronized Map<UUID, TemporaryFilePlaygroundSession> getOpenSessions() {
       return sessionsByState(TemporaryFilePlaygroundState.OPEN);
    }

    /**
     * Returns all sessions whose workspaces could not be deleted and need later cleanup.
     *
     * @return unmodifiable snapshot of pending cleanup sessions
     */
    public synchronized Map<UUID, TemporaryFilePlaygroundSession> getPendingCleanupSessions() {
        return sessionsByState(TemporaryFilePlaygroundState.PENDING_CLEANUP);
    }

    private Map<UUID, TemporaryFilePlaygroundSession> sessionsByState(TemporaryFilePlaygroundState state) {
        return Collections.unmodifiableMap(
                sessions.entrySet()
                        .stream()
                        .filter(entry -> entry.getValue().state() == state)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (e1, e2) -> e1, LinkedHashMap::new))
        );
    }

    /**
     * Creates a file inside the given session workspace.
     *
     * @param sessionId session to create the file in
     * @param fileName  file name (not a nested path)
     * @return created file path
     */
    public synchronized Path createFile(UUID sessionId, String fileName) {
        return createFile(sessionId, null, fileName);
    }

    /**
     * Creates a file inside the given session workspace (or inside the provided parent directory).
     *
     * @param sessionId       session to create the file in
     * @param parentDirectory optional target directory inside the workspace (null uses workspace root)
     * @param fileName        file name (not a nested path)
     * @return created file path
     */
    public synchronized Path createFile(UUID sessionId, Path parentDirectory, String fileName) {
        TemporaryFilePlaygroundSession session = getOpenSession(sessionId);
        Path targetDirectory = resolveTargetDirectory(session.workspacePath(), parentDirectory);
        String sanitizedName = validateChildName(targetDirectory, fileName, "File name");
        Path filePath = targetDirectory.resolve(sanitizedName).normalize();

        try {
            return Files.createFile(filePath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create file '" + sanitizedName + "'", e);
        }
    }

    /**
     * Creates a folder inside the given session workspace.
     *
     * @param sessionId   session to create the folder in
     * @param folderName  folder name (not a nested path)
     * @return created folder path
     */
    public synchronized Path createFolder(UUID sessionId, String folderName) {
        return createFolder(sessionId, null, folderName);
    }

    /**
     * Creates a folder inside the given session workspace (or inside the provided parent directory).
     *
     * @param sessionId       session to create the folder in
     * @param parentDirectory optional target directory inside the workspace (null uses workspace root)
     * @param folderName      folder name (not a nested path)
     * @return created folder path
     */
    public synchronized Path createFolder(UUID sessionId, Path parentDirectory, String folderName) {
        TemporaryFilePlaygroundSession session = getOpenSession(sessionId);
        Path targetDirectory = resolveTargetDirectory(session.workspacePath(), parentDirectory);
        String sanitizedName = validateChildName(targetDirectory, folderName, "Folder name");
        Path folderPath = targetDirectory.resolve(sanitizedName).normalize();

        try {
            return Files.createDirectory(folderPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create folder '" + sanitizedName + "'", e);
        }
    }

    /**
     * Imports dropped files/folders into the session workspace.
     *
     * @param sessionId   target session
     * @param paths dropped files/folders to copy
     */
    public synchronized void importPaths(UUID sessionId, List<Path> paths) {
        TemporaryFilePlaygroundSession session = getOpenSession(sessionId);

        if(paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("No Files or Folders to import.");
        }

        Path workspacePath = session.workspacePath();
        List<ImportPlan> importPlans = new ArrayList<>();
        Set<Path> plannedTargets = new HashSet<>();

        for(Path path : paths) {

            if(path == null) throw new  IllegalArgumentException("Dropped Item cannot be null.");

            Path normalizedPath = path.toAbsolutePath().normalize();
            Path fileName = normalizedPath.getFileName();

            if(fileName == null) throw new  IllegalArgumentException("Dropped Item has no valid name" + normalizedPath);
            if(!Files.exists(normalizedPath)) throw new IllegalArgumentException("Dropped Item does not exist: " + normalizedPath);

            Path targetPath = workspacePath.resolve(fileName).normalize();

            if(Files.isDirectory(normalizedPath) && targetPath.toAbsolutePath().normalize().startsWith(normalizedPath)) {
                throw new IllegalArgumentException("Dropped folder cannot be imported into itself: " + normalizedPath);
            }

            if(!plannedTargets.add(targetPath)) {
                throw new IllegalArgumentException("Dropped Item already exists: " + targetPath);
            }

            if(Files.exists(targetPath)) throw new IllegalArgumentException("Dropped Item already exists: " + targetPath);
            importPlans.add(new ImportPlan(normalizedPath, targetPath, fileName));
        }
        for(ImportPlan importPlan : importPlans) {
            try {
                copyPath(importPlan.source(), importPlan.target());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to import '" + importPlan.fileName() + "' into workspace" , e);
            }
        }
    }

    private String validateChildName(Path workspacePath, String value, String fieldName) {
        if(value == null) throw new IllegalArgumentException(fieldName + " cannot be null");

        String trimmed = value.trim();
        if(trimmed.isBlank()) throw new IllegalArgumentException(fieldName + " cannot be blank");

        if(trimmed.contains("/") || trimmed.contains("\\")) throw new IllegalArgumentException(fieldName + " cannot contain path separators");

        Path target = workspacePath.resolve(trimmed).normalize();
        if(Files.exists(target)) throw new IllegalArgumentException("'" + trimmed + "' already exists");

        return trimmed;
    }

    private Path resolveTargetDirectory(Path workspacePath, Path parentDirectory) {
        Path candidate = parentDirectory == null
                ? workspacePath
                : parentDirectory.toAbsolutePath().normalize();

        if(!candidate.startsWith(workspacePath)) throw new IllegalArgumentException("Target directory must be inside the workspace");

        if(!Files.exists(candidate) || !Files.isDirectory(candidate)) throw new IllegalArgumentException("Target directory does not exist or is not a directory");

        return candidate;
    }

    private void copyPath(Path source, Path target) throws IOException {
        if(Files.isDirectory(source)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(dir);
                    Path destination = target.resolve(relative);
                    Files.copy(dir, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(file);
                    Path destination = target.resolve(relative);
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });

            return;
        }

        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void deletePathRecursively(Path path) throws IOException {
        if(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for(Path child : children) {
                    deletePathRecursively(child);
                }
            }
        }

        Files.delete(path);
    }

    /**
     * Returns the normalized absolute playground root path.
     *
     * @return playground root
     */
    public Path getPlaygroundRoot() { return playgroundRoot; }
    private record ImportPlan(Path source, Path target, Path fileName) { }

    interface PlaygroundOSIntegration {
        void openPath(Path path) throws IOException;
    }

    interface PlaygroundDeletion {
        void delete(Path path) throws IOException;
    }

    static class DesktopPlaygroundOSIntegration implements PlaygroundOSIntegration {
        @Override
        public void openPath(Path path) throws IOException {
            Desktop desktop = requireDesktopAction(Desktop.Action.OPEN);
            desktop.open(path.toFile());
        }

        private Desktop requireDesktopAction(Desktop.Action action) throws IOException {
            if(!Desktop.isDesktopSupported()) throw new IOException("Desktop Integration is not supported on this platform");

            Desktop desktop = Desktop.getDesktop();
            if(!desktop.isSupported(action)) throw new IOException("Desktop Integration is not supported on this platform");

            return desktop;
        }
    }
}