package atlanteshellsing.aegis.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;

public class AEGISLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.logging.Logger AEGIS_LOGGER = Logger.getLogger("AEGIS LOG");

    public enum LogColor {
        CYAN("\u001B[36m"),
        GREEN("\u001B[32m"),
        YELLOW("\u001B[33m"),
        RED("\u001B[31m"),
        RESET("\u001B[0m");

        final String code;

        LogColor(String code) {
            this.code = code;
        }
    }

    public enum AEGISLogKey {
        AEGIS_MAIN, AEGIS_TOOL
    }

    public enum AEGISLogLevel {
        FINE(Level.FINE, LogColor.CYAN), INFO(Level.INFO, LogColor.GREEN), WARNING(Level.WARNING, LogColor.YELLOW), SEVERE(Level.SEVERE, LogColor.RED);

        final LogColor color;
        final Level level;

        AEGISLogLevel(Level level, LogColor color) {
            this.level = level;
            this.color = color;
        }
    }

    static {
        AEGIS_LOGGER.setUseParentHandlers(false);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter() {
            /**
             * Formats a LogRecord into a colorized, timestamped log line and appends any attached throwable stack trace.
             *
             * <p>The formatted line contains an ANSI color code, timestamp, log source key (defaults to AEGIS_MAIN if not provided
             * via record parameters), the record's level name, the message, and a reset color code. If the LogRecord has a thrown
             * Throwable, its stack trace and nested causes are appended after the main line.</p>
             *
             * @param logRec the LogRecord to format; may carry optional parameters where index 0 is an AEGISLogKey and index 1 is
             *               an AEGISLogLevel that determines the color
             * @return a single string containing the complete formatted log entry (including newline), with ANSI color codes and
             *         any appended stack trace for an attached Throwable
             */
            @Override
            public synchronized String format(LogRecord logRec) {
                StringBuilder builder = new StringBuilder();

                String timestamp = LocalDateTime.now().format(FORMATTER);
                AEGISLogKey key = AEGISLogKey.AEGIS_MAIN;
                AEGISLogLevel aegisLevel = null;

                Object[] params = logRec.getParameters();
                if (params != null) {
                    if (params.length > 0 && params[0] instanceof AEGISLogKey logKey) {
                        key = logKey;
                    }
                    if (params.length > 1 && params[1] instanceof AEGISLogLevel logLevel) {
                        aegisLevel = logLevel;
                    }
                }

                String color = aegisLevel != null ? aegisLevel.color.code : LogColor.RESET.code;

                builder.append(String.format("%s[%s] [%s] [%s] %s%s%n",
                        color,
                        timestamp,
                        key.name(),
                        logRec.getLevel().getName(),
                        logRec.getMessage(),
                        LogColor.RESET.code
                ));

                //print throwables
                if(logRec.getThrown() != null) {
                    Throwable thrown = logRec.getThrown();
                    printThrowable(builder, thrown);
                }

                return builder.toString();
            }

            /**
             * Appends a colorized, multiline representation of the given throwable and its nested causes to the supplied StringBuilder.
             *
             * The appended text includes an exception header for each throwable and its stack trace lines; nested causes are processed recursively.
             *
             * @param builder the destination buffer to which the formatted throwable text will be appended
             * @param thrown  the throwable to format (may contain a cause chain) 
             */
            private void printThrowable(StringBuilder builder, Throwable thrown) {

                  String grey = "\u001B[90m";
                  String red = "\u001B[31m";

                // Print Exception Header In Red
                builder.append(red)
                        .append("Caused by: ")
                        .append(thrown.toString())
                        .append(LogColor.RESET.code)
                        .append(System.lineSeparator());

                // Print Stack Trace In Grey
                for(StackTraceElement element : thrown.getStackTrace()) {
                    builder.append(grey)
                            .append("\tat ")
                            .append(element.toString())
                            .append(LogColor.RESET.code)
                            .append(System.lineSeparator());
                }

                //Handle Nested Exceptions
                Throwable cause = thrown.getCause();
                if(cause != null)
                    printThrowable(builder, cause);
            }
        });

        handler.setLevel(Level.ALL);
        AEGIS_LOGGER.addHandler(handler);
        AEGIS_LOGGER.setLevel(Level.ALL);
    }

    /**
     * Log a message with the specified AEGIS log key and level.
     *
     * @param key     the logical source identifier to attach to the log entry (AEGISLogKey)
     * @param level   the severity and associated console color for the log entry (AEGISLogLevel)
     * @param message the message text to record
     */
    public static void log(AEGISLogKey key, AEGISLogLevel level, String message) {
        log(key, level, message, null);
    }

    /**
     * Logs a message under the given AEGIS source key and severity, optionally
     * associating a Throwable (Exception, Error, etc.) with the entry.
     *
     * @param key      the source of the log entry (e.g., AEGIS_MAIN, AEGIS_TOOL)
     * @param level    the AEGISLogLevel that determines severity and console color
     * @param message  the log message text
     * @param thrown   a Throwable to include with the log entry; may be null
     */
    public static void log(AEGISLogKey key, AEGISLogLevel level, String message, Throwable thrown) {
        LogRecord logRec = new LogRecord(level.level, message);
        logRec.setParameters(new Object[]{ key, level });
        logRec.setThrown(thrown);
        AEGIS_LOGGER.log(logRec);
    }
}