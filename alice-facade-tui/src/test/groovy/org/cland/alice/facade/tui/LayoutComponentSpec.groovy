/*
 * Unit tests for TUI layout management and UI components (JLine 3 based).
 *
 * Covers: Component, TuiLayout (TAO四段式), HeaderComponent, FooterComponent,
 *         InputComponent, InputBlockComponent, ThinkBlockComponent,
 *         ActionBlockComponent, ObserveBlockComponent, TaoTag
 */
package org.cland.alice.facade.tui

import spock.lang.Specification
import spock.lang.Title
import org.cland.alice.facade.tui.component.*
import org.cland.alice.facade.tui.layout.TuiLayout

@Title("TUI Layout and Component Unit Tests (v3.1 TAO四段式)")
class LayoutComponentSpec extends Specification {

    static class TestComponent extends Component {
        TestComponent(String name) { super(name) }
        @Override
        java.util.List<String> render() {
            clearDirty()
            return visible && width > 0 && height > 0
                ? ["test line"] * height
                : []
        }
    }

    static Component componentStub(String name) { return new TestComponent(name) }

    static String stripAnsi(String s) {
        def ESC = (char)27 as String
        return s.replaceAll(ESC + '\\[\\d+(;\\d+)*m', "")
    }

    static TuiLayout createLayout() {
        return new TuiLayout(
            new HeaderComponent(),
            new InputBlockComponent(),
            new ThinkBlockComponent(),
            new ActionBlockComponent(),
            new ObserveBlockComponent(),
            new InputComponent(),
            new FooterComponent())
    }

    // ===================================================================
    // Component (abstract base)
    // ===================================================================

    def "Component initial state"() {
        given: "a concrete Component implementation"
        def comp = componentStub("TestComp")
        expect: "initial state"
        comp.name == "TestComp"
        comp.isVisible()
        comp.isDirty()
        comp.row() == 0
        comp.col() == 0
        comp.width() == 0
        comp.height() == 0
    }

    def "Component setBounds updates position and size and marks dirty"() {
        given: "a Component"
        def comp = componentStub("Test")
        when: "setting bounds"
        comp.setBounds(3, 5, 80, 20)
        then: "position and size are updated"
        comp.row() == 3
        comp.col() == 5
        comp.width() == 80
        comp.height() == 20
        comp.isDirty()
    }

    def "Component render clears dirty flag"() {
        given: "a Component"
        def comp = componentStub("Test")
        comp.setBounds(0, 0, 80, 1)
        when: "rendering"
        comp.render()
        then: "dirty flag cleared"
        !comp.isDirty()
    }

    def "Component render returns empty when invisible or zero-sized"() {
        given: "a Component"
        def comp = componentStub("Test")
        expect: "empty for invisible"
        comp.hide()
        comp.render().isEmpty()
        and: "empty for zero width"
        comp.show()
        comp.setSize(0, 5)
        comp.render().isEmpty()
    }

    // ===================================================================
    // TuiLayout — TAO 四段式 (v3.1)
    // ===================================================================

    def "TuiLayout FIXED_ROWS is 6 (Header+InputBlock+ActionBlock+Footer)"() {
        expect:
        TuiLayout.FIXED_ROWS == 6
        TuiLayout.HEADER_HEIGHT == 1
        TuiLayout.INPUT_BLOCK_HEIGHT == 2
        TuiLayout.ACTION_BLOCK_HEIGHT == 2
        TuiLayout.STATUS_HEIGHT == 1
    }

    def "TuiLayout recalculate positions all TAO zones for standard 80x24"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "recalculating for 80x24 terminal"
        layout.recalculate(80, 24)
        then: "header at row 0"
        layout.header().row() == 0
        layout.header().width() == 80

        and: "InputBlock at rows 1-2"
        layout.inputBlockStartRow() == 1
        layout.inputBlockHeight() == 2
        layout.inputBlock().row() == 1
        layout.inputBlock().height() == 2
        layout.inputBlock().width() == 80

        and: "ThinkBlock below InputBlock"
        layout.thinkBlockStartRow() == 3
        layout.thinkBlock().row() == 3
        layout.thinkBlock().width() == 80
        layout.thinkBlockHeight() > 0

        and: "ActionBlock below ThinkBlock"
        layout.actionBlockStartRow() == layout.thinkBlockStartRow() + layout.thinkBlockHeight()
        layout.actionBlockHeight() == 2
        layout.actionBlock().row() == layout.actionBlockStartRow()
        layout.actionBlock().height() == 2
        layout.actionBlock().width() == 80

        and: "ObserveBlock below ActionBlock"
        layout.observeBlockStartRow() == layout.actionBlockStartRow() + layout.actionBlockHeight()
        layout.observeBlock().row() == layout.observeBlockStartRow()
        layout.observeBlock().width() == 80
        layout.observeBlockHeight() > 0

        and: "separator below ObserveBlock"
        layout.separatorRow() == layout.observeBlockStartRow() + layout.observeBlockHeight()

        and: "queue row after separator, then input"
        layout.queueRow() == layout.separatorRow() + 1
        layout.inputRow() == layout.separatorRow() + 2
        layout.input().row() == layout.inputRow()

        and: "footer at bottom row"
        layout.footerRow() == 23
        layout.footer().row() == 23

        and: "ThinkBlock + ObserveBlock fill remaining space"
        int fixed = TuiLayout.HEADER_HEIGHT + TuiLayout.INPUT_BLOCK_HEIGHT + TuiLayout.ACTION_BLOCK_HEIGHT + TuiLayout.STATUS_HEIGHT
        int contentRows = 24 - fixed - 3 // -3 for separator + queue + input
        layout.thinkBlockHeight() + layout.observeBlockHeight() == contentRows
    }

    def "TuiLayout enforces minimum terminal dimensions"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "recalculating with tiny terminal (20x3)"
        layout.recalculate(20, 3)
        then: "minimum width is 40"
        layout.terminalWidth() == 40
        and: "minimum height is FIXED_ROWS + 6 = 12"
        layout.terminalHeight() >= TuiLayout.FIXED_ROWS + 6
        and: "all zone heights are positive"
        layout.thinkBlockHeight() > 0
        layout.observeBlockHeight() > 0
    }

    def "TuiLayout recalculate marks all components dirty"() {
        given: "a TuiLayout"
        def layout = createLayout()
        layout.markAllDirty()
        layout.header().clearDirty()
        layout.inputBlock().clearDirty()
        layout.thinkBlock().clearDirty()
        layout.actionBlock().clearDirty()
        layout.observeBlock().clearDirty()
        layout.input().clearDirty()
        layout.footer().clearDirty()

        when: "recalculating"
        layout.recalculate(80, 24)

        then: "all 7 components are dirty"
        layout.header().isDirty()
        layout.inputBlock().isDirty()
        layout.thinkBlock().isDirty()
        layout.actionBlock().isDirty()
        layout.observeBlock().isDirty()
        layout.input().isDirty()
        layout.footer().isDirty()
    }

    def "TuiLayout getComponents returns all seven components"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "getting components"
        def components = layout.getComponents()
        then: "returns exactly 7 components"
        components.size() == 7
        components.contains(layout.header())
        components.contains(layout.inputBlock())
        components.contains(layout.thinkBlock())
        components.contains(layout.actionBlock())
        components.contains(layout.observeBlock())
        components.contains(layout.input())
        components.contains(layout.footer())
    }

    def "TuiLayout separatorLine generates ANSI dim line of correct length"() {
        given: "a TuiLayout"
        def layout = createLayout()
        layout.recalculate(60, 24)
        when:
        def line = layout.separatorLine()
        then:
        line.contains("\u001B[38;5;242m")
        line.contains("\u001B[0m")
        def escChar = (char)27 as String
        def plain = line.replaceAll(escChar + '\\[\\d+(;\\d+)*m', "")
        plain.length() == 60
    }

    // ===================================================================
    // HeaderComponent
    // ===================================================================

    def "HeaderComponent default label and render"() {
        given: "a HeaderComponent"
        def header = new HeaderComponent()
        header.setBounds(0, 0, 60, 1)
        expect:
        header.label() == "alice-agent v0.60.0"
        def lines = header.render()
        lines.size() == 1
        lines[0].contains("alice-agent v0.60.0")
        lines[0].contains("\uD83E\uDD16")
        stripAnsi(lines[0]).length() == 60
    }

    // ===================================================================
    // FooterComponent
    // ===================================================================

    def "FooterComponent default values and render"() {
        given: "a FooterComponent"
        def footer = new FooterComponent()
        footer.setBounds(0, 0, 80, 1)
        footer.setCost('\$0.041')
        footer.setSpeed('125 t/s')
        footer.setModel("gpt-4o-mini")
        footer.setTool("shell")
        expect:
        footer.costInfo() == '\$0.041'
        def lines = footer.render()
        lines.size() == 1
        lines[0].contains('\$0.041')
        lines[0].contains('125 t/s')
        lines[0].contains("gpt-4o-mini")
        lines[0].contains("\u001B[48;5;208m") // orange bg
        lines[0].contains("\u001B[48;5;35m")  // green bg
        lines[0].contains("\u001B[48;5;239m") // dark gray bg
        stripAnsi(lines[0]).length() == 80
    }

    // ===================================================================
    // InputComponent
    // ===================================================================

    def "InputComponent basic operations"() {
        given: "an InputComponent"
        def input = new InputComponent()
        expect: "initial state"
        input.getText() == ""
        input.prompt() == ""
        when: "setting text and rendering"
        input.setBounds(0, 0, 40, 1)
        input.setText("ls -la")
        def lines = input.render()
        then:
        lines.size() == 1
        lines[0].contains("ls -la")
        lines[0].length() == 40
    }

    // ===================================================================
    // InputBlockComponent (TAO 顶部 — 输入内容区)
    // ===================================================================

    def "InputBlockComponent renders user input with dark bg"() {
        given: "an InputBlockComponent"
        def block = new InputBlockComponent()
        block.setBounds(0, 0, 60, 5)
        when: "showing user input"
        block.showUserInput("debug current program")
        def lines = block.render()
        then:
        stripAnsi(lines[0]).contains("debug current program")
        and: "all rows have dark background"
        lines.every { it.contains("\u001B[48;5;236m") }
        and: "exactly 5 rows"
        lines.size() == 5
    }

    def "InputBlockComponent clear removes all content"() {
        given: "an InputBlockComponent"
        def block = new InputBlockComponent()
        block.setBounds(0, 0, 60, 3)
        block.showUserInput("test")
        when: "clearing"
        block.clear()
        then: "all empty"
        block.render().every { stripAnsi(it).trim().isEmpty() }
    }

    // ===================================================================
    // ThinkBlockComponent (TAO 中间 — 思考区)
    // ===================================================================

    def "ThinkBlockComponent addThought renders step marker + content with light bg"() {
        given: "a ThinkBlockComponent"
        def block = new ThinkBlockComponent()
        block.setBounds(0, 0, 60, 5)
        when: "adding thought with step=1"
        block.addThought("analyzing data", 1)
        def lines = block.render()
        then: "step marker on first line"
        stripAnsi(lines[0]).contains("Step 1")
        and: "content on second line"
        stripAnsi(lines[1]).contains("analyzing data")
        and: "no TaoTag color block"
        !lines[0].contains("\u001B[48;5;239m")
        !lines[0].contains("THOUGHT")
        and: "all rows have ThinkBlock light background"
        lines.every { it.contains("\u001B[48;5;255m") }
    }

    def "ThinkBlockComponent addAgentMessage strips FINISH marker"() {
        given: "a ThinkBlockComponent"
        def block = new ThinkBlockComponent()
        block.setBounds(0, 0, 60, 3)
        when: "adding agent message with [FINISH]"
        block.addAgentMessage("done [FINISH]")
        then:
        !stripAnsi(block.render()[0]).contains("[FINISH]")
        stripAnsi(block.render()[0]).contains("done")
    }

    def "ThinkBlockComponent clear and scrolling"() {
        given: "a ThinkBlockComponent"
        def block = new ThinkBlockComponent()
        block.setBounds(0, 0, 60, 3)
        (1..10).each { block.addThought("step $it", it) }
        // Each thought = 2 lines (Step marker + content), 10 thoughts = 20 lines
        expect: "scrolling up shows Step 9 marker"
        block.scrollUp()
        stripAnsi(block.render()[0]).contains("Step 9")
        and: "scrolling to bottom shows step 9 content"
        block.scrollToBottom()
        stripAnsi(block.render()[0]).contains("step 9")
        block.clear()
        block.render().every { stripAnsi(it).trim().isEmpty() }
    }

    // ===================================================================
    // ActionBlockComponent (TAO 中下 — 动作区，与 InputBlock 同风格)
    // ===================================================================

    def "ActionBlockComponent addCommand renders with dollar prefix and timeout"() {
        given: "an ActionBlockComponent"
        def block = new ActionBlockComponent()
        block.setBounds(0, 0, 60, 3)
        when: "adding a command"
        block.addCommand("bash execute")
        def line = block.render()[0]
        then:
        line.contains("\u001B[38;5;39m") // blue cmd prefix
        line.contains('$')
        line.contains("bash execute")
        and: "timeout tag in light blue"
        line.contains("\u001B[38;5;147m")
        line.contains("(timeout 120s)")
        and: "has InputBlock-like dark background"
        line.contains("\u001B[48;5;236m")
    }

    def "ActionBlockComponent multiple commands and scrolling"() {
        given: "an ActionBlockComponent"
        def block = new ActionBlockComponent()
        block.setBounds(0, 0, 60, 3)
        when: "adding 5 commands"
        (1..5).each { block.addCommand("cmd $it") }
        then: "shows latest"
        stripAnsi(block.render()[0]).contains("cmd 3")
        when: "scrolling up"
        block.scrollUp()
        then:
        stripAnsi(block.render()[0]).contains("cmd 2")
        when: "scrolling to bottom"
        block.scrollToBottom()
        then:
        stripAnsi(block.render()[0]).contains("cmd 3")
    }

    def "ActionBlockComponent dark background on all rows"() {
        given: "an ActionBlockComponent"
        def block = new ActionBlockComponent()
        block.setBounds(0, 0, 60, 4)
        block.addCommand("ls -la")
        when: "rendering"
        def lines = block.render()
        then: "all rows have dark bg"
        lines.every { it.contains("\u001B[48;5;236m") }
        and: "exactly 4 rows"
        lines.size() == 4
    }

    def "ActionBlockComponent clear removes all content"() {
        given: "an ActionBlockComponent"
        def block = new ActionBlockComponent()
        block.setBounds(0, 0, 60, 3)
        block.addCommand("test")
        expect: "has content"
        !block.render().every { stripAnsi(it).trim().isEmpty() }
        when: "clearing"
        block.clear()
        then: "all empty"
        block.render().every { stripAnsi(it).trim().isEmpty() }
    }

    // ===================================================================
    // ObserveBlockComponent (TAO 底部 — 观察输出区)
    // ===================================================================

    def "ObserveBlockComponent addOutput renders results"() {
        given: "an ObserveBlockComponent"
        def block = new ObserveBlockComponent()
        block.setBounds(0, 0, 60, 5)
        when: "adding output"
        block.addOutput("-rw------- 1 alice alice 111 config.json")
        def lines = block.render()
        then:
        stripAnsi(lines[0]).contains("config.json")
        and: "all rows have terminal dark background"
        lines.every { it.contains("\u001B[48;5;234m") }
        and: "exactly 5 rows"
        lines.size() == 5
    }

    def "ObserveBlockComponent highlights directory listings in yellow"() {
        given: "an ObserveBlockComponent"
        def block = new ObserveBlockComponent()
        block.setBounds(0, 0, 60, 3)
        when: "adding ls -la output"
        block.addOutput("drwxrwxr-x 2 alice alice 4096 6月 27 15:15 wal")
        then:
        block.render()[0].contains("\u001B[38;5;222m") // yellow
    }

    def "ObserveBlockComponent addTiming renders Took X.Xs line"() {
        given: "an ObserveBlockComponent"
        def block = new ObserveBlockComponent()
        block.setBounds(0, 0, 60, 3)
        when: "adding timing"
        block.addTiming(1.5)
        then:
        stripAnsi(block.render()[0]).contains("Took 1.5s")
        block.render()[0].contains("\u001B[38;5;246m") // dim color
    }

    def "ObserveBlockComponent addCollapsedLines renders count info"() {
        given: "an ObserveBlockComponent"
        def block = new ObserveBlockComponent()
        block.setBounds(0, 0, 60, 3)
        when: "adding collapsed line count"
        block.addCollapsedLines(5)
        then:
        stripAnsi(block.render()[0]).contains("5 earlier lines")
    }

    def "ObserveBlockComponent clear and scrolling"() {
        given: "an ObserveBlockComponent"
        def block = new ObserveBlockComponent()
        block.setBounds(0, 0, 60, 3)
        (1..10).each { n ->
            block.addOutput("output $n")
            block.addTiming(0.1 * n)
        }
        expect: "scrolling up once shows output 9"
        block.scrollUp()
        stripAnsi(block.render().join(" ")).contains("output 9")
        block.scrollToBottom()
        and: "at bottom shows output 10"
        stripAnsi(block.render().join(" ")).contains("output 10")
        block.clear()
        block.render().every { stripAnsi(it).trim().isEmpty() }
    }

    // ===================================================================
    // TaoTag
    // ===================================================================

    def "TaoTag enum values have correct ANSI colors and text"() {
        expect: "THOUGHT: dark gray bg, white fg"
        TaoTag.THOUGHT.render().contains("\u001B[48;5;239m")
        TaoTag.THOUGHT.render().contains("\u001B[37m")
        TaoTag.THOUGHT.plainText() == " THOUGHT "
        and: "ACTION: orange bg, black fg"
        TaoTag.ACTION.render().contains("\u001B[48;5;214m")
        TaoTag.ACTION.render().contains("\u001B[30m")
        TaoTag.ACTION.plainText() == " ACTION  "
        and: "OBSERVE: green bg, black fg"
        TaoTag.OBSERVE.render().contains("\u001B[48;5;35m")
        TaoTag.OBSERVE.render().contains("\u001B[30m")
        TaoTag.OBSERVE.plainText() == " OBSERVE "
    }

    def "TaoTag all tags are equal width (9 chars)"() {
        expect:
        TaoTag.THOUGHT.plainText().length() == 9
        TaoTag.ACTION.plainText().length() == 9
        TaoTag.OBSERVE.plainText().length() == 9
    }
}
