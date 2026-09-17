package com.stocktrader.selenium;

import com.stocktrader.selenium.utils.WebDriverManager;
import com.stocktrader.selenium.utils.TestDataProvider;
import org.openqa.selenium.WebDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.Properties;

/**
 * Base test class that provides common setup and teardown functionality for all
 * test classes in the Stock Trader Selenium V2 suite.
 *
 * Loads configuration, initializes WebDriver with SSL bypass and window
 * sizing, and exposes helpers to access configured URLs.
 */
@ExtendWith(FailureWatcher.class)
public class BaseTest {
    
    protected WebDriver driver;
    protected Properties config;
    protected TestDataProvider testData;
    
    @BeforeEach
    public void setUp() throws IOException {
        // Load configuration
        config = TestDataProvider.loadConfig();
        
        // Initialize test data provider
        testData = new TestDataProvider();
        
        // Initialize WebDriver
        driver = WebDriverManager.getDriver(config.getProperty("test.browser"));
        
        // Set implicit wait
        driver.manage().timeouts().implicitlyWait(
            java.time.Duration.ofSeconds(Integer.parseInt(config.getProperty("test.timeout.implicit")))
        );
        
        // Set window size
        driver.manage().window().setSize(
            new org.openqa.selenium.Dimension(
                Integer.parseInt(config.getProperty("browser.window.width")),
                Integer.parseInt(config.getProperty("browser.window.height"))
            )
        );
    }
    
    @AfterEach
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }
    
    /**
     * Navigate to the login page
     */
    protected void navigateToLogin() {
        driver.get(config.getProperty("app.login.url"));
    }
    
    /**
     * Get the base URL from configuration
     */
    protected String getBaseUrl() {
        return config.getProperty("app.base.url");
    }
}
