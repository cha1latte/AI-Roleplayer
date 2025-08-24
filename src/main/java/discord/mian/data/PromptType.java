package discord.mian.data;

public enum PromptType {
    INSTRUCTION("Instructions"), WORLD("Personas"), CHARACTER("Characters");

    public final String displayName;

    PromptType(String displayName) {
        this.displayName = displayName;
    }
}
