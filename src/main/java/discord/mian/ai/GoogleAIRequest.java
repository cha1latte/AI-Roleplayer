package discord.mian.ai;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import io.github.sashirestela.openai.domain.chat.ChatMessage;

import java.util.List;
import java.util.Optional;
import java.lang.reflect.Field;
import java.util.Map;

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
        
        // Try to set the API key in the environment through reflection
        try {
            setEnvironmentVariable("GOOGLE_API_KEY", apiKey);
        } catch (Exception e) {
            // Fallback to system property
            System.setProperty("GOOGLE_API_KEY", apiKey);
        }
        this.client = new Client();
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @SuppressWarnings("unchecked")
    private static void setEnvironmentVariable(String key, String value) throws Exception {
        Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
        Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
        theEnvironmentField.setAccessible(true);
        Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
        env.put(key, value);
        
        Field theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
        theCaseInsensitiveEnvironmentField.setAccessible(true);
        Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
        cienv.put(key, value);
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