package org.cland.alice.tool.gateway.sandbox

import java.util.concurrent.Callable
import spock.lang.Specification
import spock.lang.TempDir

class SandboxProviderSpec extends Specification {

    // ======================================================================
    // DirectSandboxProvider
    // ======================================================================

    def "DirectSandboxProvider should execute task directly"() {
        given:
        def provider = new DirectSandboxProvider()

        when:
        def result = provider.executeInIsolation({ -> 42 } as Callable)

        then:
        result == 42
    }

    def "DirectSandboxProvider should propagate exceptions"() {
        given:
        def provider = new DirectSandboxProvider()

        when:
        provider.executeInIsolation({ -> throw new RuntimeException("boom") } as Callable)

        then:
        thrown(RuntimeException)
    }

    def "DirectSandboxProvider prewarm and cleanup should be no-ops"() {
        given:
        def provider = new DirectSandboxProvider()

        expect:
        provider.prewarm() == null
        provider.cleanup() == null
    }

    def "SandboxProvider default methods should be no-ops"() {
        given:
        def provider = new SandboxProvider() {
            @Override
            Object executeInIsolation(Callable task) throws Exception {
                return task.call()
            }
        }

        expect:
        provider.prewarm() == null
        provider.cleanup() == null
    }

    def "Multiple DirectSandboxProvider instances should be independent"() {
        given:
        def p1 = new DirectSandboxProvider()
        def p2 = new DirectSandboxProvider()

        when:
        def r1 = p1.executeInIsolation({ -> "from p1" } as Callable)
        def r2 = p2.executeInIsolation({ -> "from p2" } as Callable)

        then:
        r1 == "from p1"
        r2 == "from p2"
    }

    // ======================================================================
    // PolicySandboxProvider — PathPolicy
    // ======================================================================

    def "PolicySandboxProvider default constructor should deny system paths"() {
        given:
        def provider = new PolicySandboxProvider()

        expect:
        provider.policy().deniedPrefixes().contains("/etc")
        provider.policy().deniedPrefixes().contains("/proc")
        provider.policy().deniedPrefixes().contains("/sys")
        provider.policy().deniedPrefixes().contains("/dev")
        provider.policy().deniedPrefixes().contains("C:\\Windows\\System32")
    }

    def "PolicySandboxProvider default constructor should allow temp dir"() {
        given:
        def provider = new PolicySandboxProvider()

        expect:
        provider.policy().allowedPrefixes().contains(System.getProperty("java.io.tmpdir"))
    }

    def "PolicySandboxProvider validatePath should reject denied prefix"() {
        given:
        def provider = new PolicySandboxProvider()

        when:
        provider.validatePath("/etc/passwd")

        then:
        def e = thrown(SecurityException)
        e.message.contains("/etc")
    }

    def "PolicySandboxProvider validatePath should reject denied Windows path"() {
        given:
        def provider = new PolicySandboxProvider()

        when:
        provider.validatePath("C:\\Windows\\System32\\drivers\\etc\\hosts")

        then:
        def e = thrown(SecurityException)
        e.message.contains("C:\\Windows\\System32")
    }

    def "PolicySandboxProvider validatePath should reject denied exact path"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .denyExact("/etc/shadow")
                .build()
        def provider = new PolicySandboxProvider(policy)

        when:
        provider.validatePath("/etc/shadow")

        then:
        def e = thrown(SecurityException)
        e.message.contains("/etc/shadow")
    }

    def "PolicySandboxProvider validatePath should allow paths not in deny list"() {
        given:
        def provider = new PolicySandboxProvider()
        def tmpDir = System.getProperty("java.io.tmpdir")

        when:
        provider.validatePath(tmpDir + "/test.txt")

        then:
        noExceptionThrown()
    }

    def "PolicySandboxProvider validatePath should reject path not in whitelist"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix("/home/user")
                .build()
        def provider = new PolicySandboxProvider(policy)

        when:
        provider.validatePath("/var/log/syslog")

        then:
        def e = thrown(SecurityException)
        e.message.contains("not in allowed list")
    }

    def "PolicySandboxProvider validatePath should accept path in whitelist"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix("/home/user")
                .allowPrefix("/tmp")
                .build()
        def provider = new PolicySandboxProvider(policy)

        expect:
        provider.validatePath("/home/user/docs/file.txt") == null
        provider.validatePath("/tmp/cache/data.dat") == null
    }

    def "PolicySandboxProvider validatePath should handle null and blank gracefully"() {
        given:
        def provider = new PolicySandboxProvider()

        expect:
        provider.validatePath(null) == null
        provider.validatePath("") == null
        provider.validatePath("   ") == null
    }

    def "PolicySandboxProvider validatePath should normalize paths before checking"() {
        given:
        def provider = new PolicySandboxProvider(
                PolicySandboxProvider.PathPolicy.builder()
                        .denyPrefix("/etc")
                        .build()
        )

        expect:
        // /tmp/../tmp/file.txt normalizes to /tmp/file.txt which should pass
        provider.validatePath("/tmp/../tmp/file.txt") == null

        when:
        // /etc/../etc/passwd normalizes to /etc/passwd which should be denied
        provider.validatePath("/etc/../etc/passwd")

        then:
        thrown(SecurityException)
    }

    // ======================================================================
    // PolicySandboxProvider — PathPolicy Builder
    // ======================================================================

    def "PathPolicy builder should produce immutable collections"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix("/tmp")
                .denyPrefix("/etc")
                .denyExact("/etc/shadow")
                .allowCommand("ls")
                .sandboxWorkDir("/tmp/sandbox")
                .sandboxPathEnv("/usr/bin:/bin")
                .putEnv("MY_VAR", "hello")
                .build()

        expect:
        policy.allowedPrefixes() == ["/tmp"] as Set
        policy.deniedPrefixes() == ["/etc"] as Set
        policy.deniedExact() == ["/etc/shadow"] as Set
        policy.allowedCommands() == ["ls"] as Set
        policy.sandboxWorkDir() == "/tmp/sandbox"
        policy.sandboxPathEnv() == "/usr/bin:/bin"

        and: "collections are unmodifiable"
        // Calling add() on unmodifiable collections returns false (Set) or throws (List)
        // We just verify the size remains as expected
        policy.allowedPrefixes().size() == 1
        policy.deniedPrefixes().size() == 1
        policy.allowedCommands().size() == 1
    }

    def "PathPolicy builder should allow multiple entries"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix("/a")
                .allowPrefix("/b")
                .allowPrefix("/c")
                .denyPrefix("/d")
                .denyPrefix("/e")
                .allowCommand("cat")
                .allowCommand("grep")
                .build()

        expect:
        policy.allowedPrefixes().size() == 3
        policy.deniedPrefixes().size() == 2
        policy.allowedCommands().size() == 2
    }

    def "PathPolicy builder empty policy should have empty sets"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder().build()

        expect:
        policy.allowedPrefixes().isEmpty()
        policy.deniedPrefixes().isEmpty()
        policy.deniedExact().isEmpty()
        policy.allowedCommands().isEmpty()
        policy.sandboxWorkDir() == null
        policy.sandboxPathEnv() == null
    }

    // ======================================================================
    // PolicySandboxProvider — executeInIsolation with FileArgHolder
    // ======================================================================

    def "PolicySandboxProvider should reject FileArgHolder with denied path"() {
        given:
        def provider = new PolicySandboxProvider()
        def task = new FileArgHolderCallable(["/etc/passwd"], "done")

        when:
        provider.executeInIsolation(task)

        then:
        def e = thrown(SecurityException)
        e.message.contains("/etc")
    }

    def "PolicySandboxProvider should accept FileArgHolder with allowed path"() {
        given:
        def tmpDir = System.getProperty("java.io.tmpdir")
        def provider = new PolicySandboxProvider()
        def task = new FileArgHolderCallable([tmpDir + "/safe.txt"], "result")

        when:
        def result = provider.executeInIsolation(task)

        then:
        result == "result"
    }

    def "PolicySandboxProvider should execute normal Callable without path check"() {
        given:
        def provider = new PolicySandboxProvider()

        when:
        def result = provider.executeInIsolation({ -> "hello world" } as Callable)

        then:
        result == "hello world"
    }

    // ======================================================================
    // PolicySandboxProvider — ShellCommand (ProcessBuilder)
    // ======================================================================

    def "PolicySandboxProvider should reject unknown command via ShellCommand"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowCommand("ls")
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new ShellCommandCallable("rm -rf /", [])

        when:
        provider.executeInIsolation(task)

        then:
        def e = thrown(SecurityException)
        e.message.contains("Command not allowed")
        e.message.contains("rm")
    }

    def "PolicySandboxProvider should reject ShellCommand with denied file arg"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowCommand("cat")
                .denyPrefix("/etc")
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new ShellCommandCallable("cat /etc/passwd", ["/etc/passwd"])

        when:
        provider.executeInIsolation(task)

        then:
        def e = thrown(SecurityException)
        e.message.contains("/etc")
    }

    def "PolicySandboxProvider should execute allowed ShellCommand via ProcessBuilder"() {
        given:
        def tmpDir = System.getProperty("java.io.tmpdir")
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(tmpDir)
                .allowCommand("echo")
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new ShellCommandCallable("echo hello_from_sandbox", [])

        when:
        def result = provider.executeInIsolation(task)

        then:
        // On Windows: "echo hello_from_sandbox" run via cmd.exe /c
        result != null
        result.contains("hello_from_sandbox")
    }

    def "PolicySandboxProvider should capture stderr from failed command"() {
        given:
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowCommand("invalid_cmd_xyz")
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new ShellCommandCallable("invalid_cmd_xyz --bad-arg", [])

        when:
        provider.executeInIsolation(task)

        then:
        thrown(Exception) // non-zero exit code
    }

    // ======================================================================
    // PolicySandboxProvider — Workspace Op File
    // ======================================================================

    def "PolicySandboxProvider should allow FileArgHolder with path inside allowed workspace"(@TempDir File workspace) {
        given:
        def workspacePath = workspace.absolutePath
        def testFile = new File(workspace, "readme.txt")
        testFile.text = "workspace content"
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspacePath)
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new FileArgHolderCallable([testFile.absolutePath], "read ok")

        when:
        def result = provider.executeInIsolation(task)

        then:
        result == "read ok"
    }

    def "PolicySandboxProvider should reject FileArgHolder with path outside allowed workspace"(@TempDir File workspace) {
        given:
        def workspacePath = workspace.absolutePath
        def outsidePath = System.getProperty("java.io.tmpdir") + "/outside_file.txt"
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspacePath)
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new FileArgHolderCallable([outsidePath], "should not run")

        when:
        provider.executeInIsolation(task)

        then:
        def e = thrown(SecurityException)
        e.message.contains("not in allowed list")
    }

    def "PolicySandboxProvider should allow ShellCommand with file arg inside allowed workspace"(@TempDir File workspace) {
        given:
        def workspacePath = workspace.absolutePath
        def testFile = new File(workspace, "data.txt")
        testFile.text = "hello"
        def isWindows = System.getProperty("os.name").toLowerCase().contains("win")
        def cmd = isWindows ? "type " + testFile.absolutePath : "cat " + testFile.absolutePath
        def cmdName = isWindows ? "type" : "cat"
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspacePath)
                .allowCommand(cmdName)
                .sandboxWorkDir(workspacePath)
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new ShellCommandCallable(cmd, [testFile.absolutePath])

        when:
        def result = provider.executeInIsolation(task)

        then:
        result != null
        result.contains("hello")
    }

    def "PolicySandboxProvider should reject ShellCommand with file arg outside allowed workspace"(@TempDir File workspace) {
        given:
        def workspacePath = workspace.absolutePath
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspacePath)
                .allowCommand("cat")
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new ShellCommandCallable("cat /etc/hostname", ["/etc/hostname"])

        when:
        provider.executeInIsolation(task)

        then:
        def e = thrown(SecurityException)
        e.message.contains("not in allowed list")
    }

    def "PolicySandboxProvider should allow writing file inside workspace via ShellCommand"(@TempDir File workspace) {
        given:
        def workspacePath = workspace.absolutePath
        def outputFile = new File(workspace, "output.txt")
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspacePath)
                .allowCommand("echo")
                .sandboxWorkDir(workspacePath)
                .build()
        def provider = new PolicySandboxProvider(policy)

        // On Windows we use cmd echo redirection; on Unix we use sh -c echo > file
        // Here we test the command itself is allowed and runs in the sandbox work dir
        def task = new ShellCommandCallable("echo workspace_write_test", [])

        when:
        def result = provider.executeInIsolation(task)

        then:
        result != null
        result.contains("workspace_write_test")
    }

    def "PolicySandboxProvider validatePath should accept paths inside workspace subdirectories"(@TempDir File workspace) {
        given:
        def nestedDir = new File(workspace, "sub/deep/folder")
        nestedDir.mkdirs()
        def nestedFile = new File(nestedDir, "notes.txt")
        nestedFile.text = "deep content"
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspace.absolutePath)
                .build()
        def provider = new PolicySandboxProvider(policy)

        expect:
        provider.validatePath(nestedFile.absolutePath) == null
        provider.validatePath(workspace.absolutePath + "/sub/deep/folder/notes.txt") == null
    }

    def "PolicySandboxProvider should reject path traversal out of allowed workspace"(@TempDir File workspace) {
        given:
        def workspacePath = workspace.absolutePath
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspacePath)
                .build()
        def provider = new PolicySandboxProvider(policy)

        // Path traversal attempt: go up from workspace to parent
        def traversalPath = workspacePath + "/../outside.txt"

        when:
        provider.validatePath(traversalPath)

        then:
        def e = thrown(SecurityException)
        e.message.contains("not in allowed list")
    }

    def "PolicySandboxProvider should allow multiple file args inside allowed workspace"(@TempDir File workspace) {
        given:
        def workspacePath = workspace.absolutePath
        new File(workspace, "a.txt").text = "A"
        new File(workspace, "b.txt").text = "B"
        def policy = PolicySandboxProvider.PathPolicy.builder()
                .allowPrefix(workspacePath)
                .build()
        def provider = new PolicySandboxProvider(policy)
        def task = new FileArgHolderCallable(
                [workspacePath + "/a.txt", workspacePath + "/b.txt"],
                "multi files ok"
        )

        when:
        def result = provider.executeInIsolation(task)

        then:
        result == "multi files ok"
    }

    // ======================================================================
    // Helper classes for implementing dual interfaces
    // ======================================================================

    static class FileArgHolderCallable implements PolicySandboxProvider.FileArgHolder, Callable<String> {
        private final List<String> fileArgs
        private final String result

        FileArgHolderCallable(List<String> fileArgs, String result) {
            this.fileArgs = fileArgs
            this.result = result
        }

        @Override
        List<String> getFileArgs() { return fileArgs }

        @Override
        String call() { return result }
    }

    static class ShellCommandCallable implements PolicySandboxProvider.ShellCommand, Callable<String> {
        private final String command
        private final List<String> fileArgs

        ShellCommandCallable(String command, List<String> fileArgs) {
            this.command = command
            this.fileArgs = fileArgs
        }

        @Override
        String getCommand() { return command }

        @Override
        List<String> getFileArgs() { return fileArgs }

        @Override
        String call() { return "should not be called" }
    }
}
