package io.kubastion.ssh;

/**
 * Traduce l'errore grezzo di ssh o kubectl in un messaggio che dica cosa fare.
 *
 * E' la parte che rende la scadenza della credenziale un non-problema: leggi il
 * messaggio, ricarichi la chiave, e la UI riparte da sola. Senza questo, in UI
 * arriverebbe "Permission denied (publickey)" e ogni volta dovresti ricordarti
 * cosa significa.
 */
public final class RemoteErrors {

    private RemoteErrors() {
    }

    public static String explain(String stderr) {
        String raw = stderr == null ? "" : stderr.trim();
        String lower = raw.toLowerCase();

        if (lower.contains("permission denied") || lower.contains("no supported authentication")) {
            return "Credenziale SSH scaduta o non valida. Ricarica la chiave (ssh-add) e riparte da sola.";
        }
        if (lower.contains("could not resolve hostname") || lower.contains("name or service not known")) {
            return "Jump host non risolvibile: sei connesso alla VPN?";
        }
        if (lower.contains("timed out") || lower.contains("network is unreachable")
                || lower.contains("no route to host")) {
            return "Jump host non raggiungibile: controlla la VPN.";
        }
        if (lower.contains("command not found") || (lower.contains("kubectl") && lower.contains("not found"))) {
            return "kubectl non trovato sul jump host: controlla kubastion.kubectl.binary.";
        }
        if (lower.contains("unable to connect to the server") || lower.contains("you must be logged in")) {
            return "kubectl sul jump host non riesce a parlare col cluster: credenziali del cluster scadute.";
        }
        if (lower.contains("forbidden") || lower.contains("cannot list resource")) {
            return "Permessi insufficienti sul namespace richiesto.";
        }
        if (raw.isEmpty()) {
            return "Connessione chiusa. Nuovo tentativo in corso…";
        }
        return raw.lines().filter(line -> !line.isBlank()).findFirst().orElse(raw);
    }
}
