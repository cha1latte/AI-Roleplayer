package discord.mian.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

public class GoogleAIRequest {
    private final String apiKey;
    private final String model;
    private final List<ChatMessage> messages;
    private final double temperature;
    private final int maxTokens;

    public GoogleAIRequest(String apiKey, String model, List<ChatMessage> messages, double temperature, int maxTokens) {
        // Debug logging
        discord.mian.Constants.LOGGER.info("GoogleAIRequest constructor called with apiKey: " + (apiKey != null ? "***SET***" : "NULL"));
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Google AI API key is null or empty");
        }
        
        this.apiKey = apiKey;
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }
    
    public String getModel() {
        return model;
    }

    public GoogleAIResponse generate() throws IOException {
        String prompt = convertMessagesToPrompt();
        
        OkHttpClient client = new OkHttpClient.Builder().build();
        
        // Create request body for Google AI API
        String requestBody = "{"
            + "\"contents\": [{"
            + "\"parts\": [{\"text\": \"" + prompt.replace("\"", "\\\"") + "\"}]"
            + "}],"
            + "\"generationConfig\": {"
            + "\"temperature\": " + temperature + ","
            + "\"maxOutputTokens\": " + maxTokens
            + "}"
            + "}";
        
        Request request = new Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody, MediaType.get("application/json; charset=utf-8")))
            .build();
            
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Google AI API request failed: " + response.code() + " " + response.message());
            }
            
            String responseBody = response.body().string();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonResponse = mapper.readTree(responseBody);
            
            JsonNode candidates = jsonResponse.get("candidates");
            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content = firstCandidate.get("content");
                if (content != null) {
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray() && parts.size() > 0) {
                        String text = parts.get(0).get("text").asText();
                        return new GoogleAIResponse(text);
                    }
                }
            }
            
            throw new IOException("No valid response from Google AI API");
        }
    }
    
    public static class GoogleAIResponse {
        private final String text;
        
        public GoogleAIResponse(String text) {
            this.text = text;
        }
        
        public String text() {
            return text;
        }
    }

    public String convertMessagesToPrompt() {
        StringBuilder prompt = new StringBuilder();
        
        for (ChatMessage message : messages) {
            if (message instanceof ChatMessage.SystemMessage systemMessage) {
                prompt.append("System: ").append(systemMessage.getContent()).append("\n\n");
            } else if (message instanceof ChatMessage.UserMessage userMessage) {
                prompt.append("User: ").append(userMessage.getContent()).append("\n\n");
            } else if (message instanceof ChatMessage.AssistantMessage assistantMessage) {
                prompt.append("Assistant: ").append(assistantMessage.getContent()).append("\n\n");
            }
        }
        
        return prompt.toString().trim();
    }

    public GenerateContentResponse generate() {
        String prompt = convertMessagesToPrompt();
        
        // Use the simple API as shown in the documentation
        return client.models.generateContent(model, prompt, null);
    }

    public Client getClient() {
        return client;
    }

    public String getModel() {
        return model;
    }
}