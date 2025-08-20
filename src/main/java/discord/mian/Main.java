package discord.mian;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClients;
import discord.mian.ai.AIBot;
import discord.mian.data.ConfigEntryCodec;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.AnnotatedEventManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws Exception {
        String discord_bot_token = System.getenv("DISCORD_BOT_TOKEN");
        if (discord_bot_token == null && args.length > 0) {
            discord_bot_token = args[0];
        }
        
        String connectionString = System.getenv("MONGODB_CONNECTION_STRING");
        if (connectionString == null) {
            connectionString = args.length > 1 ? args[1] : "mongodb://localhost:27017/roleplayer";
        }

        ServerApi serverApi = ServerApi.builder()
                .version(ServerApiVersion.V1)
                .build();
        CodecRegistry codecRegistry = CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(new ConfigEntryCodec()),
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
        );
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .serverApi(serverApi)
                .codecRegistry(codecRegistry)
                .build();
        Util.DATABASE = MongoClients.create(settings).getDatabase("roleplayer");

        Constants.LOGGER.info("IT'S TIME TO ROLEPLAY KIDDOS");

        try {
            new AIBot(JDABuilder.create(discord_bot_token, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                    .setEventManager(new AnnotatedEventManager())
                    .addEventListeners(new Listener())
                    .build());
        } catch (Exception e) {
            Constants.LOGGER.error("Failure during initialization", e);
            throw e;
        }

        Cats.create();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            Constants.LOGGER.info("Set up randomizing cat every hour..");
            scheduler.scheduleAtFixedRate(Cats::create, 0, 1, TimeUnit.HOURS);
        }
    }
}
