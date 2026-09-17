package com.stocktrader.selenium.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * TestDataProvider utility class for loading test configuration and data
 */
public class TestDataProvider {
    
    private static final String CONFIG_FILE = "config/test-config.properties";
    
    /**
     * Load configuration properties from the config file
     * @return Properties object containing configuration
     * @throws IOException if the config file cannot be loaded
     */
    public static Properties loadConfig() throws IOException {
        Properties config = new Properties();
        
        try (InputStream input = TestDataProvider.class.getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            
            if (input == null) {
                throw new IOException("Unable to find " + CONFIG_FILE);
            }
            
            config.load(input);
        }
        
        return config;
    }
    
    /**
     * Get a configuration value by key
     * @param key The configuration key
     * @return The configuration value
     */
    public String getConfigValue(String key) {
        try {
            Properties config = loadConfig();
            return config.getProperty(key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
    }
    
    /**
     * Get the login URL from configuration
     * @return The login URL
     */
    public String getLoginUrl() {
        String base = getBaseUrl();
        if (base.endsWith("/")) return base + "login";
        return base + "/login";
    }
    
    /**
     * Get the base URL from configuration
     * @return The base URL
     */
    public String getBaseUrl() {
        return getConfigValue("app.base.url");
    }

    /**
     * Get the summary URL derived from base URL
     */
    public String getSummaryUrl() {
        String base = getBaseUrl();
        if (base.endsWith("/")) return base + "summary";
        return base + "/summary";
    }
    
    /**
     * Get valid username from configuration
     * @return The valid username
     */
    public String getValidUsername() {
        return getConfigValue("test.user.valid.username");
    }
    
    /**
     * Get valid password from configuration
     * @return The valid password
     */
    public String getValidPassword() {
        return getConfigValue("test.user.valid.password");
    }
    
    /**
     * Get invalid username from configuration
     * @return The invalid username
     */
    public String getInvalidUsername() {
        return getConfigValue("test.user.invalid.username");
    }
    
    /**
     * Get invalid password from configuration
     * @return The invalid password
     */
    public String getInvalidPassword() {
        return getConfigValue("test.user.invalid.password");
    }

    /**
     * Trading/test data helpers
     */
    public String getOwnerPrefix() {
        return getConfigValue("owner.prefix");
    }

    public String[] getTradingSymbols() {
        String csv = getConfigValue("trading.symbols");
        return (csv == null || csv.isEmpty()) ? new String[0] : csv.split("\\s*,\\s*");
    }

    public String getInitialBalance() {
        return getConfigValue("trading.initial.balance");
    }

    public String getTradingCurrency() {
        return getConfigValue("trading.currency");
    }

    public int getSharesMin() {
        return Integer.parseInt(getConfigValue("trading.shares.min"));
    }

    public int getSharesMax() {
        return Integer.parseInt(getConfigValue("trading.shares.max"));
    }
}
