package com.stocktrader.selenium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import com.stocktrader.selenium.utils.TestDataProvider;

/**
 * Integration test that exercises the complete portfolio CRUD workflow:
 * Login -> Create Portfolio -> View -> Buy -> Sell -> OK -> Delete.
 *
 * Uses robust, attribute-based selectors with JS click fallbacks to handle
 * dynamic UI content and Bootstrap behaviors.
 */
public class PortfolioCrudIT extends BaseTest {

    private static final TestDataProvider CONFIG = new TestDataProvider();
    private String createdOwner; // track owner created for cleanup

    @Test
    public void testPortfolioCrudEndToEnd() {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        // Generate unique data per run from config
        String owner = CONFIG.getOwnerPrefix() + System.currentTimeMillis();
        createdOwner = owner;
        String[] symbols = CONFIG.getTradingSymbols();
        String symbol = symbols[new Random().nextInt(symbols.length)];
        int min = CONFIG.getSharesMin();
        int max = CONFIG.getSharesMax();
        int shares = min + new Random().nextInt(Math.max(1, (max - min + 1)));
        try {
            // 1) Login
            driver.get(CONFIG.getLoginUrl());
            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("username")));
            driver.findElement(By.id("username")).sendKeys(CONFIG.getValidUsername());
            driver.findElement(By.id("password")).sendKeys(CONFIG.getValidPassword());
            driver.findElement(By.name("submit")).click();
            wait.until(ExpectedConditions.urlContains("/summary"));

            // 2) Create portfolio
            By createButtonInForm = By.xpath("//form[.//input[@name='action' and @value='create']]//button[@type='submit' and contains(normalize-space(.), 'Create Portfolio')]");
            WebElement createBtn = wait.until(ExpectedConditions.presenceOfElementLocated(createButtonInForm));
            clickWithFallback(wait, createBtn);

            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("owner")));
            driver.findElement(By.id("owner")).sendKeys(owner);
            WebElement balanceField = driver.findElement(By.id("balance"));
            balanceField.clear();
            balanceField.sendKeys(CONFIG.getInitialBalance());
            new Select(driver.findElement(By.id("currency"))).selectByValue(CONFIG.getTradingCurrency());

            By addPortfolioSubmit = By.xpath("//form//button[@type='submit' and contains(normalize-space(.), 'Add Portfolio')]");
            WebElement addBtn = wait.until(ExpectedConditions.presenceOfElementLocated(addPortfolioSubmit));
            clickWithFallback(wait, addBtn);
            wait.until(ExpectedConditions.urlContains("/summary"));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//tr[@data-owner='" + owner + "']")));

            // 3) View -> Buy stock
            WebElement viewBtn = driver.findElement(By.xpath("//tr[@data-owner='" + owner + "']//button[@title='View Portfolio']"));
            clickWithFallback(wait, viewBtn);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("main-card")));

            By buySellLocator = By.xpath("//button[@type='submit' and @name='submit' and @value='Buy/Sell Stock']");
            WebElement buySellBtn = wait.until(ExpectedConditions.presenceOfElementLocated(buySellLocator));
            clickWithFallback(wait, buySellBtn);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.name("symbol")));
            driver.findElement(By.name("symbol")).sendKeys(symbol);
            driver.findElement(By.name("shares")).sendKeys(String.valueOf(shares));

            WebElement actionButton = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("actionButton")));
            clickWithFallback(wait, actionButton);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("main-card")));

            // 4) Sell stock
            buySellBtn = wait.until(ExpectedConditions.presenceOfElementLocated(buySellLocator));
            clickWithFallback(wait, buySellBtn);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.name("symbol")));
            driver.findElement(By.name("symbol")).clear();
            driver.findElement(By.name("symbol")).sendKeys(symbol);
            WebElement sharesField = driver.findElement(By.name("shares"));
            sharesField.clear();
            sharesField.sendKeys(String.valueOf(shares));
            WebElement sellRadio = driver.findElement(By.id("sellRadio"));
            clickWithFallback(wait, sellRadio);
            actionButton = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("actionButton")));
            clickWithFallback(wait, actionButton);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("main-card")));

            // OK back to summary
            By okLocator = By.xpath("//button[@type='submit' and @name='submit' and @value='OK']");
            WebElement okBtn = wait.until(ExpectedConditions.presenceOfElementLocated(okLocator));
            clickWithFallback(wait, okBtn);
            wait.until(ExpectedConditions.urlContains("/summary"));

            // 5) Delete portfolio (primary path)
            deletePortfolioIfExists(wait, owner);
        } finally {
            // Safety net cleanup in case of early failure
            try {
                WebDriverWait waitCleanup = new WebDriverWait(driver, Duration.ofSeconds(15));
                deletePortfolioIfExists(waitCleanup, createdOwner);
            } catch (Exception ignored) {}
        }
    }

    private void clickWithFallback(WebDriverWait wait, WebElement element) {
        try {
            wait.until(ExpectedConditions.elementToBeClickable(element));
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'});", element);
            element.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    private void deletePortfolioIfExists(WebDriverWait wait, String owner) {
        if (owner == null) return;
        driver.get(CONFIG.getSummaryUrl());
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("summary-card")));
        } catch (Exception ignored) {}
        List<WebElement> rows = driver.findElements(By.xpath("//tr[@data-owner='" + owner + "']"));
        if (rows.isEmpty()) return;
        WebElement deleteBtn = driver.findElement(By.xpath("//tr[@data-owner='" + owner + "']//button[@title='Delete Portfolio']"));
        clickWithFallback(wait, deleteBtn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("deleteConfirmModal")));
        WebElement confirmBtn = wait.until(ExpectedConditions.elementToBeClickable(By.id("confirmDeleteBtn")));
        clickWithFallback(wait, confirmBtn);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("deleteConfirmModal")));
    }
}
