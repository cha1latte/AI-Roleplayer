package discord.mian.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
        
        // Create request body for Google AI API using proper JSON building
        ObjectMapper mapper = new ObjectMapper();
        try {
            // Build JSON using Jackson to avoid escaping issues
            var contentMap = Map.of(
                "contents", List.of(Map.of(
                    "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                    "temperature", temperature,
                    "maxOutputTokens", maxTokens
                )
            );
            String requestBody = mapper.writeValueAsString(contentMap);
            
            Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.get("application/json; charset=utf-8")))
                .build();
                
            try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error details";
                discord.mian.Constants.LOGGER.error("Google AI API request failed. Status: " + response.code() + 
                    ", Message: " + response.message() + ", Body: " + errorBody);
                discord.mian.Constants.LOGGER.error("Request URL: " + request.url());
                discord.mian.Constants.LOGGER.error("Request body: " + requestBody);
                throw new IOException("Google AI API request failed: " + response.code() + " " + response.message() + 
                    ". Error: " + errorBody);
            }
            
            String responseBody = response.body().string();
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
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to create request or parse response", e);
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

}