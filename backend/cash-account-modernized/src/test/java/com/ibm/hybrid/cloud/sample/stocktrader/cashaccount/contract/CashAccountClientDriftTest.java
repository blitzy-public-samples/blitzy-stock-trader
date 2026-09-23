package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Asserts this module's verbatim copies of broker's seam files still match broker's originals. */
class CashAccountClientDriftTest {

    private static final List<String> SEAM_SOURCES = List.of(
            "com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java",
            "com/ibm/hybrid/cloud/sample/stocktrader/broker/json/CashAccount.java");

    private static final String COPY_SOURCE_ROOT = "src/test/java";

    private static final String ORIGIN_SOURCE_ROOT = "src/main/java";

    private static final String DEFAULT_BROKER_ROOT = "../broker";

    private static final String BROKER_SOURCE_PROPERTY = "cashaccount.broker.source";

    private static final String STANDALONE_PROPERTY = "cashaccount.standalone";

    private static final String MODULE_MARKER = "pom.xml";

    // Trust order: the pom sets cashaccount.module.basedir on Surefire and Failsafe alike, project.basedir
    // covers a runner configured with Maven's conventional name, and user.dir is the working directory Maven
    // and most IDEs give a forked test JVM.
    private static final List<String> BASE_DIRECTORY_PROPERTIES =
            List.of("cashaccount.module.basedir", "project.basedir", "user.dir");

    @Test
    void copiedSeamFilesAreVerbatimFromTheBrokerOriginals() {
        Path moduleBase = moduleBaseDirectory();
        Path brokerRoot = brokerSourceRoot(moduleBase);

        for (String seamSource : SEAM_SOURCES) {
            Path copy = moduleBase.resolve(COPY_SOURCE_ROOT).resolve(seamSource).normalize();
            Path origin = brokerRoot.resolve(ORIGIN_SOURCE_ROOT).resolve(seamSource).normalize();

            List<String> copyLines = normalizedLines(requireCopy(copy));
            List<String> originLines = normalizedLines(requireBrokerOriginal(origin, brokerRoot));

            // Compared from the package statement onward: each copy prepends a provenance header naming its
            // origin and the obligation to re-sync, and that header is the only permitted difference.
            int copyPackageLine = packageLineIndex(copyLines, copy);
            int originPackageLine = packageLineIndex(originLines, origin);

            // A copy that lost its provenance header would still compare equal from the package
            // statement onward while no longer naming where it came from or when to re-sync it.
            assertThat(copyPackageLine)
                    .as("%s must keep its provenance header above the package statement, naming the "
                                    + "origin path %s and the obligation to re-sync it when broker changes",
                            copy, origin)
                    .isGreaterThanOrEqualTo(1);

            String copySlice = slice(copyLines, copyPackageLine);
            String originSlice = slice(originLines, originPackageLine);

            // The message is supplied lazily so the difference is located only when there is one.
            assertThat(copySlice)
                    .withFailMessage(() ->
                            """
                            the verbatim copy has drifted from broker's original.
                              module copy    : %s
                              broker original: %s
                            %s
                            Re-sync the copy from the broker original, which is authoritative and out of \
                            scope for this module; never edit backend/broker to close this gap."""
                                    .formatted(copy, origin, firstDifference(originSlice, copySlice)))
                    .isEqualTo(originSlice);
        }
    }

    private static Path brokerSourceRoot(Path moduleBase) {
        String override = System.getProperty(BROKER_SOURCE_PROPERTY);
        String configured = override == null || override.isBlank() ? DEFAULT_BROKER_ROOT : override.trim();
        return moduleBase.resolve(configured).normalize();
    }

    private static Path moduleBaseDirectory() {
        List<String> tried = new ArrayList<>();
        for (String property : BASE_DIRECTORY_PROPERTIES) {
            String value = System.getProperty(property);
            tried.add("%s=%s".formatted(property, value == null || value.isBlank() ? "<unset>" : value));
            if (value != null && !value.isBlank()) {
                // Deliberately normalize() and never toRealPath(): a path that does not exist is a
                // condition to report with diagnostics, not one to crash on.
                Path candidate = Path.of(value.trim()).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate.resolve(MODULE_MARKER))) {
                    return candidate;
                }
            }
        }
        return fail(("unable to resolve the cash-account-modernized module directory: no candidate holds "
                        + "%s. Candidates tried, in order: %s")
                .formatted(MODULE_MARKER, String.join(", ", tried)));
    }

    private static Path requireCopy(Path copy) {
        assertThat(copy)
                .as("this module must carry a verbatim copy of broker's seam file at %s; the contract "
                                + "tests compile against it",
                        copy)
                .exists()
                .isRegularFile()
                .isReadable();
        return copy;
    }

    // Absent broker sources fail rather than skip, because in CI output a skipped guard is indistinguishable
    // from a passing one; only an explicit -Dcashaccount.standalone=true reports as skipped.
    private static Path requireBrokerOriginal(Path origin, Path brokerRoot) {
        if (Files.isRegularFile(origin) && Files.isReadable(origin)) {
            return origin;
        }
        String diagnosis =
                """
                broker's original seam file is not readable, so drift cannot be ruled out.
                  missing file       : %s
                  resolved broker root: %s (exists: %s)
                  %s=%s
                  %s
                Point the guard at broker with -D%s=<path>, or declare a checkout that genuinely has no \
                broker module with -D%s=true."""
                        .formatted(
                                origin,
                                brokerRoot,
                                Files.isDirectory(brokerRoot),
                                BROKER_SOURCE_PROPERTY,
                                describeProperty(BROKER_SOURCE_PROPERTY),
                                observedBaseDirectoryProperties(),
                                BROKER_SOURCE_PROPERTY,
                                STANDALONE_PROPERTY);

        if (Boolean.parseBoolean(System.getProperty(STANDALONE_PROPERTY, "false"))) {
            return Assumptions.abort("-D%s=true was passed explicitly; %s"
                    .formatted(STANDALONE_PROPERTY, diagnosis));
        }
        return fail(diagnosis);
    }

    private static List<String> normalizedLines(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("%s could not be read as UTF-8".formatted(file), e);
        }
        // Only terminators are normalized - broker's CashAccountClient is tab-indented, so whitespace-insensitive
        // comparison would let real drift through - and the -1 limit keeps a trailing empty line so a missing
        // trailing newline counts as drift.
        return List.of(content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));
    }

    private static int packageLineIndex(List<String> lines, Path file) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("package")) {
                return index;
            }
        }
        return fail(("%s carries no line beginning with \"package\", so the comparison window this guard "
                        + "relies on does not exist")
                .formatted(file));
    }

    private static String slice(List<String> lines, int fromIndex) {
        return String.join("\n", lines.subList(fromIndex, lines.size()));
    }

    // The differing lines are quoted, which is what makes a tab-versus-space or trailing-space difference
    // readable in the failure message.
    private static String firstDifference(String origin, String copy) {
        List<String> originLines = List.of(origin.split("\n", -1));
        List<String> copyLines = List.of(copy.split("\n", -1));
        int shared = Math.min(originLines.size(), copyLines.size());
        for (int index = 0; index < shared; index++) {
            if (!originLines.get(index).equals(copyLines.get(index))) {
                return """
                        first difference at line %d of the compared window (the package statement is line 1):
                          broker original: "%s"
                          module copy    : "%s\""""
                        .formatted(index + 1, originLines.get(index), copyLines.get(index));
            }
        }
        return ("the compared windows agree on their first %d line(s) and then differ in length: broker "
                        + "original has %d line(s), module copy has %d")
                .formatted(shared, originLines.size(), copyLines.size());
    }

    private static String observedBaseDirectoryProperties() {
        List<String> observed = new ArrayList<>();
        for (String property : BASE_DIRECTORY_PROPERTIES) {
            observed.add("%s=%s".formatted(property, describeProperty(property)));
        }
        return String.join(System.lineSeparator() + "  ", observed);
    }

    private static String describeProperty(String property) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? "<unset>" : value;
    }
}
