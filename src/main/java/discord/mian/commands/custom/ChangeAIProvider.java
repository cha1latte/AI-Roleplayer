package discord.mian.commands.custom;

import discord.mian.Constants;
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

import java.util.Arrays;
import java.util.function.Consumer;

public class ChangeAIProvider extends SlashCommand {

    public ChangeAIProvider() {
        super("ai_provider",
                "Change between OpenRouter and Google AI");
        this.addOption(OptionType.STRING, "provider", "Choose your AI provider", true, true);
        this.setContexts(InteractionContextType.GUILD);
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) throws Exception {
        String providerName = event.getOption("provider", OptionMapping::getAsString);
        Roleplay roleplay = AIBot.bot.getChat(event.getGuild());

        if (providerName == null || providerName.isEmpty()) {
            event.reply("You must specify a provider!").setEphemeral(true).queue();
            return true;
        }

        AIProvider aiProvider;
        try {
            aiProvider = AIProvider.valueOf(providerName.toUpperCase());
        } catch (IllegalArgumentException e) {
            event.reply("Invalid AI provider! Choose from: " + 
                       Arrays.toString(AIProvider.values())).setEphemeral(true).queue();
            return true;
        }

        Consumer<InteractionHook> consumer = (interactionHook) -> {
            roleplay.setAIProvider(aiProvider);
            
            // Update model to default for the provider if current model is incompatible
            if (aiProvider == AIProvider.GOOGLE_AI) {
                String defaultModel = Constants.DEFAULT_GOOGLEAI_MODEL;
                String id = defaultModel.substring(0, defaultModel.indexOf("|"));
                String display = defaultModel.substring(defaultModel.indexOf("|") + 1);
                roleplay.setModel(new Model(id, display));
            } else if (aiProvider == AIProvider.OPENROUTER) {
                String defaultModel = Constants.DEFAULT_MODEL;
                String id = defaultModel.substring(0, defaultModel.indexOf("|"));
                String display = defaultModel.substring(defaultModel.indexOf("|") + 1);
                roleplay.setModel(new Model(id, display));
            }
        };

        String message = "Switched to " + aiProvider.displayName + "! ";
        if (aiProvider == AIProvider.GOOGLE_AI) {
            message += "Make sure to set your Google AI Studio API key in the server configuration. Default model set to Gemini 2.5 Flash.";
        } else {
            message += "Make sure to set your OpenRouter API key in the server configuration. Default model set to Llama 3.3 70B.";
        }

        ReplyCallbackAction reply = event.reply(message).setEphemeral(true);
        reply.queue(consumer);
        return true;
    }

    @Override
    public void autoComplete(CommandAutoCompleteInteractionEvent event) {
        String selectedProvider = event.getFocusedOption().getValue().toLowerCase();
        
        event.replyChoices(
            Arrays.stream(AIProvider.values())
                .filter(provider -> provider.name().toLowerCase().contains(selectedProvider) ||
                                  provider.displayName.toLowerCase().contains(selectedProvider))
                .limit(25)
                .map(provider -> new Command.Choice(provider.displayName, provider.name()))
                .toList()
        ).queue();
    }
}