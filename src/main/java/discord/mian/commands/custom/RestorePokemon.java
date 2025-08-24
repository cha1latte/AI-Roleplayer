package discord.mian.commands.custom;

import discord.mian.Util;
import discord.mian.ai.AIBot;
import discord.mian.commands.SlashCommand;
import discord.mian.data.Server;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public class RestorePokemon extends SlashCommand {
    public RestorePokemon() {
        super("restore-pokemon", "Restore missing Pokemon content from defaults and fix database inconsistencies");
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) throws Exception {
        if (!Util.hasMasterPermission(event.getMember())) {
            event.reply("You don't have permission to use this command!").setEphemeral(true).queue();
            return true;
        }
        
        event.deferReply().queue();
        
        try {
            Server server = AIBot.bot.getServerData(event.getGuild());
            if (server != null) {
                server.restorePokemonContent();
                event.getHook().sendMessage("Pokemon content restoration completed! Check the logs for details. You should now see the Pokemon system prompt and persona in the interface.").queue();
            } else {
                event.getHook().sendMessage("Failed to access server data.").queue();
            }
        } catch (Exception e) {
            event.getHook().sendMessage("Error during Pokemon content restoration: " + e.getMessage()).queue();
        }
        return true;
    }

}