package discord.mian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Constants {
    public static final Logger LOGGER = LoggerFactory.getLogger("AI Roleplayer");
    public static final String BASE_URL = "https://openrouter.ai/api";
    public static final String DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct:free|Meta: Llama 3.3 70B Instruct (free)";
    public static final String DEFAULT_GOOGLEAI_MODEL = "gemini-2.5-flash|Gemini 2.5 Flash";
    public static final int HISTORY_COUNT = 100;

    public static final List<Long> ALLOWED_USER_IDS = new ArrayList<>();

    public static final boolean PUBLIC = true;
}
