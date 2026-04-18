package atlanteshellsing.aegis.fileplayground.service;

import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundState;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;import java.util.stream.Collectors;

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

    /**
     * Creates a manager using the provided playground root.
     *
     * @param playgroundRoot root directory for all Temporary File Playground sessions
     */
    public AEGISTemporaryFilePlaygroundManager(Path playgroundRoot) {
        this.playgroundRoot = Objects.requireNonNull(playgroundRoot, "playgroundRoot cannot be null")
                .toAbsolutePath()
                .normalize();
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
        return Collections.unmodifiableMap(
                sessions.entrySet()
                        .stream()
                        .filter(entry -> entry.getValue().isOpen())
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (e1, e2) -> e1,
                                LinkedHashMap::new
                        ))
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
        TemporaryFilePlaygroundSession session = getSession(sessionId);
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
        TemporaryFilePlaygroundSession session = getSession(sessionId);
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
        TemporaryFilePlaygroundSession session = getSession(sessionId);

        if(paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("No Files or Folders to import.");
        }

        Path workspacePath = session.workspacePath();
        for(Path path : paths) {
            if(path == null) throw new  IllegalArgumentException("Dropped Item cannot be null.");

            Path normalizedPath = path.toAbsolutePath().normalize();
            Path fileName = normalizedPath.getFileName();
            if(fileName == null) throw new  IllegalArgumentException("Dropped Item has no valid name" + normalizedPath);
            if(!Files.exists(normalizedPath)) throw new IllegalArgumentException("Dropped Item does not exist: " + normalizedPath);

            Path targetPath = workspacePath.resolve(fileName).normalize();
            if(Files.exists(targetPath)) throw new IllegalArgumentException("Dropped Item already exists: " + targetPath);

            try {
                copyPath(normalizedPath, targetPath);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to import '" + fileName + "' into workspace" , e);
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

    /**
     * Returns the normalized absolute playground root path.
     *
     * @return playground root
     */
    public Path getPlaygroundRoot() { return playgroundRoot; }
}