/*
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

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

/*
 * Why this class exists: the refactor's central claim is that backend/broker is never edited and that
 * this service satisfies broker's MicroProfile REST Client interface exactly as it stands. Proving it
 * without a Maven dependency on broker's Liberty WAR module means keeping verbatim copies of the two
 * seam files in this module's test tree - and a verbatim copy rots silently. This is what makes rot
 * loud: it compares the copies the contract tests compile against with broker's authoritative
 * originals on disk. It is a detector, never a synchronizer; a difference is resolved by re-syncing
 * the copy from broker, never by editing broker and never by relaxing the comparison.
 *
 * Why the comparison starts at the package declaration: each copy deliberately replaces nothing and
 * instead prepends a provenance header naming its origin path and the obligation to re-sync. That
 * header is the only permitted difference, so the window is "package statement onward" on both sides.
 *
 * Why only line terminators are normalized: broker's CashAccountClient is tab-indented, so a
 * whitespace-insensitive comparison would let real drift through, and a whitespace-adding
 * normalization would invent failures. Terminators alone are normalized because a Windows or
 * core.autocrlf checkout of the broker submodule is a checkout artifact and not drift.
 *
 * Why this fails rather than skips when broker's sources cannot be found: in CI output a skipped
 * guard is indistinguishable from a passing one, which is the one failure mode this guard may not
 * have. The single exception is an explicit -Dcashaccount.standalone=true, which states that the
 * broker sources are genuinely absent from the checkout; that reports as skipped, never as passed.
 *
 * Scope: this proves textual fidelity of two files. It evidences nothing about migration, dual-run or
 * cutover, and it reads broker's tree read-only - it writes nothing outside target/.
 */

/** Asserts this module's verbatim copies of broker's seam files still match broker's originals. */
class CashAccountClientDriftTest {

    /** Package-relative locations of the seam files, identical under both source roots. */
    private static final List<String> SEAM_SOURCES = List.of(
            "com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java",
            "com/ibm/hybrid/cloud/sample/stocktrader/broker/json/CashAccount.java");

    /** Source root holding the copies inside this module. */
    private static final String COPY_SOURCE_ROOT = "src/test/java";

    /** Source root holding the originals inside the broker module. */
    private static final String ORIGIN_SOURCE_ROOT = "src/main/java";

    /** Broker module directory relative to this module, as the umbrella checkout lays it out. */
    private static final String DEFAULT_BROKER_ROOT = "../broker";

    /** Overrides the broker module directory; honoured ahead of {@link #DEFAULT_BROKER_ROOT}. */
    private static final String BROKER_SOURCE_PROPERTY = "cashaccount.broker.source";

    /** Accepts an absent broker module as a genuinely standalone checkout when set to {@code true}. */
    private static final String STANDALONE_PROPERTY = "cashaccount.standalone";

    /** The file whose presence marks a candidate directory as a Maven module directory. */
    private static final String MODULE_MARKER = "pom.xml";

    /**
     * System properties that may carry this module's directory, in the order they are trusted: the pom
     * sets {@code cashaccount.module.basedir} on Surefire and Failsafe alike, so it is authoritative
     * when present; {@code project.basedir} covers a runner configured with Maven's conventional name;
     * {@code user.dir} is the working directory Maven and most IDEs give a forked test JVM.
     */
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

    /**
     * Resolves the broker module directory, preferring an explicit override.
     *
     * @param moduleBase this module's directory, which relative values resolve against
     * @return the normalized broker module directory, whether or not it exists
     */
    private static Path brokerSourceRoot(Path moduleBase) {
        String override = System.getProperty(BROKER_SOURCE_PROPERTY);
        String configured = override == null || override.isBlank() ? DEFAULT_BROKER_ROOT : override.trim();
        return moduleBase.resolve(configured).normalize();
    }

    /**
     * Resolves this module's directory, accepting the first candidate that holds the module descriptor.
     *
     * @return the absolute, normalized module directory
     */
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

    /**
     * Asserts this module's copy is present and readable.
     *
     * @param copy the expected location of the copy
     * @return the same path, once it is known to be a readable regular file
     */
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

    /**
     * Asserts broker's original is reachable read-only, or aborts for an explicitly standalone checkout.
     *
     * @param origin the expected location of broker's original
     * @param brokerRoot the resolved broker module directory, reported in diagnostics
     * @return the same path, once it is known to be a readable regular file
     */
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

    /**
     * Reads a file as UTF-8 and splits it into lines with only its terminators normalized.
     *
     * @param file the file to read
     * @return the file's lines; a trailing terminator yields a trailing empty line, so its presence is
     *     itself compared
     */
    private static List<String> normalizedLines(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("%s could not be read as UTF-8".formatted(file), e);
        }
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

    /**
     * Describes the first difference between two slices.
     *
     * @param origin the broker original's slice
     * @param copy this module's copy's slice
     * @return a quoted, line-numbered description; quoting makes a tab-versus-space or trailing-space
     *     difference readable
     */
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
