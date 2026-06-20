package org.cland.alice.tool.gateway.builtin

import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * BuiltinTools 单元测试。
 *
 * <p>测试覆盖所有 9 个内置工具的参数校验、正向逻辑和异常场景。
 * web_search 的网络相关测试默认跳过（@IgnoreIf），
 * 由 hole test（e2e）在可联网环境中执行真实网络调用。
 */
class BuiltinToolsSpec extends Specification {

    @TempDir
    Path tempDir

    def builtin = new BuiltinTools()

    def "grep should find matching lines in a file"() {
        given:
        def file = tempDir.resolve("test.txt")
        Files.writeString(file, "line one\nline two\nline three\n")

        when:
        def result = builtin.grep("two", file.toString())

        then:
        result.contains("2: line two")
        result.startsWith("Found 1 match")
    }

    def "grep should return no match message when pattern not found"() {
        given:
        def file = tempDir.resolve("empty.txt")
        Files.writeString(file, "hello world\n")

        when:
        def result = builtin.grep("xyz", file.toString())

        then:
        result.startsWith("No matches found")
    }

    def "grep should handle multiple matches"() {
        given:
        def file = tempDir.resolve("multi.txt")
        Files.writeString(file, "apple\nbanana\napple pie\ncherry\n")

        when:
        def result = builtin.grep("apple", file.toString())

        then:
        result.contains("1: apple")
        result.contains("3: apple pie")
        result.startsWith("Found 2 match")
    }

    def "grep should throw when file not found"() {
        when:
        builtin.grep("foo", tempDir.resolve("nonexistent.txt").toString())

        then:
        def e = thrown(IOException)
        e.message.contains("file not found")
    }

    def "grep should throw when path is blank"() {
        when:
        builtin.grep("foo", "")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    def "grep should throw when pattern is blank"() {
        given:
        def file = tempDir.resolve("dummy.txt")
        Files.writeString(file, "content")

        when:
        builtin.grep("", file.toString())

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("pattern is required")
    }

    // ==================================================================
    // run — shell command execution
    // ==================================================================

    def "run should execute a simple echo command"() {
        given:
        def osName = System.getProperty("os.name").toLowerCase()
        def cmd = osName.contains("win") ? "echo hello_from_shell" : "echo hello_from_shell"

        when:
        def result = builtin.run(cmd)

        then:
        result != null
        result.contains("hello_from_shell")
    }

    def "run should throw when command is blank"() {
        when:
        builtin.run("")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("command is required")
    }

    def "run should throw when command is null"() {
        when:
        builtin.run(null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("command is required")
    }

    // ==================================================================
    // read_file
    // ==================================================================

    def "read_file should return content of an existing file"() {
        given:
        def file = tempDir.resolve("hello.txt")
        Files.writeString(file, "Hello, World!")

        when:
        def result = builtin.readFile(file.toString())

        then:
        result == "Hello, World!"
    }

    def "read_file should throw when file not found"() {
        when:
        builtin.readFile(tempDir.resolve("nope.txt").toString())

        then:
        def e = thrown(IOException)
        e.message.contains("file not found")
    }

    def "read_file should throw when path is blank"() {
        when:
        builtin.readFile("")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    def "read_file should throw when path is null"() {
        when:
        builtin.readFile(null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    // ==================================================================
    // write_file
    // ==================================================================

    def "write_file should create file with content"() {
        given:
        def file = tempDir.resolve("output.txt")

        when:
        def result = builtin.writeFile(file.toString(), "written content")

        then:
        Files.readString(file) == "written content"
        result.contains("Wrote")
        result.contains("output.txt")
    }

    def "write_file should create parent directories"() {
        given:
        def file = tempDir.resolve("sub/deep/nested/output.txt")

        when:
        builtin.writeFile(file.toString(), "nested content")

        then:
        Files.exists(file)
        Files.readString(file) == "nested content"
    }

    def "write_file should throw when path is blank"() {
        when:
        builtin.writeFile("", "content")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    def "write_file should handle null content as empty string"() {
        given:
        def file = tempDir.resolve("null_content.txt")

        when:
        def result = builtin.writeFile(file.toString(), null)

        then:
        Files.readString(file) == ""
        result.contains("0 bytes")
    }

    // ==================================================================
    // list_dir
    // ==================================================================

    def "list_dir should list files and directories"() {
        given:
        Files.writeString(tempDir.resolve("a.txt"), "a")
        Files.writeString(tempDir.resolve("b.txt"), "b")
        Files.createDirectory(tempDir.resolve("sub"))

        when:
        def result = builtin.listDir(tempDir.toString())

        then:
        result.contains("a.txt")
        result.contains("b.txt")
        result.contains("sub/")
    }

    def "list_dir should return marker for empty directory"() {
        given:
        Files.createDirectory(tempDir.resolve("empty"))

        when:
        def result = builtin.listDir(tempDir.resolve("empty").toString())

        then:
        result == "[empty directory]"
    }

    def "list_dir should throw when directory not found"() {
        when:
        builtin.listDir(tempDir.resolve("nonexistent").toString())

        then:
        def e = thrown(IOException)
        e.message.contains("directory not found")
    }

    def "list_dir should throw when path is blank"() {
        when:
        builtin.listDir("")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    // ==================================================================
    // file_exists
    // ==================================================================

    def "file_exists should return true for existing file"() {
        given:
        def file = tempDir.resolve("exists.txt")
        Files.writeString(file, "hello")

        expect:
        builtin.fileExists(file.toString()) == "true"
    }

    def "file_exists should return false for non-existing file"() {
        expect:
        builtin.fileExists(tempDir.resolve("nothere.txt").toString()) == "false"
    }

    def "file_exists should return true for existing directory"() {
        given:
        Files.createDirectory(tempDir.resolve("mydir"))

        expect:
        builtin.fileExists(tempDir.resolve("mydir").toString()) == "true"
    }

    def "file_exists should throw when path is blank"() {
        when:
        builtin.fileExists("")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    // ==================================================================
    // search_file
    // ==================================================================

    def "search_file should find files matching glob pattern"() {
        given:
        Files.writeString(tempDir.resolve("Foo.java"), "class Foo {}")
        Files.writeString(tempDir.resolve("Bar.java"), "class Bar {}")
        Files.writeString(tempDir.resolve("README.md"), "# readme")
        Files.createDirectory(tempDir.resolve("src"))
        Files.writeString(tempDir.resolve("src/Main.java"), "class Main {}")

        when:
        def result = builtin.searchFile(tempDir.toString(), "*.java", "10")

        then:
        result.contains("Foo.java")
        result.contains("Bar.java")
        result.contains("src/Main.java")
        result.startsWith("Found 3 file")
    }

    def "search_file should return no match message when nothing matches"() {
        given:
        Files.writeString(tempDir.resolve("readme.md"), "content")

        when:
        def result = builtin.searchFile(tempDir.toString(), "*.java", "5")

        then:
        result.startsWith("No files matching")
    }

    def "search_file should throw when directory not found"() {
        when:
        builtin.searchFile(tempDir.resolve("nowhere").toString(), "*.java", "5")

        then:
        def e = thrown(IOException)
        e.message.contains("directory not found")
    }

    def "search_file should throw when path is blank"() {
        when:
        builtin.searchFile("", "*.java", "5")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    // ==================================================================
    // remove_file
    // ==================================================================

    def "remove_file should delete an existing file"() {
        given:
        def file = tempDir.resolve("todelete.txt")
        Files.writeString(file, "will be deleted")

        when:
        def result = builtin.removeFile(file.toString())

        then:
        !Files.exists(file)
        result.contains("Deleted:")
        result.contains("todelete.txt")
    }

    def "remove_file should be idempotent for missing file"() {
        when:
        def result = builtin.removeFile(tempDir.resolve("missing.txt").toString())

        then:
        result.startsWith("File not found")
    }

    def "remove_file should throw when path is blank"() {
        when:
        builtin.removeFile("")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("path is required")
    }

    def "remove_file should refuse to delete a directory"() {
        given:
        Files.createDirectory(tempDir.resolve("mydir"))

        when:
        builtin.removeFile(tempDir.resolve("mydir").toString())

        then:
        def e = thrown(IOException)
        e.message.contains("refusing to delete directory")
    }

    // ==================================================================
    // web_search
    // ==================================================================

    def "web_search should throw when query is blank"() {
        when:
        builtin.webSearch("", "5")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("query is required")
    }

    def "web_search should throw when query is null"() {
        when:
        builtin.webSearch(null, "5")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("query is required")
    }

    def "web_search should throw when maxResults is not a number"() {
        when:
        builtin.webSearch("test", "abc")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("maxResults must be an integer")
    }

    @IgnoreIf({ true })
    def "web_search should clamp maxResults to 1-10"() {
        when:
        def result = builtin.webSearch("test", "999")

        then:
        // 真实网络调用——跳过
        noExceptionThrown()
        result != null
    }

    @IgnoreIf({ true })
    def "web_search integration should return results from DuckDuckGo"() {
        given:
        // 取消 @IgnoreIf 后手动运行
        def result = builtin.webSearch("java programming language", "3")

        expect:
        result != null
    }
}
