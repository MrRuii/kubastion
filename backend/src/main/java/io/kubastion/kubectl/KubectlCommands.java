package io.kubastion.kubectl;

import io.kubastion.config.KubastionProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Costruisce le stringhe di comando kubectl eseguite sul jump host.
 *
 * Tutto cio' che finisce nella riga di comando viene validato: la shell remota
 * esegue quello che le passiamo, quindi nessun valore — nemmeno quelli che
 * arrivano dal file di configurazione — ci entra senza controllo.
 */
@Component
public class KubectlCommands {

    /** Nomi di risorsa Kubernetes (RFC 1123): minuscole, cifre, trattini, punti. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$");

    /** Il binario puo' essere un percorso, ma senza metacaratteri di shell. */
    private static final Pattern SAFE_BINARY = Pattern.compile("^[A-Za-z0-9._/\\\\:-]{1,200}$");

    private final String binary;
    private final String namespace;
    private final String context;

    public KubectlCommands(KubastionProperties props) {
        this.binary = requireBinary(props.kubectl().binary());
        this.namespace = requireName(props.kubectl().namespace(), "kubectl.namespace");
        String ctx = props.kubectl().context();
        this.context = (ctx == null || ctx.isBlank()) ? "" : requireName(ctx, "kubectl.context");
    }

    /**
     * Elenco dei pod in JSON.
     *
     * Qui il polling e' la scelta giusta, non un ripiego: la sessione del
     * terminale e' condivisa con l'utente, e un `--watch` la terrebbe occupata
     * per sempre impedendogli di lavorare.
     */
    public String getPods() {
        return base() + " get pods -o json";
    }

    public String namespace() {
        return namespace;
    }

    private String base() {
        StringBuilder sb = new StringBuilder(binary);
        if (!context.isEmpty()) {
            sb.append(" --context=").append(context);
        }
        return sb.append(" -n ").append(namespace).toString();
    }

    /** Valida un nome di risorsa/namespace/contesto. */
    public static String requireName(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Valore mancante per " + what);
        }
        String trimmed = value.trim();
        if (!SAFE_NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Valore non valido per " + what + ": " + value);
        }
        return trimmed;
    }

    private static String requireBinary(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Valore mancante per kubectl.binary");
        }
        String trimmed = value.trim();
        if (!SAFE_BINARY.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Percorso kubectl non valido: " + value);
        }
        return trimmed;
    }
}
