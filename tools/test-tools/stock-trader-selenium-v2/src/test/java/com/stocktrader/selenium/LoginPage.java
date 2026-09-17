package com.stocktrader.selenium;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * Page Object for the Login page of the Stock Trader application.
 *
 * Encapsulates locators and interactions for login UI, including entering
 * credentials, toggling password visibility, and checking success/failure.
 */
public class LoginPage extends BasePage {
    
    // Locators for the new Bootstrap-based UI
    private By usernameField = By.id("username");
    private By passwordField = By.id("password");
    private By loginButton = By.name("submit");
    private By togglePasswordBtn = By.id("togglePassword");
    private By togglePasswordIcon = By.id("togglePasswordIcon");
    private By loginHeading = By.className("login-heading");
    private By headerImage = By.className("header-img");
    private By footerImage = By.className("footer-img");
    
    // Error/validation locators
    private By invalidFeedback = By.className("invalid-feedback");
    private By wasValidated = By.className("was-validated");
    
    public LoginPage(WebDriver driver) {
        super(driver, new WebDriverWait(driver, Duration.ofSeconds(30)));
    }
    
    /**
     * Navigate to the login page
     */
    public void navigateToLoginPage(String loginUrl) {
        driver.get(loginUrl);
        waitForPageToLoad();
    }
    
    /**
     * Wait for the page to load completely
     */
    public void waitForPageToLoad() {
        wait.until(ExpectedConditions.presenceOfElementLocated(loginHeading));
        wait.until(ExpectedConditions.elementToBeClickable(loginButton));
    }
    
    /**
     * Enter username in the username field
     * @param username The username to enter
     */
    public void enterUsername(String username) {
        type(usernameField, username);
    }
    
    /**
     * Enter password in the password field
     * @param password The password to enter
     */
    public void enterPassword(String password) {
        type(passwordField, password);
    }
    
    /**
     * Enter both username and password
     * @param username The username to enter
     * @param password The password to enter
     */
    public void enterCredentials(String username, String password) {
        enterUsername(username);
        enterPassword(password);
    }
    
    /**
     * Click the login button
     */
    public void clickLogin() {
        click(loginButton);
    }
    
    /**
     * Toggle password visibility
     */
    public void togglePasswordVisibility() {
        click(togglePasswordBtn);
    }
    
    /**
     * Check if password is visible (text type) or hidden (password type)
     * @return true if password is visible, false if hidden
     */
    public boolean isPasswordVisible() {
        WebElement passwordElement = driver.findElement(passwordField);
        return "text".equals(passwordElement.getAttribute("type"));
    }
    
    /**
     * Check if the login was successful by checking if we're redirected to summary page
     * @return true if login was successful, false otherwise
     */
    public boolean isLoginSuccessful() {
        try {
            // Wait for redirect to summary page or error page
            wait.until(ExpectedConditions.or(
                ExpectedConditions.urlContains("/summary"),
                ExpectedConditions.urlContains("/error")
            ));
            
            // Check if we're on the summary page (successful login)
            return driver.getCurrentUrl().contains("/summary");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if we're still on the login page (login failed)
     * @return true if still on login page, false otherwise
     */
    public boolean isLoginFailed() {
        return driver.getCurrentUrl().contains("/login");
    }
    
    /**
     * Check if form validation is triggered
     * @return true if form has validation class, false otherwise
     */
    public boolean isFormValidated() {
        try {
            WebElement form = driver.findElement(By.tagName("form"));
            return form.getAttribute("class").contains("was-validated");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get the current page title
     * @return The page title
     */
    public String getPageTitle() {
        return driver.getTitle();
    }
    
    /**
     * Get the login heading text
     * @return The login heading text
     */
    public String getLoginHeading() {
        try {
            WebElement heading = driver.findElement(loginHeading);
            return heading.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Check if header image is present
     * @return true if header image is present, false otherwise
     */
    public boolean isHeaderImagePresent() {
        try {
            return driver.findElement(headerImage).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if footer image is present
     * @return true if footer image is present, false otherwise
     */
    public boolean isFooterImagePresent() {
        try {
            return driver.findElement(footerImage).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Perform complete login with credentials
     * @param username The username
     * @param password The password
     * @return true if login was successful, false otherwise
     */
    public boolean login(String username, String password) {
        enterCredentials(username, password);
        clickLogin();
        return isLoginSuccessful();
    }
}
