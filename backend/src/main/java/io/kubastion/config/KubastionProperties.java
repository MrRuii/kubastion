package io.kubastion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Application configuration.
 *
 * Nothing sensitive lives here: kubastion knows no keys, passwords or remote
 * hosts. It opens a local shell and you log in through it yourself, exactly as
 * you would in any terminal.
 */
@ConfigurationProperties(prefix = "kubastion")
public record KubastionProperties(
        Terminal terminal,
        Kubectl kubectl,
        Monitor monitor) {

    /**
     * The local shell shown in the browser terminal. From there you run
     * ssh-add, ssh, the gateway menu and everything else.
     *
     * @param command shell executable; empty means "pick the OS default"
     * @param args    shell arguments
     */
    public record Terminal(String command, List<String> args) {
    }

    /**
     * @param binary    path to kubectl ON THE REMOTE MACHINE
     * @param namespace namespace to watch
     * @param context   optional kubectl context
     */
    public record Kubectl(String binary, String namespace, String context) {
    }

    /**
     * @param intervalSeconds delay between `kubectl get pods` runs
     * @param timeoutSeconds  past this, the command is considered lost and the
     *                        terminal is handed back to the user
     * @param quietSeconds    how long the terminal must be quiet before a
     *                        command may be injected. The session is yours: a
     *                        half-typed line always pauses polling, whatever
     *                        this is set to. 0 disables the timer only.
     */
    public record Monitor(int intervalSeconds, int timeoutSeconds, int quietSeconds) {
    }
}
