import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;


public class ATMNode {

    private static final int NUM_NODI = 4;           // il sistema ha esattamente 4 nodi
    private static final int PORTA_BASE = 5000;      // ATMi -> porta 5000 + i
    private static final String HOST = "localhost";  // tutti i nodi sono su localhost
    private static final int SALDO_INIZIALE = 1000;  // saldo iniziale del conto
    private static final int NODO_INIZIALE = 1;      // ATM1 possiede inizialmente il token

    /** Pausa (ms) prima di inoltrare il token: rende leggibile la circolazione nei log.
     *  Modificabile con -Dritardo=<ms> (es. java -Dritardo=500 ATMNode 1). */
    private static final long RITARDO_MS = Long.getLong("ritardo", 1500);

    /** Durata simulata (ms) della transazione dentro la sezione critica. */
    private static final long DURATA_TRANSAZIONE_MS = Long.getLong("durataTx", 1000);

    private final int id;                 // identificatore del nodo (1..4)
    private final int portaLocale;        // porta su cui il nodo riceve il token
    private final int idSuccessore;       // unico nodo conosciuto: il successore
    private final int portaSuccessore;    // porta del successore

    // Coda delle transazioni richieste dall'utente di questo ATM e non ancora eseguite.
    private final BlockingQueue<Transazione> richiestePendenti = new LinkedBlockingQueue<>();

    /** true solo mentre il nodo possiede fisicamente il token  */
    private volatile boolean haToken = false;

    /** Ultimo numero di sequenza del token visto: serve a scartare eventuali duplicati. */
    private long ultimoSeqVisto = -1;

    /** Numero progressivo delle transazioni eseguite da questo ATM. */
    private int contatoreTransazioni = 0;

    /** File di log su disco (uno per nodo), oltre all'output su console. */
    private final PrintWriter fileLog;

    private static final SimpleDateFormat ORA = new SimpleDateFormat("HH:mm:ss.SSS");

    /** Tipo di operazione bancaria. */
    enum TipoOperazione { DEPOSITO, PRELIEVO }

    /** Una transazione richiesta su questo ATM. */
    static class Transazione {
        final TipoOperazione tipo;
        final int importo;
        Transazione(TipoOperazione tipo, int importo) { this.tipo = tipo; this.importo = importo; }

        /** Converte una stringa tipo "PRELIEVO:200" oppure "prelievo 200". */
        static Transazione parse(String testo) {
            String[] parti = testo.trim().split("[:\\s]+");
            if (parti.length != 2) throw new IllegalArgumentException("formato atteso OPERAZIONE:IMPORTO");
            TipoOperazione tipo = TipoOperazione.valueOf(parti[0].toUpperCase());
            int importo = Integer.parseInt(parti[1]);
            if (importo <= 0) throw new IllegalArgumentException("l'importo deve essere positivo");
            return new Transazione(tipo, importo);
        }
        @Override public String toString() { return tipo + " " + importo; }
    }


    static class Token {
        long seq;   // incrementato a ogni passaggio di mano
        int saldo;  // valore ufficiale (e unico) del saldo condiviso
        Token(long seq, int saldo) { this.seq = seq; this.saldo = saldo; }

        String serializza() { return "TOKEN;seq=" + seq + ";saldo=" + saldo; }

        /*restituisce null se il messaggio non e' un token. */
        static Token deserializza(String riga) {
            if (riga == null || !riga.startsWith("TOKEN;")) return null;
            long seq = -1; Integer saldo = null;
            for (String campo : riga.split(";")) {
                if (campo.startsWith("seq="))   seq = Long.parseLong(campo.substring(4));
                if (campo.startsWith("saldo=")) saldo = Integer.parseInt(campo.substring(6));
            }
            return (seq < 0 || saldo == null) ? null : new Token(seq, saldo);
        }
    }

    public ATMNode(int id) throws IOException {
        this.id = id;
        this.portaLocale = PORTA_BASE + id;
        this.idSuccessore = (id % NUM_NODI) + 1;          // 1->2, 2->3, 3->4, 4->1
        this.portaSuccessore = PORTA_BASE + idSuccessore;
        new File("logs").mkdirs();
        this.fileLog = new PrintWriter(new FileWriter("logs/ATM" + id + ".log", false), true);
    }


    public void avvia() throws Exception {
        log("AVVIO", "ATM" + id + " in ascolto sulla porta " + portaLocale
                + " | successore: ATM" + idSuccessore + " (porta " + portaSuccessore + ")");
        if (!richiestePendenti.isEmpty()) {
            log("RICHIESTE", "transazioni in attesa del token: " + richiestePendenti);
        }

        // Thread che legge nuove transazioni da tastiera (opzionale, utile per la demo)
        Thread tastiera = new Thread(this::leggiTastiera, "tastiera");
        tastiera.setDaemon(true);
        tastiera.start();

        // Solo ATM1 crea l'unico token del sistema, con il saldo iniziale.
        // Il token viene "consegnato a se stesso": ATM1 lo gestisce come se l'avesse
        // appena ricevuto (vedi Sequenza di Esecuzione della traccia, passo 1).
        if (id == NODO_INIZIALE) {
            Token token = new Token(0, SALDO_INIZIALE);
            log("TOKEN", "ATM" + id + " crea l'UNICO token del sistema (saldo iniziale " + SALDO_INIZIALE + ")");
            new Thread(() -> gestisciToken(token), "token-iniziale").start();
        }

        // Ciclo principale: attende il token dal predecessore
        ascoltaToken();
    }


    private void ascoltaToken() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(HOST, portaLocale));
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)) {

                    String riga = in.readLine();
                    Token token = Token.deserializza(riga);
                    if (token == null) {
                        log("ERRORE", "messaggio non riconosciuto: " + riga);
                        continue;
                    }
                    // Conferma di ricezione (ACK): il mittente sa che il token e' arrivato
                    // e puo' considerarsi non piu' detentore. Evita perdita del token.
                    out.println("ACK;seq=" + token.seq);

                    // Scarto di eventuali duplicati (es. ritrasmissione dopo ACK perso)
                    if (token.seq <= ultimoSeqVisto) {
                        log("DUPLICATO", "token seq=" + token.seq + " gia' ricevuto: ignorato");
                        continue;
                    }
                    gestisciToken(token);
                }
            }
        }
    }


    private void gestisciToken(Token token) {
        // 1) have_token := true -> da questo momento SOLO questo nodo puo' entrare in CS
        haToken = true;
        ultimoSeqVisto = token.seq;
        // Il nodo conosce solo il proprio successore: non registra (ne' calcola) chi
        // gli ha inviato il token. Il collegamento invio/ricezione si ricostruisce dai
        // log grazie al numero di sequenza (ATMx "INOLTRATO seq=n" -> ATMy "RICEVUTO seq=n").
        String nota = (token.seq == 0) ? " | (token iniziale)" : "";
        log("TOKEN RICEVUTO", "seq=" + token.seq + " | saldo=" + token.saldo + nota);

        // 2) if need_cs: se ci sono richieste pendenti, entra in sezione critica.
        //    Le richieste in coda al momento dell'arrivo del token vengono servite
        //    una dopo l'altra, sempre mentre il token e' trattenuto.
        Transazione tx = richiestePendenti.poll();
        if (tx == null) {
            log("NESSUNA TX", "nessuna transazione da eseguire, inoltro il token");
        }
        while (tx != null) {
            sezioneCritica(token, tx);
            tx = richiestePendenti.poll();
        }

        // 3) send TOKEN to successor -> il token continua a circolare (liveness)
        pausa(RITARDO_MS);
        token.seq++;
        inoltraToken(token);

        // 4) have_token := false
        haToken = false;
    }


    private void sezioneCritica(Token token, Transazione tx) {
        // Controllo di sicurezza: senza token non si entra MAI in sezione critica
        if (!haToken) throw new IllegalStateException("Tentativo di entrare in CS senza token!");

        int numero = ++contatoreTransazioni;
        String idTx = "T" + id + "." + numero;
        log(">>> INIZIO TX", idTx + " " + tx + "  [ENTRATA IN SEZIONE CRITICA]");

        // (1) Lettura del saldo corrente (dal token)
        int saldoLetto = token.saldo;
        log("   1-LETTURA", "saldo corrente = " + saldoLetto);

        // (2) Validazione dell'operazione
        boolean valida = (tx.tipo == TipoOperazione.DEPOSITO) || (tx.importo <= saldoLetto);
        if (valida) {
            log("   2-VALIDAZIONE", "OK");
        } else {
            log("   2-VALIDAZIONE", "RIFIUTATA: fondi insufficienti (richiesti " + tx.importo
                    + ", disponibili " + saldoLetto + ")");
        }

        // Simula il tempo di elaborazione della transazione (il token resta trattenuto)
        pausa(DURATA_TRANSAZIONE_MS);

        int nuovoSaldo = saldoLetto;
        if (valida) {
            // (3) Aggiornamento del saldo (calcolo su variabile locale)
            nuovoSaldo = (tx.tipo == TipoOperazione.DEPOSITO) ? saldoLetto + tx.importo
                                                                : saldoLetto - tx.importo;
            log("   3-AGGIORNAMENTO", saldoLetto + (tx.tipo == TipoOperazione.DEPOSITO ? " + " : " - ")
                    + tx.importo + " = " + nuovoSaldo);
            // (4) Scrittura del nuovo valore nel token
            token.saldo = nuovoSaldo;
            log("   4-SCRITTURA", "nuovo saldo scritto nel token");
        }

        // (5) Registrazione (log) dell'operazione su registro locale
        registraOperazione(idTx, tx, saldoLetto, nuovoSaldo, valida);
        log("   5-REGISTRAZIONE", "operazione registrata in logs/registro_ATM" + id + ".txt");

        log("<<< FINE TX", idTx + (valida ? " COMPLETATA" : " ANNULLATA")
                + "  [USCITA DALLA SEZIONE CRITICA]");
        log("SALDO AGGIORNATO", String.valueOf(token.saldo));
    }

    /** Registro contabile locale del nodo (append su file). */
    private void registraOperazione(String idTx, Transazione tx, int prima, int dopo, boolean ok) {
        try (PrintWriter pw = new PrintWriter(new FileWriter("logs/registro_ATM" + id + ".txt", true))) {
            pw.println(ORA.format(new Date()) + " | " + idTx + " | " + tx + " | saldo " + prima
                    + " -> " + dopo + " | " + (ok ? "OK" : "RIFIUTATA"));
        } catch (IOException e) {
            log("ERRORE", "impossibile scrivere il registro: " + e.getMessage());
        }
    }


    private void inoltraToken(Token token) {
        String messaggio = token.serializza();
        int tentativi = 0;
        while (true) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, portaSuccessore), 2000);
                socket.setSoTimeout(5000);
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

                // Il log precede l'invio: nella timeline l'inoltro compare sempre
                // prima della ricezione da parte del successore.
                log("TOKEN INOLTRATO", "a ATM" + idSuccessore + " | seq=" + token.seq
                        + " | saldo=" + token.saldo);
                out.println(messaggio);                    // invio del token
                String ack = in.readLine();                // attesa conferma (ACK)
                if (ack != null && ack.equals("ACK;seq=" + token.seq)) {
                    return;                                // consegna confermata
                }
                throw new IOException("ACK non valido: " + ack);
            } catch (IOException e) {
                if (tentativi++ % 5 == 0) {
                    log("ATTESA", "ATM" + idSuccessore + " non raggiungibile (" + e.getMessage()
                            + "), ritento...");
                }
                pausa(1000);
            }
        }
    }

    private void leggiTastiera() {
        try (BufferedReader tastiera = new BufferedReader(new InputStreamReader(System.in))) {
            String riga;
            while ((riga = tastiera.readLine()) != null) {
                if (riga.isBlank()) continue;
                try {
                    Transazione tx = Transazione.parse(riga);
                    richiestePendenti.add(tx);   // need_cs := true (nessun messaggio inviato)
                    log("RICHIESTA", tx + " accodata, in attesa del token");
                } catch (Exception e) {
                    System.out.println("Comando non valido (" + e.getMessage()
                            + "). Esempi: deposito 50 | prelievo 30");
                }
            }
        } catch (IOException ignored) { }
    }

    /** Stampa su console e su file: [ora] [ATMi] EVENTO | dettaglio */
    private synchronized void log(String evento, String dettaglio) {
        String riga = String.format("[%s] [ATM%d] %-18s | %s", ORA.format(new Date()), id, evento, dettaglio);
        System.out.println(riga);
        fileLog.println(riga);
    }

    private static void pausa(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: java ATMNode <id 1..4> [OPERAZIONE:IMPORTO ...]");
            System.out.println("Es.: java ATMNode 2 PRELIEVO:200");
            return;
        }
        int id = Integer.parseInt(args[0]);
        if (id < 1 || id > NUM_NODI) {
            System.out.println("L'id deve essere compreso tra 1 e " + NUM_NODI);
            return;
        }
        ATMNode nodo = new ATMNode(id);
        // Transazioni iniziali passate da riga di comando
        for (int i = 1; i < args.length; i++) {
            nodo.richiestePendenti.add(Transazione.parse(args[i]));
        }
        nodo.avvia();
    }
}
