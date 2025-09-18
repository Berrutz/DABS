package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.core.behaviours.*;
import jade.proto.SubscriptionInitiator;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.*;
import java.util.regex.*;

import org.jpl7.Query;
import org.jpl7.Term;

import utils.LLMService;

public class LogicAgent extends Agent {

    private AID queryAgent = null;
    private final String KB_FILE = "web-ui/kb/knowledge.pl";
    private final Set<String> dynamicPredicates = new HashSet<>();

    protected void setup() {

        System.out.println("🟢 LogicAgent ready");

        // Register to the DF as "logic"
        ServiceDescription sd = new ServiceDescription();
        sd.setType("logic");
        sd.setName(getLocalName());

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
            System.out.println("✅ LogicAgent registered in DF");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        
        // === SWI-Prolog initialization ===
        try {
            String consult = "consult('" + KB_FILE + "')";
            Query q = new Query(consult);
            System.out.println("🧠 Loading knowledge.pl: " + (q.hasSolution() ? "OK" : "FAILED"));
        } catch (org.jpl7.PrologException pe) {
            String msg = pe.getMessage();
            System.err.println("PrologException (query): " + msg);
            if (msg != null && msg.toLowerCase().contains("existence_error")) {
                sendAnswerToFrontend("I don't know. No information in the knowledge base.");
            } else {
                sendAnswerToFrontend("I don't know. Unable to evaluate the question.");
            }
        } catch (Exception e) {
            System.err.println("❌ Error during Prolog consult: " + e.getMessage());
        }

        
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.MatchPerformative(ACLMessage.REQUEST));
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

        
        addBehaviour(new CyclicBehaviour() {
            public void action() {
                ACLMessage msg = receive(MessageTemplate.MatchPerformative(ACLMessage.INFORM));
                if (msg != null) {
                    String content = msg.getContent();
                    System.out.println("📩 Message received by LogicAgent: " + content);

                    // Logical Prasing and valid inputs
                    handleInput(content);

                    System.out.println("✅ LogicAgent ready to receive a new message...");

                } else {
                    block();
                }
            }
        });
    }

    private void handleInput(String input) {
        // Extract expected type if present as prefix: ##TYPE:<type>## <formula>
        String expectedType = "auto";
        input = input == null ? "" : input.trim();
        if (input.startsWith("##TYPE:")) {
            int end = input.indexOf("##", 7);
            if (end > 7) {
                expectedType = input.substring(7, end).trim().toLowerCase();
                input = input.substring(end + 2).trim();
            }
        }

        // Base sanitization: remove code fences/backticks and extra spaces
        input = input.replace("```prolog", "").replace("```", "").replace("`", "").trim();

        // 1) Remove single-line Prolog comments: everything after % until newline
        input = input.replaceAll("(?m)%.*$", "");

        // 2) Normalize whitespace to a single line
        input = input.replaceAll("\n|\r", " ").replaceAll("\\s+", " ").trim();

        // 3) If final dot is missing, add it for robustness
        int dot = input.indexOf('.') ;
        if (dot >= 0) {
            input = input.substring(0, dot + 1).trim();
        } else {
            input = input + ".";
        }

        if (input.startsWith("?-")) {
            
            String cleaned = input.substring(2).trim(); // rimuove "?-"
            if (cleaned.endsWith(".")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1).trim(); // rimuove il punto finale
            }
            System.out.println("🧹 Query sanificata: " + cleaned);
            handleQuery(cleaned);
            return;
        }

        
        String cleaned = input.substring(0, input.length() - 1).trim();
        System.out.println("🧹 Clausola sanificata: " + cleaned);

        
        if (indexOfTopLevel(cleaned, ":-") >= 0) {
            handlePrologRule(cleaned);
        } else if (hasTopLevelComma(cleaned)) {
           
            if ("fact".equals(expectedType)) {
                if (hasVariables(cleaned)) {
                    System.out.println("⚠️ Input marcato come FATTO ma contiene variabili/congiunzione: scarto → " + cleaned);
                    return;
                }
                System.out.println("ℹ️ Input FATTO con lista di fatti multipli: assert multipli");
                for (String part : splitTopLevelByComma(cleaned)) {
                    String fact = part.trim();
                    if (fact.isEmpty()) continue;
                    if (indexOfTopLevel(fact, ":-") >= 0) {
                        System.out.println("⚠️ Parti con ':-' non sono fatti: salto → " + fact);
                        continue;
                    }
                    handlePrologFact(fact);
                }
            } else if (hasVariables(cleaned)) {
                
                System.out.println("ℹ️ Rilevata congiunzione di goal con variabili: eseguo come query");
                handleQuery(cleaned);
            } else {
                
                System.out.println("ℹ️ Rilevata lista di fatti multipli: eseguo assert multipli");
                for (String part : splitTopLevelByComma(cleaned)) {
                    String fact = part.trim();
                    if (fact.isEmpty()) continue;
                    if (indexOfTopLevel(fact, ":-") >= 0) {
                        System.out.println("⚠️ Parti con ':-' non sono fatti: salto → " + fact);
                        continue;
                    }
                    handlePrologFact(fact);
                }
            }
        } else {
            handlePrologFact(cleaned);
        }
    }

    
    private boolean hasTopLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) return true;
        }
        return false;
    }

    
    private int indexOfTopLevel(String s, String token) {
        int depth = 0;
        for (int i = 0; i <= s.length() - token.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth = Math.max(0, depth - 1); continue; }
            if (depth == 0 && s.startsWith(token, i)) return i;
        }
        return -1;
    }

    
    private boolean hasVariables(String s) {
        return java.util.regex.Pattern.compile("\\b([A-Z_][A-Za-z0-9_]*)\\b").matcher(s).find();
    }

    
    private java.util.List<String> splitTopLevelByComma(String s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) parts.add(s.substring(start));
        return parts;
    }

    private void handlePrologFact(String fact) {
        declareDynamic(fact);
        String prologCmd = "assertz(" + fact + ")";
        System.out.println("🛠 Final Prolog command: " + prologCmd);
        try {
            Query q = new Query(prologCmd);
            if (q.hasSolution()) {
                System.out.println("📚 Fact added to Prolog: " + fact);
                appendToKnowledgeFile(fact + ".");
                // Notify the frontend that the fact was inserted correctly
                sendAnswerToFrontend("✅ Fact inserted: " + fact + ".");
            } else {
                System.out.println("⚠️ Prolog did not accept the fact.");
                sendAnswerToFrontend("❌ Error: the fact was not accepted.");
            }
        } catch (Exception e) {
            System.err.println("❌ Prolog error (fact): " + e.getMessage());
            sendAnswerToFrontend("❌ Error while inserting the fact.");
        }
    }
    

    private void handlePrologRule(String rule) {
        declareDynamic(rule);
        String prologCmd = "assertz((" + rule + "))";
        try {
            Query q = new Query(prologCmd);
            if (q.hasSolution()) {
                System.out.println("📚 Rule added to Prolog: " + rule);
                appendToKnowledgeFile(rule + ".");
                // Notify the frontend that the rule was inserted correctly
                sendAnswerToFrontend("✅ Rule inserted: " + rule + ".");
            } else {
                System.out.println("⚠️ Prolog did not accept the rule.");
                sendAnswerToFrontend("❌ Error: the rule was not accepted.");
            }
        } catch (Exception e) {
            System.err.println("❌ Prolog error (rule): " + e.getMessage());
            sendAnswerToFrontend("❌ Error while inserting the rule.");
        }
    }

    private void handleQuery(String query) {
        System.out.println("🔍 Running query: " + query);
        try {
            Query q = new Query(query);
            if (q.hasSolution()) {
                Map<String, Term>[] solutions = q.allSolutions();
                String logicResult;

                if (solutions.length > 0 && !solutions[0].isEmpty()) {
                    StringBuilder result = new StringBuilder();
                    for (Map<String, Term> solution : solutions) {
                        for (String var : solution.keySet()) {
                            result.append(var)
                                .append(" = ")
                                .append(solution.get(var))
                                .append("; ");
                        }
                    }
                    logicResult = result.toString().trim();
                } else {
                    
                    logicResult = "✅ The query is true.";
                }

                System.out.println("✅ Logical result: " + logicResult);

                
                LLMService.humanizeAnswer(logicResult, query, new LLMService.LLMCallback() {
                    @Override
                    public void onSuccess(String result) {
                        sendAnswerToFrontend(result);
                    }

                    @Override
                    public void onError(String error) {
                        System.err.println("⚠️ LLM error: " + error);
                        sendAnswerToFrontend("⚠️ Error generating the natural-language answer.");
                    }
                });

            } else {
                // ❌ No answer
                sendAnswerToFrontend("❌ There is no information in the knowledge base to answer this question.");
            }
        } catch (Exception e) {
            System.err.println("❌ Prolog error (query): " + e.getMessage());
            sendAnswerToFrontend("❌ There is no information in the knowledge base to answer this question.");
        }
    }


    private void sendAnswerToFrontend(String response) {
        String host = System.getenv().getOrDefault("FRONT_HOST", "127.0.0.1");
        int port; try { port = Integer.parseInt(System.getenv().getOrDefault("FRONT_PORT", "5002")); } catch (Exception e) { port = 5002; }
        try (Socket socket = new Socket(host, port);
             OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream())) {
            writer.write(response + "\n");
            writer.flush();
            System.out.println("📤 Answer sent to the frontend: " + response);
        } catch (IOException e) {
            System.err.println("❌ Error sending to frontend: " + e.getMessage());
        }
    }

    private void declareDynamic(String clause) {
        if (clause == null) {
            return;
        }
        String trimmed = clause.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int ruleSep = indexOfTopLevel(trimmed, ":-");
        if (ruleSep >= 0) {
            trimmed = trimmed.substring(0, ruleSep).trim();
        }
        String name;
        int arity = 0;
        int paren = trimmed.indexOf('(');
        if (paren < 0) {
            name = trimmed;
        } else {
            name = trimmed.substring(0, paren).trim();
            int close = trimmed.lastIndexOf(')');
            if (close > paren) {
                String args = trimmed.substring(paren + 1, close).trim();
                if (!args.isEmpty()) {
                    arity = splitTopLevelByComma(args).size();
                }
            }
        }
        if (name == null || name.isEmpty()) {
            return;
        }
        String key = name + "/" + arity;
        if (!dynamicPredicates.add(key)) {
            return;
        }
        String directive = ":- dynamic " + name + "/" + arity + ".";
        appendToKnowledgeFile(directive);
        try {
            Query q = new Query("dynamic(" + key + ")");
            q.hasSolution();
        } catch (Exception e) {
            System.err.println("[!] Unable to declare predicate dynamic (" + key + "): " + e.getMessage());
        }
    }

    private void appendToKnowledgeFile(String line) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(KB_FILE, true))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[!] Error writing to knowledge.pl: " + e.getMessage());
        }
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println("🔴 LogicAgent terminated.");
    }


}
