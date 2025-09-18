package utils;

import okhttp3.*;
import org.json.*;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class LLMService {
    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final String API_KEY = "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"; // Replace with your key
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String KB_FILE = "web-ui/kb/knowledge.pl";

    // Callback to handle async response
    public interface LLMCallback {
        void onSuccess(String result);
        void onError(String error);
    }

    private static java.util.Set<String> parsePredicateSet(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null || s.trim().isEmpty()) return out;
        for (String part : s.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String enforceAllowedPredicates(String content, String type, java.util.Set<String> allowed) {
        try {
            String raw = content.trim();
            String noDot = raw.endsWith(".") ? raw.substring(0, raw.length() - 1) : raw;
            String clause = noDot.startsWith("?-") ? noDot.substring(2).trim() : noDot;

            java.util.Set<String> used = extractFunctorsWithArity(clause);
            boolean anyUnknown = false;
            for (String u : used) {
                if (!allowed.contains(u)) { anyUnknown = true; break; }
            }

            if (anyUnknown) {
                if ("query".equalsIgnoreCase(type)) {
                    return "?- fail."; 
                } else {
                    
                    return content;
                }
            }
            return content;
        } catch (Exception e) {
            return content;
        }
    }

    private static java.util.Set<String> extractFunctorsWithArity(String clause) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (clause == null) return out;
        
        java.util.regex.Pattern rp = java.util.regex.Pattern.compile("([a-z][a-z0-9_]*)\\s*\\(([^)]*)\\)");
        java.util.regex.Matcher m = rp.matcher(clause);
        while (m.find()) {
            String functor = m.group(1);
            String args = m.group(2).trim();
            int arity = 0;
            if (!args.isEmpty()) {
               
                arity = 1;
                for (int i = 0, depth = 0; i < args.length(); i++) {
                    char c = args.charAt(i);
                    if (c == '(') depth++;
                    else if (c == ')') depth = Math.max(0, depth - 1);
                    else if (c == ',' && depth == 0) arity++;
                }
            }
            out.add(functor + "/" + arity);
        }
        return out;
    }

    public static String readKnowledgeAndExtractPredicates(String kbPath) {
        StringBuilder kbContent = new StringBuilder();
        Set<String> predicates = new HashSet<>();

        try (Scanner scanner = new Scanner(new File(kbPath))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty() || line.startsWith("%")) continue;

                kbContent.append(line).append("\n");

                
                Pattern p = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\(([^)]*)\\)");
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String functor = m.group(1);
                    int arity = m.group(2).split(",").length;
                    predicates.add(functor + "/" + arity);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error while reading knowledge.pl: " + e.getMessage());
        }

        System.out.println("📘 Current knowledge (knowledge.pl):");
        System.out.println(kbContent);

        return String.join(", ", predicates);
    }

    public static String readKBPredicatesSmart() {
        return readKnowledgeAndExtractPredicates(KB_FILE);
    }

    public static void humanizeAnswer(String logicResult, String queryText, LLMCallback callback) {

        String prompt = "You are a Prolog assistant that responds in English. " +
                "I will provide you with a logic query and the evaluation result. " +
                "Write a natural and short answer in English that directly expresses the meaning of the result. " +
                "Do not explain concepts of variables, do not mention letters like X or Y, " +
                "do not talk about substitutions or assignments. " +
                "Use the terms of the query to form a clear and direct sentence, " +
                "following Prolog logic (for example, predicates like doctor/1 or works_at/2 must be transformed into natural sentences). " +
                "Respond only with the final sentence, without adding explanations or comments.\n\n" +
                "Logic query: \"" + queryText + "\"\n" +
                "Logical result: \"" + logicResult + "\"\n\n" +
                "Provide the shortest Answer possible:";


        String jsonBody = "{\n" +
                "  \"model\": \"mistralai/mistral-7b-instruct\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"" + prompt.replace("\"", "\\\"") + "\"}\n" +
                "  ]\n" +
                "}";

        RequestBody body = RequestBody.create(JSON, jsonBody);

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Network error in LLM request: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("LLM API error: " + response.code() + " - " + response.body().string());
                    return;
                }

                String responseBody = response.body().string();

                Pattern pattern = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
                Matcher matcher = pattern.matcher(responseBody);
                if (matcher.find()) {
                    String rawContent = matcher.group(1)
                            .replace("\\n", " ")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .trim();
                    callback.onSuccess(rawContent);
                } else {
                    callback.onError("❌ No interpretable answer from the model.");
                }
            }
        });
    }

    public static void translateToLogic(String input, String type, LLMCallback callback) {

        // Use the same path as LogicAgent for consistency in the container (/app as cwd)
        String availablePredicates = readKBPredicatesSmart();
        Set<String> allowed = parsePredicateSet(availablePredicates);

        String prompt = "";
        if ("fact".equalsIgnoreCase(type)) {

            prompt = "You translate natural language into ONE Prolog fact.\n" +
            "STRICT RULES:\n" +
            "- Output EXACTLY ONE FACT (not a rule), one line, end with a period.\n" +
            "- Use ONLY predicates from the allowed list. Do NOT invent new predicates.\n" +
            "- Use only lowercase atoms and constants. DO NOT USE VARIABLES (no tokens starting with uppercase or underscore).\n" +
            "- Forbidden: multiple clauses; rules with ':-'; headless conjunctions; JSON; quotes; comments; anything after the first period.\n" +
            "- If the sentence cannot be expressed with the allowed predicates, output exactly: NONE.\n\n" +
            "Allowed predicates (functor/arity): " + availablePredicates + "\n" +
            "Sentence: " + input + "\n" +
            "Answer: (only the fact)";

        
        } else if ("query".equalsIgnoreCase(type)) {

            prompt = "You translate natural language into ONE Prolog query.\n" +
                "STRICT RULES:\n" +
                "- Output EXACTLY ONE QUERY, one line, starting with ?- and ending with a period.\n" +
                "- Use ONLY predicates from the allowed list. Do NOT invent new predicates or synonyms.\n" +
                "- No comments, no code fences, no explanations.\n" +
                "- If the question cannot be expressed using ONLY the allowed predicates, output exactly: ?- fail.\n\n" +
                "Allowed predicates (functor/arity): " + availablePredicates + "\n" +
                "Question: " + input + "\n" +
                "Answer: (only the query)";

        } else {
            callback.onError("Unknown LLM request type: " + type);
            return;
        }

        String jsonBody = "{\n" +
                "  \"model\": \"mistralai/mistral-7b-instruct\",\n" +
                "  \"temperature\": 0.1,\n" +
                "  \"max_tokens\": 128,\n" +
                "  \"stop\": [\"\\n\", \"%\", \"```\"],\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"system\", \"content\": \"Follow the rules strictly. Use ONLY the allowed predicates. Output exactly one Prolog item (fact or query) as requested. No comments or explanations.\"},\n" +
                "    {\"role\": \"user\", \"content\": \"" + prompt.replace("\"", "\\\"") + "\"}\n" +
                "  ]\n" +
                "}";

        RequestBody body = RequestBody.create(JSON,jsonBody);

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Errore di rete: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("Risposta API non valida: " + response.code() + " - " + response.body().string());
                    return;
                }

                String responseBody = response.body().string();

                try {
                    JSONObject root = new JSONObject(responseBody);
                    JSONObject msg = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                    String content = msg.getString("content").trim();

                    String patched = enforceAllowedPredicates(content, type, allowed);
                    if (!patched.equals(content)) {
                        msg.put("content", patched);
                        callback.onSuccess(root.toString());
                    } else {
                        callback.onSuccess(responseBody);
                    }
                } catch (Exception ex) {
                    callback.onSuccess(responseBody);
                }
            }
        });
    }
}
