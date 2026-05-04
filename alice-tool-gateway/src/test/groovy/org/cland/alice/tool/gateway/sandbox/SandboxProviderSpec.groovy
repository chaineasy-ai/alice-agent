package org.cland.alice.tool.gateway.sandbox

import java.util.concurrent.Callable
import spock.lang.Specification

class SandboxProviderSpec extends Specification {

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

    def "PolicySandboxProvider should throw UnsupportedOperationException on Java 25+"() {
        given:
        def provider = new PolicySandboxProvider(
            PolicySandboxProvider.Permissions.READ_ONLY_TEMP
        )

        when:
        provider.executeInIsolation({ ->
            2 + 2
        } as Callable)

        then:
        // Java 17+ deprecated SecurityManager; Java 25 throws UnsupportedOperationException
        def e = thrown(Exception)
        // Either UnsupportedOperationException or SecurityException is acceptable
        e instanceof UnsupportedOperationException || e instanceof SecurityException
    }

    def "PolicySandboxProvider Permissions enum should have expected values"() {
        expect:
        PolicySandboxProvider.Permissions.values() as Set == [
            PolicySandboxProvider.Permissions.READ_ONLY_TEMP,
            PolicySandboxProvider.Permissions.READ_ONLY_SPECIFIED,
            PolicySandboxProvider.Permissions.READ_WRITE_SPECIFIED,
            PolicySandboxProvider.Permissions.NONE
        ] as Set
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
}
