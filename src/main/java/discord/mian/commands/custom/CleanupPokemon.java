package discord.mian.commands.custom;

import discord.mian.ai.AIBot;
import discord.mian.commands.SlashCommand;
import discord.mian.commands.PermissionHandler;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;

public class CleanupPokemon extends SlashCommand {

    public CleanupPokemon() {
        super("cleanup_pokemon", "Remove all non-Pokemon content from database (TEMPORARY COMMAND)");
        this.setContexts(InteractionContextType.GUILD);
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) throws Exception {
        if (!discord.mian.Util.hasMasterPermission(event.getMember())) {
            event.reply("You don't have permission to use this command!").setEphemeral(true).queue();
            return false;
        }

        event.deferReply(true).queue();
        
        try {
            // Get the server and run cleanup
            AIBot.bot.getServerData(event.getGuild()).cleanupNonPokemonContent();
            
            event.getHook().editOriginal("✅ Successfully removed all non-Pokemon content from database! Only Pokemon-related characters, system prompts, and personas remain.").queue();
            
        } catch (Exception e) {
            event.getHook().editOriginal("❌ Error during cleanup: " + e.getMessage()).queue();
            throw e;
        }
        
        return true;
    }
}