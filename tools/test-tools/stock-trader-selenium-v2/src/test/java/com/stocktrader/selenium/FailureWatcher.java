package com.stocktrader.selenium;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Captures screenshot and page source on failure, before @AfterEach runs.
 *
 * Artifacts are written under target/artifacts/{TestClass}/{method_timestamp}/.
 */
public class FailureWatcher implements AfterTestExecutionCallback {

    @Override
    public void afterTestExecution(ExtensionContext context) {
        // Only capture on failure
        if (context.getExecutionException().isEmpty()) return;
        try {
            Object testInstance = context.getRequiredTestInstance();
            if (!(testInstance instanceof BaseTest)) return;

            WebDriver driver = ((BaseTest) testInstance).driver;
            if (driver == null) return;

            String className = context.getRequiredTestClass().getSimpleName();
            String methodName = context.getRequiredTestMethod().getName();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path outDir = Path.of("target", "artifacts", className, methodName + "_" + timestamp);
            Files.createDirectories(outDir);

            try {
                File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                Files.copy(src.toPath(), outDir.resolve("screenshot.png"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ignored) {}

            try {
                String source = driver.getPageSource();
                Files.writeString(outDir.resolve("pageSource.html"), source);
            } catch (Throwable ignored) {}
        } catch (IOException ignored) {
        }
    }
}


