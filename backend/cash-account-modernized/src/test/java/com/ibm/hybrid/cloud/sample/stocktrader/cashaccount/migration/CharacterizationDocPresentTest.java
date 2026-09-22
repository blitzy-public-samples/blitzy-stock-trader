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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/*
 * Why this class exists: the migration concern is built in a fixed order - characterization document,
 * then LegacyCharacterization's constants, then LegacyBalanceCalculator, then ReconciliationService
 * and ShadowComparator - and an ordering nobody can observe is an ordering nobody keeps. This is the
 * observable half of that checkpoint: reconciliation arithmetic is only trustworthy while the
 * document it was derived from is present and still cites the exact COBOL lines it was derived from.
 *
 * Why these six strings are the citation set: CASH00.cbl L221 ("MOVE WS-BALANCE TO BALANC-RATE") and
 * L222 ("COMPUTE WS-CALC = BALANCE + (RATES * BALANC-RATE)") - with L255/L256 the debit mirror - are
 * where the caller's COMMAREA amount, and not the rate table's own column, becomes the multiplicand;
 * FRANKFURT1.AMOUNT is the column that looks like the multiplicand and is referenced by no COMPUTE or
 * MOVE in the program; RoundingMode.DOWN is the Java expression of the single final truncation of
 * "WS-CALC PIC 9(7)V99" (L17), which carries neither ROUNDED nor ON SIZE ERROR. Lose any one of those
 * citations and the document no longer evidences the formula the tooling implements.
 *
 * Why the filesystem rather than the classpath: docs/ is not a resource root, so
 * getResource("/docs/legacy-characterization.md") is always null and the document has to be resolved
 * from the module directory. Why this fails and never skips: a missing or uncited document is exactly
 * the condition the checkpoint exists to catch, so nothing here is conditional on an assumption and
 * nothing here may be disabled.
 *
 * Why the helpers are package-visible and side-effect free: the sibling reconciliation integration
 * tests call them from their @BeforeAll drift guards, so these three signatures are a contract inside
 * this package - they may be extended, never narrowed or moved to a helper class of their own.
 */

/** Asserts the legacy characterization document exists and still carries its arithmetic citations. */
class CharacterizationDocPresentTest {

    /** Module-relative location of the document; written by another module file, never by this test. */
    private static final String DOCUMENT_PATH = "docs/legacy-characterization.md";

    /** The file whose presence marks a candidate directory as this module's base directory. */
    private static final String MODULE_MARKER = "pom.xml";

    /**
     * Citations of the characterized credit/debit arithmetic that the document must carry. Stated once
     * here so the set has a single definition; the two test methods and the sibling guards share it.
     */
    private static final List<String> REQUIRED_CITATIONS =
            List.of("L221", "L222", "L255", "L256", "FRANKFURT1.AMOUNT", "RoundingMode.DOWN");

    /**
     * System properties that may carry the module directory, in the order they are trusted: the pom
     * sets {@code cashaccount.module.basedir} on Surefire and Failsafe alike, so it is authoritative
     * when present; {@code project.basedir} covers a runner configured with Maven's conventional
     * name; {@code user.dir} is Maven's own working directory for a forked test JVM and is what
     * covers Failsafe or a bare JUnit launcher if neither of the first two is set.
     */
    private static final List<String> BASE_DIRECTORY_PROPERTIES =
            List.of("cashaccount.module.basedir", "project.basedir", "user.dir");

    /**
     * Resolves this module's base directory, accepting the first candidate that actually holds the
     * module descriptor.
     *
     * @return the absolute, normalized module base directory
     */
    static Path moduleBaseDirectory() {
        List<String> tried = new ArrayList<>();
        for (String property : BASE_DIRECTORY_PROPERTIES) {
            String value = System.getProperty(property);
            Path candidate = directoryOf(value);
            tried.add("%s=%s".formatted(property, candidate == null ? "<unset>" : candidate));
            if (candidate != null && holdsModuleDescriptor(candidate)) {
                return candidate;
            }
        }

        // Last resort: the test class was loaded from <module>/target/test-classes, so the module
        // directory is two parents up from the code-source location.
        Path fromCodeSource = codeSourceModuleDirectory();
        tried.add("code source=%s".formatted(fromCodeSource == null ? "<unresolvable>" : fromCodeSource));
        if (fromCodeSource != null && holdsModuleDescriptor(fromCodeSource)) {
            return fromCodeSource;
        }

        return fail(("unable to resolve the cash-account-modernized module directory: no candidate "
                        + "contains %s. Candidates tried, in order: %s")
                .formatted(MODULE_MARKER, String.join(", ", tried)));
    }

    /**
     * Location of the legacy characterization document.
     *
     * @return the absolute path the document is expected at, whether or not it exists
     */
    static Path characterizationDocument() {
        return moduleBaseDirectory().resolve(DOCUMENT_PATH);
    }

    /**
     * Asserts the legacy characterization document is present and readable, and returns its content.
     *
     * @return the document's UTF-8 content, never blank
     */
    static String requireCharacterizationDocument() {
        Path document = characterizationDocument();
        String absolutePath = document.toAbsolutePath().toString();

        assertThat(document)
                .as("the legacy characterization document must exist as a readable regular file at %s "
                                + "before any reconciliation code is trusted",
                        absolutePath)
                .exists()
                .isRegularFile()
                .isReadable();

        String content;
        try {
            content = Files.readString(document, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("the legacy characterization document at %s could not be read as UTF-8"
                    .formatted(absolutePath), e);
        }

        assertThat(content)
                .as("the legacy characterization document at %s must not be blank", absolutePath)
                .isNotBlank();
        return content;
    }

    @Test
    void characterizationDocumentExists() {
        assertThat(requireCharacterizationDocument())
                .as("content of %s", characterizationDocument().toAbsolutePath())
                .isNotBlank();
    }

    @Test
    void characterizationDocumentCitesTheLegacyArithmetic() {
        assertThat(requireCharacterizationDocument())
                .as("%s must cite the characterized credit/debit arithmetic it derives: %s",
                        characterizationDocument().toAbsolutePath(), REQUIRED_CITATIONS)
                .contains(REQUIRED_CITATIONS);
    }

    private static Path directoryOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }

    private static boolean holdsModuleDescriptor(Path candidate) {
        return Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve(MODULE_MARKER));
    }

    private static Path codeSourceModuleDirectory() {
        CodeSource codeSource = CharacterizationDocPresentTest.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        try {
            Path location = Path.of(codeSource.getLocation().toURI());
            Path target = location.getParent();
            Path moduleDirectory = target == null ? null : target.getParent();
            return moduleDirectory == null ? null : moduleDirectory.toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException
                | SecurityException e) {
            // A non-file code source (jar URL, custom class loader) simply cannot answer this
            // question; the failure message of moduleBaseDirectory() reports it as unresolvable.
            return null;
        }
    }
}
