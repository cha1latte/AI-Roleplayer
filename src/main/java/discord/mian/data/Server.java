package discord.mian.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import discord.mian.Constants;
import discord.mian.Util;
import discord.mian.data.character.Character;
import discord.mian.data.character.CharacterDocument;
import discord.mian.data.instruction.Instruction;
import discord.mian.data.instruction.InstructionDocument;
import discord.mian.data.world.World;
import discord.mian.data.world.WorldDocument;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

public class Server {
    private final HashMap<String, Character> characterDatas;
    private final HashMap<String, Instruction> systemPromptDatas;
    private final HashMap<String, World> personaDatas;
    private final Guild guild;

    public Server(Guild guild) {
        this.guild = guild;
        this.characterDatas = new HashMap<>();
        this.systemPromptDatas = new HashMap<>();
        this.personaDatas = new HashMap<>();

        saveConfig(generateConfig(getConfig())); // generates the config and missing values if they do not exist
    }

    public Role getMasterRole() {
        return guild.getRoleById(getConfig().get("bot_role_id", Long.class).getValue());
    }

    public ServerConfig getConfig() {
        MongoCollection<ServerConfig> serverConfigs = Util.DATABASE.getCollection("server", ServerConfig.class);
        MongoCursor<ServerConfig> cursor = serverConfigs.find(Filters.eq("_id", guild.getIdLong())).iterator();

        ServerConfig configuration;
        if (cursor.hasNext())
            configuration = cursor.next();
        else {
            configuration = new ServerConfig(guild.getIdLong(), new HashMap<>());

            // can be assumed that the server is new
            for (PromptType promptType : PromptType.values()) {
                File defaults = Util.getDefaultsFor(promptType);

                if (!defaults.exists()) {
                    Constants.LOGGER.warn("Defaults directory does not exist: " + defaults.getPath());
                    continue;
                }

                File[] files = defaults.listFiles();
                if (files != null) {
                    Arrays.stream(files).forEach(file -> {
                    try {
                        if (promptType == PromptType.CHARACTER) {
                            ObjectMapper mapper = new ObjectMapper();

                            JsonNode characterNode = mapper.readTree(file);
                            createCharacter(
                                    characterNode.get("name").asText(),
                                    characterNode.get("prompt").asText(),
                                    characterNode.get("talkability").asDouble()
                            );
                            characterDatas.get(characterNode.get("name").asText()).updateDocument(
                                    document -> document.setAvatar(characterNode.get("avatar").asText())
                            );
                        } else {
                            String prompt = Files.readString(file.toPath());
                            String name = file.getName();
                            name = name.substring(0, name.lastIndexOf("."));

                            if (promptType == PromptType.INSTRUCTION)
                                createSystemPrompt(name, prompt);
                            else
                                createPersona(name, prompt);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                }
            }
        }
        cursor.close();
        return configuration;
    }

    private ServerConfig generateConfig(ServerConfig configuration) {
        ConfigEntry<String> openRouter = new ConfigEntry<>(String.class);
        openRouter.setDescription("The API key to use for models on Open Router");
        openRouter.setValue("");
        configuration.putIfAbsent("open_router_key", openRouter);

        ConfigEntry<String> imgbb = new ConfigEntry<>(String.class);
        imgbb.setDescription("The API key to use for getting and posting avatars on IMGBB");
        imgbb.setValue("");
        configuration.putIfAbsent("imgbb_key", imgbb);

        ConfigEntry<String> googleAI = new ConfigEntry<>(String.class);
        googleAI.setDescription("The API key to use for Google AI Studio models");
        googleAI.setValue("");
        configuration.putIfAbsent("google_ai_key", googleAI);

        ConfigEntry<Boolean> onlyChatOnMention = new ConfigEntry<>(Boolean.class);
        onlyChatOnMention.setDescription("Whether the AI will only reply when mentioned through reply or its name");
        onlyChatOnMention.setValue(false);
        configuration.putIfAbsent("only_chat_on_mention", onlyChatOnMention);

        ConfigEntry<Boolean> useFallBackProviders = new ConfigEntry<>(Boolean.class);
        useFallBackProviders.setDescription("Whether to use fallback providers if the current selected provider is down");
        useFallBackProviders.setValue(true);
        configuration.putIfAbsent("use_fallback_providers", useFallBackProviders);

        ConfigEntry<Long> botRole = new ConfigEntry<>(Long.class);
        botRole.setDescription("Long ID of the bot controller role");
        botRole.setHidden(true);
        botRole.setValue(0L);
        configuration.putIfAbsent("bot_role_id", botRole);

        ConfigEntry<Integer> tokens = new ConfigEntry<>(Integer.class);
        tokens.setDescription("Max tokens of model");
        tokens.setHidden(true);
        tokens.setValue(8192);
        configuration.putIfAbsent("tokens", tokens);

        ConfigEntry<String> provider = new ConfigEntry<>(String.class);
        provider.setDescription("Provider of model");
        provider.setHidden(true);
        provider.setValue("");
        configuration.putIfAbsent("provider", provider);

        ConfigEntry<String> model = new ConfigEntry<>(String.class);
        model.setDescription("The model being used");
        model.setHidden(true);
        model.setValue(Constants.DEFAULT_MODEL);
        configuration.putIfAbsent("model", model);

        ConfigEntry<String> aiProvider = new ConfigEntry<>(String.class);
        aiProvider.setDescription("The AI provider to use (OpenRouter or Google AI)");
        aiProvider.setHidden(true);
        aiProvider.setValue("OPENROUTER");
        configuration.putIfAbsent("ai_provider", aiProvider);

        return configuration;
    }

    private void saveConfig(ServerConfig config) {
        MongoCollection<ServerConfig> serverConfigs = Util.DATABASE.getCollection("server", ServerConfig.class);

        serverConfigs.replaceOne(Filters.and(
                Filters.eq("_id", config.getId())
        ), config, new ReplaceOptions().upsert(true));
    }

    public void updateConfig(Consumer<ServerConfig> updateConsumer) throws MongoException {
        ServerConfig config = getConfig();
        updateConsumer.accept(config);

        saveConfig(config);
    }

    public String getLLMKey() {
        return (getConfig().get("open_router_key", String.class)).getValue();
    }
    
    public String getGoogleAIKey() {
        return (getConfig().get("google_ai_key", String.class)).getValue();
    }
    
    public String getAIProvider() {
        return (getConfig().get("ai_provider", String.class)).getValue();
    }

    public HashMap<String, ? extends Data<?>> getDatas(PromptType promptType) {
        return switch (promptType) {
            case INSTRUCTION -> getSystemPromptDatas();
            case CHARACTER -> getCharacterDatas();
            case WORLD -> getPersonaDatas();
        };
    }

    public HashMap<String, World> getPersonaDatas() {
        try (MongoCursor<WorldDocument> cursor = Util.DATABASE.getCollection("prompt", WorldDocument.class)
                .find(Filters.and(
                        Filters.eq("server", guild.getIdLong()),
                        Filters.eq("type", "worlds"))).iterator()) {
            while (cursor.hasNext()) {
                WorldDocument document = cursor.next();
                personaDatas.putIfAbsent(document.getName(), new World(document));
            }
        }

        return personaDatas;
    }

    public HashMap<String, Instruction> getSystemPromptDatas() {
        try (MongoCursor<InstructionDocument> cursor = Util.DATABASE.getCollection("prompt", InstructionDocument.class)
                .find(Filters.and(
                        Filters.eq("server", guild.getIdLong()),
                        Filters.eq("type", "instructions"))).iterator()) {
            while (cursor.hasNext()) {
                InstructionDocument document = cursor.next();
                systemPromptDatas.putIfAbsent(document.getName(), new Instruction(document));
            }
        }

        return systemPromptDatas;
    }

    public HashMap<String, Character> getCharacterDatas() {
        try (MongoCursor<CharacterDocument> cursor = Util.DATABASE.getCollection("prompt", CharacterDocument.class)
                .find(Filters.and(
                        Filters.eq("server", guild.getIdLong()),
                        Filters.eq("type", PromptType.CHARACTER.displayName.toLowerCase()))
                ).iterator()) {
            while (cursor.hasNext()) {
                CharacterDocument document = cursor.next();
                characterDatas.putIfAbsent(document.getName(), new Character(document));
            }
        }

        return characterDatas;
    }

    public void createCharacter(String name, String definition, double talkability) throws MongoException {
        createCharacter(name, definition, talkability, null, null);
    }

    public void createCharacter(String name, String definition, double talkability, String avatar, String startingMessage) throws MongoException {
        Character data = new Character(new CharacterDocument(name, guild.getIdLong()));
        data.updateDocument(document -> {
            document.setPrompt(definition);
            document.setTalkability(talkability);
            if (avatar != null && !avatar.trim().isEmpty()) {
                document.setAvatar(avatar.trim());
            }
            if (startingMessage != null && !startingMessage.trim().isEmpty()) {
                document.setStartingMessage(startingMessage.trim());
            }
        });

        characterDatas.putIfAbsent(name, data);
    }

    public void createSystemPrompt(String name, String prompt) throws MongoException {
        Instruction data = new Instruction(new InstructionDocument(name, guild.getIdLong()));
        data.updateDocument(document -> document.setPrompt(prompt));

        systemPromptDatas.putIfAbsent(name, data);
    }

    public void createPersona(String name, String prompt) throws MongoException {
        World data = new World(new WorldDocument(name, guild.getIdLong()));
        data.updateDocument(document -> document.setPrompt(prompt));

        personaDatas.putIfAbsent(name, data);
    }

    // Temporary method to clean up non-Pokemon content from database
    public void cleanupNonPokemonContent() {
        long serverId = guild.getIdLong();
        int deletedCount = 0;
        
        Constants.LOGGER.info("Starting Pokemon cleanup for server: " + guild.getName());
        
        // Pokemon content to keep (case-insensitive matching)
        String[] pokemonSystemPrompts = {"pokemon adventure"};
        String[] pokemonPersonas = {"pokemon trainer"};
        String[] pokemonCharacters = {"pokemon adventure", "pokemon-adventure"};
        
        // Remove non-Pokemon system prompts
        try {
            deletedCount += Util.DATABASE.getCollection("prompt")
                    .deleteMany(Filters.and(
                            Filters.eq("server", serverId),
                            Filters.eq("type", "instructions"),
                            Filters.not(Filters.regex("name", "(?i)pokemon"))
                    )).getDeletedCount();
            Constants.LOGGER.info("Removed non-Pokemon system prompts");
        } catch (Exception e) {
            Constants.LOGGER.error("Error removing system prompts", e);
        }
        
        // Remove non-Pokemon personas  
        try {
            deletedCount += Util.DATABASE.getCollection("prompt")
                    .deleteMany(Filters.and(
                            Filters.eq("server", serverId),
                            Filters.eq("type", "worlds"),
                            Filters.not(Filters.regex("name", "(?i)pokemon"))
                    )).getDeletedCount();
            Constants.LOGGER.info("Removed non-Pokemon personas");
        } catch (Exception e) {
            Constants.LOGGER.error("Error removing personas", e);
        }
        
        // Remove non-Pokemon characters
        try {
            deletedCount += Util.DATABASE.getCollection("prompt")
                    .deleteMany(Filters.and(
                            Filters.eq("server", serverId),
                            Filters.eq("type", "characters"),
                            Filters.not(Filters.regex("name", "(?i)pokemon"))
                    )).getDeletedCount();
            Constants.LOGGER.info("Removed non-Pokemon characters");
        } catch (Exception e) {
            Constants.LOGGER.error("Error removing characters", e);
        }
        
        // Clear the cached data so it reloads from database
        systemPromptDatas.clear();
        personaDatas.clear();
        characterDatas.clear();
        
        Constants.LOGGER.info("Pokemon cleanup completed for " + guild.getName() + " - removed " + deletedCount + " items");
    }

}
