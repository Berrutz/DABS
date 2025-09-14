package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.domain.DFService;
import jade.proto.SubscriptionInitiator;
import jade.proto.AchieveREInitiator;

import okhttp3.*;
import utils.LLMService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Date;


public class ParserAgent extends Agent {
    // Avoid retrying logic AIDs known to be unreachable for a short period
    private final java.util.Map<String, Long> suppressLogicUntil = new java.util.HashMap<>();
    private static final long SUPPRESS_MS = 60_000L; // 60s backoff for unreachable AIDs

    protected void setup() {
        System.out.println("🟢 ParserAgent started");

        // DF registration
        ServiceDescription sd = new ServiceDescription();
        sd.setType("parser");
        sd.setName(getLocalName());

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
            System.out.println("✅ ParserAgent registered in DF");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // Respond to application-level pings from the Monitor
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(jade.lang.acl.MessageTemplate.MatchPerformative(ACLMessage.REQUEST));
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

        // Behaviour to receive messages (excludes ping replies: content = "pong")
        addBehaviour(new CyclicBehaviour() {
            private final jade.lang.acl.MessageTemplate MT = jade.lang.acl.MessageTemplate.and(
                    jade.lang.acl.MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    jade.lang.acl.MessageTemplate.not(jade.lang.acl.MessageTemplate.MatchContent("pong"))
            );

            public void action() {
                ACLMessage msg = receive(MT);
                if (msg != null) {

                    String input = msg.getContent();
                    String sender = msg.getSender().getLocalName();
                    String s = sender == null ? "" : sender.toLowerCase();
        
                    System.out.println("📩 Message received from: " + sender + " → " + input);

                    // Distinguish between UserAgent and QueryAgent
                    if (s.equals("user") || s.startsWith("user-")) {
                        handleUserMessage(input, "fact");
                    } else if (s.equals("query") || s.startsWith("query-")) {
                        handleUserMessage(input, "query");
                    } else {
                        System.out.println("⚠️ Unknown sender. Treated as fact.");
                        // TODO: handle other input types
                    }


                    System.out.println("-------------------");
                    System.out.println("✅ ParserAgent ready to receive a new message...");

                } else {
                    block();
                }
            }
        });
    }

    private void handleUserMessage(String userInput,String type) {

        LLMService.translateToLogic(userInput,type, new LLMService.LLMCallback() {
            public void onSuccess(String result) {
                try {
                    JSONObject root = new JSONObject(result);
                    String content = root
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim();
                    
                    // add ?- if is a query and if the LLM did not insert it
                    if(type.equalsIgnoreCase("query") && !content.contains("?-")){
                        content = "?- " + content;
                    }

                    System.out.println("✔ Formula logic obtained: " + content);
    
                    // TODO: Send formula to LogicAgent with retry every 2 seconds
                    sendToLogicAgent(content, type);

    
                } catch (JSONException e) {
                    System.err.println("❌ Error parsing JSON: " + e.getMessage());
                }
            }

            public void onError(String error) {
                System.err.println("❌ Error LLM: " + error);
            }
        });
    }


    private void sendToLogicAgent(String formula, String type) {
        addBehaviour(new TickerBehaviour(this, 2000) {
            private int attempts = 0;
            private final int maxAttempts = 5;
            private boolean inFlight = false;

            protected void onTick() {
                if (inFlight) {
                    return; 
                }

                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("logic");
                template.addServices(sd);
    
                try {
                    DFAgentDescription[] result = DFService.search(myAgent, template);
                    if (result.length > 0) {
                        
                        AID logicAgentAID = null;
                        long now = System.currentTimeMillis();
                        for (DFAgentDescription d : result) {
                            AID cand = d.getName();
                            Long until = suppressLogicUntil.get(cand.getName());
                            if (until == null || now >= until) { logicAgentAID = cand; break; }
                        }
                        if (logicAgentAID == null) {
                            System.out.println("⏳ Tutti i LogicAgent candidati sono in backoff temporaneo. Riprovo...");
                            attempts++;
                            if (attempts >= maxAttempts) {
                                System.out.println("❌ Impossibile contattare un LogicAgent dopo vari tentativi.");
                                notifyFrontend("❌ Errore: LogicAgent non disponibile. Impossibile completare la richiesta.");
                                stop();
                            }
                            return;
                        }
                        System.out.println("➡️ Destinatario LogicAgent: " + logicAgentAID.getName());

                        // First check reachability with a ping (REQUEST→INFORM)
                        ACLMessage ping = new ACLMessage(ACLMessage.REQUEST);
                        ping.addReceiver(logicAgentAID);
                        ping.setContent("ping");
                        ping.setReplyByDate(new Date(System.currentTimeMillis() + 1800));

                        inFlight = true;
                        final TickerBehaviour tb = this;
                        final AID destAID = logicAgentAID; 
                        addBehaviour(new AchieveREInitiator(myAgent, ping) {
                            private boolean anyInform = false;

                            @Override
                            protected void handleInform(ACLMessage inform) {
                                anyInform = true;
                                String prefix = "##TYPE:" + (type == null ? "auto" : type.toLowerCase()) + "## ";
                                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                                msg.addReceiver(destAID);
                                msg.setContent(prefix + formula);
                                send(msg);
                                System.out.println("📤 Formula sent to LogicAgent: " + prefix + formula);
                                System.out.println("✅ ParserAgent ready to receive a new message...");
                                inFlight = false;
                                tb.stop(); // Ferma il TickerBehaviour
                            }

                            @Override
                            protected void handleFailure(ACLMessage failure) {
                                attempts++;
                                System.out.println("⚠️ Ping to LogicAgent failed (attempt " + attempts + ")");
                                suppressLogicUntil.put(destAID.getName(), System.currentTimeMillis() + SUPPRESS_MS);
                            }

                            @Override
                            protected void handleRefuse(ACLMessage refuse) {
                                attempts++;
                                System.out.println("⚠️ Ping to LogicAgent refused (attempt " + attempts + ")");
                                suppressLogicUntil.put(destAID.getName(), System.currentTimeMillis() + SUPPRESS_MS);
                            }

                            @Override
                            protected void handleAllResultNotifications(java.util.Vector notifications) {
                                // Se nessun INFORM ricevuto, considera timeout
                                if (!anyInform) {
                                    attempts++;
                                    System.out.println("⏱️ Ping timeout LogicAgent (attempt " + attempts + ")");
                                    suppressLogicUntil.put(destAID.getName(), System.currentTimeMillis() + SUPPRESS_MS);
                                }
                                if (attempts >= maxAttempts) {
                                    System.out.println("❌ Unable to contact LogicAgent after several attempts.");
                                    notifyFrontend("❌ Error: LogicAgent unavailable. Cannot complete the request.");
                                    tb.stop();
                                }
                                inFlight = false;
                            }
                        });
                    } else {
                        System.out.println("🔁 LogicAgent not found, retrying...");
                        attempts++;
                        if (attempts >= maxAttempts) {
                            System.out.println("❌ Unable to contact LogicAgent after several attempts.");
                            notifyFrontend("❌ Error: LogicAgent unavailable. Cannot complete the request.");
                            stop();
                        }
                    }
                } catch (FIPAException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // Notify errors to the frontend (reuses channel 5002)
    private void notifyFrontend(String message) {
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
        System.out.println("ParserAgent deregistered from DF");
        super.takeDown();
    }
}
