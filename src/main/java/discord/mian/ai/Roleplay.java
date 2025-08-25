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
    private Model model;
    private String provider;
    private AIProvider aiProvider;
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
    private final HashMap<String, Instruction> systemPrompts;
    private final HashMap<String, World> personaLore;
    private final HashMap<String, Character> characters;
    private Character currentCharacter;
    private boolean runningRoleplay = false;
    private boolean isRestoredRoleplay = false;

    public Roleplay(Guild guild) {
//        this.llm = SimpleOpenAI.builder()
//                .baseUrl(Constants.BASE_URL)
//                .apiKey(Constants.LLM_KEY)
//                .build();

        this.server = AIBot.bot.getServerData(guild);

        ServerConfig configuration = server.getConfig();

        this.setMaxTokens(configuration.get("tokens", Integer.class).getValue());

        String model = configuration.get("model", String.class).getValue();
        String id = model.substring(0, model.indexOf("|"));
        String display = model.substring(model.indexOf("|") + 1);

        this.setModel(new Model(id, display));
        this.setProvider(configuration.get("provider", String.class).getValue());
        this.setAIProvider(AIProvider.valueOf(configuration.get("ai_provider", String.class).getValue()));
        this.registry = Encodings.newDefaultEncodingRegistry();
        this.guild = guild;

        systemPrompts = new HashMap<>();
        personaLore = new HashMap<>();
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
        // Handle both OpenRouter format (author/model) and Google AI format (model-name)
        String modelName = id.contains("/") ? id.substring(id.lastIndexOf("/") + 1) : id;
        Encoding enc = registry.getEncodingForModel(modelName)
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

    private String removeThinkingTags(String content) {
        if (content == null) return null;
        
        // Remove thinking tags with various possible formats
        String filtered = content;
        
        // Standard thinking tags
        filtered = filtered.replaceAll("(?s)<thinking>.*?</thinking>", "");
        
        // Alternative thinking formats that might be used
        filtered = filtered.replaceAll("(?s)\\[thinking\\].*?\\[/thinking\\]", "");
        filtered = filtered.replaceAll("(?s)\\*thinking\\*.*?\\*/thinking\\*", "");
        
        // Remove patterns that look like internal reasoning at the start
        filtered = filtered.replaceAll("(?s)^.*?(The user wants me to|I should|Plan:|Let me|I need to).*?(?=You|\\w+:|[A-Z][a-z]+\\s)", "");
        
        // Clean up any leftover whitespace and stray punctuation at the beginning
        filtered = filtered.replaceAll("^[\\s\\.\\!\\?\\,\\;\\:]+", "").trim();
        
        // Ensure the response doesn't exceed Discord's 2000 character limit
        if (filtered.length() > 2000) {
            filtered = filtered.substring(0, 1997) + "...";
        }
        
        return filtered;
    }
    
    private boolean isThinkingContent(String content) {
        if (content == null || content.trim().isEmpty()) return false;
        
        // Check if the content appears to be thinking/reasoning content
        String lower = content.toLowerCase().trim();
        return lower.startsWith("the user wants me to") ||
               lower.startsWith("i should") ||
               lower.contains("plan:") ||
               lower.startsWith("let me") ||
               lower.startsWith("i need to") ||
               content.contains("<thinking>") ||
               content.contains("[thinking]") ||
               content.contains("*thinking*");
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
                components.add(InteractionCreator.createPermanentButton(Button.primary("edit", Emoji.fromFormatted("✏️")),
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
                        .temperature(1.0)
                        .stream(true)
                        .messages(history)));
    }

    public RestAction<GoogleAIRequest> createGoogleAIRequest(Character character) {
        return getHistory(character).map(history ->
                new GoogleAIRequest(
                        server.getGoogleAIKey(),
                        model.id,
                        history,
                        1.0,
                        maxTokens
                ));
    }

    private RestAction<ResponseInfo> generateResponse(Character character, Consumer<String> consumer) {
        if (aiProvider == AIProvider.GOOGLE_AI) {
            return generateGoogleAIResponse(character, consumer);
        } else {
            return generateOpenRouterResponse(character, consumer);
        }
    }

    private RestAction<ResponseInfo> generateOpenRouterResponse(Character character, Consumer<String> consumer) {
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

                        fullResponse += content;
                        String filteredResponse = removeThinkingTags(fullResponse);
                        
                        // Check filtered response length, but allow full response to continue if it's just thinking content
                        if (filteredResponse.length() < 2000) {
                            consumer.accept(filteredResponse);
                        } else {
                            // Only stop if the actual visible content exceeds limit
                            return new ResponseInfo(
                                    model.getDisplay(),
                                    null,
                                    filteredResponse,
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
                            removeThinkingTags(fullResponse),
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

    private RestAction<ResponseInfo> generateGoogleAIResponse(Character character, Consumer<String> consumer) {
        RestAction<GoogleAIRequest> googleAIRequestAction = createGoogleAIRequest(character);

        return googleAIRequestAction.map(googleAIRequest -> {
            String fullPrompt = googleAIRequest.convertMessagesToPrompt();
            String failedResult = "";
            
            try {
                GoogleAIRequest.GoogleAIResponse response = googleAIRequest.generate();
                
                if (response == null || response.text() == null) {
                    throw new RuntimeException("Google AI returned no content");
                }
                
                String fullResponse = response.text().trim();
                
                // For Google AI, we don't have streaming, so we call the consumer once with the full response
                if (fullResponse.length() < 2000) {
                    consumer.accept(fullResponse);
                } else {
                    return new ResponseInfo(
                            googleAIRequest.getModel(),
                            "Google AI",
                            fullResponse,
                            response.getPromptTokens(),
                            response.getCompletionTokens(),
                            fullPrompt
                    );
                }
                
                return new ResponseInfo(
                        googleAIRequest.getModel(),
                        "Google AI",
                        fullResponse,
                        response.getPromptTokens(),
                        response.getCompletionTokens(),
                        fullPrompt
                );
                
            } catch (Exception e) {
                Constants.LOGGER.error("Failed to get response from Google AI", e);
                throw new FailedResponseInfo(
                        fullPrompt,
                        failedResult,
                        "Google AI returned an error: " + e.getMessage()
                );
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
                // Skip updating the message if the response looks like thinking content
                if (isThinkingContent(response)) {
                    return;
                }
                
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

            if (newContent.isEmpty() || isThinkingContent(newContent))
                throw new RuntimeException("The provider returned no content, try again? :(");
            return info;
        });
    }

    public void promptCharacterToRoleplay(Character character, Message replyTo, boolean triggerAutoResponse) {
        try {
            Constants.LOGGER.info("promptCharacterToRoleplay called with character: " + character.getName() + ", triggerAutoResponse: " + triggerAutoResponse);
            
            if (isRunningRoleplay()) {
                if (!characters.containsKey(character.getName())) {
                    Constants.LOGGER.info("Character not in roleplay, adding: " + character.getName());
                    
                    // Add character data directly during restoration instead of trying to edit parent message
                    try {
                        addData(PromptType.CHARACTER, character);
                        setCurrentCharacter(character.getName());
                        Constants.LOGGER.info("Successfully added character during restoration: " + character.getName());
                        sendRoleplayMessage(triggerAutoResponse);
                    } catch (Exception e) {
                        Constants.LOGGER.error("Failed to add character during restoration, trying parent message update", e);
                        
                        // Fallback to original method if direct addition fails
                        Consumer<Throwable> onFail = t ->
                                Constants.LOGGER.error("Failed to add character into roleplay via parent message", t);

                        historyMarker.retrieveParentMessage().queue(parentMsg -> {
                            try {
                                Container container = parentMsg.getComponentTree().getComponents().getFirst().asContainer();
                                var displayComponent = container.getComponents().stream().filter(component -> component.getUniqueId() == 152)
                                        .findFirst().orElse(null);
                                        
                                if (displayComponent != null) {
                                    TextDisplay charactersDisplay = displayComponent.asTextDisplay();
                                    parentMsg.editMessageComponents(container.replace(ComponentReplacer.byId(152, charactersDisplay.withContent(
                                            charactersDisplay.getContent() + ", " + character.getName()
                                    )))).useComponentsV2().queue(success -> {
                                        addData(PromptType.CHARACTER, character);
                                        setCurrentCharacter(character.getName());
                                        sendRoleplayMessage(triggerAutoResponse);
                                    }, onFail);
                                } else {
                                    Constants.LOGGER.error("Could not find character display component, proceeding without updating parent");
                                    addData(PromptType.CHARACTER, character);
                                    setCurrentCharacter(character.getName());
                                    sendRoleplayMessage(triggerAutoResponse);
                                }
                            } catch (Exception ex) {
                                Constants.LOGGER.error("Exception while updating parent message components", ex);
                                onFail.accept(ex);
                            }
                        }, onFail);
                    }
                } else {
                    Constants.LOGGER.info("Character already in roleplay, setting current and sending message: " + character.getName());
                    setCurrentCharacter(character.getName());
                    sendRoleplayMessage(triggerAutoResponse);
                }
            } else {
                Constants.LOGGER.error("promptCharacterToRoleplay called but roleplay not running");
            }
        } catch (Exception e) {
            Constants.LOGGER.error("Exception in promptCharacterToRoleplay", e);
            throw e;
        }
    }

    public void sendRoleplayMessage(boolean triggerAutoResponse) {
        try {
            Constants.LOGGER.info("sendRoleplayMessage called with triggerAutoResponse: " + triggerAutoResponse);
            
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
        Constants.LOGGER.info("Checking starting message - latestAssistantMessage: " + 
            (latestAssistantMessage != null ? "exists" : "null") + 
            ", startingMessage: " + (startingMessage != null ? "exists" : "null") + 
            ", character: " + currentCharacter.getName());
        // Skip starting message if this is a restored thread
        boolean isRestoredThread = isRestoredRoleplay;
        
        if (latestAssistantMessage == null && startingMessage != null && !startingMessage.trim().isEmpty() && !isRestoredThread) {
            Constants.LOGGER.info("Sending starting message for character: " + currentCharacter.getName());
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
                
                // Continue with queued responses if any, but don't exit completely
                if (!queuedResponses.isEmpty()) {
                    Map.Entry<Character, Boolean> next = queuedResponses.getFirst();
                    queuedResponses.removeFirst();
                    promptCharacterToRoleplay(next.getKey(), null, next.getValue());
                }
            });
            return; // Only return here for starting messages
        } else if (isRestoredThread) {
            Constants.LOGGER.info("Skipping starting message - this is a restored thread, will generate contextual response");
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
            // Only try to edit/delete the message if it was sent by the current bot instance
            boolean canEditMessage = latestAssistantMessage.getAuthor().equals(AIBot.bot.getJDA().getSelfUser()) ||
                                   latestAssistantMessage.isWebhookMessage(); // We control webhooks in our threads
            
            if (canEditMessage) {
                if (latestAssistantMessage.getContentRaw().isEmpty())
                    latestAssistantMessage.delete().queue();
                else
                    latestAssistantMessage.editMessageComponents(ActionRow.of(Button.danger("destroy_button", Emoji.fromFormatted("🗑")),
                                    Button.primary("edit_button", Emoji.fromFormatted("✏️"))))
                            .queue(RestAction.getDefaultSuccess(),
                                    (t) -> {
                                    });
            } else {
                Constants.LOGGER.info("Skipping message edit - message was sent by different bot instance");
            }
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
                            Util.botifyMessage("Currently creating a response! Check back in a second..."))
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
        } catch (Exception e) {
            Constants.LOGGER.error("Exception in sendRoleplayMessage", e);
            throw e;
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
            StringBuilder systemPromptsMessage = new StringBuilder();
            systemPromptsMessage.append("You are participating in a roleplay with other users!\n");
            systemPromptsMessage.append("This is a chatbot roleplay. You are roleplaying with other users, your responses should only be a few sentences long, should incorporate humor and shouldn't be too serious. The only time this can be overridden is if custom system prompts conflict with these. \nKeep responses within a few sentences!\nDo not escape newlines or quotes in your response. Respond with actual characters, not \\\\n or \\\\\\\". Discord will display it properly.\n");
            systemPromptsMessage.append("Each user message has a name field. Use this to determine who is speaking and maintain consistency");
            systemPromptsMessage.append("Do not include the character name in your response, this is already provided programmatically by the code.\n");

            if (!systemPrompts.isEmpty()) {
                systemPromptsMessage.append("\nAdditional system prompts:\n");
                for (Instruction systemPrompt : systemPrompts.values()) {
                    systemPromptsMessage.append(systemPrompt.getChatMessage(character).getContent()).append("\n");
                }
            }

            messages.add(ChatMessage.SystemMessage.of(systemPromptsMessage.toString(), "System Prompts"));

            if (!personaLore.isEmpty()) {
                StringBuilder combinedLore = new StringBuilder();
                combinedLore.append("The following is lore and information about the persona that this roleplay takes place in!");
                for (World world : personaLore.values()) {
                    combinedLore.append(world.getChatMessage(character).getContent()).append("\n");
                }
                messages.add(ChatMessage.SystemMessage.of(combinedLore.toString(), "Lore"));
            }

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
                              List<Instruction> systemPromptList,
                              List<World> personas,
                              List<Character> characterList,
                              Consumer<Webhook> onSuccess
    ) throws ExecutionException, InterruptedException, IOException {
        // System prompts and personas are now optional - no validation needed

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
                        case INSTRUCTION -> systemPromptList;
                        case WORLD -> personas;
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
            this.isRestoredRoleplay = false; // This is a fresh roleplay, not restored

            this.latestAssistantMessage = null;
            this.swipes = null;
            this.currentSwipe = 0;

            this.characters.clear();
            this.systemPrompts.clear();
            this.personaLore.clear();
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

            // Checkmark functionality removed - no longer modifying message components
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
                                    Button.primary("edit_button", Emoji.fromFormatted("✏️"))))
                    .queue(RestAction.getDefaultSuccess(),
                            (t) ->
                                    Constants.LOGGER.warn("AI Response was unable to be stripped of its optional components", t));
        }
        // Checkmark removal functionality removed since we no longer add checkmarks
        if (parentID != 0) {
            // Reset parentID but don't modify message components
            parentID = 0;
        }
        parentID = 0L;
        historyMarker = null;
        webhook = null;
        latestAssistantMessage = null;
        swipes = null;
        currentSwipe = 0;
        characters.clear();
        systemPrompts.clear();
        personaLore.clear();
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


    public void setProvider(String provider) {
        server.updateConfig(config -> config.get("provider", String.class).setValue(provider));
        this.provider = provider;
    }

    public void setAIProvider(AIProvider aiProvider) {
        server.updateConfig(config -> config.get("ai_provider", String.class).setValue(aiProvider.name()));
        this.aiProvider = aiProvider;
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

    public AIProvider getAIProvider() {
        return aiProvider;
    }

    private HashMap<String, World> getPersonas() {
        return personaLore;
    }

    private HashMap<String, Instruction> getSystemPrompts() {
        return systemPrompts;
    }

    private HashMap<String, Character> getCharacters() {
        return characters;
    }

    public List<? extends Data> getDatas(PromptType promptType) {
        return switch (promptType) {
            case CHARACTER -> getCharacters().values().stream().toList();
            case WORLD -> getPersonas().values().stream().toList();
            case INSTRUCTION -> getSystemPrompts().values().stream().toList();
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
            case WORLD -> personaLore.putIfAbsent(data.getName(), (World) data);
            case INSTRUCTION -> systemPrompts.putIfAbsent(data.getName(), (Instruction) data);
        }
    }

    public void restoreRoleplayFromThread(ThreadChannel threadChannel) {
        // Restore roleplay state from an existing thread (e.g., after bot restart)
        if (!runningRoleplay && threadChannel != null) {
            this.historyMarker = threadChannel;
            this.runningRoleplay = true;
            this.isRestoredRoleplay = true;
            Constants.LOGGER.info("Restored roleplay state from thread: " + threadChannel.getName() + " in guild: " + guild.getName());
            
            // Restore all available characters, personas, and system prompts from server data
            Constants.LOGGER.info("Restoring characters, personas, and system prompts from server data...");
            
            // Add all available characters
            server.getCharacterDatas().values().forEach(characterData -> {
                Character character = (Character) characterData;
                characters.putIfAbsent(character.getName(), character);
                Constants.LOGGER.info("Restored character: " + character.getName() + " (talkability: " + character.getDocument().getTalkability() + ")");
            });
            
            // Add all available personas
            server.getPersonaDatas().values().forEach(personaData -> {
                personaLore.putIfAbsent(personaData.getName(), (World) personaData);
                Constants.LOGGER.info("Restored persona: " + personaData.getName());
            });
            
            // Add all available system prompts
            server.getSystemPromptDatas().values().forEach(systemPromptData -> {
                systemPrompts.putIfAbsent(systemPromptData.getName(), (Instruction) systemPromptData);
                Constants.LOGGER.info("Restored system prompt: " + systemPromptData.getName());
            });
            
            // Log final character availability
            List<Character> availableCharacters = getDatas(PromptType.CHARACTER).stream()
                    .map(data -> (Character) data)
                    .toList();
            Constants.LOGGER.info("Total available characters after restoration: " + availableCharacters.size());
            
            // Identify which character was being used in this thread (synchronously)
            identifyActiveCharacterFromHistorySync(threadChannel);
            
            // Restore webhook for the thread (synchronously)
            Constants.LOGGER.info("Restoring webhook for thread...");
            restoreWebhookForThreadSync(threadChannel);
        }
    }
    
    private void restoreWebhookForThread(ThreadChannel threadChannel) {
        // Get the parent channel and look for existing webhook or create one
        threadChannel.getParentChannel().asTextChannel().retrieveWebhooks().queue(webhooks -> {
            webhooks.stream()
                    .filter(webhook -> webhook.getName().equals(AIBot.bot.getJDA().getSelfUser().getName()))
                    .findFirst()
                    .ifPresentOrElse(
                            existingWebhook -> {
                                this.webhook = existingWebhook;
                                Constants.LOGGER.info("Restored existing webhook: " + existingWebhook.getName());
                            },
                            () -> {
                                Constants.LOGGER.info("No existing webhook found, creating new one...");
                                WebhookAction action = threadChannel.getParentChannel().asTextChannel()
                                        .createWebhook(AIBot.bot.getJDA().getSelfUser().getName());
                                if (AIBot.bot.getJDA().getSelfUser().getAvatar() != null) {
                                    try {
                                        InputStream inputStream = AIBot.bot.getJDA().getSelfUser().getAvatar().download().get();
                                        action.setAvatar(Icon.from(inputStream)).queue(createdWebhook -> {
                                            this.webhook = createdWebhook;
                                            Constants.LOGGER.info("Created new webhook with avatar: " + createdWebhook.getName());
                                        }, error -> {
                                            Constants.LOGGER.error("Failed to create webhook with avatar", error);
                                        });
                                    } catch (Exception e) {
                                        action.queue(createdWebhook -> {
                                            this.webhook = createdWebhook;
                                            Constants.LOGGER.info("Created new webhook without avatar: " + createdWebhook.getName());
                                        }, error -> {
                                            Constants.LOGGER.error("Failed to create webhook", error);
                                        });
                                    }
                                } else {
                                    action.queue(createdWebhook -> {
                                        this.webhook = createdWebhook;
                                        Constants.LOGGER.info("Created new webhook: " + createdWebhook.getName());
                                    }, error -> {
                                        Constants.LOGGER.error("Failed to create webhook", error);
                                    });
                                }
                            }
                    );
        }, error -> {
            Constants.LOGGER.error("Failed to retrieve webhooks for thread restoration", error);
        });
    }
    
    private void identifyActiveCharacterFromHistory(ThreadChannel threadChannel) {
        Constants.LOGGER.info("Analyzing thread history to identify active character...");
        
        // Look at the recent messages in the thread to find webhook messages from characters
        threadChannel.getIterableHistory().limit(50).queue(messages -> {
            for (Message message : messages) {
                if (message.isWebhookMessage()) {
                    String authorName = message.getAuthor().getName();
                    Constants.LOGGER.info("Found webhook message from: " + authorName);
                    
                    // Check if this author name matches any of our characters
                    Character matchingCharacter = characters.get(authorName);
                    if (matchingCharacter != null) {
                        this.currentCharacter = matchingCharacter;
                        Constants.LOGGER.info("Identified active character from history: " + matchingCharacter.getName());
                        return;
                    }
                }
            }
            
            // If no character found in history, log it
            Constants.LOGGER.info("No active character identified from thread history");
        }, error -> {
            Constants.LOGGER.error("Failed to retrieve thread history for character identification", error);
        });
    }
    
    private void identifyActiveCharacterFromHistorySync(ThreadChannel threadChannel) {
        Constants.LOGGER.info("Analyzing thread history to identify active character (sync)...");
        
        try {
            // Get messages synchronously
            List<Message> messages = threadChannel.getIterableHistory().limit(50).complete();
            
            Message mostRecentBotMessage = null;
            
            for (Message message : messages) {
                if (message.isWebhookMessage()) {
                    String authorName = message.getAuthor().getName();
                    
                    // Check if this author name matches any of our characters
                    Character matchingCharacter = characters.get(authorName);
                    if (matchingCharacter != null) {
                        // Only log and set if we haven't found a character yet
                        if (this.currentCharacter == null) {
                            Constants.LOGGER.info("Found webhook message from: " + authorName);
                            this.currentCharacter = matchingCharacter;
                            Constants.LOGGER.info("Identified active character from history (sync): " + matchingCharacter.getName());
                        }
                        
                        // Keep track of the most recent bot message for this character
                        if (mostRecentBotMessage == null) {
                            mostRecentBotMessage = message;
                        }
                    }
                }
            }
            
            // Restore the latest assistant message to prevent sending starting message
            Constants.LOGGER.info("About to check restoration conditions - mostRecentBotMessage: " + (mostRecentBotMessage != null ? "exists" : "null") + ", currentCharacter: " + (this.currentCharacter != null ? this.currentCharacter.getName() : "null"));
            
            if (mostRecentBotMessage != null && this.currentCharacter != null) {
                // For webhook messages, we can't check the author since webhooks have different authors
                // Instead, we assume that if we successfully restored the webhook for this thread,
                // then the webhook messages belong to us and we can safely restore latestAssistantMessage
                // However, we should NOT restore webhook messages as latestAssistantMessage because
                // the bot tries to edit latestAssistantMessage later, and editing webhook messages
                // from previous instances causes issues
                if (mostRecentBotMessage.getAuthor().equals(AIBot.bot.getJDA().getSelfUser())) {
                    this.latestAssistantMessage = mostRecentBotMessage;
                    Constants.LOGGER.info("Restored latestAssistantMessage from bot message to prevent starting message");
                } else {
                    Constants.LOGGER.info("Skipping latestAssistantMessage restoration - message was sent by different bot instance or is webhook message");
                }
            } else {
                Constants.LOGGER.info("Skipping latestAssistantMessage restoration - conditions not met");
            }
            
            Constants.LOGGER.info("About to complete character identification method");
            
            if (this.currentCharacter == null) {
                Constants.LOGGER.info("No active character identified from thread history (sync)");
            } else {
                Constants.LOGGER.info("Character identification completed successfully: " + this.currentCharacter.getName());
            }
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to retrieve thread history for character identification (sync)", e);
        }
    }
    
    private void restoreWebhookForThreadSync(ThreadChannel threadChannel) {
        try {
            List<Webhook> webhooks = threadChannel.getParentChannel().asTextChannel().retrieveWebhooks().complete();
            
            Webhook existingWebhook = webhooks.stream()
                    .filter(webhook -> webhook.getName().equals(AIBot.bot.getJDA().getSelfUser().getName()))
                    .findFirst()
                    .orElse(null);
                    
            if (existingWebhook != null) {
                this.webhook = existingWebhook;
                Constants.LOGGER.info("Restored existing webhook (sync): " + existingWebhook.getName());
            } else {
                Constants.LOGGER.info("No existing webhook found, creating new one (sync)...");
                WebhookAction action = threadChannel.getParentChannel().asTextChannel()
                        .createWebhook(AIBot.bot.getJDA().getSelfUser().getName());
                        
                if (AIBot.bot.getJDA().getSelfUser().getAvatar() != null) {
                    try {
                        InputStream inputStream = AIBot.bot.getJDA().getSelfUser().getAvatar().download().get();
                        this.webhook = action.setAvatar(Icon.from(inputStream)).complete();
                        Constants.LOGGER.info("Created new webhook with avatar (sync): " + this.webhook.getName());
                    } catch (Exception e) {
                        this.webhook = action.complete();
                        Constants.LOGGER.info("Created new webhook without avatar (sync): " + this.webhook.getName());
                    }
                } else {
                    this.webhook = action.complete();
                    Constants.LOGGER.info("Created new webhook (sync): " + this.webhook.getName());
                }
            }
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to restore webhook for thread (sync)", e);
        }
    }
}
