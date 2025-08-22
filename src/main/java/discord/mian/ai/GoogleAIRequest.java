package discord.mian.ai;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import io.github.sashirestela.openai.domain.chat.ChatMessage;

import java.util.List;
import java.util.Optional;

public class GoogleAIRequest {
    private final Client client;
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
        
        // Create client with API key using the complex constructor
        this.client = new Client(
            Optional.of(apiKey),           // API key
            Optional.empty(),              // Base URL (use default)
            Optional.empty(),              // Model (use default)
            Optional.empty(),              // Google credentials (not needed with API key)
            Optional.empty(),              // HTTP options (use default)
            Optional.empty(),              // Debug (use default)
            Optional.empty(),              // Debug config (use default)
            Optional.empty()               // Headers (use default)
        );
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
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