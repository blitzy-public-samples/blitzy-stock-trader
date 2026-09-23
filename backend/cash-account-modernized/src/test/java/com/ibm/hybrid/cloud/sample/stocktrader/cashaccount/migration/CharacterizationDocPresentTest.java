package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Asserts the characterization document exists and still carries its citations and section headings. */
class CharacterizationDocPresentTest {

    /**
     * Module-relative: {@code docs/} is not a resource root, so the document is authored and read here from the
     * module directory. Written by another module file, never by this test.
     */
    private static final String DOCUMENT_PATH = "docs/legacy-characterization.md";

    /**
     * The same document as a classpath resource, which pom.xml's {@code copy-characterization-document}
     * execution puts there so the built artifact carries its own baseline.
     */
    private static final String DOCUMENT_RESOURCE = "/" + DOCUMENT_PATH;

    /** The file whose presence marks a candidate directory as this module's base directory. */
    private static final String MODULE_MARKER = "pom.xml";

    /**
     * Citations of the characterized credit/debit arithmetic the document must carry, stated once so the set has
     * a single definition and the failing assertion can name every member. They make AAP 0.10.1's ordering
     * checkpoint observable: reconciliation arithmetic is trustworthy only while the document it derives from
     * still cites CASH00.cbl L221-L222 and the debit mirror L255-L256, where the caller's COMMAREA amount - and
     * not the rate table's own FRANKFURT1.AMOUNT, referenced by no COMPUTE or MOVE in the program - becomes the
     * multiplicand, plus RoundingMode.DOWN for the single final truncation of WS-CALC PIC 9(7)V99 (L17).
     */
    private static final List<String> REQUIRED_CITATIONS =
            List.of("L221", "L222", "L255", "L256", "FRANKFURT1.AMOUNT", "RoundingMode.DOWN");

    /**
     * The section-1 subsection headings {@code LegacyCharacterization}'s Javadoc quotes, one per constant,
     * written exactly as the document writes them so a renamed, reworded or merged heading fails here rather
     * than orphaning a reference nothing checks. The em dashes are the compiler escape {@code \u005Cu2014}, so
     * the comparison is exact under any source encoding this file is compiled with.
     */
    private static final List<String> REQUIRED_SECTION_HEADINGS = List.of(
            "### 1.3 Constant: decimal scale 2",
            "### 1.4 Constant: rounding is `RoundingMode.DOWN`",
            "### 1.5 Constant: unsigned result \u2014 where the sign is dropped",
            "### 1.6 Constant: modulus 10^7 \u2014 high-order digit loss on overflow",
            "### 1.7 Constant: rate key length 5");

    /**
     * System properties that may carry the module directory, in the order they are trusted: the pom sets
     * {@code cashaccount.module.basedir} on Surefire and Failsafe alike, so it is authoritative when present;
     * {@code project.basedir} covers a runner configured with Maven's conventional name; {@code user.dir} is
     * Maven's working directory for a forked test JVM, covering a bare JUnit launcher.
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

        // Last resort: the test class is loaded from <module>/target/test-classes, so the module directory is
        // two parents up from the code-source location.
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
     * Resolves the document under the module base directory rather than the classpath.
     *
     * @return the absolute path the document is expected at, whether or not it exists
     */
    static Path characterizationDocument() {
        return moduleBaseDirectory().resolve(DOCUMENT_PATH);
    }

    /**
     * Asserts the legacy characterization document is present, readable and still carries the section-1 headings
     * the constants quote. The sibling reconciliation integration tests call this from their {@code @BeforeAll}
     * drift guards, so the signature is a contract inside this package and the heading check lives here rather
     * than only in the test method below: a renamed heading then fails those classes too.
     *
     * @return the document's UTF-8 content, never blank
     */
    static String requireCharacterizationDocument() {
        String content = readCharacterizationDocument();
        assertLoadBearingSectionHeadings(content);
        return content;
    }

    @Test
    void characterizationDocumentExists() {
        assertThat(readCharacterizationDocument())
                .as("content of %s", characterizationDocument().toAbsolutePath())
                .isNotBlank();
    }

    @Test
    void characterizationDocumentCitesTheLegacyArithmetic() {
        assertThat(readCharacterizationDocument())
                .as("%s must cite the characterized credit/debit arithmetic it derives: %s",
                        characterizationDocument().toAbsolutePath(), REQUIRED_CITATIONS)
                .contains(REQUIRED_CITATIONS);
    }

    @Test
    void characterizationDocumentCarriesTheHeadingsTheConstantsQuote() {
        assertLoadBearingSectionHeadings(readCharacterizationDocument());
    }

    @Test
    void characterizationDocumentIsPackagedWithTheArtifactAndMatchesTheAuthoredFile() {
        // The document's trailing Status: line is copied into migration_run.characterization_status, which
        // runbook Step 1's sign-off and Step 3's gates read. Resolved only from the working directory, it read
        // DRAFT for every invocation started anywhere but the module root - and for every container run, the
        // image carrying the jar alone - whatever the real document said. Packaged, the artifact answers the
        // question wherever it runs, so the packaging is asserted here rather than trusted to the build file.
        String packaged;
        try (InputStream carried = CharacterizationDocPresentTest.class.getResourceAsStream(DOCUMENT_RESOURCE)) {
            if (carried == null) {
                packaged = fail(("the characterization document must be packaged at classpath:%s; pom.xml's "
                        + "copy-characterization-document execution puts it there, and without it a tool run "
                        + "outside the module directory records characterization_status DRAFT however the "
                        + "document reads").formatted(DOCUMENT_RESOURCE));
            } else {
                packaged = new String(carried.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            packaged = fail("the packaged characterization document at classpath:%s could not be read as UTF-8"
                    .formatted(DOCUMENT_RESOURCE), e);
        }

        // Byte-for-byte, not merely present: a stale copy would report a baseline the module no longer holds,
        // which is worse than none at all because it is signable.
        assertThat(packaged)
                .as("the packaged copy must be the authored %s verbatim", DOCUMENT_PATH)
                .isEqualTo(readCharacterizationDocument());
    }

    /**
     * Reads the document, asserting only that it is present, readable and non-blank, so a missing citation or a
     * renamed heading fails its own test and not this precondition. It fails and never skips: a missing or
     * uncited document is the condition the checkpoint exists to catch.
     *
     * @return the document's UTF-8 content, never blank
     */
    private static String readCharacterizationDocument() {
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

    private static void assertLoadBearingSectionHeadings(String content) {
        assertThat(content)
                .as("%s must carry the section-1 headings LegacyCharacterization's Javadoc quotes "
                                + "verbatim, one per characterized constant: %s",
                        characterizationDocument().toAbsolutePath(), REQUIRED_SECTION_HEADINGS)
                .contains(REQUIRED_SECTION_HEADINGS);
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
            // A non-file code source (jar URL, custom class loader) cannot answer this question; the failure
            // message of moduleBaseDirectory() reports it as unresolvable.
            return null;
        }
    }
}
