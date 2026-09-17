package com.stocktrader.selenium.utils;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;

/**
 * WebDriverManager utility class for managing WebDriver instances
 * with automatic driver management and configuration.
 */
public class WebDriverManager {
    
    /**
     * Get a WebDriver instance for the specified browser
     * @param browserName The name of the browser (chrome, firefox, edge)
     * @return WebDriver instance
     */
    public static WebDriver getDriver(String browserName) {
        WebDriver driver;
        
        switch (browserName.toLowerCase()) {
            case "chrome":
                driver = getChromeDriver();
                break;
            case "firefox":
                driver = getFirefoxDriver();
                break;
            case "edge":
                driver = getEdgeDriver();
                break;
            default:
                throw new IllegalArgumentException("Unsupported browser: " + browserName);
        }
        
        return driver;
    }
    
    /**
     * Get Chrome WebDriver with optimized settings
     */
    private static WebDriver getChromeDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--remote-allow-origins=*");
        // SSL/interstitial bypass
        options.setAcceptInsecureCerts(true);
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--ignore-ssl-errors");
        options.addArguments("--allow-running-insecure-content");
        options.addArguments("--test-type");

        // Uncomment for headless mode
        // options.addArguments("--headless=new");

        return new ChromeDriver(options);
    }
    
    /**
     * Get Firefox WebDriver with optimized settings
     */
    private static WebDriver getFirefoxDriver() {
        FirefoxOptions options = new FirefoxOptions();
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        // SSL/interstitial bypass
        options.setAcceptInsecureCerts(true);

        // Uncomment for headless mode
        // options.addArguments("--headless");

        return new FirefoxDriver(options);
    }
    
    /**
     * Get Edge WebDriver with optimized settings
     */
    private static WebDriver getEdgeDriver() {
        EdgeOptions options = new EdgeOptions();
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        // SSL/interstitial bypass
        options.setAcceptInsecureCerts(true);

        // Uncomment for headless mode
        // options.addArguments("--headless");

        return new EdgeDriver(options);
    }
}
