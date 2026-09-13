package io.kubastion.ssh;

/**
 * Turns raw ssh or kubectl errors into a message that says what to do about it.
 *
 * This is what makes a short-lived credential a non-issue: you read the
 * message, reload your key, and monitoring resumes on its own. Without it the
 * UI would show "Permission denied (publickey)" and you would have to remember
 * what that means every single time.
 */
public final class RemoteErrors {

    private RemoteErrors() {
    }

    public static String explain(String stderr) {
        String raw = stderr == null ? "" : stderr.trim();
        String lower = raw.toLowerCase();

        if (lower.contains("permission denied") || lower.contains("no supported authentication")) {
            return "SSH credential expired or invalid. Reload your key (ssh-add) — monitoring resumes by itself.";
        }
        if (lower.contains("could not resolve hostname") || lower.contains("name or service not known")) {
            return "Jump host cannot be resolved. Are you connected to the VPN?";
        }
        if (lower.contains("timed out") || lower.contains("network is unreachable")
                || lower.contains("no route to host")) {
            return "Jump host unreachable. Check your VPN connection.";
        }
        if (lower.contains("command not found") || (lower.contains("kubectl") && lower.contains("not found"))) {
            return "kubectl not found on the remote machine. Check kubastion.kubectl.binary.";
        }
        if (lower.contains("unable to connect to the server") || lower.contains("you must be logged in")) {
            return "kubectl cannot reach the cluster: the cluster credentials have expired.";
        }
        if (lower.contains("forbidden") || lower.contains("cannot list resource")) {
            return "Not enough permissions on that namespace.";
        }
        if (raw.isEmpty()) {
            return "Connection closed. Retrying…";
        }
        return raw.lines().filter(line -> !line.isBlank()).findFirst().orElse(raw);
    }
}
