package io.trino.gateway.ha;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestGatewayHaMulipleBackend {
  public static final String EXPECTED_RESPONSE1 = "{\"id\":\"testId1\"}";
  public static final String EXPECTED_RESPONSE2 = "{\"id\":\"testId2\"}";

  final int routerPort = 20000 + (int) (Math.random() * 1000);
  final int backend1Port = 21000 + (int) (Math.random() * 1000);
  final int backend2Port = 21000 + (int) (Math.random() * 1000);

  private final WireMockServer adhocBackend =
      new WireMockServer(WireMockConfiguration.options().port(backend1Port));
  private final WireMockServer scheduledBackend =
      new WireMockServer(WireMockConfiguration.options().port(backend2Port));

  @BeforeClass(alwaysRun = true)
  public void setup() throws Exception {
    HaGatewayTestUtils.prepareMockBackend(adhocBackend, "/v1/statement", EXPECTED_RESPONSE1);
    HaGatewayTestUtils.prepareMockBackend(scheduledBackend, "/v1/statement", EXPECTED_RESPONSE2);

    // seed database
    HaGatewayTestUtils.TestConfig testConfig =
        HaGatewayTestUtils.buildGatewayConfigAndSeedDb(routerPort, "test-config-template.yml");

    // Start Gateway
    String[] args = {"server", testConfig.getConfigFilePath()};
    HaGatewayLauncher.main(args);
    // Now populate the backend
    HaGatewayTestUtils.setUpBackend(
        "trino1", "http://localhost:" + backend1Port, "externalUrl", true, "adhoc", routerPort);
    HaGatewayTestUtils.setUpBackend(
        "trino2", "http://localhost:" + backend2Port, "externalUrl", true, "scheduled",
        routerPort);
  }

  @Test
  public void testQueryDeliveryToMultipleRoutingGroups() throws Exception {
    // Default request should be routed to adhoc backend
    OkHttpClient httpClient = new OkHttpClient();
    RequestBody requestBody =
        RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "SELECT 1");
    Request request1 =
        new Request.Builder()
            .url("http://localhost:" + routerPort + "/v1/statement")
            .post(requestBody)
            .build();
    Response response1 = httpClient.newCall(request1).execute();
    Assert.assertEquals(response1.body().string(), EXPECTED_RESPONSE1);

    // When X-Trino-Routing-Group is set in header, query should be routed to cluster under the
    // routing group
    Request request4 =
        new Request.Builder()
            .url("http://localhost:" + routerPort + "/v1/statement")
            .post(requestBody)
            .addHeader("X-Trino-Routing-Group", "scheduled")
            .build();
    Response response4 = httpClient.newCall(request4).execute();
    Assert.assertEquals(response4.body().string(), EXPECTED_RESPONSE2);
  }

  @AfterClass(alwaysRun = true)
  public void cleanup() {
    adhocBackend.stop();
    scheduledBackend.stop();
  }
}
