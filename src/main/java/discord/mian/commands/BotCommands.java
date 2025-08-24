package discord.mian.commands;

import discord.mian.Constants;
import discord.mian.ai.AIBot;
import discord.mian.api.CommandHandler;
import discord.mian.commands.custom.*;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

import java.util.List;
import java.util.Optional;

public class BotCommands {
    private static final List<CommandHandler> commands = List.of(
            new Menu(),
            new SetAvatar(),
            new Poke(),
            new SetBotRole(),
            new ChangePresence(),
            new CleanupPokemon(),
            new Talk(),
            new ChangeAvatar(),
            new EditModelProperties(),
            new ChangeAIProvider(),
            new Help()
    );

    public static CommandListUpdateAction addCommands() {
        Constants.LOGGER.info("Adding bot commands!");

        return AIBot.bot.getJDA().updateCommands().addCommands(commands);
    }

    public static void handleCommand(GenericCommandInteractionEvent event) throws Exception {
        Optional<CommandHandler> commandOptional = commands.stream().filter(command -> command.getName().equalsIgnoreCase(event.getName()))
                .findFirst();
        if (commandOptional.isPresent()) {
            CommandHandler<GenericCommandInteractionEvent> commandHandler = commandOptional.get();
            try {
                commandHandler.handle(event);
            } catch (Exception e) {
                event.getHook().retrieveOriginal().queue(
                        message -> message.editMessage("Failed to execute command :<").queue(),
                        failure -> event.reply("Failed to execute command :<").setEphemeral(true).queue()
                );
                throw (e);
            }
        }
    }

    public static void handleAutoComplete(CommandAutoCompleteInteractionEvent event) {
        commands.stream().filter(command -> command.getName().equalsIgnoreCase(event.getName()) && command instanceof SlashCommand)
                .findFirst()
                .ifPresent(command -> {
                    try {
                        SlashCommand slashCommand = (SlashCommand) command;
                        slashCommand.autoComplete(event);
                    } catch (Exception e) {
                        Constants.LOGGER.error("Failed to run autocomplete", e);
                    }
                });
    }
}
