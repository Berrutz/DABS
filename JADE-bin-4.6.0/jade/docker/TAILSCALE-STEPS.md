# Tailscale Deployment Steps

## Shared prerequisites
- Install Docker Compose v2 and Tailscale on both machines.
- Join both hosts to the same Tailscale network and note the IPs:
  - `MAIN_TAIL` -> Tailscale IP of the machine that will run the JADE main container (example `100.84.182.11`).
  - `UI_TAIL`   -> Tailscale IP of the machine that will host the remote `user-ui` container.
- Allow the following ports through any local firewall: `1099`, `7778`, `4000`, `5000`, `5001`, `5002`, `4100`.

## Machine A - JADE Main + backend agents
1. `cd` into `JADE-bin-4.6.0/jade/docker`.
2. Build (or refresh) the shared image the first time:
   ```powershell
   docker compose build jade-main
   ```
3. Start the JADE Main (advertise its Tailscale IP so remote containers can call back):
   ```powershell
   $env:PUBLIC_HOST = "$MAIN_TAIL"
   docker compose --profile main up -d jade-main
   ```
   The monitor UI becomes reachable at `http://localhost:4100/monitor` or `http://$MAIN_TAIL:4100/monitor`.
4. Launch the backend JADE agents on the same host, telling them where the remote GUI lives for callbacks:
   ```powershell
   $env:PUBLIC_HOST = ""
   $env:FRONT_HOST  = "$UI_TAIL"
   docker compose --profile agent up -d parser logic query
   ```
   (Leave `PUBLIC_HOST` empty here so the containers keep advertising their Docker service names.)
5. When changes are made to the Java/Node sources, rebuild with `docker compose build jade-main` and restart the required services.

## Machine B - Remote GUI + UserAgent
1. Copy the project (or the `JADE-bin-4.6.0/jade` folder) to this machine and `cd` into `JADE-bin-4.6.0/jade/docker`.
2. Build the image if it is not already available locally:
   ```powershell
   docker compose build jade-agent
   ```
3. Start the remote UI/agent using the Tailscale-aware override so ports 1099/7778 are exposed back to the host:
   ```powershell
   $env:PUBLIC_HOST = "$UI_TAIL"
   $env:MAIN_HOST   = "$MAIN_TAIL"
   $env:QUERY_HOST  = "$MAIN_TAIL"
   docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent up -d user-ui
   ```
   - The web UI is available at `http://localhost:4000` on Machine B (and at `http://$UI_TAIL:4000` for others on Tailscale).
   - `MAIN_HOST` tells the UserAgent where to reach the JADE Main.
   - `QUERY_HOST` directs the Node.js frontend to the QueryAgent socket that is exposed on Machine A (port `5001`).
4. To stop the UI after use:
   ```powershell
   docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent down user-ui
   ```

## Verification checklist
- From Machine B, `Test-NetConnection -ComputerName $MAIN_TAIL -Port 1099` (or `nc -vz $MAIN_TAIL 1099`) succeeds.
- The JADE monitor at `http://$MAIN_TAIL:4100/monitor` lists the remote `user` container once it is running.
- Sending a query from the UI reaches the QueryAgent, and answers are pushed back on port `5002` without timeouts.
