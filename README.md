# Transazioni bancarie distribuite con Token Ring

Progetto 1 del corso di **Intelligenza Artificiale Distribuita**.

**Studente:** Luigi Colangelo  
**Matricola:** 0322600050

## Descrizione

Il progetto simula quattro sportelli ATM indipendenti che eseguono depositi e prelievi sul medesimo conto bancario. Ogni ATM è un processo Java distinto, con memoria privata, e comunica tramite socket TCP su `localhost`.

La mutua esclusione è realizzata mediante un unico token che circola nell'anello logico. Solo il nodo che lo possiede può eseguire transazioni. Il token trasporta anche il saldo corrente: non sono utilizzati memoria condivisa, file di saldo comuni o un server centrale per coordinare gli aggiornamenti.

ATM1 crea il token iniziale con `seq=0` e saldo **1000**. Ogni nodo conosce soltanto il proprio successore.

| Nodo | Indirizzo di ascolto | Successore |
| --- | --- | --- |
| ATM1 | `localhost:5001` | ATM2 |
| ATM2 | `localhost:5002` | ATM3 |
| ATM3 | `localhost:5003` | ATM4 |
| ATM4 | `localhost:5004` | ATM1 |

## Requisiti

- Java **JDK 11 o superiore**, con `java` e `javac` disponibili nel `PATH`.
- Windows, Linux o macOS.
- Quattro terminali separati.
- Porte TCP **5001-5004** libere.

Non sono richieste librerie esterne, Maven o Gradle. Il codice utilizza le librerie standard `java.io`, `java.net`, `java.text`, `java.util` e `java.util.concurrent`.

Verificare l'ambiente:

```bash
java -version
javac -version
```

## Cartella del progetto

| Percorso relativo | Contenuto |
| --- | --- |
| `src/ATMNode.java` | Codice sorgente dei quattro nodi, parametrizzato tramite identificativo. |
| `out/` | Classi Java compilate. |
| `out/logs/ATMi.log` | Log degli eventi del nodo `i`, con le istruzioni di avvio riportate sotto. |
| `out/logs/registro_ATMi.txt` | Registro locale delle operazioni del nodo `i`. |

## Compilazione

Da un terminale nella radice del progetto eseguire:

```bash
javac -encoding UTF-8 -d out src/ATMNode.java
```

Ricompilare il sorgente anche se l'archivio contiene già classi `.class`, per utilizzare la versione corrente del codice e classi compatibili con il JDK installato.

## Avvio dei quattro ATM

Aprire quattro terminali, ciascuno nella radice del progetto. Eseguire `cd out` una sola volta in ogni terminale.

**Ordine consigliato:** avviare ATM2, ATM3 e ATM4, quindi ATM1, che crea il token iniziale. Ogni comando `java` rimane in esecuzione nel proprio terminale: non avviare più nodi nella stessa console.

### Terminale 1 - ATM1: avviare per ultimo

```bash
cd out
java -Dritardo=0 ATMNode 1
```

### Terminale 2 - ATM2

```bash
cd out
java -Dritardo=0 ATMNode 2 PRELIEVO:200
```

### Terminale 3 - ATM3

```bash
cd out
java -Dritardo=0 ATMNode 3 DEPOSITO:100
```

### Terminale 4 - ATM4

```bash
cd out
java -Dritardo=0 ATMNode 4 PRELIEVO:500
```

Il numero dopo `ATMNode` identifica il nodo, determina la porta `5000 + id` e il successore `(id % 4) + 1`. I log riportano il prefisso `[ATM1]`, `[ATM2]`, `[ATM3]` o `[ATM4]`, consentendo di riconoscere il processo in ciascun terminale.

Il programma ritenta l'invio se il successore non è ancora raggiungibile; avviare ATM1 per ultimo riduce le attese iniziali.

## Risultato atteso

Con i comandi precedenti e senza ulteriori richieste:

| Nodo | Operazione | Saldo prima | Saldo dopo |
| --- | --- | --- | --- |
| ATM1 | Nessuna transazione; inoltro del token | 1000 | 1000 |
| ATM2 | Prelievo di 200 | 1000 | 800 |
| ATM3 | Deposito di 100 | 800 | 900 |
| ATM4 | Prelievo di 500 | 900 | **400** |

Il token continua a circolare anche quando non sono presenti transazioni pendenti. Il saldo resta 400 finché non viene eseguita una nuova operazione.

## Interazione da tastiera

Nel terminale dell'ATM desiderato digitare un comando e premere Invio:

```text
deposito 250
prelievo 30
```

È accettato anche il formato `DEPOSITO:250` o `PRELIEVO:30`. Gli importi devono essere numeri interi positivi; usare importi contenuti, perché saldo e importi sono rappresentati da `int` Java.

Le richieste vengono inserite nella coda locale ed eseguite quando il nodo possiede il token. La coda viene servita fino a quando risulta vuota, includendo eventualmente richieste aggiunte durante l'elaborazione.

Un prelievo superiore al saldo disponibile viene registrato come `RIFIUTATA`; il saldo rimane invariato e la transazione termina come `ANNULLATA`.

## Parametri di esecuzione

| Proprietà Java | Valore predefinito | Funzione |
| --- | --- | --- |
| `-Dritardo=<ms>` | `1500` | Pausa prima di ogni inoltro del token, anche senza richieste. |
| `-DdurataTx=<ms>` | `1000` | Durata simulata di ciascuna transazione nella sezione critica. |

Utilizzare valori non negativi. Le proprietà devono precedere il nome della classe `ATMNode`.

I comandi di avvio riportati sopra usano `-Dritardo=0` per eliminare la pausa e rispettare l'inoltro immediato a coda vuota. Per una dimostrazione con log più leggibili, sostituire questa opzione con `-Dritardo=1500` su tutti i nodi, mantenendo gli stessi identificativi e le stesse transazioni.

Esempio per ATM2 in modalità dimostrativa:

```bash
java -Dritardo=1500 -DdurataTx=1000 ATMNode 2 PRELIEVO:200
```

## Messaggi e funzionamento

| Messaggio | Formato | Significato |
| --- | --- | --- |
| TOKEN | `TOKEN;seq=<n>;saldo=<s>` | Trasferisce al successore il permesso di accesso e il saldo. |
| ACK | `ACK;seq=<n>` | Conferma la ricezione della sequenza indicata sulla stessa connessione TCP. |

I messaggi occupano una riga di testo codificata in UTF-8. La sequenza aumenta a ogni nuovo inoltro; i retry riutilizzano lo stesso messaggio e la stessa sequenza. Il ricevente risponde con l'ACK prima dell'elaborazione e ignora i token con sequenza minore o uguale all'ultima accettata. L'ACK non certifica il completamento delle transazioni o la persistenza del saldo.

Il timeout di connessione è di 2000 ms; quello di lettura dell'ACK è di 5000 ms. In caso di `IOException`, il nodo attende 1000 ms prima del nuovo tentativo e registra `ATTESA` al primo errore e poi ogni cinque errori.

## Log

Ogni nodo scrive sulla console e nei propri file locali. Avviando i processi dalla cartella `out`, i file si trovano in `out/logs/`:

- `ATMi.log`: ricezioni e inoltri del token, richieste, inizio e fine delle transazioni, saldo ed eventuali errori. Viene sovrascritto all'avvio del nodo.
- `registro_ATMi.txt`: registro delle operazioni, incluse quelle rifiutate. Viene aggiornato in append e può contenere sessioni precedenti.

I file sono creati rispetto alla directory corrente del processo. Non vengono usati per sincronizzare gli ATM né per ripristinare il saldo.


