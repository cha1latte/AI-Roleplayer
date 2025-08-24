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
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
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
            

            if (!msg.isFromGuild()) {
                return;
            }
            if (msg.getAuthor() == AIBot.bot.getJDA().getSelfUser()) {
                return;
            }
            if (msg.isWebhookMessage()) {
                return;
            }

            Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
                
            if (roleplay.isMakingResponse()) {
                return;
            }

            // Check if this is a roleplay thread (either actively running or an old one after restart)
            boolean isRoleplayThread = false;
            
            if (roleplay.isRunningRoleplay() && roleplay.getChannel() != null && 
                event.getChannel().getIdLong() == roleplay.getChannel().getIdLong()) {
                isRoleplayThread = true;
            } else if (event.getChannel().getType() == net.dv8tion.jda.api.entities.channel.ChannelType.GUILD_PUBLIC_THREAD ||
                       event.getChannel().getType() == net.dv8tion.jda.api.entities.channel.ChannelType.GUILD_PRIVATE_THREAD) {
                // Check if this is a potential roleplay thread from before bot restart
                ThreadChannel threadChannel = (ThreadChannel) event.getChannel();
                // If it's a thread and the bot isn't tracking it as active, try to restore it
                if (!roleplay.isRunningRoleplay()) {
                    roleplay.restoreRoleplayFromThread(threadChannel);
                }
                isRoleplayThread = true;
            }

            if (isRoleplayThread) {
                Random random = new Random();

                Character fromContent = roleplay.findRespondingCharacterFromContent(msg.getContentRaw());
                
                if (fromContent != null && !fromContent.getName().equals(event.getAuthor().getName())) {
                    roleplay.promptCharacterToRoleplay(fromContent, msg, true);
                } else {
                    Character data = roleplay.findRespondingCharacterFromMessage(msg);
                    
                    if (data != null && !data.getName().equals(event.getAuthor().getName())) {
                        roleplay.promptCharacterToRoleplay(data, msg, true);
                    } else {
                        boolean onlyMention = AIBot.bot.getServerData(event.getGuild()).getConfig()
                                .get("only_chat_on_mention", Boolean.class).getValue();
                        
                        if (!onlyMention) {
                            // Check if there's a current character from the original roleplay
                            Character currentCharacter = roleplay.getCurrentCharacter();
                            
                            if (currentCharacter != null && !currentCharacter.getName().equals(event.getAuthor().getName())) {
                                Constants.LOGGER.info("Using current character from roleplay: " + currentCharacter.getName());
                                try {
                                    roleplay.promptCharacterToRoleplay(currentCharacter, msg, true);
                                } catch (Exception e) {
                                    Constants.LOGGER.error("Exception in promptCharacterToRoleplay", e);
                                    throw e;
                                }
                            } else {
                                Constants.LOGGER.info("No current character set, falling back to general character selection");
                                // Fallback: use any available character if no current character is set
                                List<Character> allCharacters = roleplay.getDatas(PromptType.CHARACTER).stream()
                                        .map(data1 -> (Character) data1)
                                        .filter(data1 -> !data1.getName().equals(event.getAuthor().getName()))
                                        .toList();
                                
                                Constants.LOGGER.info("Found " + allCharacters.size() + " available characters for fallback");
                                
                                if (!allCharacters.isEmpty()) {
                                    // Just pick the first available character instead of complex random selection
                                    Character selectedCharacter = allCharacters.get(0);
                                    Constants.LOGGER.info("Selected fallback character: " + selectedCharacter.getName() + ", calling promptCharacterToRoleplay");
                                    roleplay.promptCharacterToRoleplay(selectedCharacter, msg, true);
                                } else {
                                    Constants.LOGGER.info("No characters available for response");
                                }
                            }
                        } else {
                            Constants.LOGGER.info("only_chat_on_mention is true, not responding to general message");
                        }
                    }
                }
                } catch (Exception e) {
                    Constants.LOGGER.error("Exception during roleplay thread processing", e);
                    throw e;
                }
            } else {
                Constants.LOGGER.info("Message processed in roleplay thread - no characters responded");
            }
        } else {
            Constants.LOGGER.info("User not authorized - ID not in ALLOWED_USER_IDS and PUBLIC is false");
        }
    }
}
