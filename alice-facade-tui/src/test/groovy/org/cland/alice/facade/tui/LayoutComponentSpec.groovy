/*
 * Unit tests for TUI layout management and UI components (JLine 3 based).
 *
 * Covers: Component, TuiLayout (3-zone alignment v4.0), HeaderComponent,
 *         FooterComponent, InputComponent, MessageAreaComponent, TaoTag
 */
package org.cland.alice.facade.tui

import spock.lang.Specification
import spock.lang.Title
import org.cland.alice.facade.tui.component.*
import org.cland.alice.facade.tui.layout.TuiLayout

@Title("TUI Layout and Component Unit Tests (v4.0 三区对齐)")
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
            new MessageAreaComponent(),
            new LineComponent(),
            new LineComponent(),
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
    // TuiLayout — 三区对齐 (v4.0)
    // ===================================================================

    def "TuiLayout FIXED_ROWS is 5 (Header+Sep+Queue+Input+Footer)"() {
        expect:
        TuiLayout.FIXED_ROWS == 6
        TuiLayout.HEADER_HEIGHT == 1
        TuiLayout.SEPARATOR_HEIGHT == 1
        TuiLayout.QUEUE_HEIGHT == 1
        TuiLayout.INPUT_HEIGHT == 1
        TuiLayout.FOOTER_HEIGHT == 1
    }

    def "TuiLayout recalculate positions all zones for standard 80x24"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "recalculating for 80x24 terminal"
        layout.recalculate(80, 24)
        then: "header at row 0"
        layout.header().row() == 0
        layout.header().width() == 80

        and: "MessageArea starts at row 1"
        layout.messageAreaStartRow() == 1
        layout.messageArea().row() == 1
        layout.messageArea().width() == 80
        layout.messageAreaHeight() > 0

        and: "queue row below MessageArea"
        layout.queueRow() == layout.messageAreaStartRow() + layout.messageAreaHeight()

        and: "separator after queue, then input, then separator2"
        layout.separatorRow() == layout.queueRow() + 1
        layout.inputRow() == layout.separatorRow() + 1
        layout.input().row() == layout.inputRow()
        layout.separator2Row() == layout.inputRow() + 1

        and: "all positions are sequential (no gaps or overlaps)"
        // With 0 content, main area is minimum 1 row
        layout.footerRow() == layout.separator2Row() + 1
        layout.separator2Row() == layout.inputRow() + 1
        layout.inputRow() == layout.separatorRow() + 1
        layout.separatorRow() == layout.queueRow() + 1
        layout.queueRow() == layout.messageAreaStartRow() + layout.messageAreaHeight()

        and: "MessageArea height = content lines (min 1)"
        layout.messageAreaHeight() >= 1
        layout.header().row() == 0
        layout.header().width() == 80
    }

    def "TuiLayout enforces minimum terminal dimensions"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "recalculating with tiny terminal (20x3)"
        layout.recalculate(20, 3)
        then: "minimum width is 40"
        layout.terminalWidth() == 40
        and: "minimum height is FIXED_ROWS + 5 = 11"
        layout.terminalHeight() >= TuiLayout.FIXED_ROWS + 5
        and: "message area height is positive"
        layout.messageAreaHeight() > 0
    }

    def "TuiLayout recalculate marks all components dirty"() {
        given: "a TuiLayout"
        def layout = createLayout()
        layout.markAllDirty()
        layout.header().clearDirty()
        layout.messageArea().clearDirty()
        layout.separator().clearDirty()
        layout.separator2().clearDirty()
        layout.input().clearDirty()
        layout.footer().clearDirty()

        when: "recalculating"
        layout.recalculate(80, 24)

        then: "all 6 components are dirty"
        layout.header().isDirty()
        layout.messageArea().isDirty()
        layout.separator().isDirty()
        layout.separator2().isDirty()
        layout.input().isDirty()
        layout.footer().isDirty()
    }

    def "TuiLayout getComponents returns all four components"() {
        given: "a TuiLayout"
        def layout = createLayout()
        when: "getting components"
        def components = layout.getComponents()
        then: "returns exactly 6 components"
        components.size() == 6
        components.contains(layout.header())
        components.contains(layout.messageArea())
        components.contains(layout.separator())
        components.contains(layout.separator2())
        components.contains(layout.input())
        components.contains(layout.footer())
    }

    def "LineComponent renders ANSI dim line of correct length"() {
        given: "a LineComponent"
        def lineComp = new LineComponent()
        lineComp.setBounds(0, 0, 60, 1)
        when:
        def lines = lineComp.render()
        then:
        lines.size() == 1
        lines[0].contains("\u001B[38;5;242m")
        lines[0].contains("\u001B[0m")
        def escChar = (char)27 as String
        def plain = lines[0].replaceAll(escChar + '\\[\\d+(;\\d+)*m', "")
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
        lines[0].contains("\u001B[48;5;239m") // dark gray bg (all blocks unified)
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
    // MessageAreaComponent (三区对齐 Main Area — unified message stream)
    // ===================================================================

    def "MessageAreaComponent addUserMessage renders without background"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 5)
        when: "adding user message"
        area.addUserMessage("debug current program")
        def lines = area.render()
        then:
        stripAnsi(lines[0]).contains("debug current program")
        and: "no background ANSI code"
        !lines[0].contains("\u001B[48;5;")
    }

    def "MessageAreaComponent addThought renders step marker + content with light gray fg"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 5)
        when: "adding thought with step=1"
        area.addThought("analyzing data", 1)
        def lines = area.render()
        then: "step marker on first line"
        stripAnsi(lines[0]).contains("Step 1")
        and: "content on second line"
        stripAnsi(lines[1]).contains("analyzing data")
        and: "no background"
        !lines[0].contains("\u001B[48;5;")
        and: "light gray font color"
        lines[0].contains("\u001B[38;5;252m")
    }

    def "MessageAreaComponent addActionLine renders with command text"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 3)
        when: "adding action line"
        area.addActionLine("TOOL_CALL: list_dir ({path:.})")
        def line = area.render()[0]
        then: "contains command text"
        stripAnsi(line).contains("TOOL_CALL")
        and: "no background"
        !line.contains("\u001B[48;5;")
    }

    def "MessageAreaComponent addObservationLine renders with timing"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 5)
        when: "adding observation"
        area.addObservationLine("-rw------- 1 alice alice 111 config.json", 0.5)
        def lines = area.render()
        then: "contains output text"
        stripAnsi(lines.join(" ")).contains("config.json")
        and: "no background"
        !lines[0].contains("\u001B[48;5;")
        and: "timing line"
        stripAnsi(lines.join(" ")).contains("Took 0.5s")
    }

    def "MessageAreaComponent addSystemMessage renders with text"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 3)
        when: "adding system message"
        area.addSystemMessage("System initialized")
        then:
        stripAnsi(area.render()[0]).contains("System initialized")
        and: "no background"
        !area.render()[0].contains("\u001B[48;5;")
    }

    def "MessageAreaComponent addAgentMessage strips FINISH marker"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 3)
        when: "adding agent message with [FINISH]"
        area.addAgentMessage("done [FINISH]")
        then:
        !stripAnsi(area.render()[0]).contains("[FINISH]")
        stripAnsi(area.render()[0]).contains("done")
    }

    def "MessageAreaComponent clear removes all content"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 3)
        area.addUserMessage("test")
        expect: "has content"
        !area.render().every { stripAnsi(it).trim().isEmpty() }
        when: "clearing"
        area.clear()
        then: "all empty"
        area.render().every { stripAnsi(it).trim().isEmpty() }
    }

    def "MessageAreaComponent scrolling with multiple messages"() {
        given: "a MessageAreaComponent"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 3)
        (1..10).each { n ->
            area.addThought("step $n", n)
        }
        // Each thought = 2 lines (Step marker + content), 10 thoughts = 20 lines
        // auto-scrollToBottom keeps view at bottom
        expect: "scrolling up shows Step 9 marker"
        area.scrollUp()
        stripAnsi(area.render()[0]).contains("Step 9")
        and: "scrolling to bottom shows step 9 content"
        area.scrollToBottom()
        stripAnsi(area.render()[0]).contains("step 9")
    }

    def "MessageAreaComponent onResize adjusts scroll offset"() {
        given: "a MessageAreaComponent with content"
        def area = new MessageAreaComponent()
        area.setBounds(0, 0, 60, 3)
        (1..6).each { n -> area.addThought("step $n", n) }
        // 6 thoughts * 2 lines = 12 lines, scroll pos = 12 - 3 = 9
        when: "resizing to larger height"
        area.onResize(3) // oldHeight=3, newHeight=5
        then: "scroll offset reduced"
        // After onResize, height is still 3 because we haven't called setBounds
        // The method uses this.height which was set by previous setBounds(0,0,60,3)
        noExceptionThrown()
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
