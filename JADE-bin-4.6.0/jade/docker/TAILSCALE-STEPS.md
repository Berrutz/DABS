# Guida al Deploy tramite Tailscale

## Prerequisiti comuni
- Installa Docker Compose v2 e il client Tailscale su **entrambi** i computer.
- Fai accedere i due host alla stessa rete Tailscale e annota gli indirizzi assegnati:
  - `MAIN_TAIL = 100.84.182.11` → macchina che eseguirà il container `jade-main`.
  - `UI_TAIL   = 100.96.79.2` → macchina che eseguirà il container `user-ui`.
- Apri nei firewall locali le porte `1099`, `7778`, `4000`, `5000`, `5001`, `5002`, `4100`.

Suggerimento: per verificare al volo le variabili, in PowerShell usa `echo $env:MAIN_TAIL`. Ricordati che le variabili impostate con `$env:VAR = "valore"` durano solo per la sessione corrente del terminale.

## Macchina A – JADE Main + agenti di backend (`MAIN_TAIL`)
1. Posizionati nella cartella `JADE-bin-4.6.0/jade/docker`.
2. Prepara (o aggiorna) l'immagine condivisa:
   ```powershell
   docker compose build jade-main
   ```
3. Avvia il Main dichiarando l'IP Tailscale pubblico:
   ```powershell
   $env:PUBLIC_HOST = "100.84.182.11"
   docker compose --profile main up -d jade-main
   ```
   Dashboard del monitor: `http://localhost:4100/monitor` oppure `http://100.84.182.11:4100/monitor`.
4. Avvia gli agenti server-side indicando dove risiede l'interfaccia remota:
   ```powershell
   $env:PUBLIC_HOST = ""        # lascia vuoto: gli agenti useranno i nomi Docker interni
   $env:FRONT_HOST  = "100.96.79.2"
   docker compose --profile agent up -d parser logic query
   ```
5. Dopo modifiche a codice Java/Node ricompila e riavvia i servizi interessati:
   ```powershell
   docker compose build jade-main
   docker compose restart jade-main parser logic query
   ```

## Macchina B – Interfaccia utente + UserAgent (`UI_TAIL`)
1. Copia il progetto (almeno la cartella `JADE-bin-4.6.0/jade`) su questa macchina e porta il terminale in `JADE-bin-4.6.0/jade/docker`.
2. Se l'immagine non è presente localmente, compilala:
   ```powershell
   docker compose build jade-agent
   ```
3. Avvia l'interfaccia remota con il file compose Tailscale e gli IP reali:
   ```powershell
   $env:PUBLIC_HOST = "100.96.79.2"
   $env:MAIN_HOST   = "100.84.182.11"
   $env:QUERY_HOST  = "100.84.182.11"
   docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent up -d user-ui
   ```
   - Web UI locale su `http://localhost:4000`.
   - Accesso via Tailscale da altre macchine su `http://100.96.79.2:4000`.
4. Per arrestare l'interfaccia:
   ```powershell
   docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent down user-ui
   ```

## Verifiche consigliate
- Da Macchina B: `Test-NetConnection -ComputerName 100.84.182.11 -Port 1099` (oppure `nc -vz 100.84.182.11 1099`) deve avere esito positivo.
- La dashboard JADE su `http://100.84.182.11:4100/monitor` deve elencare l'agente `user` dopo l'avvio del container remoto.
- Invia una query dalla UI: la risposta deve arrivare senza timeout (porte `5001/5002` operative).
