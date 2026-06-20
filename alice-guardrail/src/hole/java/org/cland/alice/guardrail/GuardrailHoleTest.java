package org.cland.alice.guardrail;

import java.util.Map;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.validators.HallucinationDetector;
import org.cland.alice.guardrail.validators.PermissionSandboxValidator;

/**
 * Hole test entry point for alice-guardrail.
 *
 * <p>Exercises module boundary (GuardrailService, PolicyEngine, HallucinationDetector,
 * PermissionSandboxValidator) directly, without going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-guardrail:runHoleTest --args="&lt;key&gt;"
 *
 * <p>Supported keys: verifyPlan, verifyResult, policyEngine, hallucinate, sandbox, all
 *
 * <p>Exit 0 = PASS, 1 = FAIL.
 */
public class GuardrailHoleTest {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      fail(
          "Usage: <key>\n" + "  verifyPlan, verifyResult, policyEngine, hallucinate, sandbox, all");
    }
    switch (args[0]) {
      case "verifyPlan" -> testVerifyPlan();
      case "verifyResult" -> testVerifyResult();
      case "policyEngine" -> testPolicyEngine();
      case "hallucinate" -> testHallucinationDetector();
      case "sandbox" -> testPermissionSandbox();
      case "all" -> {
        testVerifyPlan();
        testVerifyResult();
        testPolicyEngine();
        testHallucinationDetector();
        testPermissionSandbox();
      }
      default -> fail("Unknown key: " + args[0]);
    }
  }

  // ==================== GRD-P01: GuardrailService.verifyPlan() ====================

  static void testVerifyPlan() {
    GuardrailService service = new GuardrailService();

    // Legal plan
    Plan legalPlan = Plan.fastPath("say hello", "LLM_INFERENCE", "greeting");
    AuditResult legalResult = service.verifyPlan(legalPlan);
    assertTrue("legal plan passes", legalResult.isPassed());
    assertEq("legal plan status", AuditResult.Status.ALLOW, legalResult.status());

    // High-risk action
    Plan riskyPlan =
        Plan.builder()
            .type(Plan.Type.FAST_PATH)
            .summary("delete data")
            .addStep("TOOL_CALL", "DROP TABLE users")
            .build();
    AuditResult riskyResult = service.verifyPlan(riskyPlan);
    assertTrue("risky plan needs confirm", riskyResult.needsManualConfirm());
    assertEq("risky plan status", AuditResult.Status.MANUAL_CONFIRM, riskyResult.status());

    // Pre-validator blocks
    service.registerPreValidator(new PermissionSandboxValidator());
    Plan blockedPlan = Plan.fastPath("access system", "TOOL_CALL", "/etc/passwd");
    AuditResult blockedResult = service.verifyPlan(blockedPlan);
    assertTrue("blocked plan rejected", !blockedResult.isPassed());
    assertEq("blocked plan status", AuditResult.Status.REJECT, blockedResult.status());

    // Null plan
    AuditResult nullResult = service.verifyPlan(null);
    assertTrue("null plan rejected", !nullResult.isPassed());

    System.out.println("PASS: GRD-P01 GuardrailService.verifyPlan()");
  }

  // ==================== GRD-P02: GuardrailService.verifyResult() ====================

  static void testVerifyResult() {
    GuardrailService service = new GuardrailService();
    Plan plan = Plan.fastPath("query data", "TOOL_CALL", "database");

    // Valid observation
    Map<String, Object> validObs =
        Map.of(
            "status", "SUCCESS",
            "summary", "Query returned 5 rows",
            "rawData", "{\"rows\": [{\"id\": 1}]}");
    AuditResult validResult = service.verifyResult(validObs, plan);
    assertTrue("valid observation passes", validResult.isPassed());

    // FAILURE observation
    Map<String, Object> failObs =
        Map.of(
            "status", "FAILURE",
            "summary", "Connection timeout",
            "rawData", "");
    AuditResult failResult = service.verifyResult(failObs, plan);
    assertTrue("failure observation rejected", !failResult.isPassed());
    assertEq("failure status", AuditResult.Status.INVALID, failResult.status());

    // Null observation
    AuditResult nullResult = service.verifyResult(null, null);
    assertTrue("null observation rejected", !nullResult.isPassed());

    // Post-validator rejects (hallucination detected)
    service.registerPostValidator(new HallucinationDetector());
    Map<String, Object> hallucinatedObs =
        Map.of(
            "status", "SUCCESS",
            "summary", "search completed",
            "rawData", "no results found");
    AuditResult halResult = service.verifyResult(hallucinatedObs, plan);
    assertTrue("hallucinated result rejected", !halResult.isPassed());

    System.out.println("PASS: GRD-P02 GuardrailService.verifyResult()");
  }

  // ==================== GRD-P03: PolicyEngine ====================

  static void testPolicyEngine() {
    PolicyEngine engine = new PolicyEngine();

    // JsonSchemaValidator: register schema, validate JSON
    engine.schemaValidator().registerSchema("test-schema", "{}");
    assertTrue(
        "schema validates balanced JSON",
        engine.schemaValidator().validate("test-schema", "{\"a\": 1}"));
    assertTrue(
        "schema fails unbalanced JSON",
        !engine.schemaValidator().validate("test-schema", "{\"a\": 1"));

    // JsonSchemaValidator: structured data validation
    var rules = Map.<String, Class<?>>of("name", String.class, "age", Integer.class);
    Map<String, Object> validData = Map.of("name", "Alice", "age", 30);
    assertTrue(
        "structured data valid", engine.schemaValidator().validate(rules, validData).isEmpty());

    Map<String, Object> invalidData = Map.of("name", "Alice", "age", "thirty");
    assertTrue(
        "type mismatch detected", !engine.schemaValidator().validate(rules, invalidData).isEmpty());

    // RegexSafetyFilter: deny pattern
    engine.safetyFilter().addDenyPattern("DROP\\s+TABLE");
    assertTrue("safe content passes", engine.safetyFilter().isSafe("SELECT * FROM users"));
    assertTrue("unsafe content blocked", !engine.safetyFilter().isSafe("DROP TABLE users"));
    assertTrue(
        "first violation found",
        engine.safetyFilter().firstViolation("DROP TABLE users").isPresent());
    assertTrue(
        "first violation absent for clean input",
        engine.safetyFilter().firstViolation("SELECT 1").isEmpty());

    // RegexSafetyFilter: allow pattern
    engine.safetyFilter().clear();
    engine.safetyFilter().addAllowPattern("SELECT");
    assertTrue("matching allow passes", engine.safetyFilter().isSafe("SELECT * FROM users"));
    assertTrue("non-matching allow fails", !engine.safetyFilter().isSafe("DELETE FROM users"));

    System.out.println("PASS: GRD-P03 PolicyEngine");
  }

  // ==================== GRD-P04: HallucinationDetector ====================

  static void testHallucinationDetector() {
    HallucinationDetector detector = new HallucinationDetector();
    Plan plan = Plan.fastPath("search", "TOOL_CALL", "web");

    // Normal observation
    Map<String, Object> normalObs =
        Map.of(
            "status", "SUCCESS",
            "summary", "found results",
            "rawData", "{\"items\": [\"result1\"]}");
    AuditResult normalResult = detector.check(normalObs, plan);
    assertTrue("normal observation passes", normalResult.isPassed());

    // Empty result pattern
    Map<String, Object> emptyObs =
        Map.of(
            "status", "SUCCESS",
            "summary", "search completed",
            "rawData", "no results found");
    AuditResult emptyResult = detector.check(emptyObs, plan);
    assertTrue("empty result detected", !emptyResult.isPassed());
    assertEq("status is INVALID", AuditResult.Status.INVALID, emptyResult.status());

    // Error pattern
    Map<String, Object> errorObs =
        Map.of(
            "status", "SUCCESS",
            "summary", "error occurred",
            "rawData", "Error: connection refused");
    AuditResult errorResult = detector.check(errorObs, plan);
    assertTrue("error pattern detected", !errorResult.isPassed());

    // FAILURE status
    Map<String, Object> failObs =
        Map.of(
            "status", "FAILURE",
            "summary", "system crash");
    AuditResult failResult = detector.check(failObs, plan);
    assertTrue("failure status detected", !failResult.isPassed());

    System.out.println("PASS: GRD-P04 HallucinationDetector");
  }

  // ==================== GRD-P05: PermissionSandboxValidator ====================

  static void testPermissionSandbox() {
    PermissionSandboxValidator validator = new PermissionSandboxValidator();

    // Bounded action: allowed
    Plan allowedPlan = Plan.fastPath("read tmp", "TOOL_CALL", "/tmp/somefile");
    AuditResult allowedResult = validator.check(allowedPlan);
    assertTrue("bounded action passes", allowedResult.isPassed());

    // OOB: /etc/ path
    Plan oobPlan = Plan.fastPath("read etc", "TOOL_CALL", "/etc/shadow");
    AuditResult oobResult = validator.check(oobPlan);
    assertTrue("OOB /etc/ rejected", !oobResult.isPassed());
    assertEq("OOB status", AuditResult.Status.REJECT, oobResult.status());

    // OOB: rm -rf /
    Plan rmPlan = Plan.fastPath("rm root", "TOOL_CALL", "rm -rf /");
    AuditResult rmResult = validator.check(rmPlan);
    assertTrue("rm -rf / rejected", !rmResult.isPassed());

    // OOB: Windows System32
    Plan winPlan = Plan.fastPath("access system32", "TOOL_CALL", "C:\\Windows\\System32\\config");
    AuditResult winResult = validator.check(winPlan);
    assertTrue("System32 rejected", !winResult.isPassed());

    // Multiple steps: first step allowed, second blocked
    Plan multiPlan =
        Plan.builder()
            .type(Plan.Type.FAST_PATH)
            .summary("multi-step")
            .addStep("TOOL_CALL", "/tmp/file")
            .addStep("TOOL_CALL", "/etc/config")
            .build();
    AuditResult multiResult = validator.check(multiPlan);
    assertTrue("multi-step with OOB rejected", !multiResult.isPassed());

    System.out.println("PASS: GRD-P05 PermissionSandboxValidator");
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

  static void assertEq(String label, AuditResult.Status expected, AuditResult.Status actual) {
    if (expected != actual) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }
}
