package space.wlshow.scope;

import space.wlshow.scope.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigCheck {
    private static final Logger log = LoggerFactory.getLogger(ConfigCheck.class);

    public static void main(String[] args) {
        log.info("provider = {}", AppConfig.modelProvider());
        log.info("model    = {}", AppConfig.modelName());
        log.info("baseUrl  = {}", AppConfig.modelBaseUrl());
        log.info("apiKey   = {}…(masked)", AppConfig.modelApiKey().substring(0, 6));
        log.info("agent    = {}", AppConfig.agentName());
        log.info("maxIters = {}", AppConfig.maxIters());
        log.info("timeout  = {}", AppConfig.timeout());
        log.debug("sysPrompt = {}", AppConfig.sysPrompt());
    }
}