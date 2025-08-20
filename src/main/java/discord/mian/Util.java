package discord.mian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import discord.mian.ai.AIBot;
import discord.mian.data.PromptType;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import okhttp3.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Util {
    public static final HashMap<Predicate<LocalDate>, List<String>> TOOL_TIPS = new HashMap<>();
    public static MongoDatabase DATABASE;

    static {
        TOOL_TIPS.put(date -> true, List.of(
                "This is super queer :o",
                "You're valid everyday 💖",
                "Meow! 😺😽",
                "I love roleplaying every single day grrr 🦸",
                "Oooh they want me here, they need me here, they have to show me I've got work right now~",
                "Check out Rimworld, it's a pretty hawt game :>",
                "WorldBox is if you were God but went on crack!"
        ));

        TOOL_TIPS.put(currentDate -> currentDate.equals(LocalDate.of(currentDate.getYear(), Month.MARCH, 31)),
                List.of(
                        "Happy Transgender Day of Visibility! 🏳️‍⚧️"
                ));

        TOOL_TIPS.put(currentDate -> {
                    LocalDate startOfWeek = LocalDate.of(currentDate.getYear(), Month.NOVEMBER, 13);
                    LocalDate endOfWeek = startOfWeek.plusDays(6);

                    return !currentDate.isBefore(startOfWeek) && !currentDate.isBefore(endOfWeek);
                },
                List.of(
                        "I wish I could be a girl and that way you'd wish I could be your girlfriend boyfriend~",
                        "Who cares what's in your pants? 🤔",
                        "Your chromosomes do not define who you are 🏳️‍⚧️",
                        "Ngl people who identify as vampires, cats, dogs, etc, are cool af 🤘",
                        "No one can tell you who you are.",
                        "Trans people are hot 🥚",
                        "Trans rights are human rights!"
                ));

        TOOL_TIPS.put(curentDate -> curentDate.getMonth() == Month.JUNE,
                List.of(
                        "Happy Pride Month! 🌈",
                        "WOHOO!! SHOW OFF THAT PRIDE!",
                        "Those bigots ain't got shit on us",
                        "Celebrating Pride Month with joy! 🥳"
                ));

        TOOL_TIPS.put(currentDate -> currentDate.equals(LocalDate.of(currentDate.getYear(), Month.APRIL, 26)),
                List.of(
                        "Happy Lesbian Visibility Day! 🏳️‍🌈"
                ));

        // lesbian visibility week
        TOOL_TIPS.put(currentDate -> {
            LocalDate visibilityDay = LocalDate.of(currentDate.getYear(), Month.APRIL, 26);
            LocalDate startOfWeek = visibilityDay.minusDays(visibilityDay.getDayOfWeek().getValue() - 1);
            LocalDate endOfWeek = startOfWeek.plusDays(6);

            return !currentDate.isBefore(startOfWeek) && !currentDate.isAfter(endOfWeek);
        }, List.of(
                "Ever heard of the hit robot lesbian game Signalis?",
                "LESBIANS UNITE! ✊",
                "Did you ever notice that the lesbian flag kinda looks like bacon? 🥓",
                "Doesn't it suck when a woman steals your chick? 😎",
                "Girls are pretty :o 👧",
                "SHE'S SO PRETTY!!!"
        ));
    }

    public static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    public static String getRandomToolTip() {
        LocalDate date = LocalDate.now();
        List<String> validStrings = new ArrayList<>();

        TOOL_TIPS.forEach((predicate, strings) -> {
            if (predicate.test(date))
                validStrings.addAll(strings);
        });

        return validStrings.get((int) (Math.random() * validStrings.size()));
    }

    public static String botifyMessage(String string) {
        return "```" + string + "```";
    }

    public static File getDefaultsFor(PromptType promptType) {
        File data = getDataFolder();
        return new File(data.getPath() + "/defaults/" + promptType.displayName.toLowerCase());
    }

    public static File getDataFolder() {
        File data = new File("data");
        if (!data.exists()) {
            data.mkdir();
        }

        // ensures there's a default folder
        File defaultFolder = new File("data/defaults");
        if (!new File("data/defaults").exists()) {
            System.out.println("data/defaults does not exist, extracting from defaults.zip");
            try (InputStream in = Util.class.getClassLoader().getResourceAsStream("defaults.zip")) {
                if (in == null) {
                    System.out.println("ERROR: defaults.zip not found in resources!");
                    return;
                }
                System.out.println("Found defaults.zip, extracting...");
                ZipInputStream zip = new ZipInputStream(in);
                ZipEntry entry;

                while ((entry = zip.getNextEntry()) != null) {
                    // entry.getName() is a path
                    Path outPath = defaultFolder.toPath().resolve(entry.getName());

                    // creates directory if entry is a directory
                    if (entry.isDirectory()) {
                        Files.createDirectories(outPath);
                    } else {
                        // creates directory for file and then copies the file
                        Files.createDirectories(outPath.getParent());
                        Files.copy(zip, outPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zip.closeEntry();
                }
            } catch (IOException ignored) {
                Constants.LOGGER.info("Failed to create default folder!");
            }
        }

        return data;
    }

    public static File createFileRelativeToData(String name) {
        return new File(getDataFolder().getName() + "/" + name);
    }

    public static byte[] getRandomImage() throws IOException {
        File images = createFileRelativeToData("images");
        if (!images.exists())
            images.mkdir();

        File[] files = images.listFiles();
        if (files.length == 0)
            return null;

        File[] randomImages = files[(int) (Math.random() * files.length)].listFiles();
        File randomImage = randomImages[(int) (Math.random() * randomImages.length)];
        if (!randomImage.exists())
            return null;

        return Files.readAllBytes(randomImage.toPath());
    }

    public static boolean isValidImage(byte[] image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image)) != null;
    }

    public static boolean isValidImage(File image) throws IOException {
        if (!image.exists())
            return false;

        String name = image.getName();
        String extension = name.substring(name.lastIndexOf("."));

        return IMAGE_EXTENSIONS.contains(extension) && ImageIO.read(image) != null;
    }

    public static String uploadImage(String key, String name, byte[] data) throws IOException {
        if (!isValidImage(data))
            throw new RuntimeException("Invalid image!");

        Constants.LOGGER.info("Attempting to upload " + name);

        OkHttpClient httpClient = new OkHttpClient.Builder().build();

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("key", key)
                .addFormDataPart("image", name, RequestBody.create(data,
                        MediaType.parse("application/octet-stream")))
                .build();

        Request request = new Request.Builder()
                .url("https://api.imgbb.com/1/upload")
                .post(requestBody)
                .build();

        Call call = httpClient.newCall(request);
        Response response = call.execute();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(response.body().string());

        if (response.code() < 200 || response.code() >= 400) {
            Constants.LOGGER.info("Failed to upload image!");
            throw new RuntimeException(json.get("error").get("message").asText());
        }

        String link = json.get("data").get("url").asText();

        Constants.LOGGER.info("Uploaded " + name + ": " + link);
        response.body().close();

        return link;
    }

    public static void copyDirectory(Path source, Path target) throws IOException {
        // Walk the file tree starting from the source path
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy: " + sourcePath, e);
            }
        });
    }

    public static Container createBotContainer(List<ContainerChildComponent> moreComponents) {
        ArrayList<ContainerChildComponent> components = new ArrayList<>();
        components.add(TextDisplay.of("-# [Created By Your Lovely Girl: @MianReplicate](https://en.pronouns.page/@MianReplicate)"));
        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.addAll(moreComponents);

        return Container.of(components)
                .withAccentColor(new Color(
                        (int) (Math.random() * 256),
                        (int) (Math.random() * 256),
                        (int) (Math.random() * 256),
                        (int) (Math.random() * 256)));
    }

    public static boolean hasMasterPermission(Member member) {
        Role role = AIBot.bot.getServerData(member.getGuild()).getMasterRole();
        List<Long> roles = member.getRoles().stream().map(Role::getIdLong).toList();

        return role == null || roles.contains(role.getIdLong()) ||
                Constants.ALLOWED_USER_IDS.contains(member.getUser().getIdLong()); // nepotism at its finest baby :sunglasses:
    }
}
