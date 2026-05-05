package org.cland.alice.facade.cmd.render;

import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.facade.cmd.config.RunConfig;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 增强文本格式输出渲染器（默认）。
 * <p>
 * 在 stdio 上输出可读性强、带有颜标的文本。
 * 实际输出为纯文本（带 Unicode 标记符号），
 * 终端支持 ANSI 时可通过管道工具二次着色。
 */
public final class TextOutputRenderer implements OutputRenderer {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String INDENT = "  ";

    @Override
    public void render(StepResult stepResult, RunConfig config) {
        if (stepResult == null) {
            return;
        }

        switch (stepResult) {
            case StepResult.Continue cont -> renderContinue(cont, config);
            case StepResult.Finish fin    -> renderFinish(fin, config);
            case StepResult.Failure fail  -> renderFailure(fail, config);
        }
    }

    @Override
    public void renderFinal(String summary, RunConfig config) {
        String timestamp = TIME_FMT.format(Instant.now());
        System.out.println();
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  ✓ Final Answer [" + timestamp + "]");
        System.out.println("═══════════════════════════════════════════");
        System.out.println();
        System.out.println(summary);
        System.out.println();
    }

    @Override
    public void renderError(String errorMessage, RunConfig config) {
        System.err.println("✖ Error: " + errorMessage);
    }

    // ========================================================================
    // 内部渲染
    // ========================================================================

    private void renderContinue(StepResult.Continue cont, RunConfig config) {
        var action = cont.nextAction();
        var observation = cont.observation();

        if (action != null) {
            String timestamp = TIME_FMT.format(Instant.now());

            System.out.println("─── [" + timestamp + "] ───");

            if (config.verbose()) {
                String thought = action.thought();
                if (thought != null && !thought.isBlank()) {
                    System.out.println(INDENT + "💭 " + thought);
                }
            }

            System.out.print(INDENT + "⚡ " + action.type());
            if (action.target() != null && !action.target().isBlank()) {
                System.out.print(" → " + action.target());
            }
            System.out.println();
        }

        if (observation != null && config.verbose()) {
            String symbol = switch (observation.status()) {
                case SUCCESS  -> "✅";
                case FAILURE  -> "❌";
                case PARTIAL  -> "⚠️";
                case TIMEOUT  -> "⏱️";
                case BLOCKED  -> "🚫";
            };
            System.out.println(INDENT + symbol + " " + observation.summary());
        }
    }

    private void renderFinish(StepResult.Finish fin, RunConfig config) {
        // final 渲染由 renderFinal 完成
        if (config.verbose()) {
            System.out.println(INDENT + "🏁 Agent finished");
            if (fin.summary() != null) {
                System.out.println(INDENT + "📋 " + fin.summary());
            }
        }
    }

    private void renderFailure(StepResult.Failure fail, RunConfig config) {
        System.err.println("✖ Step failed: " + fail.errorMessage());
        if (fail.cause() != null && config.verbose()) {
            fail.cause().printStackTrace(System.err);
        }
    }
}
