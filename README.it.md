<p align="center">
  <a href="README.md">Русский 🇷🇺</a> ·
  <a href="README.en.md">English 🇺🇸</a> ·
  <a href="README.zh-CN.md">中文 🇨🇳</a> ·
  <a href="README.de.md">Deutsch 🇩🇪</a> ·
  <a href="README.fr.md">Français 🇫🇷</a> ·
  <a href="README.es.md">Español 🇪🇸</a> ·
  <a href="README.it.md"><strong>Italiano 🇮🇹</strong></a>
</p>

<h1 align="center">TGX</h1>
<p align="center">Un proxy locale per Telegram su Android</p>

<p align="center">
  <img src="assets/giftgx.gif" width="85%" alt="Animazione del logo TGX" />
</p>

## Che cos’è TGX?

TGX è un’app Android che avvia sul dispositivo un proxy MTProto locale per Telegram. Consente a Telegram di usare un proxy dedicato senza far transitare tutto il traffico Internet del telefono attraverso TGX. TGX non è una VPN e non modifica il routing delle altre app. La disponibilità di Telegram dipende dalla rete e dalla route usata; TGX non garantisce di aggirare i blocchi, offrire anonimato o funzionare su ogni rete.

## ❣️ Sostenere il progetto ❣️

Il sostegno è facoltativo e aiuta lo sviluppo e la manutenzione di TGX.

- **BTC:** `bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5`
- **ETH:** `0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b`
- **USDT (TRON):** `TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg`

## Come funziona

<p align="center">
  <img src="assets/TGX.png" width="90%" alt="Illustrazione concettuale delle route TGX" />
</p>

1. TGX avvia un servizio Android in primo piano e resta in ascolto all’indirizzo locale `127.0.0.1:1443`.
2. Telegram si collega a quell’indirizzo come proxy MTProto. TGX esamina i dati MTProto iniziali e seleziona una route verso un data center di Telegram.
3. Per il trasporto, TGX usa TLS/WebSocket: connessioni preriscaldate, endpoint WebSocket di Telegram e poi domini proxy Cloudflare. Per i data center configurati è disponibile anche una route TCP diretta quando non è utilizzabile una route WebSocket.
4. TGX recupera l’elenco dei domini Cloudflare da [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), lo convalida e lo aggiorna circa ogni ora. Se il download non riesce, conserva l’ultima copia locale valida.
5. Il servizio monitora lo stato della rete Android e gestisce il server locale e le connessioni upstream. Un cambio di rete o un errore di route può interrompere una connessione; il funzionamento non è garantito.

TGX gestisce solo le connessioni Telegram indirizzate al proxy locale. Non è un tunnel VPN per l’intero dispositivo.

## Installazione e primi passi

### 1. Scaricare l’APK

Scarica l’app solo dalla pagina ufficiale [GitHub Releases](https://github.com/sw1ssl0ve/TGX/releases). Al momento della stesura di questo README, il repository non aveva ancora pubblicato Release contenenti un APK. Non installare build da siti di terze parti.

### 2. Installare l’app

Se viene pubblicato un APK, aprilo su Android e conferma l’installazione. Se Android la blocca, consenti l’installazione di app da questa origine nelle impostazioni del dispositivo. I nomi dei menu variano in base alla versione Android e al produttore.

### 3. Autorizzazioni

Android potrebbe chiedere il permesso per le notifiche. Consente a TGX di mostrare la notifica del servizio in primo piano mentre il proxy locale è attivo; la notifica include anche un comando per fermarlo. Anche se neghi il permesso, TGX richiede comunque l’avvio del servizio, ma Android potrebbe nascondere la notifica. Non serve concedere manualmente l’accesso a Internet: il manifest dell’app dichiara il permesso `INTERNET`.

### 4. Prima connessione

Apri TGX e avvia il proxy. Dopo il primo avvio riuscito, TGX propone una sola volta di aprire Telegram. Conferma in TGX, poi aggiungi o attiva in Telegram il proxy MTProto suggerito. TGX apre un link `tg://proxy` con `127.0.0.1:1443` e il tuo Secret. Se Telegram non è installato o non riesce ad aprire il link, TGX mostra un messaggio; puoi configurare manualmente il proxy in Telegram usando indirizzo locale, porta e Secret visibili nelle impostazioni di TGX.

### 5. Verificare che funzioni

In Telegram verifica la connessione e il caricamento dell’elenco chat e delle conversazioni; poi invia e ricevi un messaggio di prova. Puoi anche provare ad aprire un’immagine o un altro contenuto multimediale. Il solo stato mostrato da TGX non conferma che Telegram stia trasferendo correttamente i dati.

### 6. Se Telegram non si connette

Controlla il Secret nelle impostazioni di TGX: deve contenere 32 caratteri esadecimali. Puoi generarne uno nuovo e toccare **Salva e apri Telegram**. TGX lo salva, riavvia il servizio se è già attivo oppure lo avvia, quindi apre Telegram con il nuovo link. Conferma il proxy in Telegram e seleziona la voce corrente. TGX non può leggere né modificare l’elenco dei proxy salvati in Telegram. Se Telegram mantiene la vecchia voce accanto alla nuova, seleziona il proxy attuale e, se necessario, rimuovi manualmente quella obsoleta. Se Telegram non si è aperto, verifica che sia installato e aggiungi il proxy manualmente.

## Storia del progetto e ringraziamenti

TGX riprende idee e approcci tecnici compatibili dal progetto [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), tra cui il trasporto MTProto tramite WebSocket e il routing con domini proxy Cloudflare. TGX è un’app Android indipendente, con una propria implementazione Kotlin, interfaccia e gestione del ciclo di vita del servizio Android; non è un prodotto ufficiale Flowseal.

Grazie a Flowseal e ai collaboratori del progetto per aver pubblicato questo lavoro.
## Condividere il progetto

Se TGX ti è utile, lascia una ⭐ al repository, condividilo con gli amici o parlane alle community Telegram a cui potrebbe servire: [github.com/sw1ssl0ve/TGX](https://github.com/sw1ssl0ve/TGX).
