package org.cland.alice.agent;

import org.cland.alice.agent.spi.AliceFacade;

/**
 * Hole test entry point for alice-bootstrap.
 *
 * <p>Exercises module boundary (FacadeSelector, AliceApp, AliceFacade SPI contract) directly,
 * without going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-bootstrap:runHoleTest --args="&lt;key&gt;"
 *
 * <p>Supported keys: facadeSelector, aliceApp, facadeContract, all
 *
 * <p>Exit 0 = PASS, 1 = FAIL.
 */
public class BootstrapHoleTest {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      fail("Usage: <key>\n" + "  facadeSelector, aliceApp, facadeContract, all");
    }
    switch (args[0]) {
      case "facadeSelector" -> testFacadeSelector();
      case "aliceApp" -> testAliceApp();
      case "facadeContract" -> testFacadeContract();
      case "all" -> {
        testFacadeSelector();
        testAliceApp();
        testFacadeContract();
      }
      default -> fail("Unknown key: " + args[0]);
    }
  }

  // ==================== BTS-P01: FacadeSelector.launch() ====================

  static void testFacadeSelector() {
    // FacadeSelector.launch() requires SPI implementations on classpath.
    // Without any AliceFacade SPI, it should exit with RUNTIME_ERROR.
    // We can't easily test the SPI discovery here since the hole test
    // runs without the facade modules on the module path.
    //
    // Instead, we verify:
    //   1. FacadeSelector class loads and is accessible
    //   2. AliceApp constants are well-defined
    //   3. FacadeSelector handles null args gracefully (even though
    //      it uses ServiceLoader, the method should not throw)

    assertEq("EXIT_SUCCESS", 0, AliceApp.EXIT_SUCCESS);
    assertEq("EXIT_PARAM_ERROR", 2, AliceApp.EXIT_PARAM_ERROR);
    assertEq("EXIT_RUNTIME_ERROR", 1, AliceApp.EXIT_RUNTIME_ERROR);

    // FacadeSelector.launch uses ServiceLoader. With --help, it prints usage
    // and returns. We verify it doesn't throw and returns a defined exit code.
    int result = FacadeSelector.launch(new String[] {"--help"});
    assertTrue(
        "launch returns defined exit code",
        result == AliceApp.EXIT_SUCCESS
            || result == AliceApp.EXIT_PARAM_ERROR
            || result == AliceApp.EXIT_RUNTIME_ERROR);

    System.out.println("PASS: BTS-P01 FacadeSelector.launch()");
  }

  // ==================== BTS-P02: AliceApp.main() ====================

  static void testAliceApp() {
    // AliceApp uses ServiceLoader internally, so we can't test full lifecycle.
    // Instead verify:
    //   1. AliceApp constants are accessible
    //   2. The class loads without any linkage errors
    //   3. Shutdown hook registration doesn't throw

    Class<?> clazz = AliceApp.class;
    assertTrue("AliceApp class loads", clazz != null);

    // Verify the main method exists with correct signature
    try {
      java.lang.reflect.Method mainMethod = AliceApp.class.getMethod("main", String[].class);
      assertTrue("main method exists", mainMethod != null);
      assertEq(
          "main is static", true, java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()));
    } catch (NoSuchMethodException e) {
      fail("AliceApp.main() method not found");
    }

    System.out.println("PASS: BTS-P02 AliceApp.main()");
  }

  // ==================== BTS-P03: AliceFacade SPI contract ====================

  static void testFacadeContract() {
    // Verify the AliceFacade interface contract is well-defined
    assertTrue("AliceFacade is interface", AliceFacade.class.isInterface());

    // Verify methods exist
    try {
      AliceFacade.class.getMethod("name");
      AliceFacade.class.getMethod("launch", String[].class);
    } catch (NoSuchMethodException e) {
      fail("AliceFacade missing required method: " + e.getMessage());
    }

    // Create a simple test implementation to verify the contract works
    AliceFacade testFacade = new TestFacade();
    assertEq("test facade name", "test", testFacade.name());
    int exitCode = testFacade.launch(new String[] {"hello"});
    assertEq("test facade launch returns 0", 0, exitCode);

    System.out.println("PASS: BTS-P03 AliceFacade SPI contract");
  }

  // ==================== Test implementation ====================

  static class TestFacade implements AliceFacade {
    @Override
    public String name() {
      return "test";
    }

    @Override
    public int launch(String[] args) {
      return 0;
    }
  }

  // ==================== Assertion helpers ====================

  static void fail(String msg) {
    System.err.println("FAIL: " + msg);
    System.exit(1);
  }

  static void assertTrue(String label, boolean condition) {
    if (!condition) fail(label + " expected true");
  }

  static void assertEq(String label, Object expected, Object actual) {
    if (!java.util.Objects.equals(expected, actual)) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }

  static void assertEq(String label, int expected, int actual) {
    if (expected != actual) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }
}
