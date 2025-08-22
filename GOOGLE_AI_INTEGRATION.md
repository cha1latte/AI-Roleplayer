# Google AI Studio Integration

This document explains how to use Google AI Studio with your AI Roleplayer bot.

## Setup

1. **Get a Google AI Studio API Key**:
   - Go to [Google AI Studio](https://ai.google.dev/)
   - Create an account or sign in
   - Generate an API key

2. **Configure the Bot**:
   - Use `/menu` command in Discord
   - Go to "Server Configuration"
   - Add your Google AI Studio API key in the "google_ai_key" field

3. **Switch to Google AI**:
   - Use the new `/ai_provider` command
   - Select "Google AI" from the dropdown
   - The bot will automatically switch to using Gemini 2.5 Flash model

## Features

### Supported AI Providers
- **OpenRouter**: Access to multiple AI models with streaming support
- **Google AI**: Direct access to Google's Gemini models

### Commands
- `/ai_provider`: Switch between OpenRouter and Google AI
- All existing commands work with both providers

### Models
- **Google AI**: Currently defaults to `gemini-2.5-flash`
- **OpenRouter**: Supports all available models on the platform

## Differences Between Providers

### OpenRouter
- ✅ Streaming responses (real-time text generation)
- ✅ Multiple model options
- ✅ Provider selection and fallbacks
- ✅ Detailed usage statistics

### Google AI
- ✅ Direct Google integration
- ✅ High-quality Gemini models
- ❌ No streaming (responses appear all at once)
- ❌ Limited model selection
- ❌ No detailed usage statistics

## Technical Implementation

The integration adds the following new classes:
- `AIProvider`: Enum for different AI providers
- `GoogleAIRequest`: Wrapper for Google AI API calls
- `ChangeAIProvider`: Discord command to switch providers

The `Roleplay` class now supports both providers and automatically routes requests based on the selected provider.

## Troubleshooting

### "Google AI returned no content"
- Check that your API key is valid
- Ensure the API key has proper permissions
- Verify that your prompt isn't triggering content filters

### "Google AI returned an error"
- Check your API quotas in Google AI Studio
- Ensure your API key hasn't expired
- Try switching back to OpenRouter temporarily

### Build Issues
- Make sure you have Java 21 installed
- Run `./gradlew build` to compile the project
- If Google AI dependency fails, check your internet connection

## Usage Tips

1. **For Development**: Use Google AI for quick testing with high-quality responses
2. **For Production**: Consider OpenRouter for more model variety and streaming
3. **Cost Management**: Monitor usage in both Google AI Studio and OpenRouter dashboards
4. **Model Selection**: Google AI uses fixed models, while OpenRouter allows model switching

## Code Changes Made

1. Added Google AI dependency to `build.gradle.kts`
2. Added Google AI configuration to `ServerConfig`
3. Created `GoogleAIRequest` class for API interactions
4. Modified `Roleplay` class to support multiple providers
5. Added `/ai_provider` command for switching between providers
6. Updated constants for default Google AI model

All changes are backward compatible - existing OpenRouter functionality remains unchanged.