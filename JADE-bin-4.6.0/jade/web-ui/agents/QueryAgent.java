package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAException;
import jade.proto.AchieveREInitiator;

import java.io.*;
import java.net.*;

public class QueryAgent extends Agent {
    private final java.util.Map<String, Long> suppressParserUntil = new java.util.HashMap<>();
    private static final long SUPPRESS_MS = 60_000L;
    protected void setup() {
        System.out.println("🟢 QueryAgent started");

        // DF registration
        ServiceDescription sd = new ServiceDescription();
        sd.setType("query");
        sd.setName(getLocalName());

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
            System.out.println("✅ QueryAgent registered in DF");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        
        // Respond to application-level pings from the Monitor
        addBehaviour(new jade.core.behaviours.CyclicBehaviour(this) {
            @Override
            public void action() {
                jade.lang.acl.ACLMessage msg = myAgent.receive(jade.lang.acl.MessageTemplate.MatchPerformative(jade.lang.acl.ACLMessage.REQUEST));
                if (msg == null) { block(); return; }
                String content = msg.getContent() == null ? "" : msg.getContent().trim().toLowerCase();
                if ("ping".equals(content)) {
                    jade.lang.acl.ACLMessage reply = msg.createReply();
                    reply.setPerformative(jade.lang.acl.ACLMessage.INFORM);
                    reply.setContent("pong");
                    send(reply);
                }
            }
        });
        
        // Socket listener on port 5001
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(5001, 0, InetAddress.getByName("0.0.0.0"))) {
                System.out.println("🌐 Waiting for queries on port 5001...");
                while (true) {
                    Socket socket = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String query = reader.readLine();

                    if (query != null && !query.trim().isEmpty()) {
                        System.out.println("🔎 Query received: " + query);
                        addBehaviour(new SendToParserBehaviour(query));
                    }

                    socket.getOutputStream().write("✅ Query received.\n".getBytes());
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private class SendToParserBehaviour extends TickerBehaviour {
        private String input;
        private int attempts = 0;
        private final int maxAttempts = 5;

        public SendToParserBehaviour(String input) {
            super(QueryAgent.this, 2000);
            this.input = input;
        }

        @Override
        protected void onTick() {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("parser");
            template.addServices(sd);

            try {
                DFAgentDescription[] results = DFService.search(myAgent, template);
                if (results.length > 0) {
                    AID parserAID = null;
                    long now = System.currentTimeMillis();
                    for (DFAgentDescription d : results) {
                        AID cand = d.getName();
                        Long until = suppressParserUntil.get(cand.getName());
                        if (until == null || now >= until) { parserAID = cand; break; }
                    }
                    if (parserAID == null) {
                        System.out.println("⏳ All candidate ParserAgents are in temporary backoff. Retrying...");
                        attempts++;
                        if (attempts >= maxAttempts) {
                            System.out.println("❌ Unable to contact ParserAgent after several attempts.");
                            sendErrorToFrontend("❌ Error: ParserAgent unavailable. Please try again later.");
                            stop();
                        }
                        return;
                    }
                    // Ping before sending, to avoid false positives if it's still in DF but unreachable
                    ACLMessage ping = new ACLMessage(ACLMessage.REQUEST);
                    ping.addReceiver(parserAID);
                    ping.setContent("ping");
                    ping.setReplyByDate(new java.util.Date(System.currentTimeMillis() + 1800));

                    final TickerBehaviour tb = this;
                    final AID destAID = parserAID; 
                    addBehaviour(new AchieveREInitiator(myAgent, ping) {
                        private boolean anyInform = false;

                        @Override
                        protected void handleInform(ACLMessage inform) {
                            anyInform = true;
                            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                            msg.addReceiver(destAID);
                            msg.setContent(input);
                            send(msg);
                            System.out.println("📤 Query sent to ParserAgent: " + input);
                            tb.stop();
                        }

                        @Override protected void handleFailure(ACLMessage failure) { attempts++; System.out.println("⚠️ Ping to Parser failed (attempt " + attempts + ")"); suppressParserUntil.put(destAID.getName(), System.currentTimeMillis() + SUPPRESS_MS); }
                        @Override protected void handleRefuse(ACLMessage refuse) { attempts++; System.out.println("⚠️ Ping to Parser refused (attempt " + attempts + ")"); suppressParserUntil.put(destAID.getName(), System.currentTimeMillis() + SUPPRESS_MS); }
                        @Override
                        protected void handleAllResultNotifications(java.util.Vector v) {
                            if (!anyInform) { attempts++; System.out.println("⏱️ Ping timeout Parser (attempt " + attempts + ")"); suppressParserUntil.put(destAID.getName(), System.currentTimeMillis() + SUPPRESS_MS); }
                            if (attempts >= maxAttempts) {
                                System.out.println("❌ Unable to contact ParserAgent after several attempts.");
                                sendErrorToFrontend("❌ Error: ParserAgent unavailable. Please try again later.");
                                tb.stop();
                            }
                        }
                    });
                } else {
                    System.out.println("⌛ ParserAgent not yet available...");
                    attempts++;
                    if (attempts >= maxAttempts) {
                        System.out.println("❌ Unable to contact ParserAgent after several attempts.");
                        sendErrorToFrontend("❌ Error: ParserAgent unavailable. Please try again later.");
                        stop();
                    }
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }

    // Notify errors to the frontend (same channel used by the LogicAgent)
    private void sendErrorToFrontend(String message) {
        String host = System.getenv().getOrDefault("FRONT_HOST", "127.0.0.1");
        int port; try { port = Integer.parseInt(System.getenv().getOrDefault("FRONT_PORT", "5002")); } catch (Exception e) { port = 5002; }
        try (Socket socket = new Socket(host, port);
             OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream())) {
            writer.write(message + "\n");
            writer.flush();
            System.out.println("📤 Error notified to the frontend: " + message);
        } catch (IOException e) {
            System.err.println("❌ Error sending error to the frontend: " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
        System.out.println("QueryAgent deregistered from DF");
        super.takeDown();
    }
}
