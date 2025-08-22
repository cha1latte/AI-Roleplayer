package discord.mian.ai;

public enum AIProvider {
    OPENROUTER("OpenRouter"),
    GOOGLE_AI("Google AI");

    public final String displayName;

    AIProvider(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}