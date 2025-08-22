package discord.mian.data.character;

import discord.mian.api.Chattable;
import discord.mian.data.Data;
import io.github.sashirestela.openai.domain.chat.ChatMessage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class Character extends Data<CharacterDocument> implements Chattable {
    public Character(CharacterDocument document) {
        super(CharacterDocument.class, document);
    }

    public InputStream downloadAvatar() throws IOException {
        String avatarUrl = getDocument().getAvatar();
        if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
            return null;
        }
        
        URL url = new URL(avatarUrl.trim());
        return url.openStream();
    }

    public String getFirstName() {
        String name = document.getName();
        int spaceIndex = name.indexOf(" ");
        if (spaceIndex != -1)
            return name.substring(0, spaceIndex);
        else
            return name;
    }

    public ChatMessage.SystemMessage getChatMessage(Character ignored) {
        String definition = getPrompt();
        definition = definition.replaceAll("\\{\\{char}}", getName());

        return ChatMessage.SystemMessage.of(definition, getName());
    }
}
