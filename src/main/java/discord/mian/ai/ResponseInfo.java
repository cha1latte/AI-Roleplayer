package discord.mian.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import discord.mian.Constants;
import discord.mian.api.PromptInfo;
import discord.mian.api.ProviderInfo;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Iterator;
import java.util.Optional;
import java.util.function.Function;

public class ResponseInfo implements PromptInfo, ProviderInfo {
    private final String provider;
    private final String model;
    private String response;
    private final String sent;
    private final Optional<Integer> promptTokens;
    private final Optional<Integer> completionTokens;

    public ResponseInfo(String model, String provider, String response, Integer promptTokens, Integer completionTokens, String sent) {
        this.provider = provider;
        this.model = model;
        this.response = response;
        this.promptTokens = Optional.ofNullable(promptTokens);
        this.completionTokens = Optional.ofNullable(completionTokens);
        this.sent = sent;
    }

    public Optional<Integer> getCompletionTokens() {
        return completionTokens;
    }

    public Optional<Integer> getPromptTokens() {
        return promptTokens;
    }

    public int getTotalTokens() {
        return completionTokens.orElse(0) + promptTokens.orElse(0);
    }

    public String getModel() {
        return model != null && !model.isEmpty() ? model : "Unknown!";
    }

    public String getProvider() {
        return provider != null && !provider.isEmpty() ? provider : "Unknown!";
    }

    public String getResponse() {
        return response != null && !response.isEmpty() ? response : "No response!";
    }

    public String editResponse(Function<String, String> edit) {
        this.response = edit.apply(response);
        return response;
    }

    public String getPrompt() {
        return sent;
    }

    public Double getPrice() {
        try {
            // Handle Google AI models differently from OpenRouter models
            if (provider != null && provider.equals("Google AI")) {
                return calculateGoogleAIPrice();
            }
            
            // OpenRouter pricing logic
            String author = model.substring(0, model.indexOf("/"));
            String slug = model.substring(model.indexOf("/") + 1);

            OkHttpClient client = new OkHttpClient.Builder().build();

            Request request = new Request.Builder()
                    .url("https://openrouter.ai/api/v1/models/" + author + "/" + slug + "/endpoints")
                    .get()
                    .build();

            Call call = client.newCall(request);
            try (Response response = call.execute()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode node = mapper.readTree(response.body().string());
                JsonNode dataNode = node.get("data").get("endpoints");

                for (Iterator<JsonNode> it = dataNode.values(); dataNode.values().hasNext(); ) {
                    JsonNode endpoint = it.next();
                    String name = endpoint.get("name").asText();
                    name = name.substring(0, name.indexOf("|") - 1);

                    if (name.equals(provider)) {
                        double total = 0;
                        total += endpoint.get("pricing").get("prompt").asDouble() * getCompletionTokens().orElse(0);
                        total += endpoint.get("pricing").get("completion").asDouble() * getCompletionTokens().orElse(0);
                        return total;
                    }
                }
            }
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to get price for response info", e);
        }
        return null;
    }
    
    private Double calculateGoogleAIPrice() {
        if (!promptTokens.isPresent() || !completionTokens.isPresent()) {
            return null;
        }
        
        // Google AI Studio pricing (as of 2024) - prices per 1M tokens
        // Source: https://ai.google.dev/pricing
        double inputPricePerMillion;
        double outputPricePerMillion;
        
        switch (model.toLowerCase()) {
            case "gemini-2.5-flash":
                inputPricePerMillion = 0.075;   // $0.075 per 1M input tokens
                outputPricePerMillion = 0.30;   // $0.30 per 1M output tokens
                break;
            case "gemini-2.5-pro":
                inputPricePerMillion = 1.25;    // $1.25 per 1M input tokens
                outputPricePerMillion = 5.00;   // $5.00 per 1M output tokens
                break;
            case "gemini-1.5-flash":
                inputPricePerMillion = 0.075;   // $0.075 per 1M input tokens
                outputPricePerMillion = 0.30;   // $0.30 per 1M output tokens
                break;
            case "gemini-1.5-pro":
                inputPricePerMillion = 1.25;    // $1.25 per 1M input tokens
                outputPricePerMillion = 5.00;   // $5.00 per 1M output tokens
                break;
            case "gemini-1.0-pro":
                inputPricePerMillion = 0.50;    // $0.50 per 1M input tokens
                outputPricePerMillion = 1.50;   // $1.50 per 1M output tokens
                break;
            default:
                // Default to Gemini 2.5 Flash pricing if model unknown
                inputPricePerMillion = 0.075;
                outputPricePerMillion = 0.30;
                break;
        }
        
        // Calculate total cost
        double inputCost = (promptTokens.get() / 1000000.0) * inputPricePerMillion;
        double outputCost = (completionTokens.get() / 1000000.0) * outputPricePerMillion;
        
        return inputCost + outputCost;
    }
}
