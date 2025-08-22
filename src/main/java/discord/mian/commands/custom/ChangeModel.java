package discord.mian.commands.custom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import discord.mian.ai.AIBot;
import discord.mian.ai.AIProvider;
import discord.mian.ai.Model;
import discord.mian.ai.Roleplay;
import discord.mian.commands.SlashCommand;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ChangeModel extends SlashCommand {
    public ChangeModel() {
        super("model", "Change the LLM being used (shows models for current AI provider)");
        this.addOption(OptionType.STRING, "name", "The model to use", true, true);
        this.setContexts(InteractionContextType.GUILD);
    }

    private Map<String, String> getGoogleAIModels() {
        // Available Google AI models
        return Map.of(
            "gemini-2.5-flash", "Gemini 2.5 Flash",
            "gemini-2.5-pro", "Gemini 2.5 Pro",
            "gemini-1.5-flash", "Gemini 1.5 Flash", 
            "gemini-1.5-pro", "Gemini 1.5 Pro",
            "gemini-1.0-pro", "Gemini 1.0 Pro"
        );
    }

    private Map<String, String> getOpenRouterModels() throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().build();

        Request request = new Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .get()
                .build();

        Call call = client.newCall(request);
        try (Response response = call.execute()) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(response.body().string());
            JsonNode dataNode = node.get("data");

            return dataNode.valueStream().collect(Collectors.toMap(
                    model -> model.get("id").asText(),
                    model -> model.get("name").asText()
            ));
        }
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) throws Exception {
        String id = event.getOption("name", OptionMapping::getAsString);
        Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
        AIProvider currentProvider = roleplay.getAIProvider();

        Map<String, String> validModels;
        
        if (currentProvider == AIProvider.GOOGLE_AI) {
            validModels = getGoogleAIModels();
            
            if (!validModels.containsKey(id)) {
                event.reply("Not a valid Google AI model! Available models: " + 
                           String.join(", ", validModels.values())).setEphemeral(true).queue();
                return true;
            }
        } else {
            // OpenRouter
            validModels = getOpenRouterModels();
            
            if (!validModels.containsKey(id)) {
                event.reply("Not a valid model on OpenRouter!").setEphemeral(true).queue();
                return true;
            }
            
            // Check provider compatibility for OpenRouter
            HashMap<String, Double> endpoints = ChangeProvider.getEndpoints(id);
            if (roleplay.getProvider() != null && !roleplay.getProvider().isEmpty() && !endpoints.containsKey(roleplay.getProvider())) {
                roleplay.setProvider(null); // invalid provider!
            }
        }

        Map<String, String> finalValidModels = validModels;
        Consumer<InteractionHook> consumer = (interactionHook) -> roleplay
                .setModel(new Model(id, finalValidModels.get(id)));

        String providerName = currentProvider == AIProvider.GOOGLE_AI ? "Google AI" : "OpenRouter";
        ReplyCallbackAction reply = event.reply("Changed " + providerName + " model to: " + validModels.get(id)).setEphemeral(true);
        reply.queue(consumer);
        return true;
    }

    @Override
    public void autoComplete(CommandAutoCompleteInteractionEvent event) {
        try {
            Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
            AIProvider currentProvider = roleplay.getAIProvider();
            
            Map<String, String> finalModels;
            
            if (currentProvider == AIProvider.GOOGLE_AI) {
                Map<String, String> rawModels = getGoogleAIModels();
                // For Google AI, we invert the map to show display names first
                Map<String, String> displayModels = new HashMap<>();
                rawModels.forEach((id, name) -> displayModels.put(name, id));
                finalModels = displayModels;
            } else {
                // OpenRouter
                Map<String, String> rawModels = getOpenRouterModels();
                // Invert the map to show display names first for consistency
                Map<String, String> displayModels = new HashMap<>();
                rawModels.forEach((id, name) -> displayModels.put(name, id));
                finalModels = displayModels;
            }

            String selectedModel = event.getFocusedOption().getValue().toLowerCase();
            AtomicInteger count = new AtomicInteger();

            event.replyChoices(finalModels.keySet()
                    .stream()
                    .filter(modelName -> modelName.toLowerCase().contains(selectedModel)
                            && count.getAndIncrement() < 25)
                    .map(modelName -> new Command.Choice(modelName, finalModels.get(modelName))).toList()).queue();
        } catch (Exception e) {
            // Fallback to empty list if there's an error
            event.replyChoices(java.util.List.of()).queue();
        }
    }
}
