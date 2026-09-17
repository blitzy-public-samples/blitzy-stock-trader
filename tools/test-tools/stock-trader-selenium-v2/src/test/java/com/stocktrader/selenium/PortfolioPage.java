package com.stocktrader.selenium;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

/**
 * Page Object for Portfolio management screens (summary, add, view).
 *
 * Provides convenient methods to create, view, update, and delete
 * portfolios and interact with trading and other actions.
 */
public class PortfolioPage extends BasePage {
    
    // Summary page locators
    private By createPortfolioBtn = By.xpath("//button[contains(text(), 'Create Portfolio')]");
    private By portfolioTable = By.className("table");
    private By portfolioRows = By.cssSelector("tbody tr.portfolio-row");
    private By viewPortfolioBtn = By.xpath("//button[@title='View Portfolio']");
    private By updatePortfolioBtn = By.xpath("//button[@title='Update Portfolio']");
    private By deletePortfolioBtn = By.xpath("//button[@title='Delete Portfolio']");
    private By deleteConfirmBtn = By.id("confirmDeleteBtn");
    private By deleteModal = By.id("deleteConfirmModal");
    private By noPortfoliosAlert = By.xpath("//div[contains(@class, 'alert-info') and contains(text(), 'You don')]");
    
    // Add Portfolio page locators
    private By ownerField = By.id("owner");
    private By balanceField = By.id("balance");
    private By currencySelect = By.id("currency");
    private By addPortfolioBtn = By.xpath("//button[contains(text(), 'Add Portfolio')]");
    private By cancelBtn = By.xpath("//button[contains(text(), 'Cancel')]");
    
    // View Portfolio page locators
    private By portfolioValue = By.xpath("//th[contains(text(), 'Portfolio Value')]/following-sibling::td");
    private By loyaltyLevel = By.xpath("//th[contains(text(), 'Loyalty Level')]/following-sibling::td");
    private By accountBalance = By.xpath("//th[contains(text(), 'Account Balance')]/following-sibling::td");
    private By cashAccountBalance = By.xpath("//th[contains(text(), 'Cash Account Balance')]/following-sibling::td");
    private By totalCommissions = By.xpath("//th[contains(text(), 'Total Commissions Paid')]/following-sibling::td");
    private By freeTrades = By.xpath("//th[contains(text(), 'Free Trades Available')]/following-sibling::td");
    private By sentiment = By.xpath("//th[contains(text(), 'Sentiment')]/following-sibling::td");
    private By returnOnInvestment = By.xpath("//th[contains(text(), 'Return On Investment')]/following-sibling::td");
    private By buySellStockBtn = By.xpath("//button[contains(text(), 'Buy/Sell Stock')]");
    private By submitFeedbackBtn = By.xpath("//button[contains(text(), 'Submit Feedback')]");
    private By okBtn = By.xpath("//button[contains(text(), 'OK')]");
    
    // Stock table locators
    private By stockTable = By.cssSelector("table.table-bordered");
    private By stockRows = By.cssSelector("tbody tr");
    
    public PortfolioPage(WebDriver driver) {
        super(driver, new WebDriverWait(driver, Duration.ofSeconds(30)));
    }
    
    /**
     * Navigate to the summary page
     */
    public void navigateToSummary() {
        driver.get("https://128.203.77.14/trader/summary");
        waitForPageToLoad();
    }
    
    /**
     * Wait for the page to load completely
     */
    public void waitForPageToLoad() {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("summary-card")));
    }
    
    /**
     * Click the Create Portfolio button
     */
    public void clickCreatePortfolio() {
        click(createPortfolioBtn);
    }
    
    /**
     * Fill the add portfolio form
     */
    public void fillPortfolioForm(String owner, String balance, String currency) {
        // Wait for form to load
        wait.until(ExpectedConditions.presenceOfElementLocated(ownerField));
        
        // Fill owner field
        type(ownerField, owner);
        
        // Fill balance field
        type(balanceField, balance);
        
        // Select currency
        WebElement currencyElement = driver.findElement(currencySelect);
        currencyElement.sendKeys(currency);
    }
    
    /**
     * Submit the add portfolio form
     */
    public void submitPortfolioForm() {
        click(addPortfolioBtn);
    }
    
    /**
     * Cancel the add portfolio form
     */
    public void cancelPortfolioForm() {
        click(cancelBtn);
    }
    
    /**
     * Get the number of portfolios in the table
     */
    public int getPortfolioCount() {
        try {
            List<WebElement> rows = driver.findElements(portfolioRows);
            return rows.size();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get portfolio information by owner name
     */
    public String getPortfolioInfo(String owner) {
        try {
            WebElement row = driver.findElement(By.xpath("//tr[@data-owner='" + owner + "']"));
            return row.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Check if portfolio exists by owner name
     */
    public boolean portfolioExists(String owner) {
        try {
            WebElement row = driver.findElement(By.xpath("//tr[@data-owner='" + owner + "']"));
            return row.isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * View a specific portfolio
     */
    public void viewPortfolio(String owner) {
        click(By.xpath("//tr[@data-owner='" + owner + "']//button[@title='View Portfolio']"));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("main-card")));
    }
    
    /**
     * Update a specific portfolio
     */
    public void updatePortfolio(String owner) {
        click(By.xpath("//tr[@data-owner='" + owner + "']//button[@title='Update Portfolio']"));
    }
    
    /**
     * Delete a specific portfolio
     */
    public void deletePortfolio(String owner) {
        // Click delete button
        click(By.xpath("//tr[@data-owner='" + owner + "']//button[@title='Delete Portfolio']"));
        
        // Wait for modal to appear
        wait.until(ExpectedConditions.visibilityOfElementLocated(deleteModal));
        
        // Confirm deletion
        click(deleteConfirmBtn);
    }
    
    /**
     * Get portfolio value from view page
     */
    public String getPortfolioValue() {
        try {
            WebElement valueElement = driver.findElement(portfolioValue);
            return valueElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get loyalty level from view page
     */
    public String getLoyaltyLevel() {
        try {
            WebElement loyaltyElement = driver.findElement(loyaltyLevel);
            return loyaltyElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get account balance from view page
     */
    public String getAccountBalance() {
        try {
            WebElement balanceElement = driver.findElement(accountBalance);
            return balanceElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get cash account balance from view page
     */
    public String getCashAccountBalance() {
        try {
            WebElement cashElement = driver.findElement(cashAccountBalance);
            return cashElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get total commissions from view page
     */
    public String getTotalCommissions() {
        try {
            WebElement commissionsElement = driver.findElement(totalCommissions);
            return commissionsElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get free trades from view page
     */
    public String getFreeTrades() {
        try {
            WebElement freeElement = driver.findElement(freeTrades);
            return freeElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get sentiment from view page
     */
    public String getSentiment() {
        try {
            WebElement sentimentElement = driver.findElement(sentiment);
            return sentimentElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get return on investment from view page
     */
    public String getReturnOnInvestment() {
        try {
            WebElement roiElement = driver.findElement(returnOnInvestment);
            return roiElement.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get the number of stocks in the portfolio
     */
    public int getStockCount() {
        try {
            List<WebElement> stockRows = driver.findElements(this.stockRows);
            return stockRows.size();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Click Buy/Sell Stock button
     */
    public void clickBuySellStock() {
        click(buySellStockBtn);
    }
    
    /**
     * Click Submit Feedback button
     */
    public void clickSubmitFeedback() {
        click(submitFeedbackBtn);
    }
    
    /**
     * Click OK button to return to summary
     */
    public void clickOK() {
        click(okBtn);
    }
    
    /**
     * Check if we're on the summary page
     */
    public boolean isOnSummaryPage() {
        return driver.getCurrentUrl().contains("/summary");
    }
    
    /**
     * Check if we're on the add portfolio page
     */
    public boolean isOnAddPortfolioPage() {
        return driver.getCurrentUrl().contains("/addPortfolio");
    }
    
    /**
     * Check if we're on the view portfolio page
     */
    public boolean isOnViewPortfolioPage() {
        return driver.getCurrentUrl().contains("/viewPortfolio");
    }
    
    /**
     * Get the current page title
     */
    public String getPageTitle() {
        return driver.getTitle();
    }
    
    /**
     * Get the owner name from the view portfolio page
     */
    public String getPortfolioOwner() {
        try {
            WebElement ownerElement = driver.findElement(By.xpath("//div[contains(@class, 'fw-semibold')]"));
            return ownerElement.getText().replace("for ", "");
        } catch (Exception e) {
            return "";
        }
    }
}
