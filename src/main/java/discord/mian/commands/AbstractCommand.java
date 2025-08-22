package discord.mian.commands;

import discord.mian.Constants;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public abstract class AbstractCommand {
    private final Collection<OptionData> optionDatas = new ArrayList<>();
    private final String name;
    private final String description;

    public AbstractCommand(String name, String description) {
        this(name, description, (OptionData[]) null);
    }

    public AbstractCommand(String name, String description, OptionData... optionDatas) {
        this.name = name;
        this.description = description;
        if (optionDatas != null)
            this.optionDatas.addAll(List.of(optionDatas));
        Constants.LOGGER.info("Registered Command: " + this.getName());
    }

    public abstract void execute(SlashCommandInteractionEvent event);

    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
    }

    public Collection<OptionData> getOptionDatas() {
        return optionDatas;
    }

    public String getDescription() {
        return description;
    }

    public String getName() {
        return name;
    }
}
