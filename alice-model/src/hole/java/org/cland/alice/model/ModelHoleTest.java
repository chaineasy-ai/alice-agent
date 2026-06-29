package org.cland.alice.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Hole test entry point for alice-model.
 *
 * <p>Exercises module boundary (ModelProvider, Call, ModelSupplier, ModelConfigLoader) directly,
 * without going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-model:runHoleTest --args="&lt;key&gt;"
 *
 * <p>Supported keys: dispatch, call, supplier, config, multi, all
 *
 * <p>Exit 0 = PASS, 1 = FAIL.
 */
public class ModelHoleTest {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      fail("Usage: <key>\n" + "  dispatch, call, supplier, config, multi, all");
    }

    switch (args[0]) {
      case "dispatch" -> testProviderDispatch();
      case "call" -> testCallLifecycle();
      case "supplier" -> testSupplierParse();
      case "config" -> testConfigLoader();
      case "multi" -> testMultiRouting();
      case "all" -> {
        testProviderDispatch();
        testCallLifecycle();
        testSupplierParse();
        testConfigLoader();
        testMultiRouting();
      }
      default -> fail("Unknown key: " + args[0]);
    }
  }

  // ==================== MDL-P01: ModelProvider.dispatch() ====================

  static void testProviderDispatch() {
    ModelProvider.reset();
    ModelProvider provider = ModelProvider.getInstance();

    // Register a fake supplier
    provider.registerSupplier(new FakeModelSupplier("fake"));

    // Register a model so routing works
    provider.registerModel(
        Model.builder()
            .modelId("fake-model")
            .supplierName("fake")
            .capability(Model.Capability.FUNCTION_CALL)
            .pricing(new Model.Pricing(0, 0))
            .build());

    Call call = provider.dispatch("fake-model", "Hello");
    assertTrue("response not null", call.result() != null);
    assertEq("response content", "hello from fake", call.result().content());

    System.out.println("PASS: MDL-P01 ModelProvider.dispatch()");
  }

  // ==================== MDL-P02: Call lifecycle ====================

  static void testCallLifecycle() {
    Call.Payload payload = new Call.Payload("test-model", "test prompt", Map.of());
    Call call = Call.builder().payload(payload).build();

    assertEq("initial state", CallStatus.CREATED, call.status());

    call.transitionTo(CallStatus.PENDING);
    call.transitionTo(CallStatus.RUNNING);
    call.metrics().start();

    // Simulate a response
    Call.Response response =
        Call.Response.textOnly("result", new Call.TokenUsage(10, 20, 30), Map.of("model", "test"));
    call.updateResult(response);
    call.metrics().stop();
    call.transitionTo(CallStatus.FINISHED);

    assertEq("final state", CallStatus.FINISHED, call.status());
    assertEq("response content", "result", call.result().content());
    assertEq("prompt tokens", 10, call.result().tokenUsage().promptTokens());
    assertEq("completion tokens", 20, call.result().tokenUsage().completionTokens());
    assertEq("total tokens", 30, call.result().tokenUsage().totalTokens());
    assertTrue("latency recorded", call.metrics().latencyMs() >= 0);

    System.out.println("PASS: MDL-P02 Call lifecycle");
  }

  // ==================== MDL-P03: ModelSupplier parse ====================

  static void testSupplierParse() {
    FakeModelSupplier supplier = new FakeModelSupplier("test");

    Call.Payload payload = new Call.Payload("test-model", "test prompt", Map.of());
    Call call = Call.builder().payload(payload).build();

    call.transitionTo(CallStatus.PENDING);
    call.transitionTo(CallStatus.RUNNING);

    // FakeModelSupplier returns a canned response
    Call.Response response = supplier.request(call);
    assertEq("supplier name", "test", supplier.name());
    assertEq("response content", "hello from fake", response.content());
    assertTrue("token usage not null", response.tokenUsage() != null);

    System.out.println("PASS: MDL-P03 ModelSupplier.parse()");
  }

  // ==================== MDL-P04: ModelConfigLoader ====================

  static void testConfigLoader() throws Exception {
    // Create a temp JSON config file
    // Format: providers.<name> { base_url, api_key, available_models[{ name, model, max_tokens, ...
    // }] }
    String json =
        "{\n"
            + "  \"default_model\": {\n"
            + "    \"provider\": \"openai\",\n"
            + "    \"model\": \"gpt-4o\",\n"
            + "    \"enable_thinking\": true,\n"
            + "    \"reasoning_effort\": \"high\"\n"
            + "  },\n"
            + "  \"providers\": {\n"
            + "    \"openai\": {\n"
            + "      \"base_url\": \"https://api.openai.com/v1\",\n"
            + "      \"api_key\": \"${OPENAI_API_KEY}\",\n"
            + "      \"available_models\": [\n"
            + "        {\n"
            + "          \"name\": \"gpt-4o\",\n"
            + "          \"model\": \"gpt-4o\",\n"
            + "          \"max_tokens\": 8192,\n"
            + "          \"max_output_tokens\": 4096,\n"
            + "          \"capabilities\": {\"tools\": true}\n"
            + "        }\n"
            + "      ]\n"
            + "    }\n"
            + "  }\n"
            + "}";

    Path tempDir = Files.createTempDirectory("model-test-");
    Path configFile = tempDir.resolve("model.json");
    Files.writeString(configFile, json);

    ModelConfigLoader loader = new ModelConfigLoader(configFile);
    loader.load();

    assertTrue("providers loaded", loader.getProviders().size() == 1);
    assertTrue(
        "available models loaded",
        loader.getProviders().get("openai").availableModels().size() == 1);
    assertEq("model name", "gpt-4o", loader.getModelPoolEntry("gpt-4o").model());
    assertEq("default model", "gpt-4o", loader.getDefaultModel());

    // Cleanup
    Files.deleteIfExists(configFile);
    Files.deleteIfExists(tempDir);

    System.out.println("PASS: MDL-P04 ModelConfigLoader");
  }

  // ==================== MDL-P05: Multi-supplier routing ====================

  static void testMultiRouting() {
    ModelProvider.reset();
    ModelProvider provider = ModelProvider.getInstance();

    // Register two suppliers
    provider.registerSupplier(new FakeModelSupplier("alpha"));
    provider.registerSupplier(new FakeModelSupplier("beta"));

    // Register models pointing to different suppliers
    provider.registerModel(Model.builder().modelId("model-a").supplierName("alpha").build());
    provider.registerModel(Model.builder().modelId("model-b").supplierName("beta").build());

    // dispatch to model-a -> should use alpha supplier
    Call callA = provider.dispatch("model-a", "hello");
    assertEq("model-a response", "hello from fake", callA.result().content());

    // dispatch to model-b -> should use beta supplier
    Call callB = provider.dispatch("model-b", "world");
    assertEq("model-b response", "hello from fake", callB.result().content());

    System.out.println("PASS: MDL-P05 Multi-supplier routing");
  }

  // ==================== Fake supplier ====================

  static class FakeModelSupplier implements ModelSupplier {
    private final String name;

    FakeModelSupplier(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Call.Response request(Call call) {
      // Canned response - no real API call
      return Call.Response.textOnly(
          "hello from fake", new Call.TokenUsage(5, 10, 15), Map.of("supplier", name));
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
