package atlanteshellsing.aegis.fileplayground.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable data for a Temporary File Playground Session.
 */
public record TemporaryFilePlaygroundSession(
        UUID id,
        Path workspacePath,
        Instant createdAt,
        TemporaryFilePlaygroundState state
) {

    public TemporaryFilePlaygroundSession {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(workspacePath, "workspacePath cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
    }

    public boolean isOpen() {
        return state == TemporaryFilePlaygroundState.OPEN;
    }

    public TemporaryFilePlaygroundSession asClosed() {
        return new TemporaryFilePlaygroundSession(
                id,
                workspacePath,
                createdAt,
                TemporaryFilePlaygroundState.CLOSED
        );
    }
}