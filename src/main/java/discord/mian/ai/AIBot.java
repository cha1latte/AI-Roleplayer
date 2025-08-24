package discord.mian.ai;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import discord.mian.Constants;
import discord.mian.Util;
import discord.mian.commands.BotCommands;
import discord.mian.data.Server;
import discord.mian.data.ServerConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;

import java.util.HashMap;
import java.util.Map;

public class AIBot {
    public static AIBot bot;

    private final JDA jda;

    private final Map<Guild, Roleplay> chats;
    private final Map<Guild, Server> servers;
    private final Map<Guild, Boolean> pokemonRestoredFlags = new HashMap<>();
    private static final String CLEANUP_FLAG = "pokemon_cleanup_done";
    private boolean pokemonCleanupDone = System.getProperty(CLEANUP_FLAG, "false").equals("true");

    public AIBot(JDA jda) throws Exception {
        if (bot != null)
            throw new RuntimeException("Can't create multiple AI-Bots!");
        bot = this;
        jda.awaitReady();
        this.servers = new HashMap<>();
        this.chats = new HashMap<>();
        this.jda = jda;
        Constants.ALLOWED_USER_IDS.add(this.jda.retrieveApplicationInfo().complete().getOwner().getIdLong());

        BotCommands.addCommands().queue();

        for (Guild guild : jda.getGuildCache()) {
            onServerJoin(guild);
        }

        try (MongoCursor<ServerConfig> cursor = Util.DATABASE.getCollection("server", ServerConfig.class).find().iterator()) {
            while (cursor.hasNext()) {
                ServerConfig config = cursor.next();
                if (jda.getGuildById(config.getId()) == null) {
                    Util.DATABASE.getCollection("server").deleteMany(
                            Filters.eq("_id", config.getId())
                    );
                    Util.DATABASE.getCollection("prompt")
                            .deleteMany(Filters.eq("server", config.getId()));
                }
            }
        }
        
    }

    public JDA getJDA() {
        return this.jda;
    }

    public Roleplay getChat(Guild guild) {
        if (!this.chats.containsKey(guild))
            AIBot.bot.createChat(guild);

        return this.chats.get(guild);
    }

    public Server getServerData(Guild guild) {
        // One-time cleanup of non-Pokemon content on first server access
        if (!pokemonCleanupDone) {
            Constants.LOGGER.info("Performing one-time cleanup of non-Pokemon content...");
            for (Guild g : jda.getGuildCache()) {
                try {
                    Server server = servers.get(g);
                    if (server != null) {
                        server.cleanupNonPokemonContent();
                        // Restore Pokemon defaults after cleanup
                        server.restorePokemonContent();
                        pokemonRestoredFlags.put(g, true);
                    }
                } catch (Exception e) {
                    Constants.LOGGER.error("Failed to cleanup non-Pokemon content for guild: " + g.getName(), e);
                }
            }
            pokemonCleanupDone = true;
            System.setProperty(CLEANUP_FLAG, "true");
            Constants.LOGGER.info("Pokemon cleanup and restoration completed for all servers");
        }
        
        // Ensure Pokemon content is restored once per server
        Server server = servers.get(guild);
        if (server != null && !pokemonRestoredFlags.getOrDefault(guild, false)) {
            try {
                server.restorePokemonContent();
                pokemonRestoredFlags.put(guild, true);
            } catch (Exception e) {
                Constants.LOGGER.error("Failed to restore Pokemon content for guild: " + guild.getName(), e);
            }
        }
        
        return server;
    }

    public void createChat(Guild guild) {
        Roleplay chat = new Roleplay(guild);
        Server serverData = getServerData(guild);

        this.chats.put(guild, chat);
    }

    public void onServerJoin(Guild guild) {
        try {
            Server server = new Server(guild);
            servers.put(guild, server);
            
            // Always ensure Pokemon content is restored after server creation
            server.restorePokemonContent();
            pokemonRestoredFlags.put(guild, true);
            
            Constants.LOGGER.info("Successfully initialized server data for guild: " + guild.getName());
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to create server data for guild: " + guild.getName(), e);
            throw new RuntimeException("Failed to initialize server for guild: " + guild.getName(), e);
        }
    }

    public void removeServer(Guild guild) {
        Server server = servers.remove(guild);
        pokemonRestoredFlags.remove(guild);
        if(server == null)
            return;
        ServerConfig config = server.getConfig();
        Util.DATABASE.getCollection("server").deleteMany(
                Filters.eq("_id", config.getId())
        );
        Util.DATABASE.getCollection("prompt")
                .deleteMany(Filters.eq("server", config.getId()));
    }
}
