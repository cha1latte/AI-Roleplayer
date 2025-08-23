package discord.mian.interactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import discord.mian.Cats;
import discord.mian.Constants;
import discord.mian.Direction;
import discord.mian.Util;
import discord.mian.ai.AIBot;
import discord.mian.ai.AIProvider;
import discord.mian.ai.ResponseInfo;
import discord.mian.ai.Roleplay;
import discord.mian.api.PromptInfo;
import discord.mian.api.ProviderInfo;
import discord.mian.data.ConfigEntry;
import discord.mian.data.Data;
import discord.mian.data.PromptType;
import discord.mian.data.Server;
import discord.mian.data.character.Character;
import discord.mian.data.character.CharacterDocument;
import discord.mian.data.instruction.Instruction;
import discord.mian.data.world.World;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.container.ContainerChildComponentUnion;
import net.dv8tion.jda.api.components.filedisplay.FileDisplay;
import net.dv8tion.jda.api.components.replacer.ComponentReplacer;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalTopLevelComponent;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.utils.FileUpload;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.tika.Tika;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class Interactions {
    public static Consumer<StringSelectInteractionEvent> getDeletionMenu(PromptType promptType) {
        return (event -> {
            if (!Util.hasMasterPermission(event.getMember())) {
                event.reply("nuh uh little bro bro, you dont got permission").setEphemeral(true).queue();
                return;
            }

            event.deferEdit().queue();

            for (SelectOption option : event.getSelectedOptions()) {
                String promptName = option.getValue();

                Server server = AIBot.bot.getServerData(event.getGuild());
                Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
                Data<?> data = server.getDatas(promptType).get(promptName);

                if (!roleplay.isRunningRoleplay() || (roleplay.isRunningRoleplay() &&
                        !roleplay.getDatas(promptType).contains(data))) {
                    data.nuke();
                }

                server.getDatas(promptType).remove(promptName);
            }

            createPromptViewer(event.getHook(), promptType, null);
        });
    }

    public static Consumer<StringSelectInteractionEvent> getPromptEditMenu(PromptType promptType, boolean isCreating) {
        return (event -> {
            if (!Util.hasMasterPermission(event.getMember())) {
                event.reply("nuh uh little bro bro, you dont got permission").setEphemeral(true).queue();
                return;
            }

            String promptName = event.getSelectedOptions().getFirst().getValue();

            try {
                if (isCreating) {
                    replyCreatingPrompt(event, promptType);
                } else {
                    replyEditingPrompt(event, promptType, promptName);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static Consumer<ButtonInteractionEvent> getDestroyMessage() {
        return event -> {
            event.deferReply().setEphemeral(true).queue();

            event.getMessage().delete().queue((ignored) ->
                    event.getHook().editOriginal("Deleted!").queue());
        };
    }

    public static Consumer<ButtonInteractionEvent> getEditMessage() {
        return (event -> {
            Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
            if (roleplay.isRunningRoleplay()) {
                TextInput contentInput = TextInput.create("content", "Content", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("The content that will replace this message's original content")
                        .setValue(event.getMessage().getContentRaw())
                        .build();

                event.replyModal(
                        InteractionCreator.createPermanentModal(Modal.create("edit", "Edit Message")
                                .addComponents(ActionRow.of(contentInput)), modalEvent -> {
                            String content = modalEvent.getValue("content").getAsString();
                            modalEvent.editMessage(content).queue();
                            // replaces the content at that swipe
                            roleplay.getSwipes().get(roleplay.getCurrentSwipe()).editResponse(string -> content);
                        })
                ).queue();
            } else {
                event.reply("Roleplay isn't running currently!").setEphemeral(true).queue();
            }
        });
    }

    public static Consumer<ButtonInteractionEvent> getSwipe(Direction direction) {
        return event -> {
            Roleplay roleplay = AIBot.bot.getChat(event.getGuild());
            if (roleplay.isRunningRoleplay()) {
                event.deferEdit().queue();
                roleplay.swipe(event, direction);
            } else {
                event.reply("Roleplay isn't running currently!").setEphemeral(true).queue();
            }
        };
    }

    public static void replyCreatingPrompt(GenericComponentInteractionCreateEvent event, PromptType promptType) {
        List<ModalTopLevelComponent> components = new ArrayList<>();
        components.add(ActionRow.of(
                TextInput.create("name", "Name", TextInputStyle.SHORT)
                        .setPlaceholder("Enter a name for the new prompt!")
                        .build()
        ));
        components.add(ActionRow.of(
                TextInput.create("prompt", "Prompt: {{char}} represents the character", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Edit the prompt. {{char}} represents the character")
                        .build()
        ));

        if (promptType == PromptType.CHARACTER) {
            components.add(ActionRow.of(
                    TextInput.create("startingMessage", "Starting Message", TextInputStyle.PARAGRAPH)
                            .setPlaceholder("Optional: First message character sends when roleplay starts")
                            .setRequired(false)
                            .build()
            ));
            components.add(ActionRow.of(
                    TextInput.create("talkability", "Talkability: Put a decimal from 0.0 to 1.0", TextInputStyle.SHORT)
                            .setPlaceholder("Likelihood of responding when mentioned in chat")
                            .build()
            ));
            components.add(ActionRow.of(
                    TextInput.create("avatar", "Avatar", TextInputStyle.SHORT)
                            .setPlaceholder("Direct image address to set the character's avatar")
                            .setRequired(false)
                            .build()
            ));
        }

        event.replyModal(
                InteractionCreator.createModal("Creating Prompt", (modalEvent) -> {
                    String promptName = modalEvent.getValue("name").getAsString();
                    getPromptEditor(promptType, promptName).accept(modalEvent);
                }).addComponents(components).build()
        ).queue();
    }

    public static void replyEditingPrompt(GenericComponentInteractionCreateEvent event, PromptType promptType, String promptName) throws IOException {
        TextInput.Builder promptInput = TextInput.create("prompt", "Prompt: {{char}} represents the character", TextInputStyle.PARAGRAPH)
                .setPlaceholder("Edit the prompt. {{char}} represents the character");

        Server server = AIBot.bot.getServerData(event.getGuild());
        Data<?> data = server.getDatas(promptType).get(promptName);

        if (data == null)
            throw new RuntimeException(promptName + " is not a valid prompt!");

        promptInput.setValue(data.getPrompt());

        List<ModalTopLevelComponent> components = new ArrayList<>();
        components.add(ActionRow.of(promptInput.build()));
        if (promptType == PromptType.CHARACTER) {
            Character character = (Character) data;
            TextInput.Builder startingMessageInput = TextInput.create("startingMessage", "Starting Message", TextInputStyle.PARAGRAPH)
                    .setPlaceholder("Optional: First message character sends when roleplay starts")
                    .setRequired(false);
            
            if (character.getDocument().getStartingMessage() != null && !character.getDocument().getStartingMessage().trim().isEmpty()) {
                startingMessageInput.setValue(character.getDocument().getStartingMessage());
            }
            
            components.add(ActionRow.of(startingMessageInput.build()));
            components.add(ActionRow.of(
                    TextInput.create("talkability", "Talkability: Put a decimal from 0.0 to 1.0", TextInputStyle.SHORT)
                            .setPlaceholder("Likelihood of responding when mentioned in chat")
                            .setValue(String.valueOf(character.getDocument().getTalkability()))
                            .build()
            ));
            components.add(ActionRow.of(
                    TextInput.create("avatar", "Avatar", TextInputStyle.SHORT)
                            .setPlaceholder("Direct image address to set the character's avatar")
                            .setValue(character.getDocument().getAvatar() != null ? character.getDocument().getAvatar() : "")
                            .setRequired(false)
                            .build()
            ));
        }

        event.replyModal(
                InteractionCreator.createModal("Editing " + promptName, getPromptEditor(promptType, promptName)).addComponents(components)
                        .build()
        ).queue();
    }

    private static Consumer<ModalInteractionEvent> getPromptEditor(PromptType promptType, String promptName) {
        return (event -> {
            event.deferEdit().queue();

            String name = promptName != null ? promptName : event.getValue("name").getAsString();
            String prompt = event.getValue("prompt").getAsString();

            Function<String, Double> tryParse = (string) -> {
                try {
                    return Double.parseDouble(string);
                } catch (Exception ignored) {
                    return 0.5;
                }
            };

            double talkability = Math.min(1, Math.max(0, event.getValue("talkability") != null ?
                    tryParse.apply(event.getValue("talkability").getAsString()) : 0.5));
            String avatar = event.getValue("avatar") != null ? event.getValue("avatar").getAsString() : null;
            String startingMessage = event.getValue("startingMessage") != null ? event.getValue("startingMessage").getAsString() : null;
            Server server = AIBot.bot.getServerData(event.getGuild());

            Data<?> data = server.getDatas(promptType).get(promptName);

            try {
                if (data != null) {
                    data.updateDocument(doc -> {
                        if (promptType == PromptType.CHARACTER && doc instanceof CharacterDocument chr) {
                            chr.setTalkability(talkability);
                            if (avatar != null)
                                chr.setAvatar(avatar);
                            if (startingMessage != null && !startingMessage.trim().isEmpty())
                                chr.setStartingMessage(startingMessage.trim());
                        }
                        doc.setPrompt(prompt);
                    });
                } else {
                    switch (promptType) {
                        case CHARACTER -> server.createCharacter(name, prompt, talkability, avatar, startingMessage);
                        case WORLD -> server.createWorld(name, prompt);
                        case INSTRUCTION -> server.createInstruction(name, prompt);
                    }
                }

            } catch (MongoException e) {
                Constants.LOGGER.error("Failed to edit/add prompts", e);
            }

            createPromptViewer(event.getHook(), promptType, null);
        });
    }

    public static List<SelectOption> getOptionsFromViewer(String description) {
        String[] toExclude = {":", "✅"};

        if (!description.equals("```\n```")) {
            return List.of(description.split("\n"))
                    .stream().filter(string -> !string.contains("```"))
                    .map(string -> {
                        for (String possibleExclude : toExclude) {
                            int exclude = string.indexOf(possibleExclude);
                            if (exclude != -1)
                                string = string.substring(0, exclude);
                        }
                        return SelectOption.of(string, string);
                    }).toList();
        }
        return null;
    }

    public static List<ContainerChildComponent> createConfigViewerContainer(Message message, Direction direction) {
        Server server = AIBot.bot.getServerData(message.getGuild());
        Map<String, ConfigEntry<?>> configEntries = server.getConfig().getEntries();
        List<String> display = configEntries.entrySet().stream().filter(entry ->
                !entry.getValue().getHidden()).map(Map.Entry::getKey).toList();

        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("# Configuration"));

        int show = 10;
        int maxSize = Math.max(1, (int) Math.ceil((double) display.size() / show));

        int index = 0;
        if (!message.getComponents().isEmpty()) {
            Optional<ContainerChildComponentUnion> footerUnion = message.getComponents().getFirst().asContainer().getComponents().stream()
                    .filter(component -> component.getUniqueId() == 121)
                    .findFirst();
            if (footerUnion.isPresent()) {
                String footerText = footerUnion.get().asTextDisplay().getContent();
                int indexOfSlash = footerText.indexOf("/");
                if (indexOfSlash != -1)
                    index = Integer.parseInt(footerText.substring(indexOfSlash - 1, indexOfSlash)) - 1;
                // gets the number before the /
            }
        }
        if (direction != null) {
            if (direction == Direction.NEXT)
                index++;
            if (direction == Direction.BACK)
                index--;
        }
        index = Math.max(0, Math.min(index, maxSize - 1));

        int start = index * show;
        int end = Math.min(start + show, display.size());
        start = Math.min(start, end);

        StringBuilder description = new StringBuilder("```\n");

        display = display.subList(start, end);
        for (int i = 0; i < display.size(); i++) {
            String string = display.get(i);
            description.append(string + ": " + configEntries.get(string).getDescription());

            description.append("\n");
        }
        description.append("```");
        components.add(TextDisplay.of(description.toString()).withUniqueId(100));

        components.add(TextDisplay.of("-# Displaying Configuration: " + (index + 1) + "/" + maxSize).withUniqueId(121));
        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        return components;
    }

    public static void createConfigViewer(InteractionHook hook, int forceIndex) {
        Consumer<Message> consumer = message -> {
            List<ContainerChildComponentUnion> components = new ArrayList<>(message.getComponents().getFirst().asContainer().getComponents());

            components.stream().filter(component -> component.getUniqueId() == 121)
                    .findFirst().ifPresentOrElse(footer -> {
                        String footerText = footer.asTextDisplay().getContent();
                        int slash = footerText.indexOf("/");
                        if (slash != -1) {
                            footerText = footerText.substring(0, slash - 1)
                                    + forceIndex + footerText.substring(slash);
                        }
                        message.editMessageComponents(message.getComponentTree()
                                .replace(ComponentReplacer.byId(121, footer.asTextDisplay().withContent(footerText)))
                                .getComponents()).queue(ignored ->
                                createConfigViewer((direction) -> createConfigViewerContainer(message, null), hook, null));
                    }, () -> createConfigViewer((direction) -> createConfigViewerContainer(message, null), hook, null));
        };

        if (hook.hasCallbackResponse()) {
            consumer.accept(hook.getCallbackResponse().getMessage());
        } else {
            hook.retrieveOriginal().queue(consumer, onFail -> Constants.LOGGER.error("Failed to create config viewer", onFail));
        }
    }

    public static void createConfigViewer(Function<Direction, List<ContainerChildComponent>> builder, InteractionHook hook, Direction direction) {
        Consumer<Message> consumer = message -> {
            List<ContainerChildComponent> components = builder.apply(direction);

            TextDisplay descriptionOptions =
                    (TextDisplay) components.stream().filter(child -> child.getUniqueId() == 100)
                            .findFirst().orElseThrow();
            List<SelectOption> options =
                    getOptionsFromViewer(descriptionOptions.getContent());
            components.add(
                    ActionRow.of(InteractionCreator.createStringMenu((event -> {
                                String configOption = event.getSelectedOptions().getFirst().getValue();
                                ConfigEntry<?> entry = AIBot.bot.getServerData(event.getGuild()).getConfig().get(configOption);

                                TextInput.Builder textInput = TextInput.create("value", "Value", TextInputStyle.SHORT)
                                        .setPlaceholder("Enter a valid value: For booleans, type \"true\" or \"false\".");
                                String oldVal = String.valueOf(entry.getValue());
                                if (oldVal != null && !oldVal.isBlank())
                                    textInput.setValue(oldVal);

                                event.replyModal(InteractionCreator.createModal("Editing " + configOption.toUpperCase(), (modalEvent) -> {
                                    modalEvent.deferReply(true).queue();
                                    try {
                                        String value = modalEvent.getValue("value").getAsString();
                                        if (entry.getTypeClass() == String.class) {
                                            ConfigEntry.toType(entry, String.class).setValue(value);
                                        } else if (entry.getTypeClass() == Double.class) {
                                            ConfigEntry.toType(entry, Double.class).setValue(Double.valueOf(value));
                                        } else if (entry.getTypeClass() == Long.class) {
                                            ConfigEntry.toType(entry, Long.class).setValue(Long.valueOf(value));
                                        } else if (entry.getTypeClass() == Integer.class) {
                                            ConfigEntry.toType(entry, Integer.class).setValue(Integer.valueOf(value));
                                        }

                                        AIBot.bot.getServerData(modalEvent.getGuild()).updateConfig(config ->
                                                config.put(configOption, entry));
                                        modalEvent.getHook().editOriginal("Saved config!").queue();
                                    } catch (MongoException ignored) {
                                        modalEvent.getHook().editOriginal("Failed to update config!").queue();
                                    }
                                }).addComponents(
                                        ActionRow.of(textInput.build())).build()).queue();
                            }))
                            .setRequiredRange(1, 1)
                            .setPlaceholder("Configure Option")
                            .addOptions(options)
                            .build())
            );

            components.add(ActionRow.of(
                    InteractionCreator.createButton("<--", (event) -> {
                        event.deferEdit().queue();
                        createConfigViewer(builder, hook, Direction.BACK);

                    }).withStyle(ButtonStyle.SECONDARY),
                    InteractionCreator.createButton("-->", (event) -> {
                        event.deferEdit().queue();
                        createConfigViewer(builder, hook, Direction.NEXT);

                    }).withStyle(ButtonStyle.SECONDARY)
            ));

            components.add(Separator.createDivider(Separator.Spacing.SMALL));

            ArrayList<Button> itemComponents = new ArrayList<>();
            itemComponents.add(InteractionCreator.createPermanentButton(Button.primary("view_dashboard", "View Dashboard"), (event) -> {
                event.deferEdit().queue();
                try {
                    createDashboard(event.getMessage());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).withEmoji(Emoji.fromFormatted("🔝")));

            components.add(ActionRow.of(itemComponents));


            MessageEditAction editAction = message
                    .editMessageComponents(Util.createBotContainer(components))
                    .useComponentsV2()
                    .setFiles(List.of());
            editAction.queue(InteractionCreator.queueTimeoutComponents(null));
        };

        if (hook.hasCallbackResponse()) {
            consumer.accept(hook.getCallbackResponse().getMessage());
        } else {
            hook.retrieveOriginal().queue(consumer, onFail -> Constants.LOGGER.error("Failed to create config viewer", onFail));
        }
    }


    public static List<ContainerChildComponent> createPromptViewerContainer(Message message,
                                                                            String preDescription,
                                                                            PromptType promptType,
                                                                            Direction direction,
                                                                            BiFunction<PromptType, String, String> displayItem
    ) {
        Server server = AIBot.bot.getServerData(message.getGuild());
        List<String> display = server.getDatas(promptType).keySet().stream().toList();

        List<ContainerChildComponent> components = new ArrayList<>();
        components.add(TextDisplay.of("# Available Prompts"));
        components.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (preDescription != null) {
            components.add(TextDisplay.of(preDescription));
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
        }

        int show = 10;
        int maxSize = Math.max(1, (int) Math.ceil((double) display.size() / show));

        int index = 0;

        if (!message.getComponents().isEmpty()) {
            Optional<ContainerChildComponentUnion> footerUnion = message.getComponents().getFirst().asContainer().getComponents().stream()
                    .filter(component -> component.getUniqueId() == 121)
                    .findFirst();
            if (footerUnion.isPresent()) {
                String footerText = footerUnion.get().asTextDisplay().getContent();
                int indexOfSlash = footerText.indexOf("/");
                if (indexOfSlash != -1)
                    index = Integer.parseInt(footerText.substring(indexOfSlash - 1, indexOfSlash)) - 1;
                // gets the number before the /
            }
        }
        if (direction != null) {
            if (direction == Direction.NEXT)
                index++;
            if (direction == Direction.BACK)
                index--;
        }
        index = Math.max(0, Math.min(index, maxSize - 1));

        int start = index * show;
        int end = Math.min(start + show, display.size());
        start = Math.min(start, end);

        StringBuilder description = new StringBuilder("```\n");

        display = display.subList(start, end);
        if (displayItem == null)
            displayItem = (type, string) -> string;
        for (int i = 0; i < display.size(); i++) {
            String string = display.get(i);
            description.append(displayItem.apply(promptType, string));

            description.append("\n");
        }
        description.append("```");
        components.add(TextDisplay.of(description.toString()).withUniqueId(100));

        components.add(TextDisplay.of("-# Displaying " + promptType.displayName + ": " + (index + 1) + "/" + maxSize).withUniqueId(121));
        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        return components;
    }

    public static void createPromptViewer(InteractionHook hook, PromptType promptType, Long timeOut) {
        Consumer<Message> consumer = message -> createPromptViewer(message, promptType, null, timeOut);

        if (hook.hasCallbackResponse()) {
            consumer.accept(hook.getCallbackResponse().getMessage());
        } else {
            hook.retrieveOriginal().queue(consumer, e -> Constants.LOGGER.error("Failed to get message for prompt viewer", e));
        }
    }

    public static void createPromptViewer(InteractionHook hook, PromptType promptType, Long timeOut, int forceIndex) {
        Consumer<Message> consumer = message -> {

            List<ContainerChildComponentUnion> components = new ArrayList<>(message.getComponents().getFirst().asContainer().getComponents());

            components.stream().filter(component -> component.getUniqueId() == 121)
                    .findFirst().ifPresentOrElse(footer -> {
                        String footerText = footer.asTextDisplay().getContent();
                        int slash = footerText.indexOf("/");
                        if (slash != -1) {
                            footerText = footerText.substring(0, slash - 1)
                                    + forceIndex + footerText.substring(slash);
                        }
                        message.editMessageComponents(message.getComponentTree()
                                .replace(ComponentReplacer.byId(121, footer.asTextDisplay().withContent(footerText)))
                                .getComponents()).queue((ignored) ->
                                createPromptViewer(message, promptType, null, timeOut), t -> Constants.LOGGER.error("Failed to create prompt viewer", t));
                    }, () -> createPromptViewer(message, promptType, null, timeOut));

        };

        if (hook.hasCallbackResponse()) {
            consumer.accept(hook.getCallbackResponse().getMessage());
        } else {
            hook.retrieveOriginal().queue(consumer, e -> Constants.LOGGER.error("Failed to get message for prompt viewer", e));
        }
    }

    public static void createPromptViewer(Message message, PromptType promptType, Direction direction, Long timeOut) {
        createPromptViewer((direction1) -> createPromptViewerContainer(message, null, promptType, direction1, null),
                message, promptType, true, true,
                null, direction, timeOut);
    }

    public static void createPromptViewer(Function<Direction, List<ContainerChildComponent>> buildContainer, Message message, PromptType promptType, boolean editable, boolean canGoBack,
                                          List<StringSelectMenu.Builder> onSelects, Direction direction, Long timeOut, Button... buttons) {
        List<ContainerChildComponent> components = buildContainer.apply(direction);

        TextDisplay descriptionOptions =
                (TextDisplay) components.stream().filter(child -> child.getUniqueId() == 100)
                        .findFirst().orElseThrow();
        List<SelectOption> options =
                getOptionsFromViewer(descriptionOptions.getContent());
        if (options != null) {
            if (editable) {
                components.add(
                        ActionRow.of(InteractionCreator.createStringMenu(Interactions.getPromptEditMenu(promptType, false))
                                .setRequiredRange(1, 1)
                                .setPlaceholder("Edit Prompt")
                                .addOptions(options)
                                .build())
                );
                components.add(
                        ActionRow.of(InteractionCreator.createStringMenu(Interactions.getDeletionMenu(promptType))
                                .setMaxValues(25)
                                .setPlaceholder("Delete Prompts")
                                .addOptions(options)
                                .build())
                );
            }
            if (onSelects != null) {
                for (StringSelectMenu.Builder builder : onSelects) {
                    StringSelectMenu.Builder copy = StringSelectMenu.create(builder.getCustomId())
                            .addOptions(builder.getOptions())
                            .setMinValues(builder.getMinValues())
                            .setMaxValues(builder.getMaxValues())
                            .setPlaceholder(builder.getPlaceholder());

                    components.add(ActionRow.of(
                            copy.addOptions(options)
                                    .build()));
                }
            }
        }
        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(ActionRow.of(
                InteractionCreator.createButton("<--", (event) -> {
                    event.deferEdit().queue();
                    createPromptViewer(buildContainer, message, promptType, editable, canGoBack, onSelects, Direction.BACK, timeOut, buttons);

                }).withStyle(ButtonStyle.SECONDARY),
                InteractionCreator.createButton("-->", (event) -> {
                    event.deferEdit().queue();
                    createPromptViewer(buildContainer, message, promptType, editable, canGoBack, onSelects, Direction.NEXT, timeOut, buttons);

                }).withStyle(ButtonStyle.SECONDARY)
        ));

        if (buttons.length > 0) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(ActionRow.of(buttons));
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (canGoBack || editable) {
            ArrayList<Button> itemComponents = new ArrayList<>();
            if (canGoBack)
                itemComponents.add(InteractionCreator.createPermanentButton(Button.primary("view_dashboard_2", "View Dashboard"), (event) -> {
                    event.deferEdit().queue();
                    try {
                        createDashboard(event.getMessage());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).withEmoji(Emoji.fromFormatted("🔝")));
            if (editable)
                itemComponents.add(InteractionCreator.createButton("Create Prompt", (event) -> {
                    if (!Util.hasMasterPermission(event.getMember())) {
                        event.reply("nuh uh little bro bro, you dont got permission").setEphemeral(true).queue();
                        return;
                    }

                    replyCreatingPrompt(event, promptType);
                }).withEmoji(Emoji.fromFormatted("✏️")));

            components.add(ActionRow.of(itemComponents));
        }

        MessageEditAction editAction = message
                .editMessageComponents(Util.createBotContainer(components))
                .useComponentsV2()
                .setFiles(List.of());
        editAction.queue(InteractionCreator.queueTimeoutComponents(timeOut), e -> Constants.LOGGER.error("Failed to get message for prompt viewer", e));
    }

    public static void createDashboard(Message message) throws IOException {
        Roleplay roleplay = AIBot.bot.getChat(message.getGuild());

        boolean isGif = Cats.isGif();
        Random random = new Random();
        byte[] data = random.nextBoolean() ? Cats.getCat() : Util.getRandomImage();
        if (data == null)
            data = Cats.getCat();
        String fileName = isGif ? "image.gif" : "image.png";

        Server server = AIBot.bot.getServerData(message.getGuild());
        String key = server.getLLMKey();

        ArrayList<Button> roleplayComponents = new ArrayList<>();

        roleplayComponents.add(InteractionCreator.createButton("Create Roleplay", (event) -> {
                    if (server.getInstructionDatas().isEmpty() || server.getCharacterDatas().isEmpty() || server.getWorldDatas().isEmpty()) {
                        event.reply("Must at least have one instruction, character and world created in the bot in order to start a roleplay!").setEphemeral(true).queue();
                        return;
                    }

                    if (key == null || key.isEmpty()) {
                        event.reply("No API key set! One must be set in the configuration first in order to roleplay!").setEphemeral(true).queue();
                        return;
                    }

                    event.replyModal(InteractionCreator.createModal("Name Roleplay", modal -> {
                        modal.deferEdit().queue();

                        String name = modal.getValue("name").getAsString();

                        HashMap<PromptType, ArrayList<String>> datas = new HashMap<>();
                        datas.put(PromptType.INSTRUCTION, new ArrayList<>());
                        datas.put(PromptType.WORLD, new ArrayList<>());
                        datas.put(PromptType.CHARACTER, new ArrayList<>());

                        BiConsumer<StringSelectInteractionEvent, HashMap<String, ? extends Data>> viewPromptConsumer =
                                (onSelect, dataList) -> {
                                    onSelect.deferReply(true).queue();
                                    try {
                                        Data prompt = dataList.get(onSelect.getSelectedOptions().getFirst().getValue());
                                        onSelect.getHook()
                                                .sendFiles(FileUpload.fromData(prompt.getPrompt().getBytes(), "prompt"))
                                                .setEphemeral(true)
                                                .queue();
                                    } catch (Exception e) {
                                        onSelect.getHook().editOriginal("Failed to retrieve the prompt!").queue();
                                    }
                                };

                        BiFunction<PromptType, String, String> enabledData = (promptType, string) -> datas.get(promptType).contains(string) ? string + "✅" : string;

                        Consumer<Integer> nextPromptType = new Consumer<>() {
                            @Override
                            public void accept(Integer nextInt) {
                                PromptType promptType = PromptType.values()[nextInt];
                                String display = promptType.displayName.toLowerCase();

                                ArrayList<StringSelectMenu.Builder> selects = new ArrayList<>();

                                selects.add(InteractionCreator.createStringMenu(onSelect -> {
                                    onSelect.deferEdit().queue();
                                    onSelect.getSelectedOptions().forEach(selectOption -> {
                                        ArrayList<String> prompts = datas.get(promptType);
                                        String option = selectOption.getValue();
                                        if (!prompts.contains(option))
                                            prompts.add(option);
                                        else
                                            prompts.remove(option);
                                    });
                                    accept(nextInt);
                                }).setMaxValues(25).setPlaceholder("Add/Remove Prompts"));

                                selects.add(
                                        InteractionCreator.createStringMenu(onSelect ->
                                                        viewPromptConsumer.accept(onSelect, server.getDatas(promptType)))
                                                .setRequiredRange(1, 1)
                                                .setPlaceholder("View Prompts")
                                );

                                Consumer<Message> onMessage = givenMsg -> {
                                    createPromptViewer((direction) -> createPromptViewerContainer(
                                                    givenMsg,
                                                    "Select " + display + " prompts to use in the roleplay.",
                                                    promptType,
                                                    direction, enabledData), givenMsg, promptType, false, true,
                                            selects, null, 90L, InteractionCreator.createButton(Emoji.fromFormatted("✅"), buttonEvent -> {
                                                if (datas.get(promptType).isEmpty()) {
                                                    buttonEvent.reply("Need at least one set of " + display + "!").setEphemeral(true).queue();
                                                    return;
                                                }
                                                if (nextInt + 1 >= PromptType.values().length) {
                                                    buttonEvent.getMessage().delete().queue();

                                                    try {
                                                        roleplay.startRoleplay(
                                                                buttonEvent,
                                                                name,
                                                                datas.get(PromptType.INSTRUCTION).stream().map(string -> server.getInstructionDatas().get(string)).toList(),
                                                                datas.get(PromptType.WORLD).stream().map(string -> server.getWorldDatas().get(string)).toList(),
                                                                datas.get(PromptType.CHARACTER).stream().map(string -> server.getCharacterDatas().get(string)).toList(),
                                                                hook ->
                                                                        roleplay.getDatas(PromptType.CHARACTER).forEach((chrData) ->
                                                                                roleplay.promptCharacterToRoleplay((Character) chrData, null, false))
                                                        );
                                                    } catch (ExecutionException | InterruptedException | IOException e) {
                                                        buttonEvent.reply("Failed to start chat!").setEphemeral(true).queue();
                                                    }
                                                } else {
                                                    buttonEvent.deferEdit().queue();
                                                    accept(nextInt + 1);
                                                }
                                            }));
                                };

                                InteractionHook hook = event.getHook();
                                if (hook.hasCallbackResponse()) {
                                    onMessage.accept(hook.getCallbackResponse().getMessage());
                                } else {
                                    hook.retrieveOriginal().queue(onMessage, onFail -> Constants.LOGGER.error("Failed to continue creating roleplay", onFail));
                                }
                            }
                        };

                        nextPromptType.accept(0);
                    }).addComponents(ActionRow.of(
                            TextInput.create("name", "Name", TextInputStyle.SHORT)
                                    .setRequired(true)
                                    .setPlaceholder("A very sussy roleplay")
                                    .build()
                    )).build()).queue();
                }).withEmoji(Emoji.fromFormatted("✏️"))
                .withStyle(ButtonStyle.PRIMARY).withDisabled(message.getChannelType().isThread()));

        roleplayComponents.add(InteractionCreator.createButton("Stop Roleplay", (event) ->
                {
                    if (roleplay.isMakingResponse()) {
                        event.reply("Cannot stop the roleplay while a message is being generated!").setEphemeral(true).queue();
                        return;
                    }
                    event.deferEdit().queue();
                    roleplay.stopRoleplay();
                    try {
                        createDashboard(event.getMessage());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .withStyle(ButtonStyle.DANGER)
                .withEmoji(Emoji.fromFormatted("🛑"))
                .withDisabled(!roleplay.isRunningRoleplay()));

        roleplayComponents.add(InteractionCreator.createButton("Restart Roleplay", (event) -> {
                    if (roleplay.isMakingResponse()) {
                        event.reply("Cannot restart the roleplay while a message is being generated!").setEphemeral(true).queue();
                        return;
                    }
                    event.replyModal(InteractionCreator.createModal("Name Roleplay", modal -> {
                        String name = modal.getValue("name").getAsString();

                        try {
                            roleplay.startRoleplay(
                                    modal,
                                    name,
                                    roleplay.getDatas(PromptType.INSTRUCTION).stream().map(dat -> (Instruction) dat).toList(),
                                    roleplay.getDatas(PromptType.WORLD).stream().map(dat -> (World) dat).toList(),
                                    roleplay.getDatas(PromptType.CHARACTER).stream().map(dat -> (Character) dat).toList(),
                                    hook ->
                                            roleplay.getDatas(PromptType.CHARACTER).forEach((characterData) ->
                                                    roleplay.promptCharacterToRoleplay((Character) characterData, null, false)));
                        } catch (Exception e) {
                            event.getHook().editOriginal("Failed to restart chat!").queue();
                        }
                    }).addComponents(ActionRow.of(
                            TextInput.create("name", "Name", TextInputStyle.SHORT)
                                    .setRequired(true)
                                    .setPlaceholder("A very sussy roleplay")
                                    .build()
                    )).build()).queue();
                }).withStyle(ButtonStyle.SECONDARY).withEmoji(Emoji.fromFormatted("⏪"))
                .withDisabled(!roleplay.isRunningRoleplay() || message.getChannelType().isThread()));

        List<ContainerChildComponent> components = new ArrayList<>();
        components.add(TextDisplay.of("# Dashboard"));

        // Check current AI provider and show appropriate credits/quota info
        AIProvider currentAiProvider = roleplay.getAIProvider();
        String creditsInfo = "";
        
        if (currentAiProvider == AIProvider.OPENROUTER) {
            double remaining = 0;
            if (key != null && !key.isEmpty()) {
                OkHttpClient client = new OkHttpClient.Builder().build();

                Request request = new Request.Builder()
                        .url("https://openrouter.ai/api/v1/credits")
                        .header("Authorization", "Bearer " + key)
                        .get()
                        .build();

                Call call = client.newCall(request);
                try (Response response = call.execute()) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode node = mapper.readTree(response.body().string());
                    JsonNode dataNode = node.get("data");
                    if (dataNode != null) {
                        double totalCredits = dataNode.get("total_credits").asDouble();
                        double totalUsage = dataNode.get("total_usage").asDouble();
                        remaining = totalCredits - totalUsage;
                    }
                } catch (Exception e) {
                    Constants.LOGGER.error("Failed to get usage amount for key", e);
                }
            }
            creditsInfo = "**Credits Remaining:** $" + String.format("%.2f", remaining);
        } else if (currentAiProvider == AIProvider.GOOGLE_AI) {
            creditsInfo = "**Quota Status:** Free Tier (Check [AI Studio](https://aistudio.google.com/) for limits)";
        }

        String provider = roleplay.getProvider();

        components.add(TextDisplay.of("**AI Model:** " + roleplay.getModel().getDisplay()));
        components.add(TextDisplay.of("**Forced Provider:** " + (provider != null && !provider.isEmpty() ? provider : "None")));
        components.add(TextDisplay.of(creditsInfo));

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("**Max Tokens:** " + roleplay.getMaxTokens()));
        components.add(Separator.createDivider(Separator.Spacing.SMALL));

        components.add(TextDisplay.of("### Roleplay Status: " + (roleplay.isRunningRoleplay() ? "Ongoing" : "Stopped")));
        if (roleplay.isRunningRoleplay()) {
            components.add(TextDisplay.of("[More Information](" + roleplay.getChannel().getJumpUrl() + ")"));
        }
        components.add(ActionRow.of(roleplayComponents));

        components.add(Separator.createDivider(Separator.Spacing.LARGE));

        components.add(TextDisplay.of("### View Prompts"));

        components.add(ActionRow.of(
                InteractionCreator.createPermanentButton(Button.secondary("view_instructions", "View Instructions"), (event) -> {
                    event.deferEdit().queue();
                    createPromptViewer(event.getHook(), PromptType.INSTRUCTION, null, 0);
                }).withEmoji(Emoji.fromFormatted("📋")),
                InteractionCreator.createPermanentButton(Button.secondary("view_worlds", "View Worlds"), (event) -> {
                    event.deferEdit().queue();
                    createPromptViewer(event.getHook(), PromptType.WORLD, null, 0);
                }).withEmoji(Emoji.fromFormatted("🌍")),
                InteractionCreator.createPermanentButton(Button.secondary("view_characters", "View Characters"), (event) -> {
                    event.deferEdit().queue();
                    createPromptViewer(event.getHook(), PromptType.CHARACTER, null, 0);
                }).withEmoji(Emoji.fromFormatted("🧝"))));

        components.add(Separator.createDivider(Separator.Spacing.LARGE));

        components.add(TextDisplay.of("### Configuration"));

        components.add(ActionRow.of(
                InteractionCreator.createPermanentButton(Button.secondary("server_configuration", "Server Configuration"), (event) -> {
                    event.deferEdit().queue();

                    if (!Util.hasMasterPermission(event.getMember())) {
                        event.getHook().editOriginal("nuh uh little bro bro, you dont got permission").queue();
                        return;
                    }
                    createConfigViewer(event.getHook(), 0);
                }).withEmoji(Emoji.fromFormatted("⚙️")),
                Button.link("https://openrouter.ai/models?order=pricing-low-to-high", "Free AI models")
                        .withEmoji(Emoji.fromFormatted("🤖"))));

        MessageEditAction editAction = message
                .editMessageComponents(Util.createBotContainer(components))
                .useComponentsV2();
        editAction.queue(InteractionCreator.queueTimeoutComponents(null));
    }

    public static Button createCancellableResponse() {
        return InteractionCreator.createPermanentButton(Button.danger("cancel_response",
                Emoji.fromFormatted("🛑")), event -> {
            event.deferEdit().queue();
            AIBot.bot.getChat(event.getGuild()).cancelGeneration();
        });
    }

    public static Consumer<ButtonInteractionEvent> getResponseInfo() {
        return event -> {
            try {
                event.deferReply(true).useComponentsV2().queue();
                Roleplay chat = AIBot.bot.getChat(event.getGuild());
                PromptInfo responseInfo = chat.getFailedResponseInfo();
                if (responseInfo == null) {
                    List<ResponseInfo> responseSwipes = chat.getSwipes();
                    if (responseSwipes == null || responseSwipes.isEmpty()) {
                        throw new RuntimeException("No swipes!");
                    } else {
                        responseInfo = responseSwipes.get(chat.getCurrentSwipe());
                    }
                }

                List<ContainerChildComponent> containerComponents = new ArrayList<>();

                byte[] data = null;
                try {
                    InputStream stream = chat.getCurrentCharacter().downloadAvatar();
                    if (stream != null) {
                        try (InputStream avatarStream = stream) {
                            data = avatarStream.readAllBytes();
                        }
                    }
                } catch (Exception e) {
                    Constants.LOGGER.error("Failed to get avatar, using backup", e);
                }
                if (data == null) {
                    byte[] fallbackImage = Util.getRandomImage();
                    if (fallbackImage != null) {
                        data = fallbackImage;
                    }
                }
                
                // Add response information section with or without thumbnail
                if (data != null) {
                    String type = "png"; // default
                    try {
                        Tika tika = new Tika();
                        String detectedType = tika.detect(data);
                        if (detectedType != null && detectedType.contains("/")) {
                            type = detectedType.substring(detectedType.indexOf("/") + 1);
                        }
                    } catch (Exception e) {
                        Constants.LOGGER.warn("Failed to detect file type, using default", e);
                    }

                    containerComponents.add(Section.of(
                            Thumbnail.fromFile(FileUpload.fromData(data, "avatar." + type)),
                            TextDisplay.of("# Response Information"),
                            TextDisplay.of("Metadata about the generated response")
                    ));
                } else {
                    // No image available, just add text displays directly
                    containerComponents.add(TextDisplay.of("# Response Information"));
                    containerComponents.add(TextDisplay.of("Metadata about the generated response"));
                }
                containerComponents.add(Separator.createDivider(Separator.Spacing.SMALL));
                containerComponents.add(TextDisplay.of("-# The json file sent to the LLM for a response"));
                containerComponents.add(FileDisplay.fromFile(FileUpload.fromData(responseInfo.getPrompt().getBytes(), "prompt.json")));
                containerComponents.add(Separator.createDivider(Separator.Spacing.SMALL));

                if (responseInfo instanceof ProviderInfo providerInfo) {
                    String reply = "**Model:** " + providerInfo.getModel() +
                            "\n**Provider:** " + providerInfo.getProvider() +
                            "\n**Prompt Tokens:** " + (providerInfo.getPromptTokens().isPresent() ? providerInfo.getPromptTokens().get() : "Unknown") +
                            "\n**Completion Tokens:** " + (providerInfo.getCompletionTokens().isPresent() ? providerInfo.getCompletionTokens().get() : "Unknown") +
                            "\n**Total Tokens:** " + providerInfo.getTotalTokens() +
                            "\n**Price:** " + (providerInfo.getPrice() != null ? "$" + String.format("%.4f", providerInfo.getPrice()) : "Unknown");

                    containerComponents.add(TextDisplay.of(reply));
                } else {
                    // is failed response
                    containerComponents.add(TextDisplay.of("-# The response returned by OpenRouter"));
                    containerComponents.add(FileDisplay.fromFile(FileUpload.fromData(responseInfo.getResponse().getBytes(), "response.json")));
                }

                event.getHook().editOriginalComponents(Util.createBotContainer(containerComponents))
                        .useComponentsV2()
                        .queue();
            } catch (Exception e) {
                try {
                    event.getHook().editOriginal("Failed to retrieve response information!").queue();
                } catch (Exception hookError) {
                    // If hook fails, try direct reply as fallback
                    try {
                        event.reply("Failed to retrieve response information!").setEphemeral(true).queue();
                    } catch (Exception replyError) {
                        // Both methods failed, just log
                        Constants.LOGGER.error("Failed to send error message for response info", replyError);
                    }
                }
                Constants.LOGGER.error("Failed to retrieve response information", e);
            }
        };
    }

    public static Button getContinue() {
        return InteractionCreator.createPermanentButton(Button.primary("start_here", "Continue"),
                        button -> {
                            try {
                                button.deferEdit().queue();
                                AIBot.bot.getChat(button.getGuild()).startRoleplay(
                                        button.getMessage(), button.getHook(), null
                                );
                            } catch (Exception e) {
                                button.getChannel().sendMessage("Failed to continue roleplay!\nError: " + e)
                                        .queue(msg -> msg.delete().queueAfter(10, TimeUnit.SECONDS));
                                Constants.LOGGER.error("Failed to continue roleplay", e);
                            }
                        })
                .withEmoji(Emoji.fromFormatted("🔁"));
    }

    public static Container getHelpContainer(){
        List<ContainerChildComponent> componentList = new ArrayList<>();

        componentList.add(TextDisplay.of("# Help Menu 😣"));
        componentList.add(TextDisplay.of("Use /menu to open up the main dashboard at any time"));
        componentList.add(TextDisplay.of("-# Hiya, thanks for adding me! 👋"));
        componentList.add(Separator.createDivider(Separator.Spacing.SMALL));
        componentList.add(TextDisplay.of("## How can I get started? ❓"));
        componentList.add(TextDisplay.of("1. Get an [OpenRouter API Key](https://openrouter.ai/settings/keys) if you already do not have one. You may need to make an account. This is where we will get our AI models."));
        componentList.add(TextDisplay.of("2. Get an [IMGBB API Key](https://api.imgbb.com/) if you already do not have one. This is the API we will use to upload PFPs of your characters."));
        componentList.add(TextDisplay.of("3. Run /menu, go to Server Configuration, and set your API keys to the imgbb_key and openrouter_key fields. Be sure to not add any additional spaces that could mess up the key."));
        componentList.add(TextDisplay.of("4. You are done setting up the bot! You may press \"Start Roleplay\" in the main dashboard to get started with a new roleplay :sunglasses:"));
        componentList.add(Separator.createDivider(Separator.Spacing.SMALL));
        componentList.add(TextDisplay.of("## How can I create custom characters, instructions, and world prompts? 🌍🧝🤖"));
        componentList.add(TextDisplay.of("1. Run /menu and choose from Characters | Instructions | Worlds"));
        componentList.add(TextDisplay.of("2. If you want to edit a prompt, select \"Edit Prompt\" and choose a prompt. Otherwise click \"Create Prompt\""));
        componentList.add(TextDisplay.of("3. Depending on what you selected, you can either choose a name, edit the description, and etc. Go wild here!"));
        componentList.add(Separator.createDivider(Separator.Spacing.SMALL));
        componentList.add(TextDisplay.of("## Can I save specific roleplays and come back later?"));
        componentList.add(TextDisplay.of("Yep! This is the whole reason why the bot creates your roleplays in threads. You can press \"Continue\" at any time to continue from a specific roleplay."));
        componentList.add(Separator.createDivider(Separator.Spacing.SMALL));
        componentList.add(TextDisplay.of("## How can I set permissions for what someone can do? 🛑"));
        componentList.add(TextDisplay.of("At the moment everyone can roleplay with the bot. The only few things people can't do is create and delete prompts or edit the server configuration.\n\nFor that, you must use /set_bot_role to assign a role that bypasses these restrictions."));
        return Util.createBotContainer(componentList);
    }
}
