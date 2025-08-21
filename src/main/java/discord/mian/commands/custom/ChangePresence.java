package discord.mian.commands.custom;

import discord.mian.Constants;
import discord.mian.commands.SlashCommand;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.Arrays;
import java.util.stream.Collectors;

public class ChangePresence extends SlashCommand {
    public ChangePresence() {
        super("change_presence", "Changes my presence!");
        this.addOptions(
                new OptionData(OptionType.STRING, "onlinestatus", "My online status!", false, true),
                new OptionData(OptionType.INTEGER, "activitytype", "My activity type", false, true),
                new OptionData(OptionType.STRING, "name", "Name of activity"),
                new OptionData(OptionType.STRING, "state", "State of activity (Ignore this if custom status)"),
                new OptionData(OptionType.STRING, "url", "Required if streaming!")
        );
        // Remove hardcoded user restriction - now uses bot master role instead
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) throws Exception {
        if (super.handle(event)) {
            String onlineStatusKey = event.getOption("onlinestatus", OptionMapping::getAsString);
            Integer activityTypeKey = event.getOption("activitytype", OptionMapping::getAsInt);
            String name = event.getOption("name", OptionMapping::getAsString);
            String state = event.getOption("state", OptionMapping::getAsString);
            String url = event.getOption("url", OptionMapping::getAsString);

            Activity currentBotActivity = event.getJDA().getPresence().getActivity();

            onlineStatusKey = onlineStatusKey != null ? onlineStatusKey : event.getJDA().getPresence().getStatus().getKey();
            OnlineStatus onlineStatus = OnlineStatus.fromKey(onlineStatusKey);

            if (activityTypeKey == null) {
                if (currentBotActivity != null && currentBotActivity.getType() != null) {
                    activityTypeKey = currentBotActivity.getType().getKey();
                } else {
                    activityTypeKey = 4; // Default to CUSTOM status
                }
            }

            Activity.ActivityType activityType = Activity.ActivityType.fromKey(activityTypeKey);

            if (name == null) name = currentBotActivity != null ? currentBotActivity.getName() : "AI Roleplayer";
            if (state == null) state = currentBotActivity != null ? currentBotActivity.getState() : "";
            Activity activity = Activity.of(activityType, name, url).withState(state);

            event.getJDA().getPresence().setPresence(onlineStatus, activity);
            event.reply("Changed my presence successfully!").setEphemeral(true).queue();
            return true;
        }
        return false;
    }

    @Override
    public void autoComplete(CommandAutoCompleteInteractionEvent event) {
        switch (event.getFocusedOption().getName()) {
            case "onlinestatus" -> event.replyChoices(Arrays.stream(OnlineStatus.values())
                    .filter(status -> !status.getKey().isEmpty())
                    .map(status -> new Command.Choice(status.name(), status.getKey()))
                    .collect(Collectors.toList())
            ).queue();
            case "activitytype" -> event.replyChoices(Arrays.stream(Activity.ActivityType.values())
                    .map(activityType -> new Command.Choice(activityType.name(), activityType.getKey()))
                    .collect(Collectors.toList())
            ).queue();
        }
    }
}
