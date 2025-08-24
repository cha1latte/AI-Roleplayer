package discord.mian.data;

public enum PromptType {
    INSTRUCTION("System Prompts"), WORLD("Personas"), CHARACTER("Characters");

    public final String displayName;

    PromptType(String displayName) {
        this.displayName = displayName;
    }
}
