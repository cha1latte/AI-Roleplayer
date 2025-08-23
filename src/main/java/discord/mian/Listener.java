package discord.mian;

import discord.mian.ai.AIBot;
import discord.mian.ai.Roleplay;
import discord.mian.commands.BotCommands;
import discord.mian.data.PromptType;
import discord.mian.data.character.Character;
import discord.mian.interactions.InteractionCreator;
import discord.mian.interactions.Interactions;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class Listener {
    @SubscribeEvent
    public void onMessageDelete(MessageDeleteEvent event) {
        long id = event.getMessageIdLong();
        Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
        if (roleplay.isRunningRoleplay() && roleplay.getParentID() == id) {
            roleplay.stopRoleplay(); // stops roleplay in the event that parent msg was deleted
        }
    }

    @SubscribeEvent
    public void onGuildJoin(GuildJoinEvent event) {
        for(TextChannel channel : event.getGuild().getTextChannelCache().stream().toList()){
            if(channel.canTalk()){
                channel.sendMessageComponents(Interactions.getHelpContainer()).useComponentsV2().queue();
                break;
            }
        }
        AIBot.bot.onServerJoin(event.getGuild());
    }

    @SubscribeEvent
    public void onGuildLeave(GuildLeaveEvent event) {
        AIBot.bot.removeServer(event.getGuild());
    }

    @SubscribeEvent
    public void onModalInteraction(ModalInteractionEvent event) {
        Object component =
                InteractionCreator.getComponentConsumer(event.getModalId());
        if (component == null)
            component = InteractionCreator.getModalConsumer(event.getModalId());

        if (component == null) {
            event.reply("This interaction has expired! Please redo the same steps you used to get here").setEphemeral(true).queue();
            return;
        }

        try {
            ((Consumer<Object>) component).accept(event);
        } catch (Exception e) {
            event.getHook().retrieveOriginal().queue(
                    message -> event.getHook().editOriginalComponents(
                            TextDisplay.of("An unexpected error occurred :<")
                    ).useComponentsV2().queue(),
                    failure -> event.reply("An unexpected error occurred :<").setEphemeral(true).queue()
            );
            throw (e);
        }
    }

    @SubscribeEvent
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) throws Exception {
        if(AIBot.bot.getServerData(event.getGuild()) == null){
            AIBot.bot.onServerJoin(event.getGuild());
        }
        BotCommands.handleCommand(event);
    }

    @SubscribeEvent
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (AIBot.bot.getChat(event.getGuild()) == null) {
            return;
        }
        BotCommands.handleAutoComplete(event);
    }

    @SubscribeEvent
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Consumer<? super GenericInteractionCreateEvent> component =
                InteractionCreator.getComponentConsumer(event.getComponentId());
        if (component == null) {
            event.reply("This interaction has expired! Please redo the same steps you used to get here").setEphemeral(true).queue();
            return;
        }

        try {
            component.accept(event);
        } catch (Exception e) {
            event.getHook().retrieveOriginal().queue(
                    message -> message.editMessageComponents(
                            TextDisplay.of("Failed to activate button :<")
                    ).useComponentsV2().queue(),
                    failure -> event.reply("Failed to activate button :<").setEphemeral(true).queue()
            );
            throw (e);
        }
    }

    @SubscribeEvent
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Consumer<? super GenericInteractionCreateEvent> component =
                InteractionCreator.getComponentConsumer(event.getComponentId());
        if (component == null) {
            event.reply("This interaction has expired! Please redo the same steps you used to get here").setEphemeral(true).queue();
            return;
        }

        try {
            component.accept(event);
        } catch (Exception e) {
            event.getHook().retrieveOriginal().queue(
                    message -> message.editMessageComponents(
                            TextDisplay.of("Failed to select options :<")
                    ).useComponentsV2().queue(),
                    failure -> event.reply("Failed to select options :<").setEphemeral(true).queue()
            );
            throw (e);
        }
    }

    @SubscribeEvent
    public void onMessageReceived(MessageReceivedEvent event) throws ExecutionException, InterruptedException {
        if (Constants.ALLOWED_USER_IDS.contains(event.getAuthor().getIdLong()) || Constants.PUBLIC) {
            Message msg = event.getMessage();

            if (!msg.isFromGuild())
                return;
            if (msg.getAuthor() == AIBot.bot.getJDA().getSelfUser())
                return;
            if (msg.isWebhookMessage())
                return;

            Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
                
            if (roleplay.isMakingResponse())
                return;
            if (!roleplay.isRunningRoleplay())
                return;

            if (event.getChannel().getIdLong() == roleplay.getChannel().getIdLong() && roleplay.isRunningRoleplay()) {
                Random random = new Random();

                Character fromContent = roleplay.findRespondingCharacterFromContent(msg.getContentRaw());
                
                if (fromContent != null && !fromContent.getName().equals(event.getAuthor().getName())) {
                    roleplay.promptCharacterToRoleplay(fromContent, msg, true);
                } else {
                    Character data = roleplay.findRespondingCharacterFromMessage(msg);
                    
                    if (data != null && !data.getName().equals(event.getAuthor().getName())) {
                        roleplay.promptCharacterToRoleplay(data, msg, true);
                    } else if (!AIBot.bot.getServerData(event.getGuild()).getConfig()
                            .get("only_chat_on_mention", Boolean.class).getValue()) {

                        // Always respond instead of 50% chance
                        List<Character> allCharacters = roleplay.getDatas(PromptType.CHARACTER).stream()
                                .map(data1 -> (Character) data1)
                                .filter(data1 -> !data1.getName().equals(event.getAuthor().getName()))
                                .toList();
                                
                        if (!allCharacters.isEmpty()) {
                            final double total = allCharacters.stream()
                                    .mapToDouble(character -> character.getDocument().getTalkability()).sum();

                            double percentage = Math.random();
                            
                            List<Character> meetsCriteria = allCharacters.stream()
                                    .filter(characterData -> (characterData.getDocument().getTalkability() / total) >= percentage)
                                    .toList();

                            if (!meetsCriteria.isEmpty()) {
                                Character selectedCharacter = meetsCriteria.get((int) (Math.random() * meetsCriteria.size()));
                                roleplay.promptCharacterToRoleplay(selectedCharacter, msg, true);
                            }
                        }
                    }
                }
            }
        }
    }
}
