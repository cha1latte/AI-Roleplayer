package discord.mian.commands.custom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import discord.mian.ai.AIBot;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ChangeProvider extends SlashCommand {

    public ChangeProvider() {
        super("openrouter_endpoint",
                "Change the OpenRouter endpoint/company (Anthropic, OpenAI, Meta, etc.)");
        this.addOption(OptionType.STRING, "name", "Prices are measured as one response per million tokens. Aim for around $1.50 for cheap responses", false, true);
        this.setContexts(InteractionContextType.GUILD);
    }

    public static HashMap<String, Double> getEndpoints(String model) throws IOException {
        HashMap<String, Double> endpoints = new HashMap<>();

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

            dataNode.forEach(endpoint -> {
                String name = endpoint.get("name").asText();
                name = name.substring(0, name.indexOf("|") - 1);

                double total = 0;
                total += endpoint.get("pricing").get("prompt").asDouble() * 1000000;
                total += endpoint.get("pricing").get("completion").asDouble() * 1000000;

                endpoints.put(name, total);
            });
        }
        return endpoints;
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) throws Exception {
        String provider = event.getOption("name", OptionMapping::getAsString);
        Roleplay roleplay = AIBot.bot.getChat(event.getGuild());

        Model model = roleplay.getModel();
        if (model == null) {
            event.reply("You need to select a model first before picking a provider!").setEphemeral(true).queue();
            return true;
        }

        HashMap<String, Double> endpoints = getEndpoints(model.id);

        if (provider != null && !provider.isEmpty()) {
            if (!endpoints.containsKey(provider)) {
                event.reply("Not a valid provider for this model!").setEphemeral(true).queue();
                return true;
            }
        }

        Double price = ((double) roleplay.getMaxTokens() / (endpoints.getOrDefault(provider, 0D) * 1000000));
        if (price.isInfinite() || price.isNaN())
            price = 0D;

        Consumer<InteractionHook> consumer = (interactionHook) -> roleplay.setProvider(provider);
        ReplyCallbackAction reply = event.reply("Swapped provider! The max cost for this provider based on max tokens is $"
                        + String.format("%.4f", price))
                .setEphemeral(true);
        reply.queue(consumer);
        return true;
    }

    @Override
    public void autoComplete(CommandAutoCompleteInteractionEvent event) throws IOException {
        Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
        Model model = roleplay.getModel();
        if (model == null)
            return;

        HashMap<String, Double> endpoints = getEndpoints(model.id);

        String selectedEndpoint = event.getFocusedOption().getValue().toLowerCase();
        AtomicInteger count = new AtomicInteger();

        event.replyChoices(endpoints.keySet()
                .stream()
                .filter(endpoint -> endpoint.toLowerCase().contains(selectedEndpoint)
                        && count.getAndIncrement() < 25)
                .map(endpoint -> {
                    double price = endpoints.get(endpoint);

                    return new Command.Choice(endpoint + " ($" + String.format("%.2f", price) + ")", endpoint);
                }).toList()).queue();
    }
}
