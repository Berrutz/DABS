package utils;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.proto.AchieveREInitiator;

import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.SearchConstraints;

import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MonitorAgent (compat, without ontologies):
 * - Periodic DF polling to discover new agents
 * - Application-level ping (REQUEST "ping") to keep the GUI alive and remove dead agents
 * - Replies to incoming pings with INFORM "pong"
 */
public class MonitorAgent extends Agent {

    private static final long DF_RESYNC_PERIOD_MS = 10_000L; // DF rescan every 10s
    private static final long PING_PERIOD_MS      = 10_000L; // ping every 10s
    private static final int  PING_TIMEOUT_MS     = 4_000;   // single ping timeout

    private final Map<String, AgentInfo> knownAgents = new ConcurrentHashMap<>();
    // Avoid loops: after repeated FAIL/Timeout, ignore the agent for a while.
    private final Map<String, Long> suppressUntil = new ConcurrentHashMap<>();
    private static final long SUPPRESS_AFTER_FAIL_MS = 60_000L; // 60s backoff
    private final String monitorUrl = "http://localhost:" +
            System.getenv().getOrDefault("MONITOR_PORT", "4100") + "/df-event";

    private static class AgentInfo {
        final AID aid;
        volatile long lastSeenTs;
        volatile boolean lastPingOk;
        volatile int failCount;
        AgentInfo(AID aid, long ts) {
            this.aid = aid;
            this.lastSeenTs = ts;
            this.lastPingOk = false;
            this.failCount = 0;
        }
    }

    @Override
    protected void setup() {
        log("MonitorAgent started (compat, without ontologies)");

            addBehaviour(new TickerBehaviour(this, DF_RESYNC_PERIOD_MS) {
            @Override
            protected void onTick() {
                try { fullDfRescan(); } catch (Exception e) { logErr("Error in fullDfRescan: " + e.getMessage(), e); }
            }
        });

        
        addBehaviour(new TickerBehaviour(this, PING_PERIOD_MS) {
            @Override
            protected void onTick() { pingAllKnownAgents(); }
        });

        
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
                ACLMessage msg = myAgent.receive(mt);
                if (msg == null) { block(); return; }
                String content = msg.getContent() == null ? "" : msg.getContent().trim().toLowerCase();
                if ("ping".equals(content)) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent("pong");
                    send(reply);
                }
            }
        });
    }

    @Override
    protected void takeDown() {
        log("MonitorAgent shutting down");
        super.takeDown();
    }

    // DF discovery (polling, no ontologies required)
    private void fullDfRescan() throws FIPAException {
        DFAgentDescription template = new DFAgentDescription();
        SearchConstraints sc = new SearchConstraints();
        sc.setMaxResults(-1L);

        DFAgentDescription[] found = DFService.search(this, template, sc);

        Set<String> current = Collections.newSetFromMap(new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();

        for (DFAgentDescription dfd : found) {
            if (dfd.getName() == null) continue;
            String name = dfd.getName().getName();
            if (name.equalsIgnoreCase(getAID().getName())) continue; // ignore itself
            if (name.startsWith("ams@") || name.startsWith("df@") || name.startsWith("rma@") || name.startsWith("monitor@"))
                continue; // ignore platform services
            // suppress (cooldown) if recently removed due to repeated FAILs
            Long until = suppressUntil.get(name);
            if (until != null) {
                if (now < until) {
                    continue; // still suppressed
                } else {
                    suppressUntil.remove(name);
                }
            }
            current.add(name);

            AgentInfo old = knownAgents.get(name);
            if (old == null) {
                
                log("Rilevato in DF: " + name + " (probe)");
                probeAndMaybeAdd(dfd.getName());
            } else {
                old.lastSeenTs = now;
            }
        }

        // Remove those no longer present in DF (best-effort)
        for (String k : knownAgents.keySet()) {
            if (!current.contains(k)) {
                knownAgents.remove(k);
                log("Agent removed (not in DF): " + k);
                sendDfEvent("DEREGISTER", k, null);
            }
        }

        log("DF scan: " + knownAgents.size() + " known agents");
    }

    // Ping best-effort
    private void pingAllKnownAgents() {
        for (AgentInfo info : knownAgents.values()) {
            ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
            req.addReceiver(info.aid);
            req.setContent("ping");
            req.setReplyByDate(new java.util.Date(System.currentTimeMillis() + PING_TIMEOUT_MS));

            addBehaviour(new AchieveREInitiator(this, req) {
                @Override
                protected void handleInform(ACLMessage inform) {
                    info.lastPingOk = true;
                    info.lastSeenTs = System.currentTimeMillis();
                    info.failCount = 0;
                    log("PING OK vs " + pretty(info.aid));
                    sendDfEvent("REGISTER", info.aid.getName(), null); // keep-alive per la UI
                }

                @Override
                protected void handleFailure(ACLMessage failure) {
                    info.lastPingOk = false;
                    info.failCount++;
                    log("PING FAIL vs " + pretty(info.aid) + " (" + info.failCount + ")");
                    maybeDrop(info);
                }

                @Override
                protected void handleRefuse(ACLMessage refuse) {
                    info.lastPingOk = false;
                    info.failCount++;
                    log("PING REFUSE vs " + pretty(info.aid) + " (" + info.failCount + ")");
                    maybeDrop(info);
                }

                @Override
                protected void handleAllResultNotifications(java.util.Vector notifications) {
                    boolean anyInform = false;
                    if (notifications != null) {
                        for (Object o : notifications) {
                            if (o instanceof ACLMessage) {
                                ACLMessage m = (ACLMessage) o;
                                if (m.getPerformative() == ACLMessage.INFORM) { anyInform = true; break; }
                            }
                        }
                    }
                    if (!anyInform) {
                        info.lastPingOk = false;
                        info.failCount++;
                        log("PING TIMEOUT vs " + info.aid.getName() + " (" + info.failCount + ")");
                        maybeDrop(info);
                    }
                }
            });
        }
    }

    private void maybeDrop(AgentInfo info) {
        final int MAX_FAILS = 3;
        if (info.failCount >= MAX_FAILS) {
            String name = info.aid.getName();
            if (knownAgents.remove(name) != null) {
                log("Removed due to repeated ping failures: " + name);
                sendDfEvent("DEREGISTER", name, null);
                // Suppress future re-add from DF for a while
                suppressUntil.put(name, System.currentTimeMillis() + SUPPRESS_AFTER_FAIL_MS);
            }
        }
    }

    private void probeAndMaybeAdd(AID aid) {
        final String name = aid.getName();
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.addReceiver(aid);
        req.setContent("ping");
        req.setReplyByDate(new java.util.Date(System.currentTimeMillis() + PING_TIMEOUT_MS));

        addBehaviour(new AchieveREInitiator(this, req) {
            @Override
            protected void handleInform(ACLMessage inform) {
                long now = System.currentTimeMillis();
                AgentInfo prev = knownAgents.putIfAbsent(name, new AgentInfo(aid, now));
                AgentInfo info = prev != null ? prev : knownAgents.get(name);
                info.lastPingOk = true;
                info.lastSeenTs = now;
                info.failCount = 0;
                    log("PING OK (probe) vs " + pretty(aid));
                    sendDfEvent("REGISTER", name, null);
            }

            @Override
            protected void handleFailure(ACLMessage failure) {
                log("PING FAIL (probe) vs " + pretty(aid));
                suppressUntil.put(name, System.currentTimeMillis() + SUPPRESS_AFTER_FAIL_MS);
            }

            @Override
            protected void handleAllResultNotifications(java.util.Vector notifications) {
                boolean anyInform = false;
                if (notifications != null) {
                    for (Object o : notifications) {
                        if (o instanceof ACLMessage) {
                            ACLMessage m = (ACLMessage) o;
                            if (m.getPerformative() == ACLMessage.INFORM) { anyInform = true; break; }
                        }
                    }
                }
                if (!anyInform) {
                    log("PING TIMEOUT (probe) vs " + name);
                    suppressUntil.put(name, System.currentTimeMillis() + SUPPRESS_AFTER_FAIL_MS);
                }
            }
        });
    }

    // HTTP notify to Monitor UI
    private void sendDfEvent(String type, String name, String clazz) {
        try {
            URL url = new URL(monitorUrl);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            String payload = String.format(
                    "{\"type\":\"%s\",\"name\":\"%s\",\"clazz\":\"%s\",\"when\":%d}",
                    type, name, clazz == null ? "" : clazz, System.currentTimeMillis());
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = con.getOutputStream()) { os.write(out); }
            con.getInputStream().close();
            con.disconnect();
        } catch (Exception e) {
            logErr("Errore HTTP df-event: " + e.getMessage(), e);
        }
    }

    // Utility
    private void log(String s) { System.out.println("[MonitorAgent] " + s); }
    private void logErr(String s, Throwable t) { System.err.println("[MonitorAgent][ERR] " + s); if (t != null) t.printStackTrace(System.err); }
    private static String pretty(AID aid) { return (aid == null ? "<null>" : aid.getName()); }
}
