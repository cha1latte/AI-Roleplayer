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
    private final HashMap<String, Instruction> instructionDatas;
    private final HashMap<String, World> worldDatas;
    private final Guild guild;

    public Server(Guild guild) {
        this.guild = guild;
        this.characterDatas = new HashMap<>();
        this.instructionDatas = new HashMap<>();
        this.worldDatas = new HashMap<>();

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

                Arrays.stream(Objects.requireNonNull(defaults.listFiles())).forEach(file -> {
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
                                createInstruction(name, prompt);
                            else
                                createWorld(name, prompt);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
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

        ConfigEntry<Double> temperature = new ConfigEntry<>(Double.class);
        temperature.setDescription("Temperature of model");
        temperature.setHidden(true);
        temperature.setValue(1D);
        configuration.putIfAbsent("temperature", temperature);

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

    public HashMap<String, ? extends Data<?>> getDatas(PromptType promptType) {
        return switch (promptType) {
            case INSTRUCTION -> getInstructionDatas();
            case CHARACTER -> getCharacterDatas();
            case WORLD -> getWorldDatas();
        };
    }

    public HashMap<String, World> getWorldDatas() {
        try (MongoCursor<WorldDocument> cursor = Util.DATABASE.getCollection("prompt", WorldDocument.class)
                .find(Filters.and(
                        Filters.eq("server", guild.getIdLong()),
                        Filters.eq("type", PromptType.WORLD.displayName.toLowerCase()))).iterator()) {
            while (cursor.hasNext()) {
                WorldDocument document = cursor.next();
                worldDatas.putIfAbsent(document.getName(), new World(document));
            }
        }

        return worldDatas;
    }

    public HashMap<String, Instruction> getInstructionDatas() {
        try (MongoCursor<InstructionDocument> cursor = Util.DATABASE.getCollection("prompt", InstructionDocument.class)
                .find(Filters.and(
                        Filters.eq("server", guild.getIdLong()),
                        Filters.eq("type", PromptType.INSTRUCTION.displayName.toLowerCase()))).iterator()) {
            while (cursor.hasNext()) {
                InstructionDocument document = cursor.next();
                instructionDatas.putIfAbsent(document.getName(), new Instruction(document));
            }
        }

        return instructionDatas;
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
        Character data = new Character(new CharacterDocument(name, guild.getIdLong()));
        data.updateDocument(document -> {
            document.setPrompt(definition);
            document.setTalkability(talkability);
        });

        characterDatas.putIfAbsent(name, data);
    }

    public void createInstruction(String name, String prompt) throws MongoException {
        Instruction data = new Instruction(new InstructionDocument(name, guild.getIdLong()));
        data.updateDocument(document -> document.setPrompt(prompt));

        instructionDatas.putIfAbsent(name, data);
    }

    public void createWorld(String name, String prompt) throws MongoException {
        World data = new World(new WorldDocument(name, guild.getIdLong()));
        data.updateDocument(document -> document.setPrompt(prompt));

        worldDatas.putIfAbsent(name, data);
    }

}
