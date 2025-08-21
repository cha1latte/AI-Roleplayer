package discord.mian.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import discord.mian.Constants;
import discord.mian.Direction;
import discord.mian.Util;
import discord.mian.data.Data;
import discord.mian.data.PromptType;
import discord.mian.data.Server;
import discord.mian.data.ServerConfig;
import discord.mian.data.character.Character;
import discord.mian.data.instruction.Instruction;
import discord.mian.data.world.World;
import discord.mian.interactions.InteractionCreator;
import discord.mian.interactions.Interactions;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.replacer.ComponentReplacer;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.WebhookAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class Roleplay {
    // limit roleplay to one channel lmfao

    private Call callForResponse;
    private boolean makingResponse;
    private int maxTokens;
    private double temperature;
    private Model model;
    private String provider;
    private final Guild guild;
    private final Server server;

    private ThreadChannel historyMarker;
    private long parentID;
    private Webhook webhook;

    private Message latestAssistantMessage;
    private Message errorMsgCleanup;
    private int currentSwipe;
    private ArrayList<ResponseInfo> swipes;
    private FailedResponseInfo failedResponseInfo;

    private final List<Map.Entry<Character, Boolean>> queuedResponses = new ArrayList<>();

    private final EncodingRegistry registry;

    // make possible to swipe messages
    private final HashMap<String, Instruction> instructions;
    private final HashMap<String, World> worldLore;
    private final HashMap<String, Character> characters;
    private Character currentCharacter;
    private boolean runningRoleplay = false;

    public Roleplay(Guild guild) {
//        this.llm = SimpleOpenAI.builder()
//                .baseUrl(Constants.BASE_URL)
//                .apiKey(Constants.LLM_KEY)
//                .build();

        this.server = AIBot.bot.getServerData(guild);

        ServerConfig configuration = server.getConfig();

        this.setTemperature(server.getConfig().get("temperature", Double.class).getValue());
        this.setMaxTokens(configuration.get("tokens", Integer.class).getValue());

        String model = configuration.get("model", String.class).getValue();
        String id = model.substring(0, model.indexOf("|"));
        String display = model.substring(model.indexOf("|") + 1);

        this.setModel(new Model(id, display));
        this.setProvider(configuration.get("provider", String.class).getValue());
        this.registry = Encodings.newDefaultEncodingRegistry();
        this.guild = guild;

        instructions = new HashMap<>();
        worldLore = new HashMap<>();
        characters = new HashMap<>();
    }

    public String combinePrompts(List<ChatMessage> msgs) {
        String prevType = "";
        StringBuilder combinedText = new StringBuilder();
        for (ChatMessage msg : msgs) {
            if (msg instanceof ChatMessage.UserMessage message) {
//                if(!prevType.equals("user"))
//                    combinedText.append("USER:");
                combinedText.append("\n").append(message.getContent());
                prevType = "user";
            } else if (msg instanceof ChatMessage.AssistantMessage message) {
//                if(!prevType.equals("assistant"))
//                    combinedText.append("ASSISTANT:");
                combinedText.append("\n").append(message.getContent());
                prevType = "assistant";
            } else if (msg instanceof ChatMessage.SystemMessage message) {
                if (!prevType.equals("system"))
                    combinedText.append("SYSTEM:");
                combinedText.append("\n").append(message.getContent());
                prevType = "system";
            }
        }
        return combinedText.toString();
    }

    public List<ChatMessage> trimListToMeetTokens(List<ChatMessage> msgs, int startAt) {
        String id = this.model.id;
        Encoding enc = registry.getEncodingForModel(id.substring(id.lastIndexOf("/")))
                .orElse(registry.getEncoding(EncodingType.CL100K_BASE));

        String combinedText = combinePrompts(msgs);

        int tokens = enc.countTokens(combinedText);
        if (tokens >= maxTokens) {
            int difference = tokens - maxTokens;

            int toRemove = 0;
            int current = 0;
            for (int i = startAt; i < msgs.size() && current < difference; i++) {
                ChatMessage msg = msgs.get(i);
                String content = null;
                // inaccurate, we should return maybe the entire json?
                if (msg instanceof ChatMessage.UserMessage message) {
                    content = message.getContent().toString();
                } else if (msg instanceof ChatMessage.AssistantMessage message) {
                    content = message.getContent().toString();
                } else if (msg instanceof ChatMessage.SystemMessage message) {
                    content = message.getContent();
                }
                current += enc.countTokens(content);
                toRemove++;
            }
            List<ChatMessage> newList = msgs.subList(0, startAt);
            newList.addAll(msgs.subList(toRemove + startAt, msgs.size()));
            return newList;
        }

        return msgs;
    }

    public void creatingResponseFromDiscordMessage() {
        if (makingResponse)
            throw new RuntimeException("Already generating a response!");
        this.makingResponse = true;
        if (latestAssistantMessage != null) {
            latestAssistantMessage.editMessage(latestAssistantMessage.getContentRaw())
                    .setComponents(ActionRow.of(Interactions.createCancellableResponse())).queue();
        }
    }

    public void finishedDiscordResponse(String finalResponse) {
        this.makingResponse = false;
        if (latestAssistantMessage != null && finalResponse != null) {
            ArrayList<ActionRowChildComponent> components = new ArrayList<>();
            components.add(InteractionCreator.createPermanentButton(Button.secondary("swipe_left", "<--"), Interactions.getSwipe(Direction.BACK)));
            components.add(InteractionCreator.createPermanentButton(Button.secondary("swipe_right", "-->"), Interactions.getSwipe(Direction.NEXT)));

            if (this.failedResponseInfo != null || (this.swipes != null && !this.swipes.isEmpty())) {
                components.add(InteractionCreator.createPermanentButton(Button.secondary("get_response_info", Emoji.fromFormatted("❔")),
                        Interactions.getResponseInfo()));
            }

            if (errorMsgCleanup == null) {
                components.add(InteractionCreator.createPermanentButton(Button.danger("destroy", Emoji.fromFormatted("🗑")),
                        Interactions.getDestroyMessage()));
                components.add(InteractionCreator.createPermanentButton(Button.success("edit", Emoji.fromFormatted("🪄")),
                        Interactions.getEditMessage()));
            }

            latestAssistantMessage.editMessage(finalResponse)
                    .setComponents(ActionRow.of(components)).queue();
        }
        if (this.isRunningRoleplay() && historyMarker != null) {
            historyMarker.retrieveParentMessage().queue(
                    message -> {
                        RestAction<List<ChatMessage>> getMessages = getHistory();
                        Container container = message.getComponentTree().getComponents().getFirst().asContainer();

                        Consumer<List<ChatMessage>> onRetrievedMessages = list ->
                                message.editMessageComponents(container.replace(ComponentReplacer.byId(Constants.HISTORY_COUNT,
                                        TextDisplay.of("**Chat Messages:** " + (list.size() + 1))
                                                .withUniqueId(Constants.HISTORY_COUNT)))).useComponentsV2().queue();

                        if (getMessages != null) {
                            getMessages.queue(onRetrievedMessages,
                                    e -> Constants.LOGGER.error("Failed to update chat history number", e));
                        } else {
                            onRetrievedMessages.accept(new ArrayList<>());
                        }
                    },
                    e -> Constants.LOGGER.error("Failed to update chat history number", e)
            );
        }
    }

    public Character findRespondingCharacterFromContent(String content) {
        content = content.toLowerCase();
        final String finalContent = content;

        Optional<Character> foundCharacter = server.getCharacterDatas().entrySet().stream().filter(entry ->
                        finalContent.contains(entry.getValue().getFirstName().toLowerCase())).map(entry -> entry.getValue())
                .findFirst();

        if (foundCharacter.isPresent() && getCharacters().containsKey(foundCharacter.get().getName())) {
            return foundCharacter.get();
        }
        return null;
    }

    public Character findRespondingCharacterFromMessage(Message msg) {
        if (msg.getReferencedMessage() != null && msg.getReferencedMessage().isWebhookMessage()) {
            Character character =
                    server.getCharacterDatas().get(msg.getReferencedMessage().getAuthor().getName());

            if (character == null || !getCharacters().containsKey(character.getName())) {
                return null;
            }
            return character;
        }
        return null;
    }

    public RestAction<ExtrasChatRequest> createChatRequest(Character character) {
        ExtrasChatRequest.ExtrasChatRequestBuilder requestBuilder = ExtrasChatRequest
                .extrasBuilder()
                .setProviderFallback(server.getConfig().get("use_fallback_providers", Boolean.class).getValue());
        if (provider != null && !provider.isEmpty())
            requestBuilder = requestBuilder.setProviders(provider);

        ExtrasChatRequest.ExtrasChatRequestBuilder finalRequestBuilder = requestBuilder;
        return getHistory(character).map(history ->
                finalRequestBuilder.build(ChatRequest.builder()
                        .maxCompletionTokens(this.maxTokens)
                        .model(model.id)
                        .temperature(temperature)
                        .stream(true)
                        .messages(history)));
    }

    private RestAction<ResponseInfo> generateResponse(Character character, Consumer<String> consumer) {
        RestAction<ExtrasChatRequest> chatRequestAction = createChatRequest(character);

        return chatRequestAction.map(chatRequest -> {
            ObjectMapper mapper = new ObjectMapper();
            ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
            String json;
            String fullPrompt;
            String failedResult = "";
            try {
                json = mapper.writeValueAsString(chatRequest);
                fullPrompt = writer.writeValueAsString(chatRequest);

                OkHttpClient client = new OkHttpClient.Builder().build();
                Request request = new Request.Builder()
                        .url(Constants.BASE_URL + "/v1/chat/completions")
                        .header("Authorization", "Bearer " + server.getLLMKey())
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(json, MediaType.get("application/json; charset=utf-8")))
                        .build();

                Integer errorCode = null;
                this.callForResponse = client.newCall(request);
                try (Response response = callForResponse.execute()) {
                    String fullResponse = "";

                    if (!response.isSuccessful()) {
                        errorCode = response.code();
                        failedResult = writer.writeValueAsString(mapper.readValue(response.body().string(), Object.class));
                        throw new RuntimeException(mapper.readTree(failedResult).get("error").get("message").asText());
                    }

                    BufferedSource source = response.body().source();
                    String copyOfResponse = "";
                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();

                        if (line.equals("data: [DONE]"))
                            continue;


                        if (line.startsWith("data: ")) {
                            if (line.contains("usage"))
                                copyOfResponse = line.substring(6);
                        } else {
                            continue;
                        }

                        if (!line.contains("choices"))
                            continue;

                        JsonNode responseJson = mapper.readTree(line.substring(5).trim());
                        String content = responseJson.get("choices").get(0).get("delta").get("content").toString();
                        content = content.substring(1, content.length() - 1);

                        if ((fullResponse + content).length() < 2000) {
                            fullResponse += content;
                            consumer.accept(fullResponse);
                        } else {
                            return new ResponseInfo(
                                    model.getDisplay(),
                                    null,
                                    fullResponse,
                                    null,
                                    null,
                                    fullPrompt
                            );
                        }
                    }

                    JsonNode openRouterResponse = mapper.readTree(copyOfResponse);
                    failedResult = writer.writeValueAsString(openRouterResponse);
                    if (!response.isSuccessful()) {
                        errorCode = response.code();
                        throw new RuntimeException(openRouterResponse.get("error").get("message").asText());
                    }
                    this.callForResponse = null;
                    return new ResponseInfo(
                            openRouterResponse.get("model").asText(),
                            openRouterResponse.get("provider").asText(),
                            fullResponse,
                            openRouterResponse.get("usage").get("prompt_tokens").asInt(),
                            openRouterResponse.get("usage").get("completion_tokens").asInt(),
                            fullPrompt
                    );
                } catch (IOException io) {
                    throw new FailedResponseInfo(
                            fullPrompt,
                            failedResult,
                            "Generation was cancelled or an unknown problem occurred"
                    );
                } catch (Exception e) {
                    Constants.LOGGER.error("Failed to get response from provider", e);
                    throw new FailedResponseInfo(
                            fullPrompt,
                            failedResult,
                            "OpenRouter returned error code " + (errorCode != null ? errorCode : "UNKNOWN") + " | " + e.getMessage()
                    );
                }
            } catch (Exception e) {
                this.callForResponse = null;
                if (e instanceof FailedResponseInfo info)
                    throw (info);
                throw (new RuntimeException(e));
            }
        });
    }

    private RestAction<ResponseInfo> streamOnDiscordMessage(Character character, Message msgToEdit) {
        AtomicBoolean queued = new AtomicBoolean(false);
        AtomicLong timeResponseMade = new AtomicLong(System.currentTimeMillis());
        double timeBetween = 1;

        String botUser = character.getName() + ":";
        Function<String, String> reformat = (string) ->
                string.replace(botUser, "")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\\\", "\\");

        RestAction<ResponseInfo> responseInfo = generateResponse(character, response -> {
            if (!queued.get() && System.currentTimeMillis() - timeResponseMade.get() >= timeBetween && !response.isBlank()) {
                queued.set(true);
                Consumer<Object> onComplete = ignored -> {
                    queued.set(false);
                    timeResponseMade.set(System.currentTimeMillis());
                };
                String newContent = reformat.apply(response);
                String total = Util.botifyMessage("Message is currently generating..") + "\n" + newContent;
                if (total.length() < 2000)
                    msgToEdit.editMessage(total).setComponents(ActionRow.of(
                            Interactions.createCancellableResponse()
                    )).queue(onComplete);
            }
        });
        return responseInfo.map(info -> {
            String newContent = info.editResponse(reformat);

            if (newContent.isEmpty())
                throw new RuntimeException("The provider returned no content, try again? :(");
            return info;
        });
    }

    public void promptCharacterToRoleplay(Character character, Message replyTo, boolean triggerAutoResponse) {
        if (isRunningRoleplay()) {
            if (!characters.containsKey(character.getName())) {
                Consumer<Throwable> onFail = t ->
                        Constants.LOGGER.error("Failed to add character into roleplay", t);

                historyMarker.retrieveParentMessage().queue(parentMsg -> {
                    Container container = parentMsg.getComponentTree().getComponents().getFirst().asContainer();
                    TextDisplay charactersDisplay = container.getComponents().stream().filter(component -> component.getUniqueId() == 152)
                            .findFirst().get().asTextDisplay();
                    parentMsg.editMessageComponents(container.replace(ComponentReplacer.byId(152, charactersDisplay.withContent(
                            charactersDisplay.getContent() + ", " + character.getName()
                    )))).useComponentsV2().queue(success -> {
                        addData(PromptType.CHARACTER, character);

                        setCurrentCharacter(character.getName());
                        sendRoleplayMessage(triggerAutoResponse);
                    }, onFail);
                    // adds the character to the container
                });
            } else {
                setCurrentCharacter(character.getName());
                sendRoleplayMessage(triggerAutoResponse);
            }
        }
    }

    public void sendRoleplayMessage(boolean triggerAutoResponse) {
        if (!runningRoleplay) {
            historyMarker.sendMessage(MessageCreateData.fromContent(
                    Util.botifyMessage("Cannot make a response since there is no ongoing chat!")
            )).queue();
            return;
        }
        if (isMakingResponse()) {
            queuedResponses.add(Map.entry(getCurrentCharacter(), triggerAutoResponse));
            return;
        }

        if (currentCharacter == null) {
            historyMarker.sendMessage(MessageCreateData.fromContent(
                    Util.botifyMessage("Cannot make a response since there are no characters in this chat!")
            )).queue();
            return;
        }

        // Check if this is the first message and character has a starting message
        String startingMessage = currentCharacter.getDocument().getStartingMessage();
        if (latestAssistantMessage == null && startingMessage != null && !startingMessage.trim().isEmpty()) {
            // Send the predefined starting message instead of generating
            String avatarLink = currentCharacter.getDocument().getAvatar();
            
            WebhookMessageCreateAction<Message> messageCreateData = webhook.sendMessage(startingMessage.trim())
                    .setThread(historyMarker)
                    .setUsername(currentCharacter.getName());

            if (avatarLink != null && !avatarLink.isBlank() &&
                    (avatarLink.startsWith("https://") || avatarLink.startsWith("http://"))) {
                messageCreateData = messageCreateData.setAvatarUrl(avatarLink);
            }

            messageCreateData.queue(message -> {
                latestAssistantMessage = message;
                swipes = new ArrayList<>();
                swipes.add(new ResponseInfo("Starting Message", "Manual", startingMessage.trim(), 0, 0, "Starting message"));
                currentSwipe = 0;
                
                if (!queuedResponses.isEmpty()) {
                    Map.Entry<Character, Boolean> next = queuedResponses.getFirst();
                    queuedResponses.removeFirst();
                    promptCharacterToRoleplay(next.getKey(), null, next.getValue());
                }
            });
            return;
        }
        if (this.errorMsgCleanup != null) {
            this.errorMsgCleanup.delete().queue(RestAction.getDefaultSuccess(), toThrow -> {
            });
            if (this.latestAssistantMessage == errorMsgCleanup)
                latestAssistantMessage = null;
            this.errorMsgCleanup = null;
        }
        this.failedResponseInfo = null;
        if (this.latestAssistantMessage != null) {
            if (latestAssistantMessage.getContentRaw().isEmpty())
                latestAssistantMessage.delete().queue();
            else
                latestAssistantMessage.editMessageComponents(ActionRow.of(Button.danger("destroy_button", Emoji.fromFormatted("🗑")),
                                Button.success("edit_button", Emoji.fromFormatted("🪄"))))
                        .queue(RestAction.getDefaultSuccess(),
                                (t) -> {
                                });
            latestAssistantMessage = null;
            swipes = null;
            currentSwipe = 0;
        }

        Consumer<Throwable> onError = throwable -> {
            String overrideError = null;
            if (throwable instanceof IllegalArgumentException e2 && e2.getMessage().contains("Content may not be longer")) {
                overrideError = "Response is too long!";
            }
            if (throwable instanceof FailedResponseInfo info)
                this.failedResponseInfo = info;

            currentSwipe = 0;
            this.finishedDiscordResponse(Util.botifyMessage("Failed to send a response due to an exception :< sowwy. If this keeps happening, try using a different AI model or provider.\n\nError: " + (overrideError != null ? overrideError : throwable.getMessage().substring(0, Math.min(throwable.getMessage().length(), 1750)))));
            queuedResponses.clear();
            Constants.LOGGER.error("Failed to generate response", throwable);
        };

        try {
            this.creatingResponseFromDiscordMessage();

            String avatarLink = currentCharacter.getDocument().getAvatar();

            WebhookMessageCreateAction<Message> messageCreateData = webhook.sendMessage(
                            Util.botifyMessage("Currently creating a response! Check back in a second.."))
                    .setThread(historyMarker)
                    .setComponents(ActionRow.of(Interactions.createCancellableResponse()))
                    .setUsername(currentCharacter.getName());

            if (avatarLink != null && !avatarLink.isBlank() &&
                    (avatarLink.startsWith("https://") || avatarLink.startsWith("http://"))) {
                messageCreateData = messageCreateData.setAvatarUrl(avatarLink);
            }

            Consumer<Message> consumer = (aiMsg) -> {

                latestAssistantMessage = aiMsg;
                swipes = new ArrayList<>();
                try {
                    streamOnDiscordMessage(currentCharacter, aiMsg)
                            .queue(responseInfo -> {

                                swipes.add(responseInfo);
                                errorMsgCleanup = null;
                                failedResponseInfo = null;

                                this.finishedDiscordResponse(responseInfo.getResponse());
                                if (!queuedResponses.isEmpty()) {
                                    Map.Entry<Character, Boolean> next = queuedResponses.getFirst();
                                    queuedResponses.removeFirst();
                                    promptCharacterToRoleplay(next.getKey(), null, next.getValue());
                                } else {
                                    if (triggerAutoResponse) {
                                        Character data = findRespondingCharacterFromContent(responseInfo.getResponse());
                                        if (data != null && data != currentCharacter && data.getDocument().getTalkability() >= Math.random()) {
                                            try {
                                                promptCharacterToRoleplay(data, latestAssistantMessage, true);
                                            } catch (Exception e) {
                                                Constants.LOGGER.error("Failed to prompt a response", e);
                                            }
                                        }
                                    }
                                }
                            }, onFail -> {
                                errorMsgCleanup = aiMsg;
                                onError.accept(onFail);
                            });
                } catch (Exception e) {
                    errorMsgCleanup = aiMsg;
                    onError.accept(e);
                }
            };

            messageCreateData.queue(consumer, onError);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    public void swipe(ButtonInteractionEvent event, Direction direction) {
        if (latestAssistantMessage != null) {
            if (event.getMessage().getIdLong() != latestAssistantMessage.getIdLong()) {
                event.getHook().editOriginal("You cannot swipe on this message anymore :(, consider editing it instead!")
                        .queue();
                return;
            }

            if (direction == Direction.BACK) {
                currentSwipe--;
            } else {
                if (currentSwipe + 1 >= swipes.size()) {
                    if (isMakingResponse()
                            || !queuedResponses.isEmpty()) {
                        event.getHook().editOriginal("Cannot make a response since I am already queued to create others!")
                                .queue();
                        return;
                    }
                    Character character = characters.get(latestAssistantMessage.getAuthor().getName());
                    this.creatingResponseFromDiscordMessage();

                    RestAction<ResponseInfo> infoRest = streamOnDiscordMessage(character, latestAssistantMessage);

                    infoRest.queue(responseInfo -> {
                        this.latestAssistantMessage.editMessage(responseInfo.getResponse() != null ?
                                MessageEditData.fromContent(responseInfo.getResponse()) :
                                MessageEditData.fromContent(swipes.get(currentSwipe).getResponse())).queue();

                        this.currentSwipe++;
                        this.swipes.add(responseInfo);
                        this.errorMsgCleanup = null;
                        this.failedResponseInfo = null;

                        this.finishedDiscordResponse(responseInfo.getResponse());
                    }, e -> {
                        String msg = e.toString().contains("Content may not be longer") ? "Response is too long!" : e.getMessage();
                        if (e instanceof FailedResponseInfo info) {
                            this.failedResponseInfo = info;
                        }
                        this.finishedDiscordResponse(Util.botifyMessage("Failed to make a new response!\n\nError: " + msg));
                    });
                    return;
                } else {
                    currentSwipe++;
                }
            }

            if (currentSwipe >= swipes.size())
                currentSwipe = swipes.isEmpty() ? 0 : swipes.size() - 1;

            if (currentSwipe <= -1)
                currentSwipe = 0;

            if (!swipes.isEmpty())
                latestAssistantMessage.editMessage(
                        MessageEditData.fromContent(swipes.get(currentSwipe).getResponse())).queue();
        }
    }

    public boolean isMakingResponse() {
        return this.makingResponse;
    }

    public boolean isRunningRoleplay() {
        return runningRoleplay;
    }

    public RestAction<List<ChatMessage>> getHistory() {
        return getHistory(null);
    }

    // beginning prompts not included if no character is provided
    public RestAction<List<ChatMessage>> getHistory(Character character) {
        if (historyMarker == null)
            return null;

        ArrayList<ChatMessage> messages = new ArrayList<>();

        if (character != null) {
            StringBuilder instructionsMessage = new StringBuilder();
            instructionsMessage.append("Follow the instructions below! You are participating in a roleplay with other users!\n");
            instructionsMessage.append("This is a chatbot roleplay. You are roleplaying with other users, your responses should only be a few sentences long, should incorporate humor and shouldn't be too serious. The only time this can be overridden is if later instructions conflict with these. \nKeep responses within a few sentences!\nDo not escape newlines or quotes in your response. Respond with actual characters, not \\\\n or \\\\\\\". Discord will display it properly.\n");
            instructionsMessage.append("Each user message has a name field. Use this to determine who is speaking and maintain consistency");
            instructionsMessage.append("Do not include the character name in your response, this is already provided programmatically by the code.\n");

            for (Instruction instruction : instructions.values()) {
                instructionsMessage.append(instruction.getChatMessage(character).getContent()).append("\n");
            }

            messages.add(ChatMessage.SystemMessage.of(instructionsMessage.toString(), "Instructions"));

            StringBuilder combinedLore = new StringBuilder();
            combinedLore.append("The following is lore and information about the world that this roleplay takes place in!");
            for (World world : worldLore.values()) {
                combinedLore.append(world.getChatMessage(character).getContent()).append("\n");
            }
            messages.add(ChatMessage.SystemMessage.of(combinedLore.toString(), "Lore"));

            String characterPersona = "Understand the character definition! You are playing "+character.getName()+". DO NOT PLAY ANY OTHER CHARACTER. \n" +
                    character.getChatMessage(character).getContent();
            messages.add(ChatMessage.SystemMessage.of(characterPersona, "CharacterDefinition"));
            messages.add(ChatMessage.SystemMessage.of("<CHAT HISTORY>"));
        }
        int required = messages.size();

        return historyMarker.getIterableHistory().map(listOfMessages -> {
            for (int i = listOfMessages.size() - 1; i >= 0; i--) {
                Message message = listOfMessages.get(i);
                if (message.getAuthor() == AIBot.bot.getJDA().getSelfUser())
                    continue;
                if (message.getContentRaw().contains("Currently creating a response"))
                    continue;
                if (latestAssistantMessage != null &&
                        (latestAssistantMessage.getIdLong() == message.getIdLong() ||
                                latestAssistantMessage.getTimeCreated().isBefore(message.getTimeCreated())))
                    continue;

                String contents = message.getContentRaw();
                String username = message.getAuthor().getGlobalName();
                if (username == null)
                    username = message.getAuthor().getName();

                String formatted = contents
                        .replaceAll("<@" + message.getAuthor().getId() + ">", "")
                        .replaceAll("<|im_end|>", "");

                if (character != null && character.getName().equals(username) && message.isWebhookMessage()) {
                    messages.add(
                            ChatMessage.AssistantMessage.builder()
                                    .content(username + ": " + formatted)
                                    .name(username)
                                    .build()
                    );
                } else {
                    messages.add(ChatMessage.UserMessage.of(username + ": " + formatted, username));
                }
            }
            if (character != null)
                messages.add(ChatMessage.AssistantMessage.of("Write as " + character.getName() + " for your next response! DO NOT WRITE FOR ANY OTHER CHARACTER"));
            return trimListToMeetTokens(messages, required);
        });
    }

    public void startRoleplay(IReplyCallback event,
                              String rpName,
                              List<Instruction> instructionList,
                              List<World> worlds,
                              List<Character> characterList,
                              Consumer<Webhook> onSuccess
    ) throws ExecutionException, InterruptedException, IOException {
        if (instructionList.size() <= 0)
            throw new RuntimeException("Need at least one set of instructions!");
        if (worlds.size() <= 0)
            throw new RuntimeException("Need at least one set of world lore!");

        event.deferReply().queue(hook -> {
            List<ContainerChildComponent> components = new ArrayList<>();

            components.add(TextDisplay.of("# " + rpName).withUniqueId(1));
            components.add(TextDisplay.of("This roleplay starts in the thread created under this message. Have fun!"));
            components.add(TextDisplay.of("-# As a reminder, note that every generated message by AI is fictional and should not be taken as actual or professional advice."));
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of("**Chat Messages:** " + 0).withUniqueId(Constants.HISTORY_COUNT)); // chat messages marker
            components.add(Separator.createDivider(Separator.Spacing.SMALL));

            AtomicInteger uniqueId = new AtomicInteger(150);

            Function<PromptType, List<? extends Data>> getDatas = (promptType) ->
                    switch (promptType) {
                        case INSTRUCTION -> instructionList;
                        case WORLD -> worlds;
                        case CHARACTER -> characterList;
                    };

            for (PromptType promptType : PromptType.values()) {
                List<? extends Data> promptDatas = getDatas.apply(promptType);

                StringBuilder display = new StringBuilder();
                components.add(TextDisplay.of("**" + promptType.displayName + " Involved" + "**"));

                for (int i = 0; i < promptDatas.size(); i++) {
                    String name = promptDatas.get(i).getName();
                    display.append(name);
                    if (i != promptDatas.size() - 1) {
                        display.append(", ");
                    }
                }
                components.add(TextDisplay.of(display.toString()).withUniqueId(uniqueId.getAndIncrement()));
            }

            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(ActionRow.of(Interactions.getContinue()));

            event.getHook().editOriginalComponents(Util.createBotContainer(components))
                    .useComponentsV2()
                    .queue(message -> message.createThreadChannel(rpName)
                            .queue(thread -> startRoleplay(message, null, onSuccess)));
        });
    }

    public void startRoleplay(Message roleplayInfo, InteractionHook optionalHook, Consumer<Webhook> onSuccess) {
        if (isRunningRoleplay())
            stopRoleplay();

        // completely async
        Consumer<Throwable> onFail = t -> {
            stopRoleplay();
            Constants.LOGGER.error("Failed to start roleplay", t);
            throw new RuntimeException(t);
        };

        parentID = roleplayInfo.getIdLong();

        Consumer<Webhook> onQueue = hook -> {
            this.webhook = hook;

            this.runningRoleplay = true;

            this.latestAssistantMessage = null;
            this.swipes = null;
            this.currentSwipe = 0;

            this.characters.clear();
            this.instructions.clear();
            this.worldLore.clear();
            this.queuedResponses.clear();

            AtomicInteger uniqueId = new AtomicInteger(150);
            for (PromptType promptType : PromptType.values()) {
                Container container = roleplayInfo.getComponentTree().getComponents().getFirst().asContainer();
                container.getComponents().stream().filter(component -> component.getUniqueId() == uniqueId.get())
                        .findFirst().ifPresent(component -> {
                            uniqueId.incrementAndGet();
                            TextDisplay display = component.asTextDisplay();
                            List<String> prompts = List.of(display.getContent().split(", "));
                            prompts.forEach(name -> {
                                Data data = server.getDatas(promptType).get(name);
                                if (data != null)
                                    addData(promptType, data);
                            });
                        });
            }

            Stream<String> stream = characters.keySet().stream();
            stream.findAny().ifPresent(this::setCurrentCharacter);

            roleplayInfo.getChannel().retrieveMessageById(
                    roleplayInfo.getIdLong()
            ).queue(message -> {
                this.historyMarker = message.getStartedThread();
                if (onSuccess != null)
                    onSuccess.accept(hook);
            }, onFail);

            ComponentReplacer replacer = ComponentReplacer.byId(1, oldComponent -> {
                if (oldComponent instanceof TextDisplay display && !display.getContent().contains(" ✅")) {
                    return display.withContent(display.getContent() + " ✅");
                }
                return oldComponent;
            });
            if (optionalHook != null) {
                optionalHook.retrieveOriginal().queue(msg -> {
                    optionalHook.editOriginalComponents(
                            msg.getComponentTree().replace(replacer)
                    ).useComponentsV2().queue(RestAction.getDefaultSuccess(), onFail);
                }, onFail);
            } else {
                roleplayInfo.editMessageComponents(
                        roleplayInfo.getComponentTree().replace(replacer)
                ).useComponentsV2().queue(RestAction.getDefaultSuccess(), onFail);
            }
        };

        roleplayInfo.getChannel().asTextChannel().retrieveWebhooks().queue(webhooks -> webhooks.stream().filter(find -> find.getName().equals(AIBot.bot.getJDA().getSelfUser().getName()))
                .findFirst().ifPresentOrElse(onQueue, () -> {
                    WebhookAction action = roleplayInfo.getChannel().asTextChannel()
                            .createWebhook(AIBot.bot.getJDA().getSelfUser().getName());
                    if (AIBot.bot.getJDA().getSelfUser().getAvatar() != null) {
                        try {
                            InputStream inputStream = AIBot.bot.getJDA().getSelfUser().getAvatar().download().get();
                            action.setAvatar(Icon.from(inputStream)).queue(onQueue, onFail);
                        } catch (Exception e) {
                            action.queue(onQueue, onFail);
                        }
                    } else {
                        action.queue(onQueue, onFail);
                    }
                }), onFail);
    }

    public void stopRoleplay() {
        cancelGeneration();
        runningRoleplay = false;
        if (latestAssistantMessage != null) {
            latestAssistantMessage.editMessageComponents(
                            ActionRow.of(Button.danger("destroy_button", Emoji.fromFormatted("🗑")),
                                    Button.success("edit_button", Emoji.fromFormatted("🪄"))))
                    .queue(RestAction.getDefaultSuccess(),
                            (t) ->
                                    Constants.LOGGER.warn("AI Response was unable to be stripped of its optional components", t));
        }
        if (parentID != 0) {
            guild.getTextChannelCache().stream().forEach(textChannel -> {
                textChannel.retrieveMessageById(parentID).queue(oldMessage -> {
                    oldMessage.editMessageComponents(oldMessage.getComponentTree().replace(
                            ComponentReplacer.byId(1, oldComponent -> {
                                if (oldComponent instanceof TextDisplay display) {
                                    int index = display.getContent().indexOf(" ✅");
                                    if (index != -1) {
                                        return TextDisplay.of(display.getContent().substring(0, index))
                                                .withUniqueId(1);
                                    } else {
                                        return oldComponent;
                                    }
                                }
                                return oldComponent;
                            })
                    )).useComponentsV2().queue(RestAction.getDefaultSuccess(), ignored -> {
                    });
                }, ignored -> {
                });
            });
        }
        parentID = 0L;
        historyMarker = null;
        webhook = null;
        latestAssistantMessage = null;
        swipes = null;
        currentSwipe = 0;
        characters.clear();
        instructions.clear();
        worldLore.clear();
        queuedResponses.clear();
    }

    public void cancelGeneration() {
        if (this.callForResponse != null) {
            this.callForResponse.cancel();
        }
        this.callForResponse = null;
    }

    public long getParentID() {
        return parentID;
    }

    public int getCurrentSwipe() {
        return currentSwipe;
    }

    public ArrayList<ResponseInfo> getSwipes() {
        return swipes;
    }

    public void setMaxTokens(int maxTokens) {
        server.updateConfig(config -> config.get("tokens", Integer.class).setValue(maxTokens));
        this.maxTokens = maxTokens;
    }

    public void setModel(Model model) {
        server.updateConfig(config -> config.get("model", String.class).setValue(model.toString()));
        this.model = model;
    }

    public void setTemperature(double temperature) {
        server.updateConfig(config -> config.get("temperature", Double.class).setValue(temperature));
        this.temperature = Math.max(0, Math.min(temperature, 2));
    }

    public void setProvider(String provider) {
        server.updateConfig(config -> config.get("provider", String.class).setValue(provider));
        this.provider = provider;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getProvider() {
        return provider;
    }

    public Model getModel() {
        return model;
    }

    private HashMap<String, World> getWorlds() {
        return worldLore;
    }

    private HashMap<String, Instruction> getInstructions() {
        return instructions;
    }

    private HashMap<String, Character> getCharacters() {
        return characters;
    }

    public List<? extends Data> getDatas(PromptType promptType) {
        return switch (promptType) {
            case CHARACTER -> getCharacters().values().stream().toList();
            case WORLD -> getWorlds().values().stream().toList();
            case INSTRUCTION -> getInstructions().values().stream().toList();
        };
    }

    public Character getCurrentCharacter() {
        return currentCharacter;
    }

    public ThreadChannel getChannel() {
        return historyMarker;
    }

    public Message getLatestAssistantMessage() {
        return latestAssistantMessage;
    }

    public FailedResponseInfo getFailedResponseInfo() {
        return failedResponseInfo;
    }

    public void setCurrentCharacter(String name) {
        currentCharacter = characters.get(name);
    }

    public void addData(PromptType type, Data<?> data) {
        if (!server.getDatas(type).containsKey(data.getName()))
            throw new RuntimeException(data.getName() + " is no longer a valid data!");
        switch (type) {
            case CHARACTER -> {
                characters.putIfAbsent(data.getName(), (Character) data);
                currentCharacter = (Character) data;
            }
            case WORLD -> worldLore.putIfAbsent(data.getName(), (World) data);
            case INSTRUCTION -> instructions.putIfAbsent(data.getName(), (Instruction) data);
        }
    }
}
