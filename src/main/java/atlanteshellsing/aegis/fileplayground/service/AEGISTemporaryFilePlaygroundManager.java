package atlanteshellsing.aegis.fileplayground.service;

import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundState;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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

            while(true) {
                String sessionId = UUID.randomUUID().toString();
                Path sessionPath = playgroundRoot.resolve(sessionId);

               try {
                   Files.createDirectory(sessionPath);
               } catch (FileAlreadyExistsException e) {
                   continue;
               }

                Files.createDirectory(sessionPath);

                TemporaryFilePlaygroundSession session = new TemporaryFilePlaygroundSession(
                        UUID.fromString(sessionId),
                        sessionPath.toAbsolutePath().normalize(),
                        Instant.now(),
                        TemporaryFilePlaygroundState.OPEN
                );

                sessions.put(session.id(), session);
                return session;

            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temporary file playground session", e);
        }
    }

    /**
     * Returns a tracked session by ID, or null if not found.
     *
     * @param sessionId the session ID
     * @return the session or null
     */
    public synchronized TemporaryFilePlaygroundSession getSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return sessions.get(sessionId);
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
     * Returns the normalized absolute playground root path.
     *
     * @return playground root
     */
    public Path getPlaygroundRoot() { return playgroundRoot; }

}