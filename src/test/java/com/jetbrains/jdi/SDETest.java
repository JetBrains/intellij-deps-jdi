package com.jetbrains.jdi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SDETest {
    // Original code:
    //
    //object A {
    //    @JvmStatic
    //    inline fun inlineFun() {
    //        println() // line = 6
    //    }
    //}
    //
    //class B {
    //    fun inlineInlineFun() {
    //        A.inlineFun()
    //    }
    //}
    // // total lines in file = 26
    private static final String TEST_SMAP = """
            SMAP
            inline.kt
            Kotlin
            *S Kotlin
            *F
            + 1 inline.kt
            kt/breakpoints/inline/B
            + 2 inline.kt
            kt/breakpoints/inline/A
            *L
            1#1,26:1
            6#2,2:27
            *S KotlinDebug
            *F
            + 1 inline.kt
            kt/breakpoints/inline/B
            *L
            12#1:27,2
            *E""";

    private SDE getTestSde() {
        SDE sde = new SDE(TEST_SMAP);
        assertTrue(sde.isValid());
        return sde;
    }

    @Test
    void testStratumOrNull() {
        SDE sde = getTestSde();
        assertNotNull(sde.stratumOrNull("Kotlin"));
        assertNotNull(sde.stratumOrNull("KotlinDebug"));
        // Java stratum is always present
        assertNotNull(sde.stratumOrNull("Java"));

        assertNull(sde.stratumOrNull("Non-existent stratum"));
    }

    @Test
    void testSourcePaths() {
        SDE sde = getTestSde();
        SDE.Stratum stratum = sde.stratumOrNull("Kotlin");

        // we can pass null here as a source path is present in the SMAP
        List<String> sourcePaths = stratum.sourcePaths(null);

        assertEquals(List.of("kt/breakpoints/inline/B", "kt/breakpoints/inline/A"), sourcePaths);
    }

    @Test
    void testMappedLines() {
        SDE sde = getTestSde();
        SDE.Stratum stratum = sde.stratumOrNull("Kotlin");

        List<SDE.LineTableRecord> lineMappings = stratum.mappingsToPath(null, "kt/breakpoints/inline/A");
        assertEquals(1, lineMappings.size());

        SDE.LineTableRecord lineMapping = lineMappings.get(0);
        assertEquals(27, lineMapping.jplsStart);
        assertEquals(28, lineMapping.jplsEnd);
        assertEquals(1, lineMapping.jplsLineInc);
        assertEquals(6, lineMapping.njplsStart);
    }

    @Test
    void testContainsLine() {
        SDE sde = getTestSde();
        SDE.Stratum stratum = sde.stratumOrNull("Kotlin");

        List<SDE.LineTableRecord> lineMappings = stratum.mappingsToPath(null, "kt/breakpoints/inline/A");
        SDE.LineTableRecord lineMapping = lineMappings.get(0);

        for (int i = 0; i < 5; i++) {
            assertFalse(lineMapping.containsMappedLine(i));
        }
        assertTrue(lineMapping.containsMappedLine(6));
        assertTrue(lineMapping.containsMappedLine(7));
        for (int i = 8; i < 27; i++) {
            assertFalse(lineMapping.containsMappedLine(i));
        }
    }
}
