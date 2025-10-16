package onion.network.helpers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TorStatusFormatter {

    private static final Pattern BOOTSTRAP_PATTERN = Pattern.compile("Bootstrapped\\s+(\\d+%)");

    private TorStatusFormatter() {
    }

    public static Status parse(String line) {
        if (line == null) {
            return Status.noChange();
        }

        String status = line.trim();
        if (status.isEmpty()) {
            return Status.noChange();
        }

        if (status.contains("Bootstrapped 100%")) {
            return Status.ready();
        }

        Matcher matcher = BOOTSTRAP_PATTERN.matcher(status);
        if (matcher.find()) {
            return Status.message("Loading " + matcher.group(1));
        }

        if (status.toLowerCase().contains("starting")) {
            return Status.message("Starting Tor...");
        }

        return Status.noChange();
    }

    public static final class Status {
        private final boolean ready;
        private final String message;
        private final boolean changed;

        private Status(boolean ready, String message, boolean changed) {
            this.ready = ready;
            this.message = message;
            this.changed = changed;
        }

        public static Status ready() {
            return new Status(true, null, true);
        }

        public static Status message(String message) {
            return new Status(false, message, true);
        }

        public static Status noChange() {
            return new Status(false, null, false);
        }

        public boolean isReady() {
            return ready;
        }

        public String getMessage() {
            return message;
        }

        public boolean hasChanged() {
            return changed;
        }
    }
}
