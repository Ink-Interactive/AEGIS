package atlanteshellsing.aegis.fileplayground.model;

import atlanteshellsing.aegis.annotations.ExcludeAsGenerated;

/**
 * Represents the lifecycle state of a Temporary File Playground Session.
 */
@ExcludeAsGenerated
public enum TemporaryFilePlaygroundState {
    OPEN,
    PENDING_CLEANUP,
    CLOSED
}