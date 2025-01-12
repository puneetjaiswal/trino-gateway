package io.trino.gateway.ha.router;

import java.io.FileReader;
import java.util.HashMap;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rules;
import org.jeasy.rules.api.RulesEngine;
import org.jeasy.rules.core.DefaultRulesEngine;
import org.jeasy.rules.mvel.MVELRuleFactory;
import org.jeasy.rules.support.reader.YamlRuleDefinitionReader;

/**
 * RoutingGroupSelector provides a way to match an HTTP request to a Gateway routing group.
 */
public interface RoutingGroupSelector {
  String ROUTING_GROUP_HEADER = "X-Trino-Routing-Group";

  /**
   * Routing group selector that relies on the X-Trino-Routing-Group
   * header to determine the right routing group.
   */
  static RoutingGroupSelector byRoutingGroupHeader() {
    return request -> request.getHeader(ROUTING_GROUP_HEADER);
  }

  /**
   * Routing group selector that uses routing engine rules
   * to determine the right routing group.
   */
  static RoutingGroupSelector byRoutingRulesEngine(String rulesConfigPath) {
    try {
      RulesEngine rulesEngine = new DefaultRulesEngine();
      MVELRuleFactory ruleFactory = new MVELRuleFactory(new YamlRuleDefinitionReader());
      ConnectionChecker connectionChecker = new ConnectionChecker();
      Logger.log.info("reading rules from {}", rulesConfigPath);
      Rules rules = ruleFactory.createRules(
          new FileReader(rulesConfigPath));

      return request -> {
        Logger.log.debug("Thread id {} : applying the routing rules",
            Thread.currentThread().getId());
        request.setAttribute("connectionChecker", connectionChecker);
        Facts facts = new Facts();
        HashMap<String, String> result = new HashMap<String, String>();
        facts.put("request", request);
        facts.put("result", result);
        rulesEngine.fire(rules, facts);
        return result.get("routingGroup");
      };
    } catch (Exception e) {
      return request -> {
        Logger.log.error("Error opening rules configuration file,"
            + " using routing group header as default.", e);
        return request.getHeader(ROUTING_GROUP_HEADER);
      };
    }
  }

  /**
   * Given an HTTP request find a routing group to direct the request to. If a routing group cannot
   * be determined return null.
   */
  String findRoutingGroup(HttpServletRequest request);

  @Slf4j
  final class Logger {
  }
}
