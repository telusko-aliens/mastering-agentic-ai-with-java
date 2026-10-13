package com.novacart.config;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.ApigeeLlm;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Claude;
import com.google.adk.models.Gemini;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.novacart.session.JdbcSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ADK plumbing, as Spring beans.
 *
 * <p>In classes 1 to 4 {@code InMemoryRunner} built three of these for us. Here we choose each
 * one, because that choice is the difference between a demo and something a user can come back
 * to tomorrow.
 *
 * <pre>
 *   Runner ── agent            what to do           (AgentConfig)
 *          ── sessionService   conversations        H2, survives a restart
 *          ── memoryService    across conversations in memory for now
 *          ── artifactService  files                in memory for now
 * </pre>
 *
 * <p>One Runner serves every request, and that is safe: a Runner holds no conversation. The
 * conversation is the Session, looked up per request by id.
 */
@Configuration
public class AdkConfig {

    /**
     * Which model the whole application talks to.
     *
     * <p>ADK has no model type of its own: it has {@link BaseLlm}, and three implementations
     * ship in the jar. Everything else is written against the abstract type, which is why this
     * is the only place in the application that names a provider.
     */
    @Bean
    public BaseLlm chatModel(
            @Value("${novacart.model.provider:gemini}") String provider,
            @Value("${novacart.model.name:gemini-flash-latest}") String modelName,
            @Value("${novacart.model.ollama-url:http://localhost:11434/v1}") String ollamaUrl) {

        return switch (provider.toLowerCase()) {

            case "ollama" ->
                // ApigeeLlm is the ADK class that speaks the OpenAI wire format, which is what
                // Ollama serves at /v1. The apigee/openai/ prefix is not decoration: ADK reads
                // it to decide which JSON shape to send.
                    ApigeeLlm.builder()
                            .modelName("apigee/openai/" + modelName)
                            .proxyUrl(ollamaUrl)
                            .apiType(ApigeeLlm.ApiType.CHAT_COMPLETIONS)
                            .build();

            case "claude" -> new Claude(modelName,
                    AnthropicOkHttpClient.builder()
                            .apiKey(required("ANTHROPIC_API_KEY"))
                            .build());

            case "gemini" -> Gemini.builder()
                    .modelName(modelName)
                    .apiKey(required("GOOGLE_API_KEY"))
                    .build();

            // Failing loudly at startup beats a typo quietly billing a cloud API.
            default -> throw new IllegalStateException(
                    "Unknown novacart.model.provider '" + provider
                            + "'. Use gemini, ollama or claude.");
        };
    }

    /**
     * Conversations, in H2.
     *
     * <p>ADK Java ships {@code InMemorySessionService} and {@code VertexAiSessionService} and
     * nothing else, so a database-backed one is something you write. {@link JdbcSessionService}
     * is the class 2 implementation, unchanged: five methods against an interface.
     *
     * <p>Swap the URL for Postgres and not one line of the application changes, because nothing
     * above this bean knows which implementation it got.
     */
    @Bean
    public BaseSessionService sessionService(
            @Value("${novacart.sessions.jdbc-url}") String jdbcUrl) {
        return new JdbcSessionService(jdbcUrl);
    }

    /**
     * Files. In memory, which means a boarding pass is gone when the app restarts.
     *
     * <p>Fine for a class. For production the jar also ships {@code GcsArtifactService}, and the
     * interface is small enough to implement over your own storage.
     */
    @Bean
    public BaseArtifactService artifactService() {
        return new InMemoryArtifactService();
    }

    /** What is remembered across conversations. Also in memory for now. */
    @Bean
    public BaseMemoryService memoryService() {
        return new InMemoryMemoryService();
    }

    /**
     * The one Runner the whole application shares.
     *
     * <p>Built with {@code .agent(...)} rather than {@code .app(...)}. The App form is what you
     * need for event compaction, and it also validates its name: underscores only, no hyphens.
     */
    @Bean
    public Runner runner(LlmAgent copilot,
                         BaseSessionService sessionService,
                         BaseArtifactService artifactService,
                         BaseMemoryService memoryService,
                         @Value("${novacart.app-name}") String appName) {

        return Runner.builder()
                .agent(copilot)
                .appName(appName)
                .sessionService(sessionService)
                .artifactService(artifactService)
                .memoryService(memoryService)
                .build();
    }

    private static String required(String variable) {

        String value = System.getenv(variable);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException("""
                    %s is not set.

                    Windows : set %s=your-key-here
                    Linux   : export %s=your-key-here

                    Or run with no key at all:
                      --novacart.model.provider=ollama""".formatted(variable, variable, variable));
        }

        return value;
    }
}
