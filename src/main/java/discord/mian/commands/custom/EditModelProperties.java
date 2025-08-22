package discord.mian.commands.custom;

import discord.mian.Util;
import discord.mian.commands.SlashCommand;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.HashMap;

public class EditModelProperties extends SlashCommand {
    private static final ChangeModel MODEL = new ChangeModel();
    private static final ChangeProvider PROVIDER = new ChangeProvider();
    private static final ChangeTemp TEMP = new ChangeTemp();
    private static final ChangeMaxTokens TOKENS = new ChangeMaxTokens();

    private static final HashMap<String, SlashCommand> SUBCOMMANDS = new HashMap<>();

    static {
        SUBCOMMANDS.put(MODEL.getName(), MODEL);
        SUBCOMMANDS.put(PROVIDER.getName(), PROVIDER);
        SUBCOMMANDS.put(TEMP.getName(), TEMP);
        SUBCOMMANDS.put(TOKENS.getName(), TOKENS);
    }

    public EditModelProperties() {
        super("edit_model", "Configure model, temperature, OpenRouter endpoints, etc!");
        SUBCOMMANDS.forEach((name, command) ->
                this.addSubcommands(new SubcommandData(name, command.getDescription())
                        .addOptions(command.getOptions())));
        this.setContexts(InteractionContextType.GUILD);
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) throws Exception {
        if (super.handle(event)) {
            if (!Util.hasMasterPermission(event.getMember())) {
                event.reply("nuh uh little bro bro, you dont got permission").setEphemeral(true).queue();
                return true;
            }

            return SUBCOMMANDS.get(event.getSubcommandName()).handle(event);
        }
        return false;
    }

    @Override
    public void autoComplete(CommandAutoCompleteInteractionEvent event) throws Exception {
        SUBCOMMANDS.get(event.getSubcommandName()).autoComplete(event);
    }
}
