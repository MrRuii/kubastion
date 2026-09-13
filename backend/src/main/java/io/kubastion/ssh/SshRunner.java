package io.kubastion.ssh;

import io.kubastion.config.KubastionProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Esegue comandi sul jump host invocando il binario ssh di sistema.
 *
 * Scelta deliberata: nessuna libreria SSH e nessuna gestione di chiavi.
 * Cosi' kubastion eredita ~/.ssh/config, l'ssh-agent, i jump host e le policy
 * gia' configurate sulla macchina, e non vede mai una chiave privata.
 */
@Component
public class SshRunner {

    private final KubastionProperties props;

    public SshRunner(KubastionProperties props) {
        this.props = props;
    }

    /**
     * Avvia un comando remoto e restituisce il processo, per leggerne lo stream
     * mentre gira. Usato dal watch dei pod, che resta aperto a lungo.
     */
    public Process start(String remoteCommand) throws IOException {
        return new ProcessBuilder(buildCommand(remoteCommand))
                .redirectErrorStream(false)
                .start();
    }

    /** Esegue un comando remoto fino alla fine e ne raccoglie l'output. */
    public Result run(String remoteCommand, Duration timeout) throws IOException, InterruptedException {
        Process process = start(remoteCommand);

        // stderr va drenato in parallelo: se il suo buffer si riempie mentre noi
        // leggiamo stdout, il processo remoto si blocca e non finisce mai.
        StringBuilder errors = new StringBuilder();
        Thread errorReader = Thread.ofVirtual().start(() -> readInto(process.getErrorStream(), errors));

        String stdout;
        try (InputStream out = process.getInputStream()) {
            stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Comando remoto scaduto dopo " + timeout);
        }
        errorReader.join(Duration.ofSeconds(1));
        return new Result(process.exitValue(), stdout, errors.toString());
    }

    /** Legge uno stream riga per riga accodandolo al buffer, ignorando la chiusura. */
    public static void readInto(InputStream stream, StringBuilder target) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                target.append(line).append(System.lineSeparator());
            }
        } catch (IOException ignored) {
            // lo stream si chiude quando il processo termina: non e' un errore
        }
    }

    private List<String> buildCommand(String remoteCommand) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        if (props.ssh().options() != null) {
            cmd.addAll(props.ssh().options());
        }
        cmd.add(props.ssh().destination());
        cmd.add(remoteCommand);
        return cmd;
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }
}
