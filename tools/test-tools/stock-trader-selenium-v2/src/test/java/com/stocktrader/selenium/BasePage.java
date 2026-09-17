package com.stocktrader.selenium;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * BasePage provides resilient, reusable interaction helpers for all Page Objects.
 *
 * Encapsulates explicit waits and safe interactions (JS fallback) to keep
 * test code readable and less flaky across UI changes.
 */
public abstract class BasePage {

    protected final WebDriver driver;
    protected final WebDriverWait wait;

    protected BasePage(WebDriver driver, WebDriverWait wait) {
        this.driver = driver;
        this.wait = wait;
    }

    /**
     * Wait until element located by the given locator is visible and return it.
     */
    protected WebElement waitVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    /**
     * Wait until element is clickable and return it.
     */
    protected WebElement waitClickable(By locator) {
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    /**
     * Click an element found by locator with JS fallback.
     */
    protected void click(By locator) {
        click(waitClickable(locator));
    }

    /**
     * Click an element with a JS fallback if the native click fails.
     */
    protected void click(WebElement element) {
        try {
            wait.until(ExpectedConditions.elementToBeClickable(element));
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'});", element);
            element.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    /**
     * Clear and type the provided value into the element located by locator.
     */
    protected void type(By locator, String value) {
        WebElement el = waitClickable(locator);
        el.clear();
        el.sendKeys(value);
    }

    /**
     * Select an option by value from a standard select element.
     */
    protected void selectByValue(By locator, String value) {
        WebElement el = waitVisible(locator);
        new Select(el).selectByValue(value);
    }
}


