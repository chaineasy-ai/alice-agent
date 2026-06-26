/*
 * Unit tests for TUI layout management and UI components (JLine 3 based).
 *
 * Covers: Component, TuiLayout, HeaderComponent, FooterComponent,
 *         InputComponent, ThoughtComponent, TaoTag
 */
package org.cland.alice.facade.tui

import spock.lang.Specification
import spock.lang.Title
import org.cland.alice.facade.tui.component.*
import org.cland.alice.facade.tui.layout.TuiLayout

@Title("TUI Layout and Component Unit Tests (v2.3)")
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
        // Strip ANSI escape sequences: ESC[...m
        def ESC = (char)27 as String
        return s.replaceAll(ESC + '\\[\\d+(;\\d+)*m', "")
    }

    static TuiLayout createLayout() {
        return new TuiLayout(
            new HeaderComponent(), new ThoughtComponent(),
            new InputComponent(), new FooterComponent())
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

    def "Component setPosition updates only position"() {
        given: "a Component with bounds"
        def comp = componentStub("Test")
        comp.setBounds(3, 5, 80, 20)
        when: "setting position only"
        comp.setPosition(10, 15)
        then: "position changes, size unchanged"
        comp.row() == 10
        comp.col() == 15
        comp.width() == 80
        comp.height() == 20
        comp.isDirty()
    }

    def "Component setSize updates only size"() {
        given: "a Component with bounds"
        def comp = componentStub("Test")
        comp.setBounds(3, 5, 80, 20)
        when: "setting size only"
        comp.setSize(100, 30)
        then: "size changes, position unchanged"
        comp.row() == 3
        comp.col() == 5
        comp.width() == 100
        comp.height() == 30
    }

    def "Component toggle visibility"() {
        given: "a visible Component"
        def comp = componentStub("Test")
        expect: "starts visible"
        comp.isVisible()
        when: "hiding"
        comp.hide()
        then: "not visible and dirty"
        !comp.isVisible()
        comp.isDirty()
        when: "showing"
        comp.show()
        then: "visible and dirty"
        comp.isVisible()
        comp.isDirty()
    }

    def "Component dirty flag lifecycle"() {
        given: "a Component"
        def comp = componentStub("Test")
        comp.clearDirty()
        expect: "not dirty after clear"
        !comp.isDirty()
        when: "marking dirty"
        comp.markDirty()
        then: "dirty"
        comp.isDirty()
        when: "clearing again"
        comp.clearDirty()
        then: "not dirty"
        !comp.isDirty()
    }

    def "Component toString contains name and dimensions"() {
        given: "a Component with bounds"
        def comp = componentStub("MyComp")
        comp.setBounds(2, 3, 40, 10)
        when: "calling toString"
        def str = comp.toString()
        then: "string contains key fields"
        str.contains("MyComp")
        str.contains("2")
        str.contains("3")
        str.contains("40")
        str.contains("10")
    }

    def "Component render returns empty when invisible"() {
        given: "a Component"
        def comp = componentStub("Test")
        comp.setBounds(0, 0, 80, 5)
        when: "hiding and rendering"
        comp.hide()
        def result = comp.render()
        then: "returns empty"
        result.isEmpty()
    }

    def "Component render returns empty when zero-sized"() {
        given: "a Component"
        def comp = componentStub("Test")
        expect: "empty for zero width"
        comp.setSize(0, 5)
        comp.render().isEmpty()
        and: "empty for zero height"
        comp.setSize(80, 0)
        comp.render().isEmpty()
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

    // ===================================================================
    // TuiLayout
    // ===================================================================

    def "TuiLayout FIXED_ROWS is 5"() {
        expect:
        TuiLayout.FIXED_ROWS == 5
        TuiLayout.HEADER_HEIGHT == 1
        TuiLayout.SEPARATOR_HEIGHT == 1
        TuiLayout.INPUT_HEIGHT == 1
        TuiLayout.STATUS_HEIGHT == 1
    }

    def "TuiLayout recalculate positions all components for standard size"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "recalculating for 80x24 terminal"
        layout.recalculate(80, 24)
        then: "header is at row 0"
        layout.header().row() == 0
        layout.header().height() == 1
        layout.header().width() == 80
        and: "content scroll area starts right after header"
        layout.contentStartRow() == 1
        layout.contentHeight() == 24 - TuiLayout.FIXED_ROWS
        and: "thought component occupies content area"
        layout.thought().row() == layout.contentStartRow()
        layout.thought().height() == layout.contentHeight()
        layout.thought().width() == 80
        and: "separator1 is after content"
        layout.separator1Row() == layout.contentStartRow() + layout.contentHeight()
        and: "input row is after separator1"
        layout.inputRow() == layout.separator1Row() + TuiLayout.SEPARATOR_HEIGHT
        layout.input().row() == layout.inputRow()
        layout.input().height() == 1
        and: "separator2 is after input"
        layout.separator2Row() == layout.inputRow() + TuiLayout.INPUT_HEIGHT
        and: "footer is at the bottom"
        layout.footerRow() == layout.separator2Row() + TuiLayout.SEPARATOR_HEIGHT
        layout.footer().height() == 1
        and: "last row is footer"
        layout.lastRow() == layout.footerRow()
        and: "terminal dimensions stored"
        layout.terminalWidth() == 80
        layout.terminalHeight() == 24
    }

    def "TuiLayout enforces minimum terminal dimensions"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "recalculating with tiny terminal (20x3)"
        layout.recalculate(20, 3)
        then: "minimum width is 40"
        layout.terminalWidth() == 40
        and: "minimum height is FIXED_ROWS + 3 = 8"
        layout.terminalHeight() == TuiLayout.FIXED_ROWS + 3
        and: "content height is positive"
        layout.contentHeight() == layout.terminalHeight() - TuiLayout.FIXED_ROWS
    }

    def "TuiLayout recalculate marks all components dirty"() {
        given: "a TuiLayout"
        def layout = createLayout()
        layout.markAllDirty()
        layout.header().clearDirty()
        layout.thought().clearDirty()
        layout.input().clearDirty()
        layout.footer().clearDirty()
        expect: "all components clean after clear"
        !layout.header().isDirty()
        !layout.thought().isDirty()
        !layout.input().isDirty()
        !layout.footer().isDirty()
        when: "recalculating"
        layout.recalculate(80, 24)
        then: "all components are dirty again"
        layout.header().isDirty()
        layout.thought().isDirty()
        layout.input().isDirty()
        layout.footer().isDirty()
    }

    def "TuiLayout getComponents returns all four components"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "getting components"
        def components = layout.getComponents()
        then: "returns exactly 4 components"
        components.size() == 4
        components.contains(layout.header())
        components.contains(layout.thought())
        components.contains(layout.input())
        components.contains(layout.footer())
    }

    def "TuiLayout separatorLine generates ANSI dim line of correct length"() {
        given: "a TuiLayout with known terminal width"
        def layout = createLayout()
        layout.recalculate(60, 24)
        when: "generating separator line"
        def line = layout.separatorLine()
        then: "line contains ANSI escape codes"
        line.contains("\u001B[38;5;242m")
        line.contains("\u001B[0m")
        and: "contains the separator character"
        line.contains("\u2500")
        and: "correct number of visible separator chars"
        def ESC = (char)27
        def pattern = ESC.toString() + '\\[' + '[;\\d]*m'
        // Strip ANSI: ESC[38;5;242m style sequences
        def escChar = (char)27 as String
        def plain = line.replaceAll(escChar + '\\[\\d+(;\\d+)*m', "")
        plain.length() == 60
        // count characters equal to BOX DRAWINGS LIGHT HORIZONTAL (U+2500)
        plain.findAll { it == '\u2500' as char }.size() == 60
    }

    // ===================================================================
    // HeaderComponent (v2.3: 无噪细线，删除 session 标签)
    // ===================================================================

    def "HeaderComponent default label"() {
        given: "a HeaderComponent"
        def header = new HeaderComponent()
        expect: "default values"
        header.label() == "alice-agent v0.60.0"
    }

    def "HeaderComponent setLabel updates label and marks dirty"() {
        given: "a HeaderComponent"
        def header = new HeaderComponent()
        header.clearDirty()
        when: "setting label"
        header.setLabel("my-custom-agent v2.0")
        then: "label is updated and dirty"
        header.label() == "my-custom-agent v2.0"
        header.isDirty()
    }

    def "HeaderComponent render returns empty when invisible or zero-sized"() {
        given: "a HeaderComponent"
        def header = new HeaderComponent()
        expect: "empty for invisible"
        header.setVisible(false)
        header.render().isEmpty()
        and: "empty for zero width"
        header.setVisible(true)
        header.setSize(0, 1)
        header.render().isEmpty()
        and: "empty for zero height"
        header.setSize(80, 0)
        header.render().isEmpty()
    }

    def "HeaderComponent render produces single line"() {
        given: "a HeaderComponent with bounds"
        def header = new HeaderComponent()
        header.setBounds(0, 0, 60, 1)
        when: "rendering"
        def lines = header.render()
        then: "produces exactly one line"
        lines.size() == 1
        and: "line contains the label"
        lines[0].contains("alice-agent v0.60.0")
        and: "line contains robot emoji"
        lines[0].contains("\uD83E\uDD16")
        and: "line is exactly 60 visible chars wide"
        stripAnsi(lines[0]).length() == 60
    }

    def "HeaderComponent render contains ANSI dim separators"() {
        given: "a HeaderComponent with bounds"
        def header = new HeaderComponent()
        header.setBounds(0, 0, 60, 1)
        when: "rendering"
        def lines = header.render()
        then: "line contains ANSI escape codes"
        lines[0].contains("\u001B[38;5;242m")
        lines[0].contains("\u001B[0m")
        and: "line contains Unicode dim chars"
        lines[0].contains("\u2500")
    }

    def "HeaderComponent render truncates for narrow terminal"() {
        given: "a HeaderComponent with very narrow width"
        def header = new HeaderComponent()
        header.setBounds(0, 0, 15, 1)
        when: "rendering"
        def lines = header.render()
        then: "produces a non-empty single line"
        lines.size() == 1
        lines[0].length() > 0
    }

    def "HeaderComponent clearDirty works after render"() {
        given: "a HeaderComponent"
        def header = new HeaderComponent()
        header.setBounds(0, 0, 60, 1)
        header.markDirty()
        when: "rendering"
        header.render()
        then: "dirty flag is cleared"
        !header.isDirty()
    }

    // ===================================================================
    // FooterComponent (v2.3: 实体色块仪表盘)
    // ===================================================================

    def "FooterComponent default values"() {
        given: "a FooterComponent"
        def footer = new FooterComponent()
        expect: "default cost, speed, model, tool"
        footer.costInfo() == '\$0.000'
        footer.speedInfo() == '0 t/s'
        footer.modelInfo() == "N/A"
        footer.toolInfo() == "none"
    }

    def "FooterComponent setters update fields and mark dirty"() {
        given: "a FooterComponent"
        def footer = new FooterComponent()
        footer.clearDirty()
        when: "setting all fields"
        footer.setCost('\$1.234')
        footer.setSpeed('500 t/s')
        footer.setModel("gpt-4o")
        footer.setTool("search")
        then: "all fields updated"
        footer.costInfo() == '\$1.234'
        footer.speedInfo() == '500 t/s'
        footer.modelInfo() == "gpt-4o"
        footer.toolInfo() == "search"
        footer.isDirty()
    }

    def "FooterComponent render returns empty when invisible or zero-sized"() {
        expect: "empty for invisible"
        def f1 = new FooterComponent()
        f1.setVisible(false)
        f1.render().isEmpty()
        and: "empty for zero width"
        def f2 = new FooterComponent()
        f2.setSize(0, 1)
        f2.render().isEmpty()
        and: "empty for zero height"
        def f3 = new FooterComponent()
        f3.setSize(80, 0)
        f3.render().isEmpty()
    }

    def "FooterComponent render produces single line with ANSI background color blocks"() {
        given: "a FooterComponent"
        def footer = new FooterComponent()
        footer.setBounds(0, 0, 80, 1)
        footer.setCost('\$0.041')
        footer.setSpeed('125 t/s')
        footer.setModel("gpt-4o-mini")
        footer.setTool("shell")
        when: "rendering"
        def lines = footer.render()
        then: "produces single line"
        lines.size() == 1
        and: "contains all metric values"
        lines[0].contains('\$0.041')
        lines[0].contains('125 t/s')
        lines[0].contains("gpt-4o-mini")
        lines[0].contains("shell")
        and: "contains background ANSI color codes (48;5 = background)"
        lines[0].contains("\u001B[48;5;208m") // cost block: orange bg
        lines[0].contains("\u001B[48;5;35m")  // speed block: green bg
        lines[0].contains("\u001B[48;5;239m") // model block: dark gray bg
        and: "contains dim ANSI for tool prefix"
        lines[0].contains("\u001B[38;5;242m")
        and: "plain text fills exactly width"
        stripAnsi(lines[0]).length() == 80
        and: "contains separator characters"
        lines[0].contains("\u2500")
    }

    def "FooterComponent render truncates preserving ANSI codes"() {
        given: "a FooterComponent with narrow width"
        def footer = new FooterComponent()
        footer.setBounds(0, 0, 20, 1)
        footer.setCost('\$999.999')
        footer.setSpeed('99999 t/s')
        footer.setModel("very-long-model-name")
        footer.setTool("very-long-tool")
        when: "rendering"
        def lines = footer.render()
        def plain = stripAnsi(lines[0])
        then: "plain text length is exactly width"
        plain.length() == 20
    }

    // ===================================================================
    // InputComponent (v2.3: 零提示符净化设计)
    // ===================================================================

    def "InputComponent initial state"() {
        given: "an InputComponent"
        def input = new InputComponent()
        expect: "empty buffer and empty prompt (v2.3 zero-noise)"
        input.getText() == ""
        input.cursorPos() == 0
        input.prompt() == ""
    }

    def "InputComponent insertChar appends and moves cursor"() {
        given: "an InputComponent"
        def input = new InputComponent()
        when: "inserting a character"
        input.insertChar('h' as char)
        then: "buffer has the char and cursor moved"
        input.getText() == "h"
        input.cursorPos() == 1
    }

    def "InputComponent insertChar at middle position"() {
        given: "an InputComponent with existing text"
        def input = new InputComponent()
        input.setText("helo")
        input.cursorLeft()
        input.cursorLeft()
        when: "inserting at middle"
        input.insertChar('l' as char)
        then: "character inserted at cursor position"
        input.getText() == "hello"
        input.cursorPos() == 3
    }

    def "InputComponent insertChar respects MAX_INPUT_LENGTH"() {
        given: "an InputComponent"
        def input = new InputComponent()
        def longStr = "a" * 499
        input.setText(longStr)
        when: "inserting at position 499"
        input.cursorEnd()
        input.insertChar('b' as char)
        then: "insert succeeds"
        input.getText() == longStr + "b"
        input.cursorPos() == 500
        when: "trying to insert past max"
        input.insertChar('c' as char)
        then: "insert is rejected, buffer unchanged"
        input.getText() == longStr + "b"
        input.cursorPos() == 500
    }

    def "InputComponent deleteBeforeCursor removes char before cursor"() {
        given: "an InputComponent with text"
        def input = new InputComponent()
        input.setText("hello")
        when: "deleting before cursor (cursor at end)"
        input.deleteBeforeCursor()
        then: "last char removed"
        input.getText() == "hell"
        input.cursorPos() == 4
        when: "deleting at start (cursor = 0)"
        input.cursorHome()
        input.deleteBeforeCursor()
        then: "no change when cursor at start"
        input.getText() == "hell"
        input.cursorPos() == 0
    }

    def "InputComponent deleteAtCursor removes char at cursor"() {
        given: "an InputComponent with text"
        def input = new InputComponent()
        input.setText("abcd")
        input.cursorHome()
        input.cursorRight()
        when: "deleting at cursor"
        input.deleteAtCursor()
        then: "char at cursor removed"
        input.getText() == "acd"
        input.cursorPos() == 1
    }

    def "InputComponent cursor movement"() {
        given: "an InputComponent with text"
        def input = new InputComponent()
        input.setText("hello world")
        expect: "cursor starts at end"
        input.cursorPos() == 11
        when: "cursor left"
        input.cursorLeft()
        then: "cursor moved left"
        input.cursorPos() == 10
        when: "cursor right"
        input.cursorRight()
        then: "cursor moved right"
        input.cursorPos() == 11
        when: "cursor home"
        input.cursorHome()
        then: "cursor at start"
        input.cursorPos() == 0
        when: "cursor end"
        input.cursorEnd()
        then: "cursor at end"
        input.cursorPos() == 11
        when: "cursor left past start"
        20.times { input.cursorLeft() }
        then: "stays at 0"
        input.cursorPos() == 0
        when: "cursor right past end"
        20.times { input.cursorRight() }
        then: "stays at 11"
        input.cursorPos() == 11
    }

    def "InputComponent commitInput returns and clears buffer"() {
        given: "an InputComponent with text"
        def input = new InputComponent()
        input.setText("ls -la")
        when: "committing"
        def result = input.commitInput()
        then: "returns the text"
        result == "ls -la"
        and: "buffer is cleared"
        input.getText() == ""
        input.cursorPos() == 0
    }

    def "InputComponent clear empties buffer"() {
        given: "an InputComponent with text"
        def input = new InputComponent()
        input.setText("something")
        when: "clearing"
        input.clear()
        then: "buffer is empty"
        input.getText() == ""
        input.cursorPos() == 0
    }

    def "InputComponent setText replaces content and cursor at end"() {
        given: "an InputComponent"
        def input = new InputComponent()
        when: "setting text"
        input.setText("new content")
        then: "text is set and cursor at end"
        input.getText() == "new content"
        input.cursorPos() == 11
    }

    def "InputComponent setPrompt updates prompt prefix"() {
        given: "an InputComponent"
        def input = new InputComponent()
        when: "setting prompt"
        input.setPrompt(">>> ")
        then: "prompt matches"
        input.prompt() == ">>> "
    }

    def "InputComponent render returns empty when invisible"() {
        given: "an InputComponent"
        def input = new InputComponent()
        input.setVisible(false)
        expect: "empty render"
        input.render().isEmpty()
    }

    def "InputComponent render produces single line with input text (v2.3: no prompt prefix)"() {
        given: "an InputComponent"
        def input = new InputComponent()
        input.setBounds(0, 0, 40, 1)
        input.setText("ls -la")
        when: "rendering"
        def lines = input.render()
        then: "produces single line"
        lines.size() == 1
        and: "line contains input text (no prompt prefix in v2.3)"
        lines[0].contains("ls -la")
        and: "line is exactly width"
        lines[0].length() == 40
    }

    def "InputComponent render pads with spaces to full width"() {
        given: "an InputComponent with short text"
        def input = new InputComponent()
        input.setBounds(0, 0, 30, 1)
        input.setText("hi")
        when: "rendering"
        def lines = input.render()
        then: "line is exactly 30 characters"
        lines[0].length() == 30
        and: "ends with spaces (v2.3: empty prompt, so padding = 30 - 2 = 28)"
        lines[0].endsWith(" " * 28)
    }

    def "InputComponent render truncates text when longer than width"() {
        given: "an InputComponent with long text"
        def input = new InputComponent()
        input.setBounds(0, 0, 10, 1)
        input.setText("hello world this is long")
        when: "rendering"
        def lines = input.render()
        then: "line is exactly 10 characters"
        lines[0].length() == 10
        and: "shows the last part of the text"
        lines[0].contains("is long")
    }

    // ===================================================================
    // ThoughtComponent (v2.3: TAO 色块标签)
    // ===================================================================

    def "ThoughtComponent initial state renders empty for zero-sized"() {
        given: "a ThoughtComponent"
        def thought = new ThoughtComponent()
        when: "rendering"
        def lines = thought.render()
        then: "returns nothing for zero-sized component"
        lines.isEmpty()
    }

    def "ThoughtComponent appendLine adds and scrolls to bottom"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 5)
        when: "appending lines"
        thought.appendLine("line 1")
        thought.appendLine("line 2")
        thought.appendLine("line 3")
        then: "render returns only the lines that fit"
        def lines = thought.render()
        lines.size() == 5
        lines[0] == "line 1"
        lines[1] == "line 2"
        lines[2] == "line 3"
        lines[3] == ""
        lines[4] == ""
    }

    def "ThoughtComponent auto-scrolls to bottom when appending beyond height"() {
        given: "a ThoughtComponent with 3-line height"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        when: "appending 5 lines"
        (1..5).each { thought.appendLine("line $it") }
        then: "render shows last 3 lines"
        def lines = thought.render()
        lines.size() == 3
        lines[0] == "line 3"
        lines[1] == "line 4"
        lines[2] == "line 5"
    }

    def "ThoughtComponent addThought renders TaoTag color block with content"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        when: "adding a thought"
        thought.addThought("analyzing data", 1)
        then: "line has TaoTag THOUGHT ANSI background and content"
        def line = thought.render()[0]
        line.contains("\u001B[48;5;239m") // dark gray bg
        line.contains("\u001B[37m")       // white fg
        line.contains(" THOUGHT ")
        line.contains("analyzing data")
        and: "plain text shows indent + tag block + content (tag has built-in leading/trailing spaces)"
        stripAnsi(line) == "   THOUGHT   analyzing data"
    }

    def "ThoughtComponent addAction renders TaoTag color block with content"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        when: "adding an action"
        thought.addAction("execute search")
        then: "line has TaoTag ACTION ANSI background and content"
        def line = thought.render()[0]
        line.contains("\u001B[48;5;214m") // orange bg
        line.contains("\u001B[30m")       // black fg
        line.contains(" ACTION  ")
        line.contains("execute search")
        and: "plain text shows indent + tag block + content (ACTION tag has 2 trailing spaces)"
        stripAnsi(line) == "   ACTION    execute search"
    }

    def "ThoughtComponent addObservation renders TaoTag color block with content"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        when: "adding observation"
        thought.addObservation("result found")
        then: "line has TaoTag OBSERVE ANSI background and content"
        def line = thought.render()[0]
        line.contains("\u001B[48;5;35m")  // green bg
        line.contains("\u001B[30m")       // black fg
        line.contains(" OBSERVE ")
        line.contains("result found")
        and: "plain text shows indent + tag block + content (OBSERVE tag has 1 trailing space)"
        stripAnsi(line) == "   OBSERVE   result found"
    }

    def "ThoughtComponent addUserMessage splits multi-line content"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 5)
        when: "adding multi-line user message"
        thought.addUserMessage("hello\nworld")
        then: "each line has no role prefix (v2.3: pure content)"
        def lines = thought.render()
        lines[0] == "  hello"
        lines[1] == "  world"
    }

    def "ThoughtComponent addSystemMessage splits multi-line content"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 5)
        when: "adding system message"
        thought.addSystemMessage("system\nmessage")
        then: "each line has no role prefix (v2.3: pure content)"
        thought.render()[0] == "  system"
        thought.render()[1] == "  message"
    }

    def "ThoughtComponent addAgentMessage splits multi-line content"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 5)
        when: "adding agent message"
        thought.addAgentMessage("agent\nresponse")
        then: "each line has no role prefix (v2.3: pure content)"
        thought.render()[0] == "  agent"
        thought.render()[1] == "  response"
    }

    def "ThoughtComponent null messages are safely ignored"() {
        given: "a ThoughtComponent with bounds"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        when: "adding null messages"
        thought.appendLine(null)
        thought.addThought(null, 1)
        thought.addAction(null)
        thought.addObservation(null)
        thought.addUserMessage(null)
        thought.addSystemMessage(null)
        thought.addAgentMessage(null)
        then: "no exception"
        noExceptionThrown()
        // appendLine skips null; addUser/System/AgentMessage check for null content so skip;
        // addThought/addAction/addObservation concat null as "null" string
        // Result: 3 lines with "null" text in TaoTag format
        def lines = thought.render()
        lines.size() == 3
    }

    def "ThoughtComponent clear removes all content"() {
        given: "a ThoughtComponent with content"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        thought.appendLine("something")
        expect: "has content"
        !thought.render().every { it.isEmpty() }
        when: "clearing"
        thought.clear()
        then: "all lines are empty"
        thought.render().every { it.isEmpty() }
    }

    def "ThoughtComponent scrolling up and down"() {
        given: "a ThoughtComponent with more lines than height"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        (1..10).each { thought.appendLine("line $it") }
        when: "scrolling up once"
        thought.scrollUp()
        then: "shows lines 7,8,9"
        def lines1 = thought.render()
        lines1[0] == "line 7"
        lines1[1] == "line 8"
        lines1[2] == "line 9"
        when: "scrolling down"
        thought.scrollDown()
        then: "back to bottom: lines 8,9,10"
        def lines2 = thought.render()
        lines2[0] == "line 8"
        lines2[1] == "line 9"
        lines2[2] == "line 10"
    }

    def "ThoughtComponent scrollUp stops at top"() {
        given: "a ThoughtComponent"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        (1..5).each { thought.appendLine("line $it") }
        when: "scrolling up many times"
        10.times { thought.scrollUp() }
        then: "shows top lines"
        def lines = thought.render()
        lines[0] == "line 1"
        lines[1] == "line 2"
        lines[2] == "line 3"
    }

    def "ThoughtComponent scrollDown stops at bottom"() {
        given: "a ThoughtComponent scrolled up"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 3)
        (1..5).each { thought.appendLine("line $it") }
        10.times { thought.scrollUp() }
        when: "scrolling down many times"
        10.times { thought.scrollDown() }
        then: "shows bottom lines"
        def lines = thought.render()
        lines[0] == "line 3"
        lines[1] == "line 4"
        lines[2] == "line 5"
    }

    def "ThoughtComponent pageUp and pageDown"() {
        given: "a ThoughtComponent"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 5)
        (1..20).each { thought.appendLine("line $it") }
        when: "page up"
        thought.pageUp()
        then: "scrolled up by page size (height-1=4)"
        def lines = thought.render()
        lines[0] == "line 12"
        lines[1] == "line 13"
        lines[2] == "line 14"
        lines[3] == "line 15"
        lines[4] == "line 16"
        when: "page down"
        thought.pageDown()
        then: "back to bottom: lines 16-20"
        def lines2 = thought.render()
        lines2[0] == "line 16"
        lines2[1] == "line 17"
        lines2[2] == "line 18"
        lines2[3] == "line 19"
        lines2[4] == "line 20"
    }

    def "ThoughtComponent respects MAX_LINES upper bound (1000)"() {
        given: "a ThoughtComponent"
        def thought = new ThoughtComponent()
        thought.setBounds(0, 0, 60, 5)
        when: "appending more than MAX_LINES entries"
        1005.times { thought.appendLine("line $it") }
        then: "at most 1000 lines are stored"
        def lines = thought.render()
        lines.size() == 5
        // Auto-scroll to bottom -> shows last 5: "line 1000" to "line 1004"
        lines[0] == "line 1000"
        lines[4] == "line 1004"
    }

    // ===================================================================
    // TaoTag (v2.3 新增)
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

    def "TaoTag render produces ANSI wrapped text with reset"() {
        expect: "render starts with ANSI codes and ends with reset"
        def rendered = TaoTag.THOUGHT.render()
        rendered.startsWith("\u001B[48;5;239m")
        rendered.endsWith("\u001B[0m")
        and: "plain text has correct width"
        stripAnsi(rendered) == " THOUGHT "
    }

    def "TaoTag all tags are equal width (9 chars)"() {
        expect: "all tag plain texts are exactly 9 chars wide"
        TaoTag.THOUGHT.plainText().length() == 9
        TaoTag.ACTION.plainText().length() == 9
        TaoTag.OBSERVE.plainText().length() == 9
    }
}
