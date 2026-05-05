/*
 * Alice Agent --- Application Layer Orchestrator
 *
 * Design doc §2.2: AliceAgent entity
 *   The system's "main switch", responsible for coordinating
 *   the core-agent with facade-* modules.
 */
package org.cland.alice.agent;

import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.model.supplier.OpenAiSupplier;

import java.lang.System.Logger;

public final class AliceAgent implements AutoCloseable {

    private static final Logger logger = System.getLogger(AliceAgent.class.getName());

    public static final String VERSION = "0.1.0";

    private final Agent agent;
    private final AgentConfig agentConfig;
    private FacadeSelector.FacadeType facadeType;
    private volatile boolean running;

    public AliceAgent() {
        this(AgentConfig.defaults());
    }

    public AliceAgent(AgentConfig config) {
        this.agentConfig = config;
        this.agent = new Agent(config);
        this.running = false;

        logger.log(System.Logger.Level.INFO, "AliceAgent [{0}] created with model={1}, maxIterations={2}",
            agent.agentId(), config.defaultModelId(), config.maxIterations());
    }

    public static int bootstrap(String[] args) {
        logger.log(System.Logger.Level.INFO, "Alice Agent v{0} bootstrapping...", VERSION);

        try {
            initializeModelProvider();
        } catch (Exception e) {
            logger.log(System.Logger.Level.ERROR, "Failed to initialize ModelProvider", e);
            return AliceApp.EXIT_RUNTIME_ERROR;
        }

        FacadeSelector.FacadeType facadeType = FacadeSelector.detect(args);
        logger.log(System.Logger.Level.INFO, "Facade selected: {0}", facadeType);

        AgentConfig config = buildConfig(args);

        try (AliceAgent orchestrator = new AliceAgent(config)) {
            orchestrator.facadeType = facadeType;
            int exitCode = orchestrator.start(args);
            logger.log(System.Logger.Level.INFO,
                "AliceAgent [{0}] exited with code {1}",
                orchestrator.agent.agentId(), exitCode);
            return exitCode;
        } catch (Exception e) {
            logger.log(System.Logger.Level.ERROR, "AliceAgent bootstrap failed", e);
            return AliceApp.EXIT_RUNTIME_ERROR;
        }
    }

    public int start(String[] args) {
        this.running = true;
        logger.log(System.Logger.Level.INFO,
            "AliceAgent [{0}] starting {1} facade...",
            agent.agentId(), facadeType);

        if (agent.agentCore() == null) {
            logger.log(System.Logger.Level.ERROR, "AgentCore not initialized");
            return AliceApp.EXIT_RUNTIME_ERROR;
        }

        return FacadeSelector.launch(facadeType, agent, args);
    }

    @Override
    public void close() {
        if (!running) return;
        running = false;
        logger.log(System.Logger.Level.INFO, "AliceAgent [{0}] shutting down...", agent.agentId());
        try { agent.close(); }
        catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Error closing Agent core", e);
        }
        logger.log(System.Logger.Level.INFO, "AliceAgent [{0}] shut down complete", agent.agentId());
    }

    public Agent agent() { return agent; }
    public AgentConfig config() { return agentConfig; }
    public FacadeSelector.FacadeType facadeType() { return facadeType; }
    public boolean isRunning() { return running; }

    private static void initializeModelProvider() {
        logger.log(System.Logger.Level.INFO, "Initializing ModelProvider...");
        ModelProvider provider = ModelProvider.getInstance();
        provider.registerBuiltinModels();

        String openAiKey = System.getenv("OPENAI_API_KEY");
        if (openAiKey != null && !openAiKey.isEmpty()) {
            provider.registerSupplier(new OpenAiSupplier(openAiKey));
            logger.log(System.Logger.Level.INFO, "OpenAI supplier registered");
        } else {
            logger.log(System.Logger.Level.WARNING,
                "OPENAI_API_KEY not set. LLM calls via OpenAI will be unavailable.");
        }

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isEmpty()) {
            logger.log(System.Logger.Level.INFO, "Anthropic API key detected");
        }
        logger.log(System.Logger.Level.INFO, "ModelProvider initialized");
    }

    private static AgentConfig buildConfig(String[] args) {
        AgentConfig.Builder builder = AgentConfig.builder();
        if (args == null || args.length == 0) return builder.build();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--model": case "-m":
                    if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                        builder.defaultModelId(args[++i]);
                    break;
                case "--max-iterations":
                    if (i + 1 < args.length) {
                        try { builder.maxIterations(Integer.parseInt(args[++i])); }
                        catch (NumberFormatException e) { logger.log(System.Logger.Level.WARNING, "Invalid max-iterations"); }
                    }
                    break;
                case "--timeout":
                    if (i + 1 < args.length) {
                        try { builder.actionTimeoutMs(Long.parseLong(args[++i]) * 1000); }
                        catch (NumberFormatException e) { logger.log(System.Logger.Level.WARNING, "Invalid timeout"); }
                    }
                    break;
                case "--verbose": case "-v": case "--debug":
                    builder.debug(true);
                    break;
                case "--no-pre-verify":
                    builder.preVerifyEnabled(false);
                    break;
                case "--no-post-verify":
                    builder.postVerifyEnabled(false);
                    break;
            }
        }
        return builder.build();
    }
}
